package com.mytetz.graph

import com.mytetz.llm.FakeLlmClient
import com.mytetz.llm.LlmChunk
import com.mytetz.llm.LlmClient
import com.mytetz.llm.LlmEffort
import com.mytetz.llm.LlmRequest
import com.mytetz.llm.LlmStreamTruncatedException
import com.mytetz.llm.LlmUsage
import com.mytetz.llm.Pricing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExplanationGraphTest {

    private val database = MongoTestSupport.database("explanation_graph")
    private val repository = ExplanationRepository(database)
    private val llm = FakeLlmClient()

    /**
     * Deliberately none of GraphConfig's defaults. `promptVersion` is not [PromptBuilder.VERSION],
     * `maxOutputTokens` is not 4000 and `effort` is not LOW, so an implementation that reaches past
     * its config for the constant it happens to know is visible rather than accidentally right.
     */
    private val config = GraphConfig(promptVersion = "vTest", maxOutputTokens = 1234, effort = LlmEffort.MEDIUM)

    private val graph = graphWith()

    private val body = "The microscopic realm studied by quantum theory is the subatomic scale, " +
        "a universe smaller than 0.1 nanometers where the traditional laws of physics collapse."

    private fun graphWith(
        client: LlmClient = llm,
        config: GraphConfig = this.config,
        clock: () -> Long = { FIXED_NOW },
    ) = ExplanationGraph(
        repository = repository,
        llm = client,
        validator = ExplanationValidator(),
        config = config,
        clock = clock,
    )

    /**
     * Every field is a sentinel that appears in exactly one place in the rendered prompt. In
     * particular `spanSentence` does NOT contain `span`: if it did, a `contains(span)` assertion
     * would still pass for an implementation that dropped the highlighted span entirely.
     */
    private fun request(
        span: String = "microscopic realm",
        parentKey: String? = "parent-of-quantum",
        verb: Verb = Verb.EXPLAIN,
        variant: Int = 0,
    ) = GraphRequest(
        topicSlug = "quantum-physics",
        topicTitle = "Quantum Physics",
        parentKey = parentKey,
        ancestors = listOf(Ancestor("fundamental physical theory", "The pillars of modern physics rest on two…")),
        span = span,
        spanSentence = "…Quantum Mechanics governs the very small.",
        verb = verb,
        variant = variant,
        depth = 2,
    )

    private suspend fun documentCount(key: String): Int =
        database.getCollection<Explanation>("explanations")
            .find(com.mongodb.client.model.Filters.eq("_id", key))
            .count()

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Explanation>("explanations").drop()
        repository.ensureIndexes()
        llm.nextBody = body
        llm.nextStopReason = "end_turn"
        llm.failWith = null
        llm.bodyByPromptSubstring.clear()
        llm.calls.clear()
    }

    // ------------------------------------------------------------------ miss

    @Test
    fun `a miss announces itself, streams the model's own deltas and persists once`() = runTest {
        val chunks = graph.getOrGenerate(request()).toList()

        val meta = chunks.first()
        assertEquals(
            GraphChunk.Meta(graph.keyFor(request()), cached = false),
            meta,
            "the first chunk must name the content key and say the store did not have it",
        )

        val deltas = chunks.filterIsInstance<GraphChunk.Delta>().map { it.text }
        assertEquals(
            body.chunked(16),
            deltas,
            "deltas must be forwarded as the model produced them, not buffered into one block",
        )

        val done = chunks.last()
        val stored = repository.findByKey(graph.keyFor(request()))
        assertEquals(GraphChunk.Done(stored!!), done, "the terminal chunk must be the document that was stored")
        assertEquals(1, documentCount(stored.key))
        assertEquals(1, llm.calls.size)
    }

    @Test
    fun `the stored body is the validated form, not the raw stream`() = runTest {
        llm.nextBody = "\n\n  $body  \n"

        val chunks = graph.getOrGenerate(request()).toList()

        assertEquals(
            "\n\n  $body  \n",
            chunks.filterIsInstance<GraphChunk.Delta>().joinToString("") { it.text },
            "the learner sees exactly what the model emitted",
        )
        assertEquals(body, chunks.filterIsInstance<GraphChunk.Done>().single().explanation.body)
        assertEquals(body, repository.findByKey(graph.keyFor(request()))?.body)
    }

    @Test
    fun `the persisted document records the request, the model, the config and the clock`() = runTest {
        graph.getOrGenerate(request()).toList()

        val stored = repository.findByKey(graph.keyFor(request()))!!

        assertEquals("quantum-physics", stored.topicSlug)
        assertEquals("parent-of-quantum", stored.parentKey)
        assertEquals("microscopic realm", stored.span)
        assertEquals("…Quantum Mechanics governs the very small.", stored.spanSentence)
        assertEquals(Verb.EXPLAIN, stored.verb)
        assertEquals(0, stored.variant)
        assertEquals(2, stored.depth)
        assertEquals(body, stored.body)
        assertFalse(stored.grounded)
        assertEquals(emptyList(), stored.sources)
        assertEquals("vTest", stored.promptVersion, "promptVersion must come from the config, not PromptBuilder")
        assertEquals(llm.modelFamily, stored.modelFamily)
        assertEquals(llm.modelId, stored.modelId)
        assertEquals(0, stored.requestCount, "the generating caller is not yet a repeat request")
        assertEquals(FIXED_NOW, stored.createdAtEpochMillis, "the clock must be the injected one")
    }

    @Test
    fun `a seed is identified and stored without a span`() = runTest {
        val seed = request().copy(verb = Verb.SEED, parentKey = null, depth = 0)

        graph.getOrGenerate(seed).toList()

        val stored = repository.findByKey(graph.keyFor(seed))!!
        assertNull(stored.span, "a seed has no highlighted span to store")
        assertNull(stored.spanSentence)
        assertEquals(Verb.SEED, stored.verb)
        assertFalse(
            llm.calls.single().userPrompt.contains("microscopic realm"),
            "a seed prompt must not carry a highlighted span",
        )
    }

    // ------------------------------------------------------------------ the model call

    @Test
    fun `the model call is built from the request and the config`() = runTest {
        graph.getOrGenerate(request()).toList()

        val call = llm.calls.single()
        assertEquals(PromptBuilder.system(), call.system)
        assertEquals(1234, call.maxTokens, "maxTokens must come from the config, not a hard-coded default")
        assertEquals(LlmEffort.MEDIUM, call.effort, "effort must come from the config")

        assertTrue(call.userPrompt.contains("Quantum Physics"), "the topic title must reach the model")
        assertTrue(call.userPrompt.contains("microscopic realm"), "the highlighted span must reach the model")
        assertTrue(
            call.userPrompt.contains("governs the very small"),
            "the sentence the span appeared in must reach the model",
        )
        assertTrue(
            call.userPrompt.contains("fundamental physical theory"),
            "the ancestor span must reach the model",
        )
        assertTrue(
            call.userPrompt.contains("The pillars of modern physics rest on two…"),
            "the ancestor body must reach the model",
        )
        assertTrue(
            call.userPrompt.contains("Explain that highlighted phrase"),
            "the verb must select its instruction",
        )
    }

    // ------------------------------------------------------------------ identity

    @Test
    fun `keyFor is decided by the identity-bearing fields and nothing else`() {
        val base = request()
        val key = graph.keyFor(base)

        assertNotEquals(key, graph.keyFor(base.copy(parentKey = "another-parent")))
        assertNotEquals(key, graph.keyFor(base.copy(span = "traditional laws of physics")))
        assertNotEquals(key, graph.keyFor(base.copy(verb = Verb.DIG_DEEPER)))
        assertNotEquals(key, graph.keyFor(base.copy(variant = 1)))
        assertNotEquals(
            key,
            graphWith(config = config.copy(promptVersion = "vNext")).keyFor(base),
            "bumping the prompt version must invalidate every downstream explanation",
        )
        assertNotEquals(
            key,
            graphWith(client = FakeLlmClient(modelFamily = "another-family")).keyFor(base),
            "a different model family must not reuse another family's answers",
        )

        // Prompt-shaping context is deliberately NOT identity: the parent key already carries the
        // whole ancestry in 32 bytes, so two learners who arrived by the same path share one
        // document even if the caller hydrated the readable chain differently.
        assertEquals(key, graph.keyFor(base.copy(ancestors = emptyList())))
        assertEquals(key, graph.keyFor(base.copy(topicTitle = "Something Else Entirely")))
        assertEquals(key, graph.keyFor(base.copy(spanSentence = "A different carrier sentence.")))
        assertEquals(key, graph.keyFor(base.copy(depth = 9)))
        assertEquals(key, graph.keyFor(base.copy(topicSlug = "another-topic")))
    }

    @Test
    fun `a seed is keyed by its topic, not by whatever span the caller carried`() {
        val seed = request().copy(verb = Verb.SEED)

        assertEquals(
            ContentKey.seed("quantum-physics", "vTest", llm.modelFamily),
            graph.keyFor(seed),
            "a seed key must be derived from the topic slug",
        )
        assertEquals(
            graph.keyFor(seed),
            graph.keyFor(seed.copy(span = "irrelevant", parentKey = "irrelevant", variant = 0)),
            "a seed has no parent and no span, so neither may move its key",
        )
        assertNotEquals(graph.keyFor(seed), graph.keyFor(seed.copy(topicSlug = "microbiology")))
    }

    @Test
    fun `the same span under different ancestry is generated and stored separately`() = runTest {
        llm.bodyByPromptSubstring["fundamental physical theory"] =
            "In physics the microscopic realm is the subatomic scale, far below anything a light microscope resolves."
        llm.bodyByPromptSubstring["the study of living things"] =
            "In biology the microscopic realm is the world of cells and bacteria, visible only under a microscope."

        val underPhysics = request(parentKey = "physics-parent")
        val underBiology = request(parentKey = "biology-parent")
            .copy(ancestors = listOf(Ancestor("the study of living things", "Biology is the science of life…")))

        graph.getOrGenerate(underPhysics).toList()
        graph.getOrGenerate(underBiology).toList()

        assertEquals(2, llm.calls.size, "different ancestry must not share a cache entry")
        assertTrue(
            repository.findByKey(graph.keyFor(underPhysics))!!.body.startsWith("In physics"),
            "each key must hold the answer generated for its own context",
        )
        assertTrue(
            repository.findByKey(graph.keyFor(underBiology))!!.body.startsWith("In biology"),
            "each key must hold the answer generated for its own context",
        )
    }

    // ------------------------------------------------------------------ hits

    @Test
    fun `a hit serves the stored document and never calls the model`() = runTest {
        graph.getOrGenerate(request()).toList()
        val stored = repository.findByKey(graph.keyFor(request()))!!
        llm.calls.clear()
        // A body the store has never seen: if the hit path regenerates, the served text changes.
        llm.nextBody = "A COMPLETELY DIFFERENT ANSWER that was never stored under this key."

        val chunks = graph.getOrGenerate(request()).toList()

        assertEquals(
            GraphChunk.Meta(stored.key, cached = true),
            chunks.first(),
            "a hit must announce itself as cached",
        )
        assertEquals(
            stored.body,
            chunks.filterIsInstance<GraphChunk.Delta>().joinToString("") { it.text },
            "the body served on a hit must be the body that was stored",
        )
        // Field for field, including cost and creation time: the document handed to the caller is
        // the one that was read, not a fresh assembly that merely happens to carry the same prose.
        // The demand counter is asserted separately because it is written after the read, so the
        // served snapshot honestly shows the value it was read at.
        assertEquals(
            stored,
            chunks.filterIsInstance<GraphChunk.Done>().single().explanation,
            "a hit must serve the stored document as it was read",
        )
        assertEquals(0, llm.calls.size, "a cache hit must not call the model")
        assertEquals(1, repository.findByKey(stored.key)?.requestCount, "the hit is still counted as demand")
    }

    @Test
    fun `a hit increments demand and adds no cost`() = runTest {
        graph.getOrGenerate(request()).toList()
        val key = graph.keyFor(request())
        val afterGeneration = repository.findByKey(key)!!

        graph.getOrGenerate(request()).toList()
        graph.getOrGenerate(request()).toList()

        val afterHits = repository.findByKey(key)!!
        assertEquals(2, afterHits.requestCount, "each hit is one more unit of demand")
        assertEquals(
            afterGeneration.costMicros,
            afterHits.costMicros,
            "a hit must not add cost — the daily spend breaker reads this ledger",
        )
        assertEquals(afterGeneration.inputTokens, afterHits.inputTokens)
        assertEquals(afterGeneration.outputTokens, afterHits.outputTokens)
        assertEquals(afterGeneration.body, afterHits.body, "the stored body is immutable")
        assertEquals(1, documentCount(key), "a hit must not write a second document")
    }

    // ------------------------------------------------------------------ cost

    @Test
    fun `the persisted cost is computed from the tokens the stream reported`() = runTest {
        val probe = ProbeLlmClient(body = body, usage = LlmUsage(inputTokens = 777, outputTokens = 333))
        val graph = graphWith(client = probe)

        graph.getOrGenerate(request()).toList()

        val stored = repository.findByKey(graph.keyFor(request()))!!
        assertEquals(777, stored.inputTokens, "input tokens must come from the stream, not a guess")
        assertEquals(333, stored.outputTokens, "output tokens must come from the stream, not a guess")
        assertEquals(
            Pricing.costMicros("claude-sonnet-5", LlmUsage(inputTokens = 777, outputTokens = 333)),
            stored.costMicros,
            "cost must be priced for the model that actually generated it",
        )
        assertTrue(stored.costMicros > 0, "a zero-cost document silently weakens the daily spend breaker")
    }

    // ------------------------------------------------------------------ stampede

    @Test
    fun `twelve concurrent misses generate once and agree on the answer`() = runTest {
        val probe = ProbeLlmClient(body = body)
        val graph = graphWith(client = probe)
        val key = graph.keyFor(request())

        val results = coroutineScope {
            (1..12).map { async { graph.getOrGenerate(request()).toList() } }.awaitAll()
        }

        assertEquals(1, probe.calls.get(), "the mutex must collapse the stampede to one generation")
        assertEquals(1, probe.maxInFlight.get(), "no two generations for one key may ever overlap")
        assertEquals(1, documentCount(key))

        val stored = repository.findByKey(key)!!
        val bodies = results.map { it.filterIsInstance<GraphChunk.Done>().single().explanation.body }
        assertEquals(setOf(stored.body), bodies.toSet(), "all twelve callers must receive the stored body")
        assertTrue(
            results.all { it.first() == GraphChunk.Meta(key, cached = false) },
            "every caller missed when it asked, and Meta reports the store as the caller found it",
        )
        assertEquals(
            11,
            stored.requestCount,
            "the eleven that lost the race must be served from the store, not regenerate",
        )
        assertEquals(0, graph.activeLockCount, "the per-key lock must not outlive the callers holding it")
    }

    // ------------------------------------------------------------------ rejection

    @Test
    fun `every stop reason but end_turn is rejected, unpersisted and cleanly retryable`() = runTest {
        val key = graph.keyFor(request())
        val rejected = listOf("refusal", "max_tokens", "stop_sequence", "tool_use", "pause_turn", null)

        rejected.forEachIndexed { index, stopReason ->
            llm.nextStopReason = stopReason
            val failure = assertFailsWith<GenerationFailedException>("stop reason $stopReason must be rejected") {
                graph.getOrGenerate(request()).toList()
            }
            assertTrue(failure.message!!.contains(key), "the failure must name the key it applies to")
            assertNull(repository.findByKey(key), "a generation rejected for $stopReason must leave no trace")
            assertEquals(index + 1, llm.calls.size, "nothing may short-circuit the model call for $stopReason")
        }

        llm.nextStopReason = "end_turn"
        graph.getOrGenerate(request()).toList()

        assertEquals(body, repository.findByKey(key)?.body, "a retry after rejection must persist cleanly")
        assertEquals(rejected.size + 1, llm.calls.size)
    }

    @Test
    fun `a body the validator rejects is not persisted`() = runTest {
        llm.nextBody = "No."

        assertFailsWith<GenerationFailedException> { graph.getOrGenerate(request()).toList() }

        assertNull(repository.findByKey(graph.keyFor(request())), "a rejected body must leave no trace")
    }

    @Test
    fun `a stream that never reports completion is rejected rather than stored at zero cost`() = runTest {
        val probe = ProbeLlmClient(body = body, emitDone = false)
        val graph = graphWith(client = probe)

        assertFailsWith<GenerationFailedException> { graph.getOrGenerate(request()).toList() }

        assertNull(
            repository.findByKey(graph.keyFor(request())),
            "an unfinished stream has no token count, so storing it would report a free generation",
        )
    }

    // ------------------------------------------------------------------ transport failure

    @Test
    fun `a transport failure raises, keeps its cause and persists nothing`() = runTest {
        val cause = IllegalStateException("connection reset")
        llm.failWith = cause

        val failure = assertFailsWith<GenerationFailedException> { graph.getOrGenerate(request()).toList() }

        assertSame(cause, failure.cause, "the original fault must survive so the log is diagnosable")
        assertNull(repository.findByKey(graph.keyFor(request())))

        // Nothing was cached, so the retry the caller is entitled to make actually works.
        llm.failWith = null
        graph.getOrGenerate(request()).toList()
        assertEquals(body, repository.findByKey(graph.keyFor(request()))?.body)
    }

    @Test
    fun `a truncated stream raises and persists nothing`() = runTest {
        llm.failWith = LlmStreamTruncatedException("stream ended without a stop reason")

        assertFailsWith<GenerationFailedException> { graph.getOrGenerate(request()).toList() }

        assertNull(repository.findByKey(graph.keyFor(request())))
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun `cancelling a request is not reported as a generation failure`() = runTest {
        // A stream that starts, streams, then stalls for ever — the shape of a request the
        // learner abandons by navigating away mid-answer.
        val probe = ProbeLlmClient(body = body, stall = CompletableDeferred())
        val graph = graphWith(client = probe)
        val key = graph.keyFor(request())

        var observed: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                graph.getOrGenerate(request()).collect { }
            } catch (e: Throwable) {
                observed = e
            }
        }
        probe.reachedStall.await()
        job.cancelAndJoin()

        assertFalse(
            observed is GenerationFailedException,
            "a cancelled request must never be reported as a failed generation: because identity is " +
                "content addressed, a failure is safely retryable, so the caller would legitimately " +
                "ask again and pay for a generation nobody is waiting for",
        )
        val seen = observed
        assertTrue(
            seen is CancellationException,
            "cancellation must reach the caller as cancellation, not as ${seen?.let { it::class.simpleName }}",
        )
        assertNull(repository.findByKey(key), "a cancelled generation must persist nothing")
        assertEquals(0, graph.activeLockCount, "the per-key lock must be released when a caller is cancelled")
    }

    @Test
    fun `a collector that stops early is not reported as a generation failure`() = runTest {
        val chunks = graph.getOrGenerate(request()).take(2).toList()

        assertEquals(2, chunks.size)
        assertTrue(chunks[0] is GraphChunk.Meta)
        assertTrue(chunks[1] is GraphChunk.Delta)
    }

    @Test
    fun `a failure raised by the collector reaches the caller unchanged`() = runTest {
        val boom = IllegalStateException("the collector exploded")

        val thrown = assertFailsWith<IllegalStateException> {
            graph.getOrGenerate(request()).collect { chunk -> if (chunk is GraphChunk.Delta) throw boom }
        }

        assertSame(boom, thrown, "the caller's own failure must not be relabelled as a failed generation")
    }

    // ------------------------------------------------------------------ config

    @Test
    fun `GraphConfig falls back to its safe defaults for an unusable override`() {
        assertEquals(4000, GraphConfig.resolveMaxOutputTokens(null))
        assertEquals(2000, GraphConfig.resolveMaxOutputTokens("2000"))
        assertEquals(2000, GraphConfig.resolveMaxOutputTokens("  2000  "))
        assertEquals(4000, GraphConfig.resolveMaxOutputTokens("plenty"))
        assertEquals(4000, GraphConfig.resolveMaxOutputTokens(""))
        assertEquals(4000, GraphConfig.resolveMaxOutputTokens("0"))
        assertEquals(4000, GraphConfig.resolveMaxOutputTokens("-1"))

        assertEquals(LlmEffort.LOW, GraphConfig.resolveEffort(null))
        assertEquals(LlmEffort.HIGH, GraphConfig.resolveEffort("HIGH"))
        assertEquals(LlmEffort.HIGH, GraphConfig.resolveEffort(" high "))
        assertEquals(LlmEffort.LOW, GraphConfig.resolveEffort("EXTREME"))
        assertEquals(LlmEffort.LOW, GraphConfig.resolveEffort(""))
    }

    private companion object {
        const val FIXED_NOW = 1_764_000_000_000L
    }
}

/**
 * The cases [FakeLlmClient] cannot express: exact, distinctive token counts; a stream that stalls
 * until the test cancels it; a stream that ends without its terminal event; and a running record of
 * how many generations were ever in flight at the same moment.
 */
private class ProbeLlmClient(
    private val body: String,
    private val usage: LlmUsage = LlmUsage(inputTokens = 777, outputTokens = 333),
    private val stall: CompletableDeferred<Unit>? = null,
    private val emitDone: Boolean = true,
    override val modelId: String = "claude-sonnet-5",
    override val modelFamily: String = "probe-family",
) : LlmClient {

    val calls = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val reachedStall = CompletableDeferred<Unit>()
    private val inFlight = AtomicInteger()

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        calls.incrementAndGet()
        val concurrent = inFlight.incrementAndGet()
        maxInFlight.getAndUpdate { maxOf(it, concurrent) }
        try {
            body.chunked(16).forEach {
                emit(LlmChunk.Delta(it))
                // Re-dispatch between deltas. Without this a second generation could sit in the
                // dispatcher queue behind the first and never be observed as overlapping, so an
                // implementation with no mutex at all might still record maxInFlight == 1.
                yield()
            }
            reachedStall.complete(Unit)
            stall?.await()
            if (emitDone) emit(LlmChunk.Done(usage, "end_turn"))
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
