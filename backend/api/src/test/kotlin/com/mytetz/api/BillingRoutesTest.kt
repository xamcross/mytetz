package com.mytetz.api

import com.mytetz.account.AccountRepository
import com.mytetz.account.AccountService
import com.mytetz.account.MagicLinkService
import com.mytetz.account.MailSender
import com.mytetz.billing.BillingConfig
import com.mytetz.billing.BillingRepository
import com.mytetz.billing.BillingService
import com.mytetz.billing.FreemiusConfig
import com.mytetz.billing.SubscriptionStatus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.bson.Document
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val wireJson = Json { ignoreUnknownKeys = true }

/**
 * The exact HMAC-SHA256-over-raw-bytes computation Freemius documents, kept as its own copy so
 * this suite proves the route agrees with the vendor's scheme and not merely with itself — the
 * same reasoning `FreemiusWebhookTest`'s own `hmacLowerHex` states.
 */
private fun hmacLowerHex(body: String, secretKey: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * `POST /api/billing/checkout`, `POST /api/billing/webhook`, and the entitlement gate a webhook's
 * verdict actually reaches.
 *
 * The pipeline tests — `an active subscriber reaches the pipeline` and `an expired subscriber
 * answers SUBSCRIPTION_REQUIRED` — wire `sessionRoutes` into this suite's app alongside
 * `billingRoutes`, the same shape `AuthRoutesTest` uses. Without that, a passing webhook test would
 * only prove a Mongo row changed, never that the change this whole slice exists for — what a
 * signed-in learner's explain request receives — actually moved.
 */
class BillingRoutesTest {

    companion object {
        private const val SECRET_KEY = "a-test-secret-key"
    }

    /**
     * Records the link `MagicLinkService.request` would have mailed, keyed by address, so a test
     * can complete a real sign-in without a mail provider.
     */
    private class CapturingMailSender : MailSender {
        private val links = mutableMapOf<String, String>()
        override suspend fun sendMagicLink(email: String, link: String) {
            links[email] = link
        }
        fun tokenFor(email: String): String = links.getValue(email).substringAfterLast("/")
    }

    private class Scope(
        val client: HttpClient,
        val account: AccountService,
        val mailSender: CapturingMailSender,
        val stack: TestFixtures.SessionStack,
        val billingRepository: BillingRepository,
        val freemiusConfig: FreemiusConfig,
    ) {
        /** Completes a real magic-link sign-in for [http], and returns the address it signed in as. */
        suspend fun signIn(http: HttpClient = client, email: String = "learner-${UUID.randomUUID()}@example.com"): String {
            val requested = http.post("/api/auth/magic-link") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email"}""")
            }
            check(requested.status == HttpStatusCode.NoContent) {
                "fixture error: the magic-link request failed: ${requested.status}"
            }
            val consumed = http.get("/api/auth/magic-link/${mailSender.tokenFor(email)}")
            check(consumed.status == HttpStatusCode.Found) {
                "fixture error: sign-in did not redirect: ${consumed.status}"
            }
            return email
        }

        suspend fun createSession(topicSlug: String = "quantum-physics", http: HttpClient = client): SessionView {
            val response = http.post("/api/sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"topicSlug":"$topicSlug"}""")
            }
            check(response.status == HttpStatusCode.OK) {
                "fixture error: could not create a session: ${response.bodyAsText()}"
            }
            return wireJson.decodeFromString(response.bodyAsText())
        }

        /** Posts [body] to the webhook route, signed under this scope's own [FreemiusConfig]. */
        suspend fun webhook(body: String, signature: String? = hmacLowerHex(body, freemiusConfig.secretKey)): HttpResponse =
            client.post("/api/billing/webhook") {
                contentType(ContentType.Application.Json)
                signature?.let { header("X-Signature", it) }
                setBody(body)
            }
    }

    /** A span that really does sit where it says it does, taken from the session's own root body. */
    private fun SessionView.explainBody(text: String = "behavior of matter"): String {
        val root = nodes.single { it.nodeId == rootNodeId }
        val body = explanations.getValue(root.explanationKey)
        val start = body.indexOf(text)
        require(start >= 0) { "fixture error: \"$text\" is not in the root body" }
        return """{"parentNodeId":"$rootNodeId","span":{"text":"$text","start":$start,"end":${start + text.length}},"verb":"EXPLAIN"}"""
    }

    private fun app(block: suspend Scope.() -> Unit) = testApplication {
        val stack = TestFixtures.sessionApp()
        val accountRepository = AccountRepository(stack.database)
        val account = AccountService(accountRepository)
        val mailSender = CapturingMailSender()
        val magicLink = MagicLinkService(accountRepository, mailSender, baseUrl = "http://localhost")
        val billingRepository = BillingRepository(stack.database)
        val billing = BillingService(billingRepository, config = BillingConfig())
        val freemiusConfig = FreemiusConfig(secretKey = SECRET_KEY, productId = "prod-1", planId = "plan-1")

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            installErrorMapping()
            routing {
                sessionRoutes(
                    sessions = { stack.sessions },
                    quota = stack.quota,
                    billing = billing,
                    account = account,
                    cookies = TestFixtures.cookieConfig,
                    clientAddresses = ClientAddressConfig(trustedHeader = null),
                    sessionLimiter = stack.limiter,
                    explainLimiter = stack.explainLimiter,
                )
                authRoutes(
                    account = account,
                    sessions = { stack.sessions },
                    magicLink = { magicLink },
                    google = { error("google sign-in is not exercised by BillingRoutesTest") },
                    cookies = TestFixtures.cookieConfig,
                    quotaRepository = stack.quotaRepository,
                    billing = billing,
                    clientAddresses = ClientAddressConfig(trustedHeader = null),
                )
                billingRoutes(
                    account = account,
                    billing = billing,
                    freemiusConfig = { freemiusConfig },
                    cookies = TestFixtures.cookieConfig,
                )
            }
        }

        val client = createClient { install(HttpCookies); followRedirects = false }
        Scope(client, account, mailSender, stack, billingRepository, freemiusConfig).block()
    }

    private suspend fun HttpResponse.apiError(): ApiError = wireJson.decodeFromString(bodyAsText())

    // ------------------------------------------------------------------ checkout

    @Test
    fun `checkout answers 401 when signed out`() = app {
        val response = client.post("/api/billing/checkout")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("SIGN_IN_REQUIRED", response.apiError().code)
    }

    @Test
    fun `checkout returns a url carrying the learner's email`() = app {
        val email = signIn()

        val response = client.post("/api/billing/checkout")

        assertEquals(HttpStatusCode.OK, response.status)
        val body: CheckoutResponse = wireJson.decodeFromString(response.bodyAsText())
        assertTrue(
            body.url.contains("user_email=${URLEncoder.encode(email, Charsets.UTF_8)}"),
            "the url must carry the signed-in learner's own email: ${body.url}",
        )
        assertTrue(body.url.startsWith("https://checkout.freemius.com/product/prod-1/plan/plan-1/"))
    }

    @Test
    fun `the checkout url marks the address read-only`() = app {
        signIn()

        val response = client.post("/api/billing/checkout")

        val body: CheckoutResponse = wireJson.decodeFromString(response.bodyAsText())
        assertTrue(body.url.contains("readonly_user=true"), "the url must mark the address read-only: ${body.url}")
    }

    // ------------------------------------------------------------------ the webhook and email resolution

    @Test
    fun `an event with no user reference resolves by email`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id
        assertEquals(SubscriptionStatus.TRIALING, billingRepository.find(userId)?.status)

        val response = webhook(
            """{"id":"evt-by-email","type":"subscription.created","created":1000,"email":"$email"}""",
        )

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(SubscriptionStatus.ACTIVE, billingRepository.find(userId)?.status)
    }

    @Test
    fun `an event whose email matches no account changes nothing`() = app {
        val unknownEmail = "unmatched-${UUID.randomUUID()}@example.com"
        val before = stack.database.getCollection<Document>("subscriptions").countDocuments()

        val response = webhook(
            """{"id":"evt-unmatched","type":"subscription.created","created":1000,"email":"$unknownEmail"}""",
        )

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(before, stack.database.getCollection<Document>("subscriptions").countDocuments())
        assertNull(account.findByEmail(unknownEmail), "an unmatched event must not create an account either")
    }

    @Test
    fun `the webhook route reads the raw body`() = app {
        // Deliberately odd whitespace: a JSON parser is free to normalise it away when it writes
        // the value back out, so a route that hashed a re-encoded copy would refuse this body.
        val original = """{ "id": "evt-raw", "type": "subscription.created",  "created": 1000 }"""
        val reserialized = Json.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(original))
        assertNotEquals(original, reserialized, "fixture error: re-serializing must change the bytes")

        val response = webhook(original)

        assertEquals(
            HttpStatusCode.NoContent,
            response.status,
            "the exact bytes that were signed must verify, so the route must read them raw",
        )
    }

    @Test
    fun `the webhook refuses a bad signature with 401`() = app {
        val response = webhook(
            """{"id":"evt-bad-sig","type":"subscription.created","created":1000}""",
            signature = "0000000000000000000000000000000000000000000000000000000000000000",
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("SIGNATURE_INVALID", response.apiError().code)
    }

    @Test
    fun `the webhook answers 204 for a duplicate event`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id
        val body = """{"id":"evt-dup","type":"subscription.created","created":1000,"email":"$email"}"""

        val first = webhook(body)
        val second = webhook(body)

        assertEquals(HttpStatusCode.NoContent, first.status)
        assertEquals(HttpStatusCode.NoContent, second.status)
        assertEquals(SubscriptionStatus.ACTIVE, billingRepository.find(userId)?.status)
    }

    @Test
    fun `the webhook applies a first payment`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id

        val response = webhook(
            """{"id":"evt-first-payment","type":"subscription.created","created":1000,"email":"$email"}""",
        )

        assertEquals(HttpStatusCode.NoContent, response.status)
        val stored = requireNotNull(billingRepository.find(userId))
        assertEquals(SubscriptionStatus.ACTIVE, stored.status)
    }

    // ------------------------------------------------------------------ the entitlement pipeline

    @Test
    fun `an active subscriber reaches the pipeline`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id
        val created = createSession()
        val before = stack.generations

        val activated = webhook(
            """{"id":"evt-pipeline-active","type":"subscription.created","created":1000,"email":"$email"}""",
        )
        assertEquals(HttpStatusCode.NoContent, activated.status)
        assertEquals(SubscriptionStatus.ACTIVE, billingRepository.find(userId)?.status)

        val response = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(sessionView(created.sessionId).explainBody())
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(stack.generations > before, "an active subscriber's explain did not reach the model")
    }

    @Test
    fun `an expired subscriber answers SUBSCRIPTION_REQUIRED`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id
        val created = createSession()
        val before = stack.generations

        // payment.refund maps straight to EXPIRED. The row is TRIALING with no prior event, so the
        // ordering rule in BillingService.apply never drops this as stale.
        val expired = webhook(
            """{"id":"evt-pipeline-expired","type":"payment.refund","created":1000,"email":"$email"}""",
        )
        assertEquals(HttpStatusCode.NoContent, expired.status)
        assertEquals(SubscriptionStatus.EXPIRED, billingRepository.find(userId)?.status)

        val response = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(sessionView(created.sessionId).explainBody())
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("SUBSCRIPTION_REQUIRED", response.apiError().code)
        assertEquals(before, stack.generations, "an expired subscriber reached the model")
    }

    private suspend fun Scope.sessionView(sessionId: String): SessionView =
        wireJson.decodeFromString(client.get("/api/sessions/$sessionId").bodyAsText())
}
