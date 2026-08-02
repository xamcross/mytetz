package com.mytetz.session

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.Ancestor
import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationGraph
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.GraphChunk
import com.mytetz.graph.GraphRequest
import com.mytetz.graph.Verb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * The client pointed at text that is not there.
 *
 * This is the injection gate reporting, and it is the only thing standing between a learner-supplied
 * string and the model. See [SessionService.validateSpan].
 */
class SpanMismatchException(message: String) : Exception(message)

/** The chain below this node would be longer than [SessionLimits.maxDepth] links. */
class DepthLimitException(message: String) : Exception(message)

/** The session already holds [SessionLimits.maxNodes] steps. */
class SessionFullException(message: String) : Exception(message)

/** The regeneration would be numbered above [SessionLimits.maxVariants]. */
class VariantLimitException(message: String) : Exception(message)

/**
 * What the learner highlighted, and where.
 *
 * All three fields arrive from the client and none of them is trusted: [start] and [end] are
 * checked against the stored parent body, and [text] is checked against what sits between them.
 */
data class SpanSelection(val text: String, val start: Int, val end: Int)

/**
 * A validated, priced-out explain request that has not been executed yet.
 *
 * Only [SessionService.prepare] can make one — the constructor is `internal` deliberately, because
 * a plan that skipped [SessionService.validateSpan] would be a way for a caller to hand the model a
 * span of its own invention, which is precisely what the gate exists to prevent.
 *
 * [contentKey] and [cached] are the whole point of the type; see "Telling the API layer whether a
 * request will spend money" on [SessionService].
 */
class ExplainPlan internal constructor(
    val sessionId: String,
    val parentNodeId: String,
    /** The immutable identity the answer to this request will have. */
    val contentKey: String,
    /**
     * True when the store already holds [contentKey], so executing this plan calls no model and
     * costs nothing.
     *
     * A snapshot, and it can only go stale in the harmless direction — see [SessionService.prepare].
     */
    val cached: Boolean,
    internal val request: GraphRequest,
)

/**
 * Where the catalogue, the context chain, the explanation graph and the session tree meet.
 *
 * ## The one promise this class keeps
 *
 * A learner reading about *Quantum Physics* who highlights "microscopic realm" must be told about
 * the subatomic scale, never about bacteria and cells. Two things decided here are what keep that
 * promise, and both of them are prompt inputs:
 *
 * 1. **the ancestry** — [buildAncestors] over [ContextChain.pathTo], so the model is told the topic
 *    first and then every span the learner drilled through, in order; and
 * 2. **the span sentence** — [sentenceAround], because "microscopic realm" resolves differently
 *    depending on whether the sentence it came from concerned scale or measurement.
 *
 * ## Telling the API layer whether a request will spend money
 *
 * This module must not depend on `:backend:quota` and does not. But `QuotaService` gates
 * *generation* and deliberately keeps serving *cache hits* while the spend breaker is tripped —
 * that is what keeps the site usable during a spend incident — and an API-layer gate cannot tell a
 * hit from a miss before calling [explain]. By the time the stream emits `Meta(cached = false)` the
 * generation is already under way.
 *
 * So the answer is offered here, where the content key is known:
 *
 * ```
 * val plan = sessions.prepare(sessionId, nodeId, selection, verb, variant)   // no model call
 * if (!plan.cached && quota.checkGeneration(principal) != Allowed) return refuse()
 * sessions.explain(plan).collect { … }                                       // may generate
 * ```
 *
 * and [createWillGenerate] answers the same question for [create]. Neither imports anything from
 * `quota`; both hand the API layer a boolean it can put in front of one.
 *
 * The verdict is a snapshot taken before the decision, exactly like `QuotaService.checkGeneration`
 * itself, and the race is one-directional: nothing ever deletes an explanation (there is no delete
 * path on `ExplanationRepository` and no TTL index on the collection), so a key that existed at
 * [prepare] still exists at [explain]. A plan can therefore only go stale from `cached = false` to
 * "actually a hit" — the API layer consulted the quota for a request that turned out to be free,
 * which wastes a check and refuses nobody. The reverse, which would let a generation past an
 * exhausted budget, cannot happen.
 *
 * ## Cancellation drops the node, deliberately
 *
 * See [explain].
 */
class SessionService(
    private val sessions: SessionRepository,
    private val catalog: CatalogService,
    private val graph: ExplanationGraph,
    private val explanations: ExplanationRepository,
    private val limits: SessionLimits = SessionLimits(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ------------------------------------------------------------------ create

    /**
     * Starts a session on [topicSlug] and returns it with its seed explanation.
     *
     * Refuses an unknown slug **and a topic that is not published**, both as
     * [IllegalArgumentException] and deliberately with the same type: `CatalogService.findBySlug`
     * does not filter on status (`CatalogServiceTest` pins that, so that an admin lookup can see a
     * draft), which makes this the place where publication is enforced for readers. A DRAFT topic
     * is one nobody has reviewed; admitting a session on it would put unreviewed material in front
     * of a learner *and* mint an immutable, content-addressed explanation of it that there is no
     * path to delete.
     *
     * One type for both cases so that a client cannot use the response to discover which unpublished
     * topics exist. The messages differ, and they are for the log — Task 1.11 must not echo them.
     *
     * Publication is checked here and **not** in [explain]. Unpublishing a topic takes effect for
     * new sessions; a learner already inside one may finish it. Yanking a live session mid-read is
     * the worse failure for what unpublishing actually means in this catalogue (not ready for
     * browsing), and if a topic ever has to be withdrawn because its content is harmful, nothing in
     * this slice can do that anyway — the explanations are already immutable and undeletable.
     * Recorded as a decision rather than left as an omission.
     */
    suspend fun create(principalId: String, topicSlug: String): Pair<LearningSession, Explanation> {
        val topic = requirePublishedTopic(topicSlug)

        val seed = graph.getOrGenerate(seedRequest(topic))
            .toList()
            .filterIsInstance<GraphChunk.Done>()
            .single()
            .explanation

        val now = clock()
        val rootId = idFactory()
        val session = LearningSession(
            id = idFactory(),
            principalId = principalId,
            topicSlug = topic.slug,
            rootNodeId = rootId,
            currentNodeId = rootId,
            nodes = listOf(
                SessionNode(
                    nodeId = rootId,
                    parentNodeId = null,
                    explanationKey = seed.key,
                    span = "",
                    verb = Verb.SEED,
                    variant = 0,
                    depth = 0,
                    createdAtEpochMillis = now,
                )
            ),
            startedAtEpochMillis = now,
            lastActiveAtEpochMillis = now,
        )
        sessions.insert(session)
        return session to seed
    }

    /**
     * Would [create] call the model, or is this topic's seed already in the store?
     *
     * The [create] half of the quota contract described on the class. Raises for an unknown or
     * unpublished topic exactly as [create] does, so the gate cannot answer questions [create] would
     * refuse.
     */
    suspend fun createWillGenerate(topicSlug: String): Boolean {
        val topic = requirePublishedTopic(topicSlug)
        return explanations.findByKey(graph.keyFor(seedRequest(topic))) == null
    }

    // ------------------------------------------------------------------ explain

    /**
     * Everything [explain] does except call the model: load the session, walk the chain, enforce
     * the three [SessionLimits] ceilings, hydrate the ancestry, run the injection gate, and price
     * the request into a content key.
     *
     * Every ceiling is checked **before** any generation, which is the point of checking them here
     * rather than in [SessionRepository.appendNode] — a limit enforced at write time is enforced
     * after the model has already been paid.
     */
    suspend fun prepare(
        sessionId: String,
        parentNodeId: String,
        selection: SpanSelection,
        verb: Verb,
        requestedVariant: Int?,
    ): ExplainPlan {
        // SessionNotFoundException, not IllegalArgumentException: Task 1.9 introduced the type for
        // exactly this condition and documented the mapping next to it (404). `appendNode` already
        // raises it for the same session being absent, and having the two paths disagree would let
        // one stale client get a 404 and another a 400 for one situation.
        val session = sessions.findById(sessionId) ?: throw SessionNotFoundException(sessionId)

        if (session.nodes.size >= limits.maxNodes) {
            throw SessionFullException(
                "session $sessionId already holds ${session.nodes.size} of ${limits.maxNodes} nodes"
            )
        }

        // Raises IllegalArgumentException for a node id the caller made up, and
        // CorruptSessionException for a session that has stopped describing a tree. Both propagate.
        val path = ContextChain.pathTo(session, parentNodeId)
        val parent = path.last()
        val depth = parent.depth + 1

        if (depth > limits.maxDepth) {
            throw DepthLimitException("a chain of $depth links exceeds the limit of ${limits.maxDepth}")
        }

        // See ContextChain.highestVariant: a first answer is variant 0 and a regeneration is
        // numbered from 1, which is the only reason a returned 0 can be read as "untouched".
        val variant = requestedVariant
            ?: if (verb == Verb.SIDE_VIEW) {
                ContextChain.highestVariant(session, parentNodeId, selection.text, verb) + 1
            } else {
                0
            }

        if (variant > limits.maxVariants) {
            throw VariantLimitException("variant $variant exceeds the limit of ${limits.maxVariants}")
        }

        val bodies = hydrate(sessionId, path.map { it.explanationKey })
        val parentBody = bodies.getValue(parent.explanationKey).body

        validateSpan(parentBody, selection)

        val topic = catalog.findBySlug(session.topicSlug)
            // Not the caller's mistake and not a 400: the session document names a topic the
            // catalogue no longer has, which is the same class of dangling reference as a missing
            // explanation. See CorruptSessionException.
            ?: throw CorruptSessionException(
                sessionId,
                "topic ${session.topicSlug} is no longer in the catalogue",
            )

        val request = GraphRequest(
            topicSlug = topic.slug,
            topicTitle = topic.title,
            parentKey = parent.explanationKey,
            ancestors = buildAncestors(path, bodies),
            span = selection.text,
            spanSentence = sentenceAround(parentBody, selection),
            verb = verb,
            variant = variant,
            depth = depth,
        )

        val contentKey = graph.keyFor(request)
        return ExplainPlan(
            sessionId = sessionId,
            parentNodeId = parentNodeId,
            contentKey = contentKey,
            cached = explanations.findByKey(contentKey) != null,
            request = request,
        )
    }

    /**
     * Streams the answer to a prepared request and records the step it took.
     *
     * ## Cancellation drops the node, and that is the decision
     *
     * [SessionRepository.appendNode] runs after the stream completes. A learner who navigates away
     * mid-answer cancels the flow, the cancellation propagates out of `emit`, and the node is never
     * written. There is a real window in which the explanation has been generated, paid for and
     * persisted — `ExplanationGraph` inserts before it emits `Done` — and the session then has no
     * record of it.
     *
     * That is deliberate, and the alternative was considered and rejected. Writing the node under
     * `withContext(NonCancellable)` would keep the record, but:
     *
     * - Identity is content addressed, so nothing is lost that costs money. The document is in the
     *   store; the learner asking again is served from cache and *then* gets their node. The only
     *   thing dropped is a step the learner did not take.
     * - `appendNode` also moves `currentNodeId`. Recording a node the learner abandoned would
     *   resume their next session on a branch they navigated away from.
     * - A client disconnect storm would turn into a write storm, on the one path where nobody is
     *   waiting for the result.
     *
     * Task 1.7 established that cancellation semantics on this path are load bearing rather than
     * incidental, so this is written down and pinned by a test rather than left to be inferred from
     * the shape of the code.
     */
    fun explain(plan: ExplainPlan): Flow<GraphChunk> = flow {
        var generated: Explanation? = null

        graph.getOrGenerate(plan.request).collect { chunk ->
            if (chunk is GraphChunk.Done) generated = chunk.explanation
            emit(chunk)
        }

        val explanation = generated
            ?: throw IllegalStateException("graph produced no Done chunk for ${plan.contentKey}")

        // One reading, used for both. Two calls to `clock()` can straddle a millisecond boundary and
        // leave a node whose creation time is not the moment the session was last active — a
        // difference that is invisible in a test with a constant clock and permanent in the data.
        val now = clock()
        sessions.appendNode(
            sessionId = plan.sessionId,
            node = SessionNode(
                nodeId = idFactory(),
                parentNodeId = plan.parentNodeId,
                explanationKey = explanation.key,
                span = plan.request.span,
                verb = plan.request.verb,
                variant = plan.request.variant,
                depth = plan.request.depth,
                createdAtEpochMillis = now,
            ),
            nowEpochMillis = now,
        )
    }

    /** [prepare] then [explain]. The single-call form, for a caller with no quota gate to run. */
    fun explain(
        sessionId: String,
        parentNodeId: String,
        selection: SpanSelection,
        verb: Verb,
        requestedVariant: Int?,
    ): Flow<GraphChunk> = flow {
        emitAll(explain(prepare(sessionId, parentNodeId, selection, verb, requestedVariant)))
    }

    // ------------------------------------------------------------------ load

    /**
     * The session and every explanation it points at, or null when there is no such session.
     *
     * Null for an absent session — this is a lookup, and the caller asked whether it is there.
     * A session that *is* there but names an explanation the store does not have raises
     * [CorruptSessionException] instead of quietly returning a map with a hole in it, for the same
     * reason [hydrate] does: a partial map renders as a session tree with a missing step, which
     * reads to the learner as a bug in the page rather than as the data incident it is.
     */
    suspend fun load(sessionId: String): Pair<LearningSession, Map<String, Explanation>>? {
        val session = sessions.findById(sessionId) ?: return null
        return session to hydrate(sessionId, session.nodes.map { it.explanationKey })
    }

    // ------------------------------------------------------------------ internals

    private suspend fun requirePublishedTopic(topicSlug: String): Topic {
        val topic = catalog.findBySlug(topicSlug)
            ?: throw IllegalArgumentException("unknown topic: $topicSlug")
        if (topic.status != TopicStatus.PUBLISHED) {
            throw IllegalArgumentException("topic $topicSlug is ${topic.status}, not PUBLISHED")
        }
        return topic
    }

    private fun seedRequest(topic: Topic) = GraphRequest(
        topicSlug = topic.slug,
        topicTitle = topic.title,
        parentKey = null,
        ancestors = emptyList(),
        span = "",
        spanSentence = "",
        verb = Verb.SEED,
    )

    /**
     * Resolves stored explanation keys to documents.
     *
     * A key that does not resolve is [CorruptSessionException] and not `error(...)`: Task 1.9
     * introduced the type for exactly this class of fault — a stored reference that does not
     * resolve — so that Task 1.11 can map corruption to a 500 with an alert, separately from caller
     * error. Nothing deletes explanations, so a dangling key here means the document was never
     * written or the collection was damaged; either way somebody should look.
     */
    private suspend fun hydrate(sessionId: String, keys: List<String>): Map<String, Explanation> =
        keys.distinct().associateWith { key ->
            explanations.findByKey(key) ?: throw CorruptSessionException(
                sessionId,
                "a node points at explanation $key, which is not in the store",
            )
        }

    /**
     * The ancestry the generation is answered against: the topic first, then every span the learner
     * drilled through, ending with the parent whose body holds the current span.
     *
     * The whole path, parent included — the span was highlighted *in* the parent's body, so the
     * parent is the most important link of all. The seed carries an empty span, and an empty
     * `They highlighted ""` line would read to the model as a highlight of nothing.
     */
    private fun buildAncestors(path: List<SessionNode>, bodies: Map<String, Explanation>): List<Ancestor> =
        path.map { node ->
            Ancestor(
                span = if (node.verb == Verb.SEED) "the topic introduction" else node.span,
                body = bodies.getValue(node.explanationKey).body,
            )
        }

    /**
     * The injection gate. The client may only point at text we generated — never supply its own.
     *
     * Both halves matter and neither is sufficient. Offsets alone would let a client point at a
     * real range and label it with any text it liked, and that text is what reaches the prompt as
     * the highlighted span and the content key. Text alone would let a client send a phrase that
     * happens to appear somewhere in the body while the surrounding sentence — also a prompt input
     * — is taken from somewhere else entirely.
     */
    private fun validateSpan(parentBody: String, selection: SpanSelection) {
        if (selection.start < 0 || selection.end > parentBody.length || selection.start >= selection.end) {
            throw SpanMismatchException(
                "span offsets [${selection.start},${selection.end}) are not a range inside a " +
                    "${parentBody.length}-character body"
            )
        }
        if (parentBody.substring(selection.start, selection.end) != selection.text) {
            throw SpanMismatchException("span text does not match the parent body at those offsets")
        }
    }

    /**
     * The sentence the highlighted span sits in — the second of this class's two prompt inputs.
     *
     * Guarantees the whole span is inside the result. The backward scan starts before the span and
     * the forward scan starts at its last character, so a span that itself straddles a terminator
     * widens the sentence rather than being cut in half by it.
     *
     * ### What counts as the end of a sentence
     *
     * Not "any `.`". Two rules, and both are here because the content this system generates trips
     * the naive version:
     *
     * 1. **A terminator is followed by whitespace or by the end of the text** (after any closing
     *    quote or bracket). This is what stops a decimal point from splitting a sentence. It is not
     *    hypothetical: the body `"…a universe smaller than 0.1 nanometers where the traditional laws
     *    of physics collapse."` is a real generated explanation in this project's own fixtures, and
     *    a naive `lastIndexOfAny('.')` before any span after that number hands the model
     *    `"1 nanometers where…"` as the sentence the learner highlighted in.
     * 2. **A dotted initialism or a known abbreviation is not a terminator** — `e.g.`, `i.e.`,
     *    `U.S.`, `etc.`, `Dr.` The first rule cannot catch these, because their dot really is
     *    followed by a space. Every entry in [ABBREVIATIONS] is a token that is never an English
     *    word on its own, so treating it as non-terminal can never merge two real sentences; the
     *    initialism rule is derived rather than listed (every dot-separated segment a single
     *    letter).
     *
     * ### What this deliberately does not do
     *
     * It is not a sentence segmenter and must not grow into one. Explanations are 1–3 sentences of
     * plain prose with no markup, capped at 600 characters by `ExplanationValidator`, and the cost
     * of a wrong boundary is a prompt carrying slightly too much or slightly too little context —
     * not a wrong answer, so long as the span itself is intact, which is guaranteed above.
     *
     * Known and accepted: multi-letter abbreviations outside the list (`Ph.D.`, `Jan.`) end a
     * sentence early; an ellipsis ends one; a sentence quoted inside another is not tracked; text
     * with no terminator at all yields the whole body; and non-Latin terminators (`。`, `؟`) are not
     * recognised, which is correct while every prompt and every topic is English.
     */
    private fun sentenceAround(body: String, selection: SpanSelection): String {
        var start = 0
        for (i in selection.start - 1 downTo 0) {
            if (isTerminator(body, i)) {
                start = i + 1
                break
            }
        }

        var end = body.length
        // From the span's LAST character, not its first: a span containing a terminator must not be
        // truncated by its own punctuation.
        for (i in (selection.end - 1).coerceAtLeast(selection.start) until body.length) {
            if (isTerminator(body, i)) {
                end = i + 1
                break
            }
        }

        return body.substring(start, end).trim()
    }

    private fun isTerminator(body: String, index: Int): Boolean {
        val char = body[index]
        if (char != '.' && char != '!' && char != '?') return false

        // Rule 1: whitespace or the end of the text must follow, past any closing punctuation.
        var next = index + 1
        while (next < body.length && body[next] in CLOSING_PUNCTUATION) next++
        if (next < body.length && !body[next].isWhitespace()) return false

        // Rule 2 applies to '.' only — nothing is abbreviated with '!' or '?'.
        return char != '.' || !endsAbbreviation(body, index)
    }

    private fun endsAbbreviation(body: String, dot: Int): Boolean {
        var from = dot
        while (from > 0 && (body[from - 1].isLetter() || body[from - 1] == '.')) from--
        val token = body.substring(from, dot)
        if (token.isEmpty()) return false

        // "e.g", "U.S", "J" — every dot-separated segment is one letter, so this is an initialism
        // or a single initial, never the end of a sentence.
        if (token.split('.').all { it.length == 1 && it[0].isLetter() }) return true

        return token.lowercase() in ABBREVIATIONS
    }

    private companion object {

        private const val CLOSING_PUNCTUATION = "\"'”’)]}»"

        /**
         * Abbreviations that end in a dot and are followed by a space.
         *
         * Every entry is a token that is never an English word in its own right. That is the
         * selection rule, and it is what makes the list safe: a false positive here would merge two
         * real sentences, which is a worse error than the one it prevents. ("no." and "fig." are
         * omitted for exactly that reason — both are ordinary words that end sentences.)
         */
        private val ABBREVIATIONS = setOf(
            "etc", "vs", "al", "cf", "approx", "eq", "ref",
            "mr", "mrs", "ms", "dr", "prof", "st",
        )
    }
}
