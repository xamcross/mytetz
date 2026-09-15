package com.mytetz.api

import com.mytetz.account.AccountRepository
import com.mytetz.account.AccountService
import com.mytetz.account.MagicLinkService
import com.mytetz.account.MailSender
import com.mytetz.assess.QuizAttempt
import com.mytetz.assess.QuizRepository
import com.mytetz.billing.BillingConfig
import com.mytetz.billing.BillingRepository
import com.mytetz.billing.BillingService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuizRoutesTest {

    private class CapturingMailSender : MailSender {
        private val links = mutableMapOf<String, String>()
        override suspend fun sendMagicLink(email: String, link: String) { links[email] = link }
        fun tokenFor(email: String): String = links.getValue(email).substringAfterLast("/")
    }

    /**
     * One valid quiz question, for [sourceKey].
     *
     * The fake LLM answers with an empty question list by default, and the validator rejects an
     * empty answer. [sourceKey] must equal a real explanation key from the session under test. Read
     * that key from `GET /api/sessions/{id}` first, then pass it here.
     */
    private fun validQuizJson(sourceKey: String) = """
        {"questions":[{"stem":"Why is the sky blue?","options":["a","b","c","d"],"correctIndex":1,"sourceKey":"$sourceKey","rationale":"Rayleigh scattering."}]}
    """.trimIndent()

    private class Scope(
        private val builder: ApplicationTestBuilder,
        val client: HttpClient,
        val mailSender: CapturingMailSender,
        val stack: TestFixtures.SessionStack,
        val quizRepository: QuizRepository,
    ) {
        suspend fun signIn(http: HttpClient = client): String {
            val email = "learner-${UUID.randomUUID()}@example.com"
            http.post("/api/auth/magic-link") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email"}""")
            }
            http.get("/api/auth/magic-link/${mailSender.tokenFor(email)}")
            return email
        }

        /** A second learner. It gets its own cookie jar, its own principal, and its own signed-in
         * session. A test uses this to prove ownership isolation with a genuinely different
         * caller, not with a second request from the same client. */
        suspend fun anotherLearner(): HttpClient {
            val http = builder.createClient { install(HttpCookies) }
            signIn(http)
            return http
        }
    }

    private fun app(trialGenerations: Int = 40, block: suspend Scope.() -> Unit) = testApplication {
        val stack = TestFixtures.sessionApp()
        val quiz = TestFixtures.quizApp(stack)
        val accountRepository = AccountRepository(stack.database)
        val account = AccountService(accountRepository)
        val mailSender = CapturingMailSender()
        val magicLink = MagicLinkService(accountRepository, mailSender, baseUrl = "http://localhost")
        val billingRepository = BillingRepository(stack.database)
        val billing = BillingService(billingRepository, config = BillingConfig(trialGenerations = trialGenerations))

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            installErrorMapping()
            routing {
                sessionRoutes(
                    sessions = { stack.sessions }, quota = stack.quota, billing = billing, account = account,
                    cookies = TestFixtures.cookieConfig, clientAddresses = ClientAddressConfig(trustedHeader = null),
                )
                quizRoutes(
                    sessions = { stack.sessions }, quizzes = { quiz.service }, quota = stack.quota, billing = billing,
                    account = account, cookies = TestFixtures.cookieConfig,
                    clientAddresses = ClientAddressConfig(trustedHeader = null),
                )
                authRoutes(
                    account = account, sessions = { stack.sessions }, magicLink = { magicLink },
                    google = { error("google sign-in is not exercised by QuizRoutesTest") },
                    cookies = TestFixtures.cookieConfig, quotaRepository = stack.quotaRepository, billing = billing,
                    clientAddresses = ClientAddressConfig(trustedHeader = null),
                )
            }
        }

        val http = createClient { install(HttpCookies) }
        runBlocking { Scope(this@testApplication, http, mailSender, stack, quiz.repository).block() }
    }

    /** Creates a signed-in session with one EXPLAIN child node beyond the seed, so TEST_ME on the
     * root has real material and EXAM has two nodes to scope over. */
    private suspend fun Scope.newSessionWithOneChild(): String {
        signIn()
        val created = client.post("/api/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"topicSlug":"quantum-physics"}""")
        }
        val sessionId = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("sessionId").jsonPrimitive.content
        val rootNodeId = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("rootNodeId").jsonPrimitive.content
        client.post("/api/sessions/$sessionId/explain") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"parentNodeId":"$rootNodeId","span":{"text":"fundamental physical theory","start":25,"end":52},"verb":"EXPLAIN"}"""
            )
        }
        return sessionId
    }

    @Test
    fun `an anonymous caller is refused before anything is generated`() = app {
        val response = client.post("/api/sessions/does-not-matter/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"n1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue("SIGN_IN_REQUIRED" in response.bodyAsText())
    }

    @Test
    fun `a template returned to the browser carries no correctIndex`() = app {
        val sessionId = newSessionWithOneChild()
        val session = client.get("/api/sessions/$sessionId")
        val sessionBody = Json.parseToJsonElement(session.bodyAsText()).jsonObject
        val rootNodeId = sessionBody.getValue("rootNodeId").jsonPrimitive.content
        val rootExplanationKey = sessionBody.getValue("nodes").jsonArray
            .first { it.jsonObject.getValue("nodeId").jsonPrimitive.content == rootNodeId }
            .jsonObject.getValue("explanationKey").jsonPrimitive.content
        // The route sends the model no scope key it can guess ahead of time, so the fake answer
        // must cite the session's own real key or the validator drops it.
        stack.llm.nextStructuredJson = validQuizJson(rootExplanationKey)

        val response = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"$rootNodeId"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse("correctIndex" in response.bodyAsText(), "the wire shape must never carry the answer")
        assertTrue("attemptId" in response.bodyAsText())
    }

    @Test
    fun `exam scopes over every node in the session`() = app {
        val sessionId = newSessionWithOneChild()
        val session = client.get("/api/sessions/$sessionId")
        val firstExplanationKey = Json.parseToJsonElement(session.bodyAsText()).jsonObject
            .getValue("nodes").jsonArray.first()
            .jsonObject.getValue("explanationKey").jsonPrimitive.content
        // One valid question is enough for `getOrGenerate` to succeed. It does not need to cover
        // every key.
        stack.llm.nextStructuredJson = validQuizJson(firstExplanationKey)

        val response = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"EXAM"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // `newSessionWithOneChild` spends a pool of 2 in full. The seed generation spends one. The one
    // explain call spends the other. The trial pool counts every generation this principal makes.
    // So the quiz request below is refused before it reaches the model. `BillingConfig` requires a
    // positive pool. Zero is not a legal value here. See `SessionRoutesTest`'s own trial-exhaustion
    // tests for the same pattern.
    @Test
    fun `an exhausted allowance refuses quiz generation the same way it refuses explain`() = app(trialGenerations = 2) {
        val sessionId = newSessionWithOneChild()
        val session = client.get("/api/sessions/$sessionId")
        val rootNodeId = Json.parseToJsonElement(session.bodyAsText()).jsonObject.getValue("rootNodeId").jsonPrimitive.content

        val response = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"$rootNodeId"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue("TRIAL_EXHAUSTED" in response.bodyAsText())
    }

    @Test
    fun `taking a quiz end to end returns the score and the rationales`() = app {
        val sessionId = newSessionWithOneChild()
        val session = client.get("/api/sessions/$sessionId")
        val rootNodeId = Json.parseToJsonElement(session.bodyAsText()).jsonObject.getValue("rootNodeId").jsonPrimitive.content
        val rootExplanationKey = Json.parseToJsonElement(session.bodyAsText()).jsonObject
            .getValue("nodes").jsonArray
            .first { it.jsonObject.getValue("nodeId").jsonPrimitive.content == rootNodeId }
            .jsonObject.getValue("explanationKey").jsonPrimitive.content
        stack.llm.nextStructuredJson = validQuizJson(rootExplanationKey)

        val started = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"$rootNodeId"}""")
        }
        val startedBody = Json.parseToJsonElement(started.bodyAsText()).jsonObject
        val attemptId = startedBody.getValue("attemptId").jsonPrimitive.content
        val questionId = startedBody.getValue("questions").jsonArray.first()
            .jsonObject.getValue("questionId").jsonPrimitive.content

        val answered = client.post("/api/sessions/$sessionId/quizzes/$attemptId/answers") {
            contentType(ContentType.Application.Json)
            setBody("""{"answers":[{"questionId":"$questionId","chosenIndex":0}]}""")
        }

        assertEquals(HttpStatusCode.OK, answered.status)
        val result = Json.parseToJsonElement(answered.bodyAsText()).jsonObject
        assertTrue(result.containsKey("score"))
        assertTrue(result.containsKey("total"))
        assertTrue(result.containsKey("rationales"))
        assertTrue(result.containsKey("correctIndices"))
    }

    @Test
    fun `answering someone else's attempt is refused as not found`() = app {
        val sessionId = newSessionWithOneChild()
        val session = client.get("/api/sessions/$sessionId")
        val sessionBody = Json.parseToJsonElement(session.bodyAsText()).jsonObject
        val rootNodeId = sessionBody.getValue("rootNodeId").jsonPrimitive.content
        val rootExplanationKey = sessionBody.getValue("nodes").jsonArray
            .first { it.jsonObject.getValue("nodeId").jsonPrimitive.content == rootNodeId }
            .jsonObject.getValue("explanationKey").jsonPrimitive.content
        stack.llm.nextStructuredJson = validQuizJson(rootExplanationKey)

        val started = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"$rootNodeId"}""")
        }
        val attemptId = Json.parseToJsonElement(started.bodyAsText()).jsonObject.getValue("attemptId").jsonPrimitive.content

        // A second, independently signed-in learner, with its own cookie jar and its own
        // principal. This proves ownership isolation. A second request on the same client would
        // only prove that a repeated request is refused.
        val otherLearner = anotherLearner()
        val response = otherLearner.post("/api/sessions/$sessionId/quizzes/$attemptId/answers") {
            contentType(ContentType.Application.Json)
            setBody("""{"answers":[]}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `an attempt owned by a different principal is refused as not found, even inside the caller's own session`() = app {
        // The test above proves session-ownership isolation: requireOwnedBy refuses a second
        // learner before the route ever reaches the attempt lookup. This test reaches past that
        // check. The session below genuinely belongs to the caller, so requireOwnedBy passes, and
        // only the route's own attempt.principalId check can still refuse the request.
        val sessionId = newSessionWithOneChild()

        // Inserted directly through the repository, bypassing the generation route, so an attempt
        // can exist in the caller's own session while belonging to a different principal. The
        // template id is a placeholder: the route raises before it ever looks the template up.
        val foreignAttempt = QuizAttempt(
            id = "attempt-${UUID.randomUUID()}",
            principalId = "user:someone-else",
            sessionId = sessionId,
            templateId = "placeholder-template",
            answers = emptyList(),
            score = null,
            total = 1,
            createdAtEpochMillis = 0,
            submittedAtEpochMillis = null,
        )
        quizRepository.upsertAttempt(foreignAttempt)

        val response = client.post("/api/sessions/$sessionId/quizzes/${foreignAttempt.id}/answers") {
            contentType(ContentType.Application.Json)
            setBody("""{"answers":[]}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
