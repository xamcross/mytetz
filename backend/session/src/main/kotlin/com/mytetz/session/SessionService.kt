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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
 * What [SessionService.create] produced.
 *
 * There is deliberately **no cost field**. A return value only exists when the call succeeded, and
 * the seed's generation can be paid for and then fail — the validator rejects an over-long body, or
 * the insert throws — at which point this object is never constructed and a cost carried on it would
 * be lost. Spend is delivered through [SessionService.create]'s `onSpend` instead, the instant it is
 * known. See [com.mytetz.graph.GraphChunk.Spent].
 */
data class SessionCreation(
    val session: LearningSession,
    val seed: Explanation,
)

/**
 * A validated, priced-out explain request that has not been executed yet.
 *
 * Only [SessionService.prepare] can make one — the constructor is `internal` deliberately, because
 * a plan that skipped [SessionService.validateSpan] would be a way for a caller to hand the model a
 * span of its own invention, which is precisely what the gate exists to prevent.
 *
 * [contentKey] and [cached] are the whole point of the type; see "Telling the API layer whether a
 * request will spend money" on [SessionService].
 *
 * ## Single use, and enforced rather than documented
 *
 * A plan carries the ceiling decisions [SessionService.prepare] made against the session *as it was
 * then*. [SessionService.explain] re-checks none of them, so executing one plan N times appends N
 * nodes and walks straight past [SessionLimits.maxNodes]. Rather than trust a comment, executing a
 * plan twice raises: the second collection of `explain(plan)` fails before it reaches the model.
 *
 * A caller that wants to run the same request again must call [SessionService.prepare] again, which
 * is what it should want anyway — the ceilings and [cached] are both stale by then.
 *
 * What this does *not* close is two `prepare` calls racing on one session, which can put the node
 * count one over the ceiling per concurrent request. That bound is set by the API layer's
 * concurrency and not by this class, exactly as `QuotaService` documents for its own
 * check-then-record pair; a version that claimed otherwise would be lying.
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
     * A snapshot that can go stale, and **not** in only the harmless direction — read the staleness
     * section on [SessionService] before putting a quota refusal behind it.
     */
    val cached: Boolean,
    internal val request: GraphRequest,
) {
    private val executed = AtomicBoolean(false)

    /** True the first time only. See "Single use" above. */
    internal fun claim(): Boolean = executed.compareAndSet(false, true)
}

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
 * ### The verdict is a snapshot, and the stale direction is not harmless
 *
 * Two facts, and only the first is comfortable.
 *
 * **A hit stays a hit.** Nothing ever deletes an explanation — `ExplanationRepository` has no delete
 * method and the collection carries no TTL index — so a key that existed at [prepare] still exists
 * at [explain]. `cached = true` never becomes false, and no generation can slip past an exhausted
 * budget on a stale plan.
 *
 * **A miss frequently becomes a hit, and refusing it is a real failure.** Another caller can persist
 * the same key between [prepare] and [explain]. An API layer that has already turned
 * `cached = false` plus `SpendLimitReached` into a refusal has then refused a request that had
 * become free — and `QuotaService.checkGeneration` is explicit that callers must keep serving cache
 * hits while the breaker is tripped, because that is what keeps the site usable during a spend
 * incident. These are not separate regimes: the window in which plans go stale and the window in
 * which the breaker is tripped are the same window, heavy generation traffic.
 *
 * **And `cached = false` is not proof that *this* caller will spend anything.** `ExplanationGraph`
 * emits `Meta` before taking its per-key lock, so in a stampede every caller sees `cached = false`
 * and exactly one of them pays. [ExplainPlan.cached] is read *earlier* than that `Meta`, so it is
 * strictly the less fresh of the two.
 *
 * ### So, concretely, for Task 1.12
 *
 * - Consult the quota when `!plan.cached`. That part is what the flag is for.
 * - **Before turning `SpendLimitReached` into a refusal, call [prepare] again and re-read
 *   [ExplainPlan.cached].** A `prepare` is a handful of indexed reads and calls no model, so the
 *   re-check is cheap, and it is the only thing standing between a spend incident and refusing
 *   requests the cache could have served for nothing.
 * - **Do not** use `plan.cached` or `Meta(cached = false)` as the trigger for
 *   `QuotaService.recordGeneration`. Neither one means "this caller spent money", and in a stampede
 *   both over-report by the width of the stampede — which trips the breaker early, on spend that
 *   never happened. `GraphChunk.Spent` is the chunk that does mean it — emitted only by the caller
 *   that called the model, carrying that caller's own cost, and emitted *before* anything that could
 *   fail afterwards. [create] delivers the same thing through its `onSpend` callback.
 *
 * ## Authorisation is the caller's, and this class does none of it
 *
 * [create] records a `principalId`. **Nothing else here ever reads it.** [prepare], [explain] and
 * [load] take a session id and act on whatever session carries it, so anyone holding an id can read
 * another learner's tree and append to it, spending its node budget.
 *
 * That is the shape the plan specifies, and it is written down here rather than left to be inferred
 * precisely because the section above spends thirty lines telling the API layer what it must do
 * about quota. The asymmetry would otherwise read as "quota is yours, ownership is handled" — it is
 * not. **The caller must establish that the principal owns the session before calling any of the
 * three**, and [ownerOf] is the cheap read that lets it do so first — before [prepare]'s refusals
 * start describing a session the caller may have no business knowing exists.
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
     * [onSpend] is called with what this caller's own model call cost, if it made one, **while the
     * generation is still in flight** — before the session document is written and before anything
     * below can raise. That ordering is the contract, not an implementation detail: a seed can be
     * generated, billed, and then rejected by the validator, and a caller that read the cost off the
     * return value would never see it. It is a callback rather than a field for exactly that reason,
     * and it takes a `Long` rather than anything from `:backend:quota`, which this module must not
     * depend on and does not.
     *
     * **It has no default**, for the same reason [com.mytetz.graph.GraphChunk.Spent] is emitted
     * rather than defaulted: a caller that forgets it bills nobody, and the two errors are not
     * symmetric — over-reporting is noticed within a day, under-reporting is invisible until the
     * invoice. A `= {}` would compile at every future call site and silently spend nothing. The
     * price is `{}` at the handful of test call sites that do not care, which is the right way round:
     * the ones that do not care say so.
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
    suspend fun create(
        principalId: String,
        topicSlug: String,
        onSpend: suspend (Long) -> Unit,
    ): SessionCreation {
        val topic = requirePublishedTopic(topicSlug)

        var seed: Explanation? = null
        graph.getOrGenerate(seedRequest(topic)).collect { chunk ->
            when (chunk) {
                // The instant the money is known, and before anything below can fail. A caller that
                // recorded spend from the return value instead would lose every generation that was
                // billed and then rejected — see the note on `onSpend`.
                is GraphChunk.Spent -> onSpend(chunk.costMicros)
                is GraphChunk.Done -> seed = chunk.explanation
                is GraphChunk.Meta, is GraphChunk.Delta, is GraphChunk.Superseded -> Unit
            }
        }

        val seedExplanation = seed
            ?: throw IllegalStateException("graph produced no Done chunk for the seed of ${topic.slug}")

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
                    explanationKey = seedExplanation.key,
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
        return SessionCreation(session, seedExplanation)
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

    /**
     * The method generates this topic's seed when the store holds none. The method creates no
     * session.
     *
     * A published topic must have a seed. No other method here can establish that. [create] is
     * the only other path to a seed. [create] also inserts a [LearningSession]. A maintenance
     * loop built on [create] therefore leaves one abandoned session for each topic.
     *
     * The method returns true when it generated the seed itself. The method returns false when
     * it found an existing seed.
     *
     * The method calls [onSpend] with the caller's own cost at the moment it learns that cost.
     * The method does this before any later step can fail. [create]'s parameter of the same name
     * holds the same contract for the same reason.
     *
     * The method raises an error for an unknown topic and for an unpublished topic. [create] and
     * [createWillGenerate] raise the same error.
     */
    suspend fun prewarmSeed(topicSlug: String, onSpend: suspend (Long) -> Unit): Boolean {
        val topic = requirePublishedTopic(topicSlug)
        val request = seedRequest(topic)

        if (explanations.findByKey(graph.keyFor(request)) != null) return false

        var generated = false
        graph.getOrGenerate(request).collect { chunk ->
            when (chunk) {
                is GraphChunk.Spent -> {
                    generated = true
                    onSpend(chunk.costMicros)
                }
                is GraphChunk.Done, is GraphChunk.Meta, is GraphChunk.Delta, is GraphChunk.Superseded -> Unit
            }
        }
        return generated
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
     *
     * Does **not** check that the principal owns [sessionId]. See "Authorisation is the caller's".
     */
    suspend fun prepare(
        sessionId: String,
        parentNodeId: String,
        selection: SpanSelection,
        verb: Verb,
        requestedVariant: Int?,
    ): ExplainPlan {
        // SEED is the create path, not a learner action, and admitting it here is worse than it
        // looks in both directions.
        //
        // Reading: `ExplanationGraph.keyFor` branches on SEED and returns
        // `ContentKey.seed(topicSlug, …)`, ignoring parentKey, span and variant entirely. So this
        // request would resolve to the topic's OPENING PARAGRAPH — already in the store, because
        // `create` put it there — and hand it back, free and instant, as the answer to whatever the
        // learner highlighted.
        //
        // Writing: it would then append a node carrying verb = SEED at depth >= 1 with the
        // learner's real span on it, and every later chain through that node would render the seed
        // body a second time in place of the step they actually took. `buildAncestors` no longer
        // keys on the verb, which closes that half independently — but a request that can only
        // produce a wrong answer should not reach the store at all.
        require(verb != Verb.SEED) {
            "SEED is not a learner action: use create() to open a session on a topic"
        }

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

        // Both ends. `requestedVariant` is client-supplied and reaches this arithmetic unfiltered,
        // and a ceiling tested only from above is not a ceiling: -1, -2, -3 are all <= maxVariants,
        // each is a distinct ContentKey.derive(…, variant.toString(), …), and so each is a fresh
        // PAID generation of the same span under the same verb — without bound. It would also
        // poison ContextChain.highestVariant, whose whole contract rests on no stored variant being
        // below 0: one stored -1 makes maxOfOrNull answer -1, the next auto-numbered SIDE_VIEW
        // becomes variant 0, and that is exactly the collision its KDoc warns hands the learner a
        // content key they have already been shown.
        if (variant < 0 || variant > limits.maxVariants) {
            throw VariantLimitException(
                "variant $variant is outside the permitted range 0..${limits.maxVariants}"
            )
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
     *
     * Does **not** check that the principal owns the session; see "Authorisation is the caller's".
     * Each [ExplainPlan] may be collected once — see [ExplainPlan].
     */
    fun explain(plan: ExplainPlan): Flow<GraphChunk> = flow {
        // Before anything, and inside the flow builder so it fires per collection rather than per
        // call: this plan carries ceiling decisions taken against the session as it was at prepare
        // time, and nothing below re-checks them.
        if (!plan.claim()) {
            throw IllegalStateException(
                "this ExplainPlan has already been executed; call prepare() again for a new one"
            )
        }

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

    /**
     * [prepare] then [explain]. The single-call form, for a caller with no quota gate to run.
     *
     * Does **not** check that the principal owns [sessionId]. See "Authorisation is the caller's".
     */
    fun explain(
        sessionId: String,
        parentNodeId: String,
        selection: SpanSelection,
        verb: Verb,
        requestedVariant: Int?,
    ): Flow<GraphChunk> = flow {
        emitAll(explain(prepare(sessionId, parentNodeId, selection, verb, requestedVariant)))
    }

    // ------------------------------------------------------------------ ownership

    /**
     * The principal [create] recorded against [sessionId], or null when there is no such session.
     *
     * This class still decides nothing about authorisation — it hands over the stored fact and the
     * caller makes the decision. It exists so the caller can make it **cheaply and first**: one
     * indexed read by `_id`, no chain walk, no hydrate, no catalogue lookup. [load] would answer the
     * same question, but only after fetching every explanation the session points at, and [prepare]
     * only after running the whole gate — whose refusals (`SPAN_MISMATCH`, `DEPTH_LIMIT`,
     * `SESSION_FULL`) are each a statement about the *contents* of a session the caller may have no
     * business knowing exists.
     *
     * So the order is: this, then everything else. See "Authorisation is the caller's".
     */
    suspend fun ownerOf(sessionId: String): String? = sessions.findById(sessionId)?.principalId

    // ------------------------------------------------------------------ load

    /**
     * The session and every explanation it points at, or null when there is no such session.
     *
     * Null for an absent session — this is a lookup, and the caller asked whether it is there.
     * A session that *is* there but names an explanation the store does not have raises
     * [CorruptSessionException] instead of quietly returning a map with a hole in it, for the same
     * reason [hydrate] does: a partial map renders as a session tree with a missing step, which
     * reads to the learner as a bug in the page rather than as the data incident it is.
     *
     * Does **not** check that the principal owns [sessionId] — this will hand any caller holding an
     * id the whole of that learner's tree. See "Authorisation is the caller's".
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
     * parent is the most important link of all. The root carries an empty span, and an empty
     * `They highlighted ""` line would read to the model as a highlight of nothing.
     *
     * **The substitution is keyed on root-ness, not on the verb**, and the difference is not
     * cosmetic. `pathTo` returns exactly one node with a null parent and it is the root, so the two
     * rules agree on every session this service writes. They stop agreeing the moment a node
     * carrying `Verb.SEED` exists at depth — which [prepare] now refuses to create, but which a
     * document written before that refusal existed, or any direct `appendNode`, still can. Under
     * the verb rule such a node reports that the learner highlighted "the topic introduction" when
     * they highlighted something else, renders the seed body twice, and erases their real step from
     * the ancestry — silently, with nothing raised, which is the exact failure this class exists to
     * prevent. Root-ness cannot be spoofed by an enum value.
     */
    private fun buildAncestors(path: List<SessionNode>, bodies: Map<String, Explanation>): List<Ancestor> =
        path.map { node ->
            Ancestor(
                span = if (node.parentNodeId == null) "the topic introduction" else node.span,
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
        // Blank, not merely empty. A run of spaces really can sit at the offsets it claims, so the
        // checks below would wave it through — and the result is `They have now highlighted: " "` in
        // the prompt and a permanent content key minted for a highlight of nothing.
        if (selection.text.isBlank()) {
            throw SpanMismatchException("a span must contain something to explain, not only whitespace")
        }
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
     * 2. **A dotted initialism or a listed abbreviation is not a terminator** — `e.g.`, `i.e.`,
     *    `U.S.`, `etc.`, `Dr.` The first rule cannot catch these, because their dot really is
     *    followed by a space.
     *
     * ### The asymmetry that governs rule 2
     *
     * The two ways of being wrong are not equally bad. **Splitting a sentence too early** costs the
     * prompt a clause of context. **Merging two real sentences** hands the model a run-on that was
     * never one sentence, and at the extreme returns the whole body — which is exactly the shape of
     * failure rule 1 exists to prevent, arriving through the door meant to fix it. So rule 2 is
     * built to be incapable of merging:
     *
     * - **Listed half:** every entry in [ABBREVIATIONS] is a token that is never an English word on
     *   its own. That is the selection rule, and it is why `no.`, `fig.`, `ms.` and `st.` are *not*
     *   listed — "the answer is no.", "…and a fig.", "…decays in 20 ms." and "the 21st." are all
     *   ordinary sentence endings, and listing them would trade one bug for a worse one.
     * - **Derived half:** an initialism must contain an internal dot. `e.g`, `U.S` and `J.R.R` all
     *   qualify; a bare single letter does not. Without that requirement `"…the field is denoted B.
     *   The strength falls off…"` reads `B` as an initial and merges the two sentences, and prose
     *   about physics or biology reaches for that shape constantly.
     *
     * ### What this deliberately does not do
     *
     * It is not a sentence segmenter and must not grow into one. Explanations are 1–3 sentences of
     * plain prose with no markup, capped at 600 characters by `ExplanationValidator`, and the cost
     * of a wrong boundary is a prompt carrying slightly too much or slightly too little context —
     * not a wrong answer, so long as the span itself is intact, which is guaranteed above.
     *
     * Known and accepted, and every one of them errs toward splitting early rather than merging:
     * multi-letter abbreviations outside the list (`Ph.D.`, `Jan.`) end a sentence; a *bare* single
     * initial ends one, so `"J. R. R. Tolkien"` splits three times — the deliberate price of the
     * internal-dot requirement above; an ellipsis ends a sentence; a sentence quoted inside another
     * is not tracked; text with no terminator at all yields the whole body; and non-Latin
     * terminators (`。`, `؟`) are not recognised, which is correct while every prompt and every topic
     * is English.
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
        // truncated by its own punctuation. `end - 1 >= start` needs no clamp — validateSpan has
        // already established `end > start`, and it is the only caller's only predecessor.
        for (i in selection.end - 1 until body.length) {
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

        // "e.g", "U.S", "J.R.R" — an INTERNAL dot, then every segment a single letter. The internal
        // dot is what stops a sentence ending in a bare initial ("…denoted B.") from being read as
        // an initialism and merged into the next one; see the asymmetry note on sentenceAround.
        if (token.contains('.') && token.split('.').all { it.length == 1 && it[0].isLetter() }) {
            return true
        }

        return token.lowercase() in ABBREVIATIONS
    }

    private companion object {

        private const val CLOSING_PUNCTUATION = "\"'”’)]}»"

        /**
         * Abbreviations that end in a dot and are followed by a space.
         *
         * **Every entry is a token that is never an English word in its own right.** That is the
         * selection rule and it is not decoration: a false positive here merges two real sentences,
         * which is the worse of the two errors (see the asymmetry note on [sentenceAround]).
         *
         * Omitted for exactly that reason, each having failed the rule: `no.` ("the answer is no."),
         * `fig.` ("…and a fig."), `ref.` ("…ask the ref."), `ms.` ("…decays in 20 ms.") and `st.`
         * ("the 21st." — the backward scan stops at the digit and sees the bare token `st`).
         */
        private val ABBREVIATIONS = setOf(
            "etc", "vs", "al", "cf", "approx", "eq",
            "mr", "mrs", "dr", "prof",
        )
    }
}
