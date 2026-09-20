package com.mytetz.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.bson.Document
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val wireJson = Json { ignoreUnknownKeys = true }

/** The name `BillingRoutes.kt` gives its own logger. Read back here to attach a test appender. */
private const val BILLING_ROUTES_LOGGER = "com.mytetz.api.BillingRoutes"

/** `2025-12-31 23:59:59` UTC, computed independently with `date -u -d ... +%s`. */
private const val DEC_31_2025_UTC_EPOCH_MILLIS = 1_767_225_599_000L

/** `2026-01-31 23:59:59` UTC, computed the same independent way. */
private const val JAN_31_2026_UTC_EPOCH_MILLIS = 1_769_903_999_000L

/**
 * Builds one full `subscription.created` event, in the exact shape
 * `packages/sdk/src/webhook/subscription.events.ts` declares. It carries a learner's own [email]
 * under `objects.user.email`, so `BillingRoutes.kt`'s resolver can find the row this test signed
 * in for.
 */
private fun subscriptionCreatedBody(
    id: String,
    email: String,
    created: String = "2025-01-01 00:00:00",
    freemiusUserId: String = "1001",
    freemiusSubscriptionId: String = "2001",
    licenseExpiration: String = "2025-12-31 23:59:59",
): String =
    """{"id":"$id","type":"subscription.created","created":"$created",""" +
        """"objects":{"user":{"id":"$freemiusUserId","email":"$email"},""" +
        """"subscription":{"id":"$freemiusSubscriptionId"},""" +
        """"license":{"expiration":"$licenseExpiration"}},""" +
        """"data":{"subscription_id":"$freemiusSubscriptionId"}}"""

/**
 * Builds one full `license.extended` event, in the exact shape
 * `packages/sdk/src/webhook/license.events.ts` declares: `objects.license.user_id`, and
 * `data.to` as the new expiration date. [email] is left out unless a caller names one:
 * `objects.user` is optional on this event, and a renewal in the wild can carry none — the exact
 * case `BillingRoutes.kt`'s email resolver, and the [freemiusUserId] fallback behind it, both
 * exist for.
 */
private fun licenseExtendedBody(
    id: String,
    to: String,
    from: String = "2025-01-01 00:00:00",
    created: String = to,
    freemiusUserId: String = "1001",
    email: String? = null,
    licenseId: String = "3001",
): String {
    val user = email?.let { ""","user":{"id":"$freemiusUserId","email":"$it"}""" }.orEmpty()
    return """{"id":"$id","type":"license.extended","created":"$created",""" +
        """"objects":{"license":{"user_id":"$freemiusUserId"}$user},""" +
        """"data":{"from":"$from","to":"$to","license_id":"$licenseId","is_renewal":true}}"""
}

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
 * Builds one webhook body in the vendor shape `packages/sdk/src/webhook/subscription.events.ts`
 * declares: a top-level `id`, `type` and `created`. When [email] is given, the body also carries
 * one `objects` object, with the address at `objects.user.email` — the field
 * `BillingRoutes.kt`'s resolver actually reads. Every test in this suite that needs a webhook
 * body from an email builds it through this one function. A fixture then never drifts back to a
 * top-level `email` field.
 */
private fun webhookBody(
    id: String,
    type: String = "subscription.created",
    created: String = "2025-01-01 00:00:00",
    email: String? = null,
): String {
    val objects = email?.let { ""","objects":{"user":{"email":"$it"}}""" }.orEmpty()
    return """{"id":"$id","type":"$type","created":"$created"$objects}"""
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
        private val builder: ApplicationTestBuilder,
        val client: HttpClient,
        val account: AccountService,
        val mailSender: CapturingMailSender,
        val stack: TestFixtures.SessionStack,
        val billingRepository: BillingRepository,
        val freemiusConfig: FreemiusConfig,
    ) {
        /**
         * A second learner: its own cookie jar and its own signed-in session. The portal rate
         * limiter keys on the learner's own id, so this is how a test proves that one learner's
         * limit does not spend a different learner's own allowance.
         */
        suspend fun anotherLearner(): HttpClient {
            val http = builder.createClient { install(HttpCookies); followRedirects = false }
            signIn(http)
            return http
        }

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

    private fun app(
        // Null keeps every existing test on a Freemius config that always builds. See the
        // fallback to the real `freemiusConfig` below. A config-missing test overrides this to a
        // throwing lambda, the same shape `AuthRoutesTest`'s own `magicLinkFactory` uses.
        freemiusConfigFactory: (() -> FreemiusConfig)? = null,
        // Null keeps every existing test on a portal client that answers a fixed link. No test
        // outside the "portal" group below ever calls the portal route, so that default never
        // actually runs. A portal test overrides this the same way a config-missing test
        // overrides `freemiusConfigFactory` above.
        freemiusApiClientFactory: (() -> FreemiusApiClient)? = null,
        // Null keeps every existing test on the production limit — five calls per ten minutes,
        // far more than one ordinary test needs. A rate-limiter test overrides this to a small
        // limiter, so it does not need to send many requests to reach it.
        portalLimiter: FixedWindowRateLimiter? = null,
        // The default `BillingConfig()` keeps every existing test on the same numbers `billing`
        // below already uses. A plans test overrides this to prove the route reads its own
        // argument, and not a second, hand-written default.
        billingConfig: BillingConfig = BillingConfig(),
        block: suspend Scope.() -> Unit,
    ) = testApplication {
        val stack = TestFixtures.sessionApp()
        val accountRepository = AccountRepository(stack.database)
        val account = AccountService(accountRepository)
        val mailSender = CapturingMailSender()
        val magicLink = MagicLinkService(accountRepository, mailSender, baseUrl = "http://localhost")
        val billingRepository = BillingRepository(stack.database)
        val billing = BillingService(billingRepository, config = billingConfig)
        val freemiusConfig = FreemiusConfig(secretKey = SECRET_KEY, productId = "prod-1", planId = "plan-1")
        val freemiusApiClient = portalClient(link = "https://example.freemius.com/portal?token=default")

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
                    quizzes = { error("quizzes are not exercised by BillingRoutesTest") },
                    clientAddresses = ClientAddressConfig(trustedHeader = null),
                )
                billingRoutes(
                    account = account,
                    billing = billing,
                    billingConfig = billingConfig,
                    freemiusConfig = freemiusConfigFactory ?: { freemiusConfig },
                    freemiusApiClient = freemiusApiClientFactory ?: { freemiusApiClient },
                    cookies = TestFixtures.cookieConfig,
                    portalLimiter = portalLimiter ?: FixedWindowRateLimiter(
                        limit = PORTAL_REQUESTS_PER_LEARNER,
                        windowMillis = PORTAL_WINDOW_MILLIS,
                    ),
                )
            }
        }

        val client = createClient { install(HttpCookies); followRedirects = false }
        Scope(this, client, account, mailSender, stack, billingRepository, freemiusConfig).block()
    }

    /**
     * A [FreemiusApiClient] wired to a [MockEngine] that answers a fixed portal login response,
     * for one test's own use of `POST /api/billing/portal`.
     *
     * [link] set answers `201` with that link, the shape a real subscriber gets. [link] null
     * answers `404`, the shape `products/generate-portal-login-link`'s own schema documents for a
     * learner with nothing to manage — see [FreemiusApiClient.fetchPortalLink]'s own KDoc.
     */
    private fun portalClient(link: String?): FreemiusApiClient {
        val engine = MockEngine {
            if (link != null) {
                respond(
                    content = """{"link": "$link"}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
        return FreemiusApiClient(HttpClient(engine), FreemiusApiConfig(apiKey = "test-api-key", productId = "prod-1"))
    }

    /**
     * Same as [portalClient], and it also counts each request the mock engine receives, into
     * [calls]. `BillingRoutes.kt` builds a fresh [FreemiusApiClient] from `freemiusApiClient` on
     * every request — see `buildConfiguredOrNull` — so [calls] is a plain counter passed in from
     * the test, and not a field on this client, or a later request would start counting from zero.
     */
    private fun portalClientCounting(link: String?, calls: AtomicInteger): FreemiusApiClient {
        val engine = MockEngine {
            calls.incrementAndGet()
            if (link != null) {
                respond(
                    content = """{"link": "$link"}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
        return FreemiusApiClient(HttpClient(engine), FreemiusApiConfig(apiKey = "test-api-key", productId = "prod-1"))
    }

    private suspend fun HttpResponse.apiError(): ApiError = wireJson.decodeFromString(bodyAsText())

    // ------------------------------------------------------------------ the plans view (issue #137)

    @Test
    fun `plans answers the price and the three billingConfig numbers, in the raw JSON`() = app {
        val response = client.get("/api/billing/plans")

        assertEquals(HttpStatusCode.OK, response.status)
        // Asserted on the raw JSON text, and not on a decoded field: BillingPlansResponse has no
        // default value on any field (see its own KDoc), so a field that regressed back to a
        // default would still decode, and only the raw text would show it is missing or wrong.
        assertEquals(
            """{"priceUsdPerMonth":12,"trialDays":7,"trialGenerations":40,"subscriberDailyExplains":25}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `plans reads a non-default billingConfig, and not the hard-coded defaults`() = app(
        billingConfig = BillingConfig(trialDays = 9, trialGenerations = 55, subscriberDailyExplains = 30),
    ) {
        val response = client.get("/api/billing/plans")

        assertEquals(
            """{"priceUsdPerMonth":12,"trialDays":9,"trialGenerations":55,"subscriberDailyExplains":30}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `plans needs no sign-in`() = app {
        // No signIn() call here, on purpose: a visitor with no session must read this route too.
        val response = client.get("/api/billing/plans")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `plans sets no cookie`() = app {
        val response = client.get("/api/billing/plans")

        assertNull(response.headers[HttpHeaders.SetCookie])
    }

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

    // ------------------------------------------------------------------ the customer portal

    @Test
    fun `portal answers 401 when signed out`() = app {
        val response = client.post("/api/billing/portal")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("SIGN_IN_REQUIRED", response.apiError().code)
    }

    @Test
    fun `portal returns the vendor's own link for a signed-in learner`() = app(
        freemiusApiClientFactory = { portalClient(link = "https://example.freemius.com/portal?token=live") },
    ) {
        signIn()

        val response = client.post("/api/billing/portal")

        assertEquals(HttpStatusCode.OK, response.status)
        val body: PortalResponse = wireJson.decodeFromString(response.bodyAsText())
        assertEquals("https://example.freemius.com/portal?token=live", body.url)
    }

    @Test
    fun `portal answers NO_SUBSCRIPTION when the vendor gives no link`() = app(
        freemiusApiClientFactory = { portalClient(link = null) },
    ) {
        signIn()

        val response = client.post("/api/billing/portal")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NO_SUBSCRIPTION", response.apiError().code)
    }

    @Test
    fun `portal answers BILLING_UNAVAILABLE when the freemius api credential is missing`() = app(
        freemiusApiClientFactory = { error("FREEMIUS_API_KEY is not set") },
    ) {
        signIn()

        val response = client.post("/api/billing/portal")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("BILLING_UNAVAILABLE", response.apiError().code)
    }

    // ------------------------------------------------------------------ the portal rate limiter

    @Test
    fun `a learner can call the portal up to the limit, then is refused`() = app(
        portalLimiter = FixedWindowRateLimiter(limit = 2, windowMillis = 600_000),
    ) {
        signIn()

        assertEquals(HttpStatusCode.OK, client.post("/api/billing/portal").status)
        assertEquals(HttpStatusCode.OK, client.post("/api/billing/portal").status)
        val refused = client.post("/api/billing/portal")

        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertEquals("RATE_LIMITED", refused.apiError().code)
        // Same shape `QuizRoutes.kt`'s own 429 uses: a `retryAfter` field, and the same value on
        // the `Retry-After` header — a proxy, a client library and a crawler all read the header,
        // and none of them reads our JSON.
        val retryAfter = assertNotNull(refused.apiError().retryAfter, "a 429 with no retryAfter tells the client to guess")
        assertEquals(retryAfter.toString(), refused.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `a refused portal call makes no vendor request`() {
        val calls = AtomicInteger(0)
        app(
            portalLimiter = FixedWindowRateLimiter(limit = 1, windowMillis = 600_000),
            freemiusApiClientFactory = {
                portalClientCounting(link = "https://example.freemius.com/portal?token=count", calls)
            },
        ) {
            signIn()

            assertEquals(HttpStatusCode.OK, client.post("/api/billing/portal").status)
            assertEquals(1, calls.get(), "fixture error: the allowed call must reach the vendor")

            val refused = client.post("/api/billing/portal")

            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertEquals(1, calls.get(), "a refused call must not reach the vendor")
        }
    }

    @Test
    fun `the portal limit of one learner does not spend a second learner's own allowance`() = app(
        portalLimiter = FixedWindowRateLimiter(limit = 1, windowMillis = 600_000),
    ) {
        signIn()
        assertEquals(HttpStatusCode.OK, client.post("/api/billing/portal").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/api/billing/portal").status)

        val other = anotherLearner()

        assertEquals(HttpStatusCode.OK, other.post("/api/billing/portal").status)
    }

    @Test
    fun `a signed-out portal call answers 401 and spends no learner's allowance`() = app(
        portalLimiter = FixedWindowRateLimiter(limit = 1, windowMillis = 600_000),
    ) {
        val signedOutResponse = client.post("/api/billing/portal")
        assertEquals(HttpStatusCode.Unauthorized, signedOutResponse.status)

        // The limiter keys on the signed-in learner's own id. A signed-out call resolves no such
        // id, so it must never reach `tryAcquire` at all — proven here by a learner who signs in
        // right after, and still gets the full limit.
        signIn()
        assertEquals(HttpStatusCode.OK, client.post("/api/billing/portal").status)
    }

    // ------------------------------------------------------------------ the webhook and email resolution

    @Test
    fun `an event with no user reference resolves by email`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id
        assertEquals(SubscriptionStatus.TRIALING, billingRepository.find(userId)?.status)

        val response = webhook(webhookBody(id = "evt-by-email", email = email))

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(SubscriptionStatus.ACTIVE, billingRepository.find(userId)?.status)
    }

    @Test
    fun `an event whose email matches no account changes nothing`() = app {
        val unknownEmail = "unmatched-${UUID.randomUUID()}@example.com"
        val before = stack.database.getCollection<Document>("subscriptions").countDocuments()

        val response = webhook(webhookBody(id = "evt-unmatched", email = unknownEmail))

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(before, stack.database.getCollection<Document>("subscriptions").countDocuments())
        assertNull(account.findByEmail(unknownEmail), "an unmatched event must not create an account either")
    }

    @Test
    fun `a vendor-shaped subscription created event activates the row with the vendor's own fields`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id

        val response = webhook(subscriptionCreatedBody(id = "evt-vendor-shape", email = email))

        assertEquals(HttpStatusCode.NoContent, response.status)
        val stored = requireNotNull(billingRepository.find(userId))
        assertEquals(SubscriptionStatus.ACTIVE, stored.status)
        assertEquals(
            DEC_31_2025_UTC_EPOCH_MILLIS,
            stored.currentPeriodEndsAtEpochMillis,
            "the period end must come from objects.license.expiration",
        )
        assertEquals("1001", stored.freemiusUserId)
        assertEquals("2001", stored.freemiusSubscriptionId)
    }

    @Test
    fun `the ISO 8601 form of created gives the same row as the space form`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id

        val response = webhook(
            subscriptionCreatedBody(id = "evt-vendor-shape-iso", email = email, created = "2025-01-01T00:00:00Z"),
        )

        assertEquals(HttpStatusCode.NoContent, response.status)
        val stored = requireNotNull(billingRepository.find(userId))
        assertEquals(SubscriptionStatus.ACTIVE, stored.status)
        assertEquals(DEC_31_2025_UTC_EPOCH_MILLIS, stored.currentPeriodEndsAtEpochMillis)
        assertEquals("1001", stored.freemiusUserId)
        assertEquals("2001", stored.freemiusSubscriptionId)
    }

    @Test
    fun `the webhook route reads the raw body`() = app {
        // Deliberately odd whitespace: a JSON parser is free to normalise it away when it writes
        // the value back out, so a route that hashed a re-encoded copy would refuse this body.
        val original = """{ "id": "evt-raw", "type": "subscription.created",  "created": "2025-01-01 00:00:00" }"""
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
            webhookBody(id = "evt-bad-sig"),
            signature = "0000000000000000000000000000000000000000000000000000000000000000",
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("SIGNATURE_INVALID", response.apiError().code)
    }

    @Test
    fun `the webhook answers 400 and logs BILLING_UNPARSEABLE_EVENT with the type and the id, and no email`() = app {
        val appender = attachAppender()
        try {
            // This body is signed, so the signature check passes. The route then reaches
            // FreemiusWebhook.parse. The body has no "id" field, so parse raises the same
            // SerializationException a wrong-typed "created" field also raises. See
            // FreemiusWebhookTest for that second case. The body also carries an email, under
            // objects.user.email, the exact place a real event carries one.
            val body = """
                {"type":"subscription.created","created":"2025-01-01 00:00:00",
                "objects":{"user":{"email":"secret-agent@example.com"}}}
            """.trimIndent()

            val response = webhook(body)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val logged = requireNotNull(
                appender.list.firstOrNull { it.formattedMessage.contains("BILLING_UNPARSEABLE_EVENT") },
            ) { "BILLING_UNPARSEABLE_EVENT was not logged: ${appender.list.map { it.formattedMessage }}" }
            assertEquals(Level.WARN, logged.level)
            assertTrue(logged.formattedMessage.contains("type=subscription.created"), logged.formattedMessage)
            assertFalse(logged.formattedMessage.contains(body), "the log line must not carry the request body")
            assertFalse(
                logged.formattedMessage.contains("secret-agent@example.com"),
                "the log line must not carry the learner's email",
            )
        } finally {
            detachAppender(appender)
        }
    }

    @Test
    fun `a signed license deleted event, in the SDK shape, answers 204 and not 400`() = app {
        // license.events.ts types 'license.deleted' with objects: { license: false }. FreemiusWebhook
        // must read this event without raising, even though this deployment maps no status to it.
        val body = """
            {"id":"evt-license-deleted","type":"license.deleted","created":"2025-01-01 00:00:00",
            "objects":{"license":false},"data":{"license_id":"3001"}}
        """.trimIndent()

        val response = webhook(body)

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `the webhook answers 204 for a duplicate event`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id
        val body = webhookBody(id = "evt-dup", email = email)

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

        val response = webhook(webhookBody(id = "evt-first-payment", email = email))

        assertEquals(HttpStatusCode.NoContent, response.status)
        val stored = requireNotNull(billingRepository.find(userId))
        assertEquals(SubscriptionStatus.ACTIVE, stored.status)
    }

    // ------------------------------------------------------------------ license.extended, the renewal event

    @Test
    fun `a signed license extended event moves the period end to data to`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id
        val created = webhook(subscriptionCreatedBody(id = "evt-extended-setup", email = email))
        assertEquals(HttpStatusCode.NoContent, created.status)
        val before = requireNotNull(billingRepository.find(userId))
        assertEquals(SubscriptionStatus.ACTIVE, before.status)

        val response = webhook(
            licenseExtendedBody(
                id = "evt-extended",
                to = "2026-01-31 23:59:59",
                email = email,
                freemiusUserId = requireNotNull(before.freemiusUserId),
            ),
        )

        assertEquals(HttpStatusCode.NoContent, response.status)
        val stored = requireNotNull(billingRepository.find(userId))
        assertEquals(SubscriptionStatus.ACTIVE, stored.status)
        assertEquals(
            JAN_31_2026_UTC_EPOCH_MILLIS,
            stored.currentPeriodEndsAtEpochMillis,
            "the period end must come from data.to",
        )
    }

    @Test
    fun `a license extended event with no objects user still finds the row by freemiusUserId`() = app {
        // license.events.ts types objects.user as optional on license.extended. This body carries
        // none, so BillingRoutes.kt's email resolver has no email to resolve, and userReference
        // stays null. The row must still be found, by objects.license.user_id.
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id
        val created = webhook(subscriptionCreatedBody(id = "evt-extended-setup-2", email = email, freemiusUserId = "5001"))
        assertEquals(HttpStatusCode.NoContent, created.status)
        assertEquals("5001", billingRepository.find(userId)?.freemiusUserId)

        val response = webhook(
            licenseExtendedBody(id = "evt-extended-no-user", to = "2026-01-31 23:59:59", freemiusUserId = "5001"),
        )

        assertEquals(HttpStatusCode.NoContent, response.status)
        val stored = requireNotNull(billingRepository.find(userId))
        assertEquals(SubscriptionStatus.ACTIVE, stored.status)
        assertEquals(JAN_31_2026_UTC_EPOCH_MILLIS, stored.currentPeriodEndsAtEpochMillis)
    }

    // ------------------------------------------------------------------ the entitlement pipeline

    @Test
    fun `an active subscriber reaches the pipeline`() = app {
        val email = signIn()
        val userId = requireNotNull(account.findByEmail(email)).id
        val created = createSession()
        val before = stack.generations

        val activated = webhook(webhookBody(id = "evt-pipeline-active", email = email))
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
        val expired = webhook(webhookBody(id = "evt-pipeline-expired", type = "payment.refund", email = email))
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

    // ------------------------------------------------------------------ a missing freemius configuration

    @Test
    fun `checkout answers BILLING_UNAVAILABLE when the freemius configuration is missing`() = app(
        freemiusConfigFactory = { error("FREEMIUS_PRODUCT_ID is not set") },
    ) {
        signIn()

        val response = client.post("/api/billing/checkout")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("BILLING_UNAVAILABLE", response.apiError().code)
    }

    @Test
    fun `the webhook answers BILLING_UNAVAILABLE when the freemius configuration is missing`() = app(
        freemiusConfigFactory = { error("FREEMIUS_SECRET_KEY is not set") },
    ) {
        val response = client.post("/api/billing/webhook") {
            contentType(ContentType.Application.Json)
            setBody(webhookBody(id = "evt-1"))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("BILLING_UNAVAILABLE", response.apiError().code)
    }

    @Test
    fun `the BILLING_UNAVAILABLE body names no variable`() = app(
        freemiusConfigFactory = { error("FREEMIUS_PRODUCT_ID=super-secret-value is not set") },
    ) {
        signIn()

        val response = client.post("/api/billing/checkout")

        assertFalse(response.bodyAsText().contains("FREEMIUS_PRODUCT_ID"))
        assertFalse(response.bodyAsText().contains("super-secret-value"))
    }

    // ------------------------------------------------------------------ log capture
    //
    // This is the same technique `ErrorMappingTest` and `BillingServiceTest` use. It attaches a
    // ListAppender straight to the route's own logger, and reads the alert token back. It then
    // detaches the appender, so one test's appender never sees another test's log line.

    private fun attachAppender(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(BILLING_ROUTES_LOGGER) as ch.qos.logback.classic.Logger).addAppender(appender)
        return appender
    }

    private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
        (LoggerFactory.getLogger(BILLING_ROUTES_LOGGER) as ch.qos.logback.classic.Logger).detachAppender(appender)
    }
}
