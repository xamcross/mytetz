package com.mytetz.graph

import com.mytetz.llm.LlmChunk
import com.mytetz.llm.LlmClient
import com.mytetz.llm.LlmRequest
import com.mytetz.llm.LlmUsage
import com.mytetz.llm.Pricing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The model was asked and the answer could not be trusted, or was never delivered.
 *
 * Nothing is persisted when this is raised, so the key stays free and a retry starts clean. That
 * is the whole point of raising rather than storing a degraded answer: identity is content
 * addressed and there is no edit path, so a bad document would be served for ever.
 */
class GenerationFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Everything needed to answer "what is the explanation here?".
 *
 * Only some of these fields decide identity — see [ExplanationGraph.keyFor]. The rest shape the
 * prompt or describe the document. There is deliberately no user, principal or session.
 */
data class GraphRequest(
    val topicSlug: String,
    val topicTitle: String,
    val parentKey: String?,
    val ancestors: List<Ancestor>,
    val span: String,
    val spanSentence: String,
    val verb: Verb,
    val variant: Int = 0,
    val depth: Int = 0,
)

/** The wire shape of one answer: what it is, then the text as it arrives, then the document. */
sealed interface GraphChunk {
    data class Meta(val contentKey: String, val cached: Boolean) : GraphChunk
    data class Delta(val text: String) : GraphChunk
    data class Done(val explanation: Explanation) : GraphChunk
}

/**
 * The content-addressed explanation store: get, or generate and keep.
 *
 * This class has no concept of a user, a session or a principal. Identical inputs give the
 * identical answer to everyone, which is simultaneously what makes the store a cache, what makes
 * its contents publishable, and what makes it testable without inventing a logged-in learner.
 * Quotas and principals belong to the layers above; keep them out of here.
 */
class ExplanationGraph(
    private val repository: ExplanationRepository,
    private val llm: LlmClient,
    private val validator: ExplanationValidator,
    private val config: GraphConfig = GraphConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class KeyLock {
        val mutex = Mutex()

        /** Guarded by ConcurrentHashMap's per-key lock, which `compute` holds for its function. */
        var holders: Int = 0
    }

    private val locks = ConcurrentHashMap<String, KeyLock>()

    /** Test seam: the lock table is a cache of in-flight work and must never accumulate. */
    internal val activeLockCount: Int get() = locks.size

    /**
     * The immutable identity of the answer to [request].
     *
     * Identity is exactly: ancestry (carried whole by the parent's key), the highlighted span, the
     * verb, the variant, the prompt version and the model family. Nothing else — not the topic
     * title, not the sentence the span came from, not the hydrated ancestor bodies, not the depth.
     * Those shape the prompt or describe the document; two callers who reached the same span by
     * the same path must land on the same document however they hydrated the chain.
     */
    fun keyFor(request: GraphRequest): String =
        if (request.verb == Verb.SEED) {
            ContentKey.seed(request.topicSlug, config.promptVersion, llm.modelFamily)
        } else {
            ContentKey.derive(
                parentKey = request.parentKey.orEmpty(),
                span = request.span,
                verb = request.verb,
                variant = request.variant,
                promptVersion = config.promptVersion,
                modelFamily = llm.modelFamily,
            )
        }

    fun getOrGenerate(request: GraphRequest): Flow<GraphChunk> = flow {
        val key = keyFor(request)

        repository.findByKey(key)?.let { stored ->
            emit(GraphChunk.Meta(key, cached = true))
            repository.incrementRequestCount(key)
            emit(GraphChunk.Delta(stored.body))
            emit(GraphChunk.Done(stored))
            return@flow
        }

        // `cached` describes the store as this caller found it, and is emitted before the lock so
        // the client's first byte is never delayed by somebody else's generation. A caller that
        // ends up served by the second look below therefore still sees cached = false: it did
        // miss, and that is what tells a client to show a "generating…" affordance.
        emit(GraphChunk.Meta(key, cached = false))

        val lock = acquireLock(key)
        try {
            lock.mutex.withLock {
                // Second look, now under the lock. Whoever held it may have just persisted this
                // exact key, and regenerating would buy a byte-identical document with real money.
                val stored = repository.findByKey(key)
                if (stored != null) {
                    repository.incrementRequestCount(key)
                    emit(GraphChunk.Delta(stored.body))
                    emit(GraphChunk.Done(stored))
                } else {
                    emit(GraphChunk.Done(generate(request, key)))
                }
            }
        } finally {
            releaseLock(key)
        }
    }

    /**
     * A per-key mutex, reference counted.
     *
     * It exists for one reason: twelve learners highlighting the same phrase at the same moment
     * must cost one generation, not twelve. It is only an optimisation, and only within one
     * process — the actual guarantee is the unique `_id` in Mongo, which makes a cross-instance
     * race benign rather than merely unlikely: `insertIfAbsent` discards the loser's copy and
     * returns the winner's document, so every caller still agrees on one immutable body. Wasteful,
     * never wrong.
     *
     * Entries are reference counted rather than dropped unconditionally on exit. Removing a mutex
     * while another caller is still queued on it hands the next arrival a *different* mutex, and
     * two generations run side by side — precisely the spend the mutex exists to prevent. That
     * window is easy to hit after a failed generation, where the loser has nothing cached to fall
     * back on. `ConcurrentHashMap.compute` is atomic for the key, so `holders` needs no other
     * guard and no lock of its own.
     *
     * The lock is held across the winner's downstream emission, not just the model call, so the
     * waiters are released the instant the document exists rather than a round trip later. The
     * cost is that a slow consumer of the winning stream also delays them; acceptable while the
     * only consumer is an SSE writer that drains as fast as the socket allows.
     */
    private fun acquireLock(key: String): KeyLock =
        locks.compute(key) { _, existing -> (existing ?: KeyLock()).also { it.holders++ } }!!

    private fun releaseLock(key: String) {
        locks.compute(key) { _, existing ->
            if (existing == null || --existing.holders <= 0) null else existing
        }
    }

    private suspend fun FlowCollector<GraphChunk>.generate(request: GraphRequest, key: String): Explanation {
        val raw = StringBuilder()
        var usage = LlmUsage()
        var stopReason: String? = null

        // Set only when OUR downstream collector is what failed, so the two directions can be told
        // apart in the catch below. `emit` is the only call in here that can fail downstream.
        var raisedDownstream: Throwable? = null

        try {
            llm.stream(
                LlmRequest(
                    system = PromptBuilder.system(),
                    userPrompt = PromptBuilder.user(
                        PromptContext(
                            topicTitle = request.topicTitle,
                            ancestors = request.ancestors,
                            span = request.span,
                            spanSentence = request.spanSentence,
                            verb = request.verb,
                        )
                    ),
                    maxTokens = config.maxOutputTokens,
                    effort = config.effort,
                )
            ).collect { chunk ->
                when (chunk) {
                    is LlmChunk.Delta -> {
                        raw.append(chunk.text)
                        try {
                            emit(GraphChunk.Delta(chunk.text))
                        } catch (e: Throwable) {
                            raisedDownstream = e
                            throw e
                        }
                    }

                    is LlmChunk.Done -> {
                        usage = chunk.usage
                        stopReason = chunk.stopReason
                    }
                }
            }
        } catch (e: CancellationException) {
            // Cancellation is not a generation failure, and this clause is load bearing:
            // CancellationException descends from RuntimeException, so a bare `catch (e: Exception)`
            // silently relabels a learner who navigated away as an upstream fault. Because identity
            // is content addressed, a *failure* here is safely retryable by design — so a caller
            // that believes the generation failed will legitimately ask again, and a cancelled
            // request would turn into real, repeated spend. `Mongo.ping()` carries the same guard
            // for the same reason. This also covers the flow's own abort signal (`take`, `first`),
            // which arrives as a CancellationException through `emit`.
            throw e
        } catch (e: Exception) {
            // A failure raised by our own collector travelled up through `emit` and belongs to the
            // caller. Rewriting it would break Flow's exception transparency contract and, again,
            // would report somebody else's problem as a failed generation.
            if (e === raisedDownstream) throw e
            throw GenerationFailedException("upstream generation failed for $key", e)
        }

        // The validator is an allowlist: a missing or unrecognised stop reason lands here too, not
        // in Valid. Nothing is persisted on rejection, so the key stays free and a retry is clean.
        val validated = when (val result = validator.validate(raw.toString(), stopReason)) {
            is ValidationResult.Valid -> result.body
            is ValidationResult.Invalid ->
                throw GenerationFailedException("invalid generation for $key: ${result.reason}")
        }

        val explanation = Explanation(
            key = key,
            topicSlug = request.topicSlug,
            parentKey = request.parentKey,
            span = request.span.takeIf { request.verb != Verb.SEED },
            spanSentence = request.spanSentence.takeIf { request.verb != Verb.SEED },
            verb = request.verb,
            variant = request.variant,
            depth = request.depth,
            body = validated,
            grounded = false,
            sources = emptyList(),
            promptVersion = config.promptVersion,
            modelFamily = llm.modelFamily,
            modelId = llm.modelId,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            // This document is the ledger the daily spend breaker will read. The counts come from
            // the stream's own terminal event and nowhere else: a generation persisted at zero cost
            // would quietly weaken the only protection against a runaway bill. A stream that never
            // delivered that event has no stop reason either, so it is rejected above.
            costMicros = Pricing.costMicros(llm.modelId, usage),
            // The caller that generated is not a repeat request; only hits increment demand, which
            // is what keeps a cache hit from double-counting anything but interest.
            requestCount = 0,
            createdAtEpochMillis = clock(),
        )

        return repository.insertIfAbsent(explanation)
    }
}
