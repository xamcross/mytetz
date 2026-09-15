package com.mytetz.api

import com.mytetz.account.AccountService
import com.mytetz.assess.QuizKind
import com.mytetz.assess.QuizService
import com.mytetz.assess.QuizSource
import com.mytetz.billing.BillingService
import com.mytetz.billing.EntitlementDecision
import com.mytetz.quota.PrincipalId
import com.mytetz.quota.QuotaService
import com.mytetz.session.SessionNotFoundException
import com.mytetz.session.SessionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class QuizRequest(val kind: QuizKind, val nodeId: String? = null)

/** The wire shape of one question — never [com.mytetz.assess.QuizQuestion.correctIndex]. */
@Serializable
data class QuizQuestionView(val questionId: String, val stem: String, val options: List<String>)

@Serializable
data class QuizTemplateView(val attemptId: String, val kind: QuizKind, val questions: List<QuizQuestionView>)

/**
 * `POST /api/sessions/{id}/quizzes` and `POST /api/sessions/{id}/quizzes/{attemptId}/answers`.
 *
 * The first is the only one of the two that can spend money, and it gates exactly the way
 * `POST /api/sessions/{id}/explain` does — sign-in, entitlement, quota, breaker — reusing that
 * route's own `refusalFor`/`recordSpend` helpers rather than a second copy of the same five checks.
 * See section 9.3 of the monetization design: "Only the endpoints that can reach the model: explain
 * and quizzes." The answers route reaches no model and gates on sign-in and ownership only.
 */
fun Route.quizRoutes(
    sessions: () -> SessionService,
    quizzes: () -> QuizService,
    quota: QuotaService,
    billing: BillingService,
    account: AccountService,
    cookies: PrincipalCookieConfig,
    clientAddresses: ClientAddressConfig = ClientAddressConfig(),
) {
    post("/api/sessions/{id}/quizzes") {
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

        val (scopeKeys, sources) = scopeFor(request, session, bodies)

        val quizService = quizzes()
        val key = quizService.keyFor(scopeKeys, request.kind)
        var cached = quizService.isCached(key)

        if (!cached) {
            quota.alignWindow(principal, entitlement.allowance)
            val refusal = quota.refusalFor(principal, entitlement.allowance, entitlement.status) {
                cached = quizService.isCached(key)
                cached
            }
            if (refusal != null) {
                call.respondRefusal(refusal)
                return@post
            }
        }

        val template = quizService.getOrGenerate(scopeKeys, request.kind, sources) { costMicros ->
            quota.recordSpend(principal, costMicros, entitlement.allowance)
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
}

/** The scope this request covers: which explanation keys, and the bodies to send to the model. */
private fun scopeFor(
    request: QuizRequest,
    session: com.mytetz.session.LearningSession,
    bodies: Map<String, com.mytetz.graph.Explanation>,
): Pair<List<String>, List<QuizSource>> = when (request.kind) {
    QuizKind.TEST_ME -> {
        val nodeId = request.nodeId ?: throw IllegalArgumentException("TEST_ME requires nodeId")
        val node = session.nodes.firstOrNull { it.nodeId == nodeId }
            ?: throw IllegalArgumentException("no such node: $nodeId")
        val body = bodies.getValue(node.explanationKey).body
        listOf(node.explanationKey) to listOf(QuizSource(node.explanationKey, body))
    }
    QuizKind.EXAM -> {
        val keys = session.nodes.map { it.explanationKey }
        val sources = keys.distinct().map { key -> QuizSource(key, bodies.getValue(key).body) }
        keys to sources
    }
}
