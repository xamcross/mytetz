package com.mytetz.api

import com.mytetz.account.AccountService
import com.mytetz.assess.AnsweredQuestion
import com.mytetz.assess.QuizAttemptNotFoundException
import com.mytetz.assess.QuizKind
import com.mytetz.assess.QuizService
import com.mytetz.assess.QuizSource
import com.mytetz.billing.BillingService
import com.mytetz.billing.EntitlementDecision
import com.mytetz.quota.PrincipalId
import com.mytetz.quota.QuotaService
import com.mytetz.session.CorruptSessionException
import com.mytetz.session.SessionNotFoundException
import com.mytetz.session.SessionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.mytetz.api.QuizRoutes")

@Serializable
data class QuizRequest(val kind: QuizKind, val nodeId: String? = null)

/** The wire shape of one question — never [com.mytetz.assess.QuizQuestion.correctIndex]. */
@Serializable
data class QuizQuestionView(val questionId: String, val stem: String, val options: List<String>)

@Serializable
data class QuizTemplateView(val attemptId: String, val kind: QuizKind, val questions: List<QuizQuestionView>)

/** The wire shape of one learner answer: which question, and which option they chose. */
@Serializable
data class AnsweredQuestionPayload(val questionId: String, val chosenIndex: Int)

@Serializable
data class QuizAnswersRequest(val answers: List<AnsweredQuestionPayload>)

/** The score for a submitted attempt, plus the answer key.
 *
 * The server sends this view only after the learner submits answers. Unlike [QuizQuestionView],
 * this view may safely carry [correctIndices] and [rationales]. */
@Serializable
data class QuizResultView(
    val score: Int,
    val total: Int,
    val correctIndices: Map<String, Int>,
    val rationales: Map<String, String>,
)

/**
 * How many quiz requests one caller address may make in [QUIZ_WINDOW_MILLIS].
 *
 * This mirrors `EXPLAINS_PER_CALLER` in `SessionRoutes.kt`, and for the same reason. Quiz
 * generation calls the same model on the same billing path as an explanation. A caller that
 * disconnects before a cost is recorded, or that mints a new principal on every request, spends
 * nothing into the ledger either way. This limit is the one bound that still applies to that
 * caller. See `EXPLAINS_PER_CALLER`'s own KDoc for the full argument. Nothing in it is specific
 * to explanations.
 */
const val QUIZZES_PER_CALLER: Int = 20

const val QUIZ_WINDOW_MILLIS: Long = 10L * 60 * 1000

/**
 * `POST /api/sessions/{id}/quizzes` and `POST /api/sessions/{id}/quizzes/{attemptId}/answers`.
 *
 * The first is the only one of the two that can spend money, and it gates the way
 * `POST /api/sessions/{id}/explain` does: a body-size check, a rate limiter, sign-in, entitlement,
 * quota, and a `NonCancellable` spend recording. It reuses `SessionRoutes.kt`'s own
 * `bodyIsSmallEnough`, `refusalFor` and `recordSpend` helpers rather than a second copy of them.
 * See section 9.3 of the monetization design: "Only the endpoints that can reach the model: explain
 * and quizzes." The answers route reaches no model. It gates on a body-size check, sign-in and
 * ownership only. It also refuses a second submission for the same attempt. See the answers
 * route below for that check.
 */
fun Route.quizRoutes(
    sessions: () -> SessionService,
    quizzes: () -> QuizService,
    quota: QuotaService,
    billing: BillingService,
    account: AccountService,
    cookies: PrincipalCookieConfig,
    clientAddresses: ClientAddressConfig = ClientAddressConfig(),
    quizLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        limit = QUIZZES_PER_CALLER,
        windowMillis = QUIZ_WINDOW_MILLIS,
    ),
) {
    post("/api/sessions/{id}/quizzes") {
        if (!call.bodyIsSmallEnough()) return@post

        // The rate limiter runs before sign-in, and before `sessions()`/`quizzes()` resolve their
        // lazy model client. See `EXPLAINS_PER_CALLER` for why the order matters: a refused request
        // must not build a client it will never use, and must not cost a cookie either.
        val caller = ClientAddress.of(call, clientAddresses)
        if (!quizLimiter.tryAcquire(caller)) {
            log.info("rate limited quiz generation from {}", caller)
            call.respondRefusal(
                Refusal(
                    HttpStatusCode.TooManyRequests,
                    ApiError(
                        code = "RATE_LIMITED",
                        message = "too many quizzes requested; try again shortly",
                        retryAfter = QUIZ_WINDOW_MILLIS / 1000,
                    ),
                )
            )
            return@post
        }

        val signedInUser = Principals.readSessionId(call, cookies)?.let { account.resolveSession(it) }
        if (signedInUser == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("SIGN_IN_REQUIRED", "sign in to request a quiz"))
            return@post
        }
        val principal = PrincipalId.user(signedInUser.id)

        val entitlement = billing.entitlementFor(signedInUser.id)
        if (entitlement !is EntitlementDecision.Allowed) {
            call.respond(
                HttpStatusCode.Forbidden,
                ApiError("SUBSCRIPTION_REQUIRED", "a subscription is required to request a quiz"),
            )
            return@post
        }

        val sessionService = sessions()
        val sessionId = call.parameters["id"].orEmpty()
        val request = call.receive<QuizRequest>()

        sessionService.requireOwnedBy(sessionId, principal)
        val (session, bodies) = sessionService.load(sessionId) ?: throw SessionNotFoundException(sessionId)

        val quizService = quizzes()
        val (scopeKeys, sources) = scopeFor(request, session, bodies, quizService.examMaxSources)

        val key = quizService.keyFor(scopeKeys, request.kind)
        val cached = quizService.isCached(key)

        if (!cached) {
            quota.alignWindow(principal, entitlement.allowance)
            val refusal = quota.refusalFor(principal, entitlement.allowance, entitlement.status) {
                // Re-read: another caller may have persisted this key since. This calls no model.
                // See `refusalFor`'s own KDoc, and `SessionRoutes.kt`'s equivalent re-check. A
                // throw here must not become the answer. A Mongo blip must degrade to "keep the
                // refusal". It must not turn into an uncaught 500 on a request the cache might
                // have served.
                try {
                    quizService.isCached(key)
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

        val template = quizService.getOrGenerate(scopeKeys, request.kind, sources) { costMicros ->
            // `NonCancellable`: a client that disconnects mid-generation must not skip this write.
            // See `SessionRoutes.kt`'s own `streamExplanation` for the full argument. An unwritten
            // node is free. Sampled tokens are not.
            withContext(NonCancellable) { quota.recordSpend(principal, costMicros, entitlement.allowance) }
        }

        val attempt = quizService.startAttempt(principal.value, sessionId, template)
        quizService.saveAttempt(attempt)

        call.respond(
            HttpStatusCode.OK,
            QuizTemplateView(
                attemptId = attempt.id,
                kind = template.kind,
                questions = template.questions.map { QuizQuestionView(it.questionId, it.stem, it.options) },
            ),
        )
    }

    post("/api/sessions/{id}/quizzes/{attemptId}/answers") {
        if (!call.bodyIsSmallEnough()) return@post

        val signedInUser = Principals.readSessionId(call, cookies)?.let { account.resolveSession(it) }
        if (signedInUser == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("SIGN_IN_REQUIRED", "sign in to answer a quiz"))
            return@post
        }
        val principal = PrincipalId.user(signedInUser.id)
        val sessionId = call.parameters["id"].orEmpty()
        val attemptId = call.parameters["attemptId"].orEmpty()

        sessions().requireOwnedBy(sessionId, principal)

        val quizService = quizzes()
        val attempt = quizService.findAttempt(attemptId)
        if (attempt == null || attempt.sessionId != sessionId || attempt.principalId != principal.value) {
            // One exception covers both "no such id" and "someone else's id". A guessed attempt
            // id must not reveal which attempts exist. See the exception's own KDoc.
            throw QuizAttemptNotFoundException(attemptId)
        }

        // A second submission would score again for free. Worse, it would return the full answer
        // key a second time. This route must never hand out the answer key more than once for
        // one attempt.
        if (attempt.submittedAtEpochMillis != null) {
            call.respond(
                HttpStatusCode.Conflict,
                ApiError("ALREADY_ANSWERED", "this quiz has already been answered"),
            )
            return@post
        }

        val template = quizService.findTemplate(attempt.templateId)
            ?: throw QuizAttemptNotFoundException(attemptId)

        val request = call.receive<QuizAnswersRequest>()
        val scored = quizService.score(
            attempt,
            template,
            answers = request.answers.map { AnsweredQuestion(it.questionId, it.chosenIndex) },
        )
        quizService.saveAttempt(scored)

        call.respond(
            QuizResultView(
                score = scored.score ?: 0,
                total = scored.total,
                correctIndices = template.questions.associate { it.questionId to it.correctIndex },
                rationales = template.questions.associate { it.questionId to it.rationale },
            ),
        )
    }
}

/**
 * The scope this request covers: which explanation keys, and the bodies to send to the model.
 *
 * The EXAM arm takes only the session's most recent [examMaxSources] nodes, by array position,
 * when the session holds more than that. [com.mytetz.assess.QuizConfig.examMaxSources] explains
 * the bound and why recency is the choice. [scopeKeys] and the returned sources both read from
 * the same de-duplicated key list, so the cache key and the material actually sent to the model
 * always agree.
 */
private fun scopeFor(
    request: QuizRequest,
    session: com.mytetz.session.LearningSession,
    bodies: Map<String, com.mytetz.graph.Explanation>,
    examMaxSources: Int,
): Pair<List<String>, List<QuizSource>> = when (request.kind) {
    QuizKind.TEST_ME -> {
        val nodeId = request.nodeId ?: throw IllegalArgumentException("TEST_ME requires nodeId")
        val node = session.nodes.firstOrNull { it.nodeId == nodeId }
            ?: throw IllegalArgumentException("no such node: $nodeId")
        val body = bodyFor(session.id, node.explanationKey, bodies)
        listOf(node.explanationKey) to listOf(QuizSource(node.explanationKey, body))
    }
    QuizKind.EXAM -> {
        val keys = session.nodes.takeLast(examMaxSources).map { it.explanationKey }.distinct()
        val sources = keys.map { key -> QuizSource(key, bodyFor(session.id, key, bodies)) }
        keys to sources
    }
}

/**
 * Resolves one stored explanation body.
 *
 * Raises [CorruptSessionException] when [key] is not in [bodies]. A session node that points at a
 * missing explanation is stored data that has gone wrong, the same fault
 * `com.mytetz.session.SessionService.hydrate` raises this exception for. It must not surface as a
 * bare `NoSuchElementException`, which `ErrorMapping.kt` would map to an unlabelled 500 with no
 * alert.
 */
private fun bodyFor(
    sessionId: String,
    key: String,
    bodies: Map<String, com.mytetz.graph.Explanation>,
): String = bodies[key]?.body ?: throw CorruptSessionException(
    sessionId,
    "a node points at explanation $key, which is not in the store",
)
