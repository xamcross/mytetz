package com.mytetz.api

import com.mytetz.graph.GraphChunk
import com.mytetz.graph.Verb
import com.mytetz.quota.PrincipalId
import com.mytetz.quota.QuotaDecision
import com.mytetz.quota.QuotaService
import com.mytetz.session.ExplainPlan
import com.mytetz.session.LearningSession
import com.mytetz.session.SessionNotFoundException
import com.mytetz.session.SessionService
import com.mytetz.session.SpanSelection
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class CreateSessionRequest(val topicSlug: String)

@Serializable
data class SpanPayload(val text: String, val start: Int, val end: Int)

@Serializable
data class ExplainRequest(
    val parentNodeId: String,
    val span: SpanPayload,
    val verb: Verb = Verb.EXPLAIN,
    val variant: Int? = null,
)

@Serializable
data class NodeView(
    val nodeId: String,
    val parentNodeId: String?,
    val explanationKey: String,
    val span: String,
    val verb: Verb,
    val variant: Int,
    val depth: Int,
)

@Serializable
data class SessionView(
    val sessionId: String,
    val topicSlug: String,
    val rootNodeId: String,
    val currentNodeId: String,
    val nodes: List<NodeView>,
    val explanations: Map<String, String>,
)

/**
 * The first event of every stream.
 *
 * [cached] is `ExplanationGraph`'s own flag and describes the store as *this* caller found it, before
 * the per-key lock — so it is a hint for the client's "generating…" affordance and nothing else.
 * Nothing on the server side branches on it, and nothing bills on it; see `GraphChunk.Done`.
 *
 * There is no node id here, and none on [DoneEvent] either. `SessionService.explain` mints the node
 * id inside the flow and does not surface it, and cancellation is specified to drop the node
 * entirely — so any id sent before the stream ends would be a promise this endpoint cannot keep. A
 * client that needs the new node re-reads `GET /api/sessions/{id}` after `done`.
 */
@Serializable
data class MetaEvent(val contentKey: String, val cached: Boolean)

@Serializable
data class DeltaEvent(val t: String)

/**
 * Discard everything streamed so far for this key and render [body] instead.
 *
 * Rare, and never within one process. See `GraphChunk.Superseded`: this fires when another instance
 * persisted the key first, so the prose the learner has been reading is this instance's own sampling
 * and is *not* what was stored — which matters because quiz and exam generation read the stored body.
 */
@Serializable
data class SupersededEvent(val body: String)

@Serializable
data class DoneEvent(val contentKey: String, val grounded: Boolean)

private val json = Json { encodeDefaults = true }

private val log = LoggerFactory.getLogger("com.mytetz.api.SessionRoutes")

/** Logged when the ledger is known to understate reality, so a spend gap is greppable. */
internal const val SPEND_UNRECORDED_ALERT: String = "SPEND_UNRECORDED"

/** How many sessions one caller address may open per [SESSION_WINDOW_MILLIS]. */
const val SESSIONS_PER_CALLER: Int = 30

const val SESSION_WINDOW_MILLIS: Long = 60L * 60 * 1000

/**
 * The largest body either session endpoint will read at all.
 *
 * Both payloads are a handful of short fields — the longest, an explain span, is bounded by
 * `ExplanationValidator`'s 600-character ceiling on the body it must appear in. Enforced on
 * `Content-Length` **before** `receive`, for the reason [MAX_TOPIC_REQUEST_BODY_BYTES] gives: by the
 * time a converter can object, the body has already been buffered on a 512 MB machine.
 */
const val MAX_SESSION_BODY_BYTES: Long = 4_096

/**
 * `POST /api/sessions`, `GET /api/sessions/{id}` and `POST /api/sessions/{id}/explain`.
 *
 * This is the only endpoint in the system that can spend money, so most of what follows is about the
 * three properties that have to hold at it.
 *
 * ## 1. A refused request must generate nothing
 *
 * Every gate runs **before** the response begins: body, ownership, `prepare`, quota. Only then does
 * the stream open. That ordering is the property, and it is also what lets a refusal carry a real
 * status code and a real `Retry-After` — a 429 a client can act on, rather than a 200 whose body
 * says the word "error".
 *
 * The version this replaced gated *inside* `collect { }` and wrote `return@collect` in both refusal
 * arms. In Kotlin that returns from the lambda for one chunk and collection carries on, so the
 * endpoint sent an error event and then generated anyway: every delta streamed, `done` fired, and
 * the model call was paid for. Both arms were decorative. Nothing about that was visible in a test,
 * because the test for it asserted only that cache hits survived the breaker.
 *
 * ## 2. A cache hit must be served even when the breaker is tripped
 *
 * `QuotaService.checkGeneration` gates *generation*; a hit costs nothing and serving hits is what
 * keeps the site usable during a spend incident. So the quota is consulted only when
 * `ExplainPlan.cached` is false — and, when the answer is a refusal, `prepare` is run **again**
 * before the refusal is final.
 *
 * That re-check is not defensive programming. `SessionService` documents that a plan's `cached` is a
 * snapshot whose stale direction is the harmful one: another caller can persist the same key between
 * `prepare` and the gate, and the window in which that happens is the same window in which the
 * breaker is tripped — heavy generation traffic. Re-running `prepare` calls no model. Refusing a
 * request that had become free is a self-inflicted outage on the most popular topic in the
 * catalogue.
 *
 * **Of the two, the re-check is the load-bearing half and the `!plan.cached` guard is an
 * optimisation.** Mutation testing established that, rather than the comment asserting it: forcing
 * the guard to `true`, so that every request consults the quota, changes no observable behaviour,
 * because a hit's re-check discards the verdict anyway. It costs one ledger read. Stated because the
 * opposite reading — that the guard is what protects cache hits — is the natural one, and it would
 * make removing the re-check look safe.
 *
 * ## 3. Spend is recorded once, by the caller that spent it, for what it cost
 *
 * The trigger is `GraphChunk.Done.spentMicros` and nothing else. Neither `plan.cached` nor
 * `Meta(cached = false)` means "this caller spent money": `ExplanationGraph` emits `Meta` before
 * taking the per-key lock, deliberately, so that first-byte latency is not held behind somebody
 * else's generation — which means that in a stampede *every* caller sees `cached = false` and
 * exactly one of them pays. And `Done.explanation.costMicros` is the *document's* cost, i.e. the
 * winner's, so billing it charges every loser for a generation it never made. Both mistakes
 * over-report by the width of the stampede and trip the global breaker early, on money nobody spent.
 *
 * ## Ownership is enforced here, because nothing below can
 *
 * `SessionService` reads a session id and acts on whatever session carries it — its KDoc says so at
 * length — so without a check here anyone holding an id can read another learner's tree and append
 * to it, spending its node budget. The signed cookie is *identity*: it stops a client choosing its
 * own principal, and confers nothing.
 *
 * A session that exists but belongs to somebody else gets **the same 404 as one that does not
 * exist**, from the same [SessionNotFoundException], and the check runs before `prepare`. Any other
 * answer — a 403, or `prepare`'s `SPAN_MISMATCH`/`SESSION_FULL` refusals, each of which is a
 * statement about the contents of that session — turns a guessed id into an oracle for which ids are
 * real.
 *
 * ## Why `post` + `SSEServerContent` and not `sse(path)`
 *
 * `Route.sse(path) { }` registers `route(path, HttpMethod.Get)` — GET only, verified in Ktor 3.1.2's
 * `ktor-server-sse/.../Routing.kt`. The request carries a JSON body naming the parent node and the
 * span, so it is a POST. Two shapes were available:
 *
 * - `route(path, HttpMethod.Post) { sse { … } }` — the path-less `sse` overload registers a
 *   method-agnostic `handle`, so this works. But the body would have to be read *inside* the SSE
 *   session, which is after the response is committed, and every refusal above would collapse back
 *   into a 200 carrying an error event.
 * - `post(path) { …gates…; call.respond(SSEServerContent(call) { … }) }` — `SSEServerContent` is
 *   public and documented as "an OutgoingContent response object that could be used to respond()".
 *
 * The second, for property 1. The four headers Ktor's own `processSSE` sets are set here instead;
 * `Content-Type: text/event-stream` comes from `SSEServerContent` itself.
 *
 * The `SSE` plugin is deliberately **not** installed: it is `createApplicationPlugin("SSE") {}`, an
 * empty marker whose only purpose is the `plugin(SSE)` assertion inside `processSSE`, which this
 * file does not call. Installing it would read as load-bearing configuration and do nothing.
 */
fun Route.sessionRoutes(
    sessions: SessionService,
    quota: QuotaService,
    cookies: PrincipalCookieConfig,
    clientAddresses: ClientAddressConfig = ClientAddressConfig(),
    sessionLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        limit = SESSIONS_PER_CALLER,
        windowMillis = SESSION_WINDOW_MILLIS,
    ),
) {

    /**
     * `POST /api/sessions`.
     *
     * ## What bounds this endpoint, given that it is unauthenticated and writes a document per call
     *
     * Two different things are unbounded here and only one of them is money.
     *
     * **Spend is bounded by the catalogue.** A seed is content addressed per topic, so the whole
     * catalogue is generated at most once per prompt version and model family — a few dollars, once,
     * for the life of a deployment. It is gated anyway, through `createWillGenerate`, because a
     * *global* breaker that one endpoint ignores is not global; and because the re-check that makes
     * a stale miss survive is the same three lines as on the explain path.
     *
     * **Session documents are not bounded, and cannot be from here.** Nothing expires them —
     * `SessionRepository` says so deliberately, they are the learner's record of what they read — so
     * the honest statement is that this limits the *rate* at which one caller can create them and
     * not the total. Keyed on [ClientAddress], not the principal, for the reason
     * `FixedWindowRateLimiter` gives: `Principals.resolve` mints a fresh principal for any request
     * without a valid cookie, so a per-principal limit limits only callers polite enough to return
     * their cookie. A per-principal session cap, or a TTL on abandoned sessions, is a product
     * decision nobody has made; it is written down here rather than left to be discovered from a
     * disk-usage alert.
     */
    post("/api/sessions") {
        if (!call.bodyIsSmallEnough()) return@post

        val caller = ClientAddress.of(call, clientAddresses)
        if (!sessionLimiter.tryAcquire(caller)) {
            log.info("rate limited session creation from {}", caller)
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiError("RATE_LIMITED", "too many sessions started; try again later", SESSION_WINDOW_MILLIS / 1000),
            )
            return@post
        }

        // After the limiter, so a refused request costs no cookie — the same ordering, for the same
        // reason, as `POST /api/topic-requests`.
        val principal = Principals.resolve(call, cookies)
        val request = call.receive<CreateSessionRequest>()

        // `createWillGenerate` raises for an unknown or unpublished topic exactly as `create` does,
        // so the gate cannot answer a question `create` would have refused.
        if (sessions.createWillGenerate(request.topicSlug)) {
            val refusal = quota.refusalFor(principal) { !sessions.createWillGenerate(request.topicSlug) }
            if (refusal != null) {
                call.respondRefusal(refusal)
                return@post
            }
        }

        val created = sessions.create(principal.value, request.topicSlug)
        quota.recordSpend(principal, created.spentMicros)

        call.respond(created.session.toView(mapOf(created.seed.key to created.seed.body)))
    }

    get("/api/sessions/{id}") {
        val principal = Principals.resolve(call, cookies)
        val id = call.parameters["id"].orEmpty()

        sessions.requireOwnedBy(id, principal)

        // Not `?: throw`: the ownership check above has already established that this session exists
        // and is this principal's, so a null here means it was deleted between two reads, which is
        // the same 404 by a different route.
        val (session, bodies) = sessions.load(id) ?: throw SessionNotFoundException(id)
        call.respond(session.toView(bodies.mapValues { it.value.body }))
    }

    post("/api/sessions/{id}/explain") {
        if (!call.bodyIsSmallEnough()) return@post

        val principal = Principals.resolve(call, cookies)
        val sessionId = call.parameters["id"].orEmpty()
        val request = call.receive<ExplainRequest>()

        // First, and before `prepare`: its refusals describe the contents of the session. See
        // "Ownership is enforced here".
        sessions.requireOwnedBy(sessionId, principal)

        suspend fun prepare() = sessions.prepare(
            sessionId = sessionId,
            parentNodeId = request.parentNodeId,
            selection = SpanSelection(request.span.text, request.span.start, request.span.end),
            verb = request.verb,
            requestedVariant = request.variant,
        )

        // Everything `explain` does except call the model — the span gate, the three ceilings, the
        // content key. Raises, and StatusPages answers, because nothing has been sent yet.
        var plan = prepare()

        if (!plan.cached) {
            val refusal = quota.refusalFor(principal) {
                // Re-read, and use the fresher plan: another caller may have persisted this key
                // since. `prepare` calls no model. See property 2.
                plan = prepare()
                plan.cached
            }
            if (refusal != null) {
                call.respondRefusal(refusal)
                return@post
            }
        }

        // Past this line nothing may be `respond`ed: the status and headers go out with the first
        // byte, and StatusPages cannot rewrite a response that has begun. Failures below become
        // `error` events.
        call.response.headers.append(HttpHeaders.CacheControl, "no-store, no-transform")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        // Inert on this deployment, and kept anyway: it is what Ktor's own `processSSE` sets, and
        // costs one header. Nothing in `browser -> Cloudflare -> fly Anycast -> Netty` reads it —
        // there is no nginx in the chain. What actually stops this stream being buffered is the
        // Cloudflare cache-bypass rule for `/api/*` in `docs/deploy.md` section 5.4, and if that
        // rule is missing this header will not save it.
        call.response.headers.append("X-Accel-Buffering", "no")

        call.respond(
            SSEServerContent(call) {
                streamExplanation(sessions, quota, principal, plan)
            }
        )
    }
}

/**
 * The stream itself, once every gate has passed.
 *
 * The `catch` clauses are the whole of the body's risk surface, and their order is load bearing.
 * [CancellationException] descends from `RuntimeException`, so a bare `catch (e: Exception)` relabels
 * a learner who navigated away mid-answer as an `INTERNAL` server error — the third time this exact
 * defect has appeared in this project, after `Mongo.ping()` and `ExplanationGraph.generate`. It is
 * not cosmetic here either: the cancellation has to reach `SessionService.explain`, which is
 * specified to drop the abandoned node rather than resume the learner on a branch they walked away
 * from.
 */
private suspend fun ServerSSESession.streamExplanation(
    sessions: SessionService,
    quota: QuotaService,
    principal: PrincipalId,
    plan: ExplainPlan,
) {
    try {
        var spentMicros = 0L

        sessions.explain(plan).collect { chunk ->
            if (chunk is GraphChunk.Done) spentMicros = chunk.spentMicros
            send(eventFor(chunk))
        }

        // Only the caller that actually called the model, and only for what its own call cost. See
        // property 3 on `sessionRoutes`. Runs after the stream so that a cancelled request records
        // nothing — consistent with the node not being written either.
        quota.recordSpend(principal, spentMicros)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        send(ServerSentEvent(event = "error", data = json.encodeToString(sseErrorFor(e))))
    }
}

/**
 * One `GraphChunk`, one SSE event.
 *
 * Exhaustive on purpose: `GraphChunk` is sealed, so a chunk added later stops this compiling rather
 * than being dropped on the floor. That matters most for `Superseded`, whose whole reason for being
 * a chunk rather than a flag on `Done` is that a client must not be able to miss it.
 */
internal fun eventFor(chunk: GraphChunk): ServerSentEvent = when (chunk) {
    is GraphChunk.Meta -> ServerSentEvent(
        event = "meta",
        data = json.encodeToString(MetaEvent(contentKey = chunk.contentKey, cached = chunk.cached)),
    )

    is GraphChunk.Delta -> ServerSentEvent(
        event = "delta",
        data = json.encodeToString(DeltaEvent(chunk.text)),
    )

    is GraphChunk.Superseded -> ServerSentEvent(
        event = "superseded",
        data = json.encodeToString(SupersededEvent(chunk.body)),
    )

    is GraphChunk.Done -> ServerSentEvent(
        event = "done",
        data = json.encodeToString(DoneEvent(chunk.explanation.key, chunk.explanation.grounded)),
    )
}

/**
 * A refusal decided before anything was sent: a status, a body and, where there is one, a wait.
 *
 * `Retry-After` is a response *header* as well as a field, because that is what a proxy, a client
 * library and a well-behaved crawler all read.
 */
private class Refusal(val status: HttpStatusCode, val error: ApiError)

private suspend fun ApplicationCall.respondRefusal(refusal: Refusal) {
    refusal.error.retryAfter?.let { response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
    respond(refusal.status, refusal.error)
}

/**
 * Runs the quota gate for a request that would generate, and returns the refusal — or null, meaning
 * proceed.
 *
 * [becameFree] is re-evaluated before **any** refusal is returned, and it is the whole point of this
 * function. `SessionService` requires it for `SpendLimitReached`; `QuotaService.checkGeneration`
 * requires the same of `PrincipalExceeded` when it says "callers must keep serving cache hits when
 * this is not Allowed". One code path so that the two cannot disagree, and so there is no branch in
 * which the re-check is forgotten.
 *
 * ## Mongo being unavailable is a refusal, not an exception
 *
 * `QuotaService` deliberately leaves this decision to its caller and recommends failing closed: a
 * check that could not be evaluated is not evidence that budget remains, and this component exists to
 * stop a runaway bill. So an unreachable ledger refuses *generation* — and, because [becameFree] is
 * still consulted, keeps serving everything the cache can answer for nothing. It gets its own code
 * rather than borrowing `SPEND_LIMIT`, because "we cannot tell" and "the budget is gone" need
 * different things from an operator.
 */
private suspend fun QuotaService.refusalFor(
    principal: PrincipalId,
    becameFree: suspend () -> Boolean,
): Refusal? {
    val decision = try {
        checkGeneration(principal)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("the quota check could not be evaluated; refusing generation and serving cache only", e)
        null
    }

    val refusal = when (decision) {
        QuotaDecision.Allowed -> return null

        is QuotaDecision.PrincipalExceeded -> Refusal(
            HttpStatusCode.TooManyRequests,
            ApiError(
                code = "QUOTA_EXCEEDED",
                message = "you have used today's allowance of new explanations",
                retryAfter = decision.retryAfterSeconds,
            ),
        )

        // 503, not 429: the caller did nothing wrong and there is nothing they can do differently.
        // No `retryAfter` — the budget resets at midnight UTC, and this layer holds no clock of its
        // own; a number invented here would be a promise made by the wrong component.
        QuotaDecision.SpendLimitReached -> Refusal(
            HttpStatusCode.ServiceUnavailable,
            ApiError("SPEND_LIMIT", "new explanations are paused for today; cached ones still work"),
        )

        null -> Refusal(
            HttpStatusCode.ServiceUnavailable,
            ApiError("QUOTA_UNAVAILABLE", "new explanations are paused; cached ones still work"),
        )
    }

    return if (becameFree()) null else refusal
}

/**
 * Records what a request actually spent, and never fails the request for it.
 *
 * By the time this runs the money is gone, so withholding the answer wastes it — `QuotaService` says
 * exactly that. A failure is the one condition under which the ledger is known to understate
 * reality, so it is logged under a token an operator can alert on rather than swallowed.
 *
 * Zero is not recorded. `recordGeneration` increments a *count* as well as a cost, so recording a
 * zero would spend one of the principal's daily explanations on a cache hit.
 */
private suspend fun QuotaService.recordSpend(principal: PrincipalId, spentMicros: Long) {
    if (spentMicros <= 0) return
    try {
        recordGeneration(principal, spentMicros)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(
            "$SPEND_UNRECORDED_ALERT principal={} costMicros={} — the generation was paid for and the " +
                "ledger did not receive it; the daily spend breaker is now understating reality",
            principal.value,
            spentMicros,
            e,
        )
    }
}

/**
 * The same 404 for "no such session" and "not yours", raised from one place so neither route can
 * answer them differently. See "Ownership is enforced here".
 */
private suspend fun SessionService.requireOwnedBy(sessionId: String, principal: PrincipalId) {
    if (ownerOf(sessionId) != principal.value) throw SessionNotFoundException(sessionId)
}

/** False once a refusal has been sent. See [MAX_SESSION_BODY_BYTES]. */
private suspend fun ApplicationCall.bodyIsSmallEnough(): Boolean {
    val declared = request.contentLength()
    if (declared != null && declared <= MAX_SESSION_BODY_BYTES) return true
    respond(
        HttpStatusCode.PayloadTooLarge,
        ApiError("PAYLOAD_TOO_LARGE", "a session request must be under $MAX_SESSION_BODY_BYTES bytes"),
    )
    return false
}

private fun LearningSession.toView(bodies: Map<String, String>) = SessionView(
    sessionId = id,
    topicSlug = topicSlug,
    rootNodeId = rootNodeId,
    currentNodeId = currentNodeId,
    nodes = nodes.map {
        NodeView(it.nodeId, it.parentNodeId, it.explanationKey, it.span, it.verb, it.variant, it.depth)
    },
    explanations = bodies,
)
