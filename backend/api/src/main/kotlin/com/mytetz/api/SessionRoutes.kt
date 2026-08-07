package com.mytetz.api

import com.mytetz.account.AccountService
import com.mytetz.account.User
import com.mytetz.billing.BillingService
import com.mytetz.billing.EntitlementDecision
import com.mytetz.billing.SubscriptionStatus
import com.mytetz.graph.GraphChunk
import com.mytetz.graph.Verb
import com.mytetz.quota.Allowance
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * Nothing on the server side branches on it, and nothing bills on it; see `GraphChunk.Spent`.
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
 * How many explanations one caller address can request in [EXPLAIN_WINDOW_MILLIS].
 *
 * ## Why this endpoint needs a rate limit of its own
 *
 * Three other bounds apply to `POST /api/sessions/{id}/explain`. One client can make all three read
 * zero. No path below needs an attacker.
 *
 * - **The ledger and the principal counter.** They move on `GraphChunk.Spent`. The graph emits
 *   `Spent` only after the model stream completes. A stream that breaks before that point emits
 *   nothing. Two paths break it: the learner leaves the page, or `AnthropicLlmClient` raises
 *   `LlmStreamTruncatedException` because the provider stream ends with no stop reason. The second
 *   path includes OkHttp's 120-second whole-call timeout on a slow stream. The token counts do not
 *   exist on these paths, so this layer cannot record them. See "The bound this still leaves" on
 *   [streamExplanation].
 * - **`SessionLimits.maxNodes`.** `appendNode` consumes it. `SessionService.explain` calls
 *   `appendNode` after the last emit, so the same two paths skip it.
 * - **`QuotaConfig.dailyExplains`.** It keys on the principal. `Principals.resolve` mints a new
 *   principal for each request that has no cookie. It bounds a polite client only.
 *
 * Before this limit, one session and one span gave unbounded paid generation. The ledger read zero.
 * The truncation path answers `GENERATION_FAILED`, and that message tells the client to try again.
 * A latency regression is sufficient to start the loop.
 *
 * ## Why 30 in ten minutes
 *
 * This limit measures the working rhythm of a learner. It does not measure a session-creation rate.
 * The 30-per-hour figure on `POST /api/sessions` is therefore the wrong number to copy.
 *
 * An honest learner must not meet this limit first. `QuotaConfig.dailyExplains` gives 20
 * explanations for each principal each day. A client that keeps its cookie meets that quota first.
 * The quota tells the learner about the allowance. This limit cannot do that.
 *
 * Three explanations in one minute is faster than a learner reads one to three sentences. The burst
 * covers a fast skim, a double click and a `retry()`. The window is ten minutes and not one hour.
 * A caller that reaches the limit therefore waits minutes.
 *
 * This limit binds the caller that the quota cannot bind:
 *
 * - a caller that mints a new principal for each request;
 * - a caller that aborts each stream, so that nothing is recorded.
 *
 * ## What this limit is not
 *
 * [FixedWindowRateLimiter] keeps its counters in the process. `fly.toml` sets
 * `auto_stop_machines = "stop"` and `min_machines_running = 0`. The machine stops when it is idle,
 * and the counters stop with it. A cold start gives the next caller a full allowance.
 *
 * A caller that waits for the idle timeout between bursts has no bound here. A shared counter with
 * a TTL closes this gap. `QuotaRepository` already uses that shape. It costs one round trip for
 * each request. `FixedWindowRateLimiter` records the same trade for the topic-request limit.
 */
const val EXPLAINS_PER_CALLER: Int = 30

const val EXPLAIN_WINDOW_MILLIS: Long = 10L * 60 * 1000

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
 * Every gate runs **before** the response begins: body, rate, ownership, `prepare`, quota. Only then
 * does the stream open. That ordering is the property, and it is also what lets a refusal carry a
 * real status code and a real `Retry-After` — a 429 a client can act on, rather than a 200 whose body
 * says the word "error".
 *
 * The rate limit is the first of the five. It is also the only one that binds a client that never
 * lets a stream finish. [EXPLAINS_PER_CALLER] carries the full argument.
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
 * The trigger is `GraphChunk.Spent` and nothing else — the chunk `ExplanationGraph` emits the
 * instant its own model call returns, carrying that caller's own cost.
 *
 * **Three other things look like they would do and none of them does.** Neither `plan.cached` nor
 * `Meta(cached = false)` means "this caller spent money": `ExplanationGraph` emits `Meta` before
 * taking the per-key lock, deliberately, so that first-byte latency is not held behind somebody
 * else's generation — which means that in a stampede *every* caller sees `cached = false` and
 * exactly one of them pays. And `Done.explanation.costMicros` is the *document's* cost, i.e. the
 * winner's, so billing it charges every loser for a generation it never made. All three over-report
 * by the width of the stampede and trip the global breaker early, on money nobody spent.
 *
 * **And the trigger has to arrive before the answer does, not with it.** A cost carried on the
 * terminal chunk is lost whenever the generation is billed and then fails — the validator rejecting
 * an over-long body, a failed insert, a correction that cannot be sent — and `GENERATION_FAILED`
 * invites the client to retry, so that loses money on a loop rather than once. `GraphChunk.Spent`
 * exists for exactly that and its KDoc carries the full argument.
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
 *
 * The one thing given up by going around `Route.sse` is `ServerSSESession.heartbeat`, which Ktor
 * offers to keep an idle stream alive. Nothing here emits a keep-alive comment, so a model that
 * stalls mid-generation leaves the connection silent, and Cloudflare's idle timeout — not this
 * application — decides when the learner's page gives up. Acceptable while `GraphConfig.maxOutputTokens`
 * is 4 000 and a generation is seconds; it is the thing to add first if stalls are ever observed,
 * and `heartbeat` is an extension on `ServerSSESession`, so it remains available inside the handler.
 *
 * ## [sessions] is a factory, and that is not a style choice
 *
 * `Components.sessions` is `by lazy` on a chain that ends at `AnthropicLlmClient()`, whose
 * constructor calls `AnthropicOkHttpClient.fromEnv()` and demands `ANTHROPIC_API_KEY` **there and
 * then**. Passing `components.sessions` into this function evaluates that lazy during routing setup,
 * i.e. inside `Application.module()`, i.e. before the engine accepts a connection — so a missing or
 * freshly-rotated key stops the process booting at all, taking `/api/health` and topic browsing with
 * it. `Components`' KDoc argues at length for the opposite, and `ComponentsTest`'s `the model client
 * is not built unless something needs a model` asserts exactly that touching `components.sessions` is
 * what builds it. A `() -> SessionService` resolved per request keeps that property: a deployment
 * with no key boots, serves the catalogue and answers `/api/health`.
 *
 * It does **not** make the session endpoints degrade gracefully, and the difference is worth stating
 * because the obvious reading of "deferred" is that it does. `Components.sessions` is one lazy over
 * the whole chain, so resolving it forces the model client even for `GET /api/sessions/{id}`, which
 * is a pure read that needs no model. On a keyless deployment those endpoints answer 500. Splitting
 * the session *store* from the session *generator* in `Components` is what would fix that, and it is
 * a larger change than this one.
 */
fun Route.sessionRoutes(
    sessions: () -> SessionService,
    quota: QuotaService,
    billing: BillingService,
    account: AccountService,
    cookies: PrincipalCookieConfig,
    clientAddresses: ClientAddressConfig = ClientAddressConfig(),
    sessionLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        limit = SESSIONS_PER_CALLER,
        windowMillis = SESSION_WINDOW_MILLIS,
    ),
    // Its own limiter, and not the session one. `FixedWindowRateLimiter` holds one window and one
    // counter table. A shared instance spends one allowance across two endpoints. The two endpoints
    // have different limits, different windows and different reasons to exist.
    explainLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        limit = EXPLAINS_PER_CALLER,
        windowMillis = EXPLAIN_WINDOW_MILLIS,
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
     *
     * ## This route must gate on the same entitlement the explain route gates on
     *
     * A signed-in caller's seed generation and their explanations spend against one counter
     * document. The counter is keyed on one `user:<id>` principal, shared by both routes.
     *
     * A mismatch between the two routes causes two failures. First: if this route used
     * `QuotaConfig.defaultAllowance` while the explain route resolved a subscriber's real
     * allowance of 25 a day, this route would refuse a paying subscriber at 20 — a limit that is
     * not theirs. Second: `QuotaService.alignWindow` would then delete the learner's counter the
     * first time an explain request ran, because the two routes would disagree about the window's
     * length.
     *
     * So this route resolves [BillingService.entitlementFor] for a signed-in caller, through
     * [createEntitlement], and passes the result to [QuotaService.refusalFor] and to
     * [QuotaService.recordSpend]. **This route must never add a refusal for the entitlement
     * itself.** The catalogue and the seeds stay open. Only the reader is gated. A signed-in
     * learner with no subscription therefore keeps the same seed access an anonymous visitor has —
     * see [createEntitlement].
     */
    post("/api/sessions") {
        if (!call.bodyIsSmallEnough()) return@post

        val caller = ClientAddress.of(call, clientAddresses)
        if (!sessionLimiter.tryAcquire(caller)) {
            log.info("rate limited session creation from {}", caller)
            // Through `respondRefusal`, so this 429 carries a `Retry-After` header like the quota
            // ones. The reasoning is the same and it is not the client's to guess: proxies, client
            // libraries and crawlers all read the header and none of them reads our JSON.
            call.respondRefusal(
                Refusal(
                    HttpStatusCode.TooManyRequests,
                    ApiError(
                        code = "RATE_LIMITED",
                        message = "too many sessions started; try again later",
                        retryAfter = SESSION_WINDOW_MILLIS / 1000,
                    ),
                )
            )
            return@post
        }

        // After the limiter, so a refused request costs no cookie — the same ordering, for the same
        // reason, as `POST /api/topic-requests`. The session service is resolved here for a second
        // reason: resolving it forces the lazy model client, so doing it before the limiter would
        // make a rate-limited request pay for building an Anthropic client, and 500 rather than 429
        // on a deployment with no key.
        val sessions = sessions()
        val (principal, user) = call.effectiveIdentity(account, cookies)
        val request = call.receive<CreateSessionRequest>()
        val (allowance, status) = billing.createEntitlement(user)

        // `createWillGenerate` raises for an unknown or unpublished topic exactly as `create` does,
        // so the gate cannot answer a question `create` would have refused.
        if (sessions.createWillGenerate(request.topicSlug)) {
            val refusal = quota.refusalFor(principal, allowance, status) { !sessions.createWillGenerate(request.topicSlug) }
            if (refusal != null) {
                call.respondRefusal(refusal)
                return@post
            }
        }

        // Recorded from inside the generation, not from the result: a seed can be billed and then
        // rejected by the validator, in which case `create` raises and there is no result to read a
        // cost off. `recordSpend` swallows its own failures, so this cannot fail the request.
        val created = sessions.create(principal.value, request.topicSlug) { costMicros ->
            withContext(NonCancellable) { quota.recordSpend(principal, costMicros, allowance) }
        }

        call.respond(created.session.toView(mapOf(created.seed.key to created.seed.body)))
    }

    get("/api/sessions/{id}") {
        val sessions = sessions()
        val (principal, _) = call.effectiveIdentity(account, cookies)
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

        // The first gate on this endpoint. The order is the property. See [EXPLAINS_PER_CALLER].
        // Nothing below this line runs for a caller that is over the limit. The session lookup,
        // `prepare` and the model call all stay behind it.
        //
        // The limiter runs before `sessions()` and before `Principals.resolve`, for the two reasons
        // that `POST /api/sessions` gives. `sessions()` builds the lazy Anthropic client, so a
        // refused request must not pay for one. A refused request must also cost no cookie.
        //
        // The key is the address and not the principal. This limit exists for the caller that mints
        // a new principal for each request. See `ClientAddress`.
        val caller = ClientAddress.of(call, clientAddresses)
        if (!explainLimiter.tryAcquire(caller)) {
            log.info("rate limited explanations from {}", caller)
            call.respondRefusal(
                Refusal(
                    HttpStatusCode.TooManyRequests,
                    ApiError(
                        code = "RATE_LIMITED",
                        message = "too many explanations requested; try again shortly",
                        // The full width of the window, which is the honest upper bound. The window
                        // is fixed and not sliding. It can therefore roll sooner, but never later.
                        retryAfter = EXPLAIN_WINDOW_MILLIS / 1000,
                    ),
                )
            )
            return@post
        }

        // The sign-in gate. Second, right after the rate limiter and before `sessions()` — which
        // forces the lazy Anthropic client — and before anything reads the request body's span. An
        // anonymous caller must learn nothing about the session or the span: a wrong-order gate
        // would let a `400 SPAN_MISMATCH` tell an unauthenticated prober whether a guessed span was
        // right.
        //
        // Ownership and quota below key on this signed-in user's principal, and not on the caller's
        // anonymous cookie — a fix-round correction. The anonymous principal is `Principals.resolve`,
        // which reads only `mytetz_pid` and can never yield a `user:` principal; a session that
        // `completeSignIn` has already reassigned onto `user:<id>` would then never match, and a
        // learner who signs in from a reading page would meet "no such session" on the very page
        // they were just reading. Reusing [signedInUser] here, rather than re-resolving, is also
        // exactly what the gate above already paid for.
        val signedInUser = Principals.readSessionId(call, cookies)?.let { account.resolveSession(it) }
        if (signedInUser == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("SIGN_IN_REQUIRED", "sign in to request an explanation"))
            return@post
        }

        val principal = PrincipalId.user(signedInUser.id)

        // The entitlement gate. Third, right after sign-in and above span validation, for the same
        // reason as the gate above: a caller with no entitlement must learn nothing about the
        // session or the span either.
        val entitlement = billing.entitlementFor(signedInUser.id)
        if (entitlement !is EntitlementDecision.Allowed) {
            call.respond(
                HttpStatusCode.Forbidden,
                ApiError("SUBSCRIPTION_REQUIRED", "a subscription is required to request an explanation"),
            )
            return@post
        }

        val sessions = sessions()

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
            // A learner's entitlement can change under a counter that does not know it changed — a
            // trial ending, a subscription starting. This is the write path, so this is where the
            // counter's window is brought in line with the entitlement that was just resolved. It
            // runs here, inside the branch that would really generate, so a request the cache can
            // still serve for free never touches the counter — and before the check below, so that
            // check never sees a stale window. `GET /api/account` must never call this: it is a
            // read, and a learner must not be able to clear their own count by opening the account
            // page.
            quota.alignWindow(principal, entitlement.allowance)

            val refusal = quota.refusalFor(principal, entitlement.allowance, entitlement.status) {
                // Re-read, and use the fresher plan: another caller may have persisted this key
                // since. `prepare` calls no model. See property 2.
                //
                // A throw here must not become the answer. The re-check exists to *soften* a
                // refusal, so a Mongo blip inside it would turn a clean 503 into a 500 on a request
                // the cache might have served — the failure this whole branch is trying to avoid,
                // arriving through the door meant to fix it. A ceiling that has genuinely been
                // crossed since the first `prepare` is refused too, one code less precisely; that is
                // the cheaper error, and nothing generates either way.
                try {
                    plan = prepare()
                    plan.cached
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.info("the quota re-check could not be evaluated; keeping the refusal", e)
                    false
                }
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
                streamExplanation(sessions, quota, principal, entitlement.allowance, plan)
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
 *
 * ## Why the recording is in a `finally` and not after `collect`
 *
 * Because two things run between the cost becoming known and the collection returning, and either
 * can throw:
 *
 * 1. the terminal `done` `send`, which writes to a socket the learner may have just closed; and
 * 2. `SessionRepository.appendNode`, which `SessionService.explain` runs **inside the flow, after the
 *    last emit** — so `collect` only returns once that Mongo write has succeeded.
 *
 * By the time either can fail, `ExplanationRepository.insertIfAbsent` has already returned: the
 * tokens are bought, the document is in the store, and `spentMicros` is a known non-zero number. A
 * recording placed after `collect` is skipped in exactly that case, and — worse — the
 * [SPEND_UNRECORDED] alert built for this condition never fires either, because the function that
 * raises it never runs. The ledger silently understates and nothing says so.
 *
 * That is not a rare shape. A client that aborts every explain once the deltas stop would move
 * neither the ledger nor the principal's counter, so `checkGeneration` answers `Allowed` for ever,
 * and `appendNode` never runs so the node budget is not consumed either: one session and one span
 * become unbounded paid generations against a ledger that reads zero. Only the address rate limit
 * is spent before the stream opens. It is therefore the one bound that survives this shape. See
 * [EXPLAINS_PER_CALLER], which exists for it and stops the loop.
 *
 * So it runs on every path, under `NonCancellable` so a cancelled coroutine cannot skip the write.
 * `recordSpend` swallows its own failures, so it cannot fail the request from any position.
 *
 * The previous rationale here — "a cancelled request records nothing, consistent with the node not
 * being written either" — was the exact conflation this task exists to prevent. An unwritten node is
 * free. Sampled tokens are not.
 *
 * ## The bound this still leaves, named rather than implied
 *
 * A cancellation that arrives **before the model's own stream completes** records nothing, and this
 * is the one case where that is not a bookkeeping choice: `ExplanationGraph.generate` rethrows
 * `CancellationException` before `LlmChunk.Done` delivers `LlmUsage`, so no token counts exist
 * anywhere and the cost is genuinely unknowable at every layer rather than merely unread here.
 *
 * The bound that leaves: a client which disconnects mid-generation pays nothing into the ledger and
 * nothing into its own daily count, which makes `QuotaConfig.dailyExplains` optional for such a
 * client. [EXPLAINS_PER_CALLER] therefore applies at the door, and it keys on the address and not
 * on the principal. It is the only bound under a client of that shape.
 * **Everything after the announcement is recorded** — a generation that is billed and then
 * rejected, or whose insert fails, or whose correction cannot be sent, all reach `Spent` first. The
 * guarantee starts at the emit rather than at the model returning: a disconnect landing in the
 * microseconds between the two loses a cost that is already known, which is a real if vanishing
 * window and is stated rather than rounded away.
 *
 * Charging the *count* without the cost is **deferred as a billing-policy decision, not blocked by
 * anything**. It was once argued here that this layer cannot tell "cancelled after the model was
 * called" from "cancelled while being served a cache hit under the lock" — true of the signals that
 * existed then, and disproved by `GraphChunk.Spent`, which demonstrates that the graph can announce
 * anything it likes at any instant it chooses, including the instant it commits to calling the
 * model. So the question is whether a learner should spend one of twenty daily explanations on an
 * answer they never received, and that is a product call nobody has made. What is *not* available
 * either way is the amount, since no token counts exist on this path; closing that needs
 * `ExplanationGraph` to report partial usage, which is the same change that would let it be billed
 * rather than merely counted. Recorded as a bound in the shape `QuotaService` uses for its own
 * overshoot.
 */
private suspend fun ServerSSESession.streamExplanation(
    sessions: SessionService,
    quota: QuotaService,
    principal: PrincipalId,
    allowance: Allowance,
    plan: ExplainPlan,
) {
    // Outside the `try`, because the `finally` reads it.
    var spentMicros = 0L
    try {
        sessions.explain(plan).collect { chunk ->
            // Recorded on arrival and never on completion: `Spent` is emitted before the validator,
            // the insert and the correction, every one of which can throw with the money already
            // gone. Assignment first, `send` second — a socket that closes during the send must not
            // lose a cost this collector has already been handed.
            //
            // `+=` rather than `=`, and it is defensive rather than load bearing. One collection of
            // one plan makes at most one model call, so `ExplanationGraph` emits at most one `Spent`
            // and the two operators are equivalent today. They stop being equivalent the moment
            // anything retries or batches inside the graph, and of the two only `+=` is right then;
            // `=` would silently keep the last cost and discard the rest. Written down so a reader
            // does not have to guess which case this is.
            if (chunk is GraphChunk.Spent) spentMicros += chunk.costMicros
            eventFor(chunk)?.let { send(it) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        send(ServerSentEvent(event = "error", data = json.encodeToString(sseErrorFor(e))))
    } finally {
        withContext(NonCancellable) { quota.recordSpend(principal, spentMicros, allowance) }
    }
}

/**
 * One `GraphChunk`, one SSE event — or null for the one chunk that is not a wire event at all.
 *
 * Exhaustive on purpose: `GraphChunk` is sealed, so a chunk added later stops this compiling rather
 * than being dropped on the floor. That matters most for `Superseded`, whose whole reason for being
 * a chunk rather than a flag on `Done` is that a client must not be able to miss it.
 *
 * `Spent` returns null. It is this server's accounting and no business of the learner's — putting a
 * price on the wire would tell every client what each answer cost us. Returning null rather than
 * filtering the chunk upstream keeps the decision inside the exhaustive `when`, where it is visible
 * and testable, instead of in a predicate somewhere that quietly starts matching something else.
 */
internal fun eventFor(chunk: GraphChunk): ServerSentEvent? = when (chunk) {
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

    is GraphChunk.Spent -> null

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
 *
 * ## Why a trial and a subscriber get a different refusal
 *
 * [status] tells [QuotaDecision.PrincipalExceeded] apart into two different answers. A trial pool
 * does not roll over: there is no midnight, no next window, nothing a `Retry-After` could honestly
 * name. A `TRIALING` caller who has used the pool up gets `403 TRIAL_EXHAUSTED` and no `retryAfter`.
 * Every other status — including null, for the one caller below with no entitlement to resolve —
 * gets the original `429 QUOTA_EXCEEDED` with the real wait, unchanged.
 *
 * [allowance] and [status] default to null for an anonymous caller. A null [allowance] keeps
 * `checkGeneration`'s own default. `POST /api/sessions` also passes null for a signed-in caller
 * with no subscription, through [createEntitlement] — that caller keeps the default allowance
 * too, and a null [status] means an exhausted default answers `QUOTA_EXCEEDED`, never
 * `TRIAL_EXHAUSTED`. Every other caller passes its own resolved allowance and status:
 * `POST /api/sessions` for a signed-in caller with a subscription, and
 * `POST /api/sessions/{id}/explain` always.
 */
private suspend fun QuotaService.refusalFor(
    principal: PrincipalId,
    allowance: Allowance? = null,
    status: SubscriptionStatus? = null,
    becameFree: suspend () -> Boolean,
): Refusal? {
    val decision = try {
        if (allowance != null) checkGeneration(principal, allowance) else checkGeneration(principal)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("the quota check could not be evaluated; refusing generation and serving cache only", e)
        null
    }

    val refusal = when (decision) {
        QuotaDecision.Allowed -> return null

        is QuotaDecision.PrincipalExceeded -> if (status == SubscriptionStatus.TRIALING) {
            Refusal(
                HttpStatusCode.Forbidden,
                ApiError(
                    code = "TRIAL_EXHAUSTED",
                    message = "the trial's explanations are used up; subscribe to keep going",
                ),
            )
        } else {
            Refusal(
                HttpStatusCode.TooManyRequests,
                ApiError(
                    code = "QUOTA_EXCEEDED",
                    message = "you have used today's allowance of new explanations",
                    retryAfter = decision.retryAfterSeconds,
                ),
            )
        }

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
 *
 * [allowance] defaults to null for an anonymous caller, and for a signed-in caller with no
 * subscription — see [createEntitlement] — both of which keep `QuotaConfig`'s own default. Every
 * other caller passes its own resolved allowance explicitly, and by name — `recordGeneration`
 * takes `costMicros` second and `allowance` third, both arguments below are given by name for that
 * reason, so the two cannot be swapped silently.
 */
private suspend fun QuotaService.recordSpend(principal: PrincipalId, spentMicros: Long, allowance: Allowance? = null) {
    if (spentMicros <= 0) return
    try {
        if (allowance != null) {
            recordGeneration(principalId = principal, costMicros = spentMicros, allowance = allowance)
        } else {
            recordGeneration(principalId = principal, costMicros = spentMicros)
        }
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
 * The identity behind whatever this caller touches on `POST /api/sessions` and
 * `GET /api/sessions/{id}`: the signed-in [User] and their principal, when the session cookie
 * resolves to one, and a null [User] with the anonymous cookie principal otherwise.
 *
 * `POST /api/sessions/{id}/explain` does not call this. Sign-in is mandatory there, so the gate
 * has already resolved the user; building `PrincipalId.user(signedInUser.id)` straight from that
 * value is the same answer this function would give, without asking Mongo for the session a
 * second time.
 *
 * `GET /api/sessions/{id}` reads only the [PrincipalId] half of the pair. `POST /api/sessions`
 * reads the [User] half too, and resolves that caller's own entitlement from it — see
 * [createEntitlement] — so that session creation and explanation gate on the same allowance.
 *
 * ## Why this matters, and what was wrong before it existed
 *
 * `Principals.resolve` reads only the `mytetz_pid` cookie and never returns a `user:` principal —
 * it does not know a session cookie exists. `AuthRoutes.completeSignIn` calls
 * `SessionService.reassignPrincipal` unconditionally on every sign-in, moving every session that
 * currently carries the caller's anonymous principal onto `user:<id>`. A route that keeps resolving
 * `Principals.resolve` after that point is asking Mongo for a principal that no longer owns
 * anything: a learner who reads a topic anonymously, highlights, meets the wall, and signs in would
 * find their own reading session answers `404 NOT_FOUND` a moment later, because the write
 * (`reassignPrincipal`) and the read (`Principals.resolve`) disagreed about which principal the
 * caller now is. This function is the one place that answer is decided, so the two cannot drift
 * apart again.
 */
private suspend fun ApplicationCall.effectiveIdentity(
    account: AccountService,
    cookies: PrincipalCookieConfig,
): Pair<PrincipalId, User?> {
    val user = Principals.readSessionId(this, cookies)?.let { account.resolveSession(it) }
    val principal = if (user != null) PrincipalId.user(user.id) else Principals.resolve(this, cookies)
    return principal to user
}

/**
 * The allowance and status `POST /api/sessions` gates on for [user].
 *
 * A null [user] is an anonymous caller. It gets the pair `null to null` — [QuotaService]'s own
 * default allowance, the same one this route used before it knew about entitlements.
 *
 * A signed-in caller with [EntitlementDecision.SubscriptionRequired] gets the same pair. This
 * route must never add its own refusal for the entitlement: the catalogue and the seeds stay
 * open, and only the reader is gated. A signed-in learner with no subscription therefore keeps
 * the same seed access an anonymous visitor has.
 *
 * A signed-in caller with [EntitlementDecision.Allowed] gets that decision's own allowance and
 * status — the same pair [BillingService.entitlementFor] would give the explain route for this
 * user, so the two routes spend against one counter under one window.
 *
 * One consequence follows. A trialing learner's own seed generation now counts against their
 * trial pool. This is correct. Task B0 pre-warms every published seed, so a trialing learner
 * reaches `create`'s own model call only for a topic the pre-warm missed — and counting that one
 * generation is the choice that protects the daily spend ceiling.
 */
private suspend fun BillingService.createEntitlement(user: User?): Pair<Allowance?, SubscriptionStatus?> {
    if (user == null) return null to null
    return when (val entitlement = entitlementFor(user.id)) {
        is EntitlementDecision.Allowed -> entitlement.allowance to entitlement.status
        EntitlementDecision.SubscriptionRequired -> null to null
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
