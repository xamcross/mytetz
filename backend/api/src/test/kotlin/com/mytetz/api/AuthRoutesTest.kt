package com.mytetz.api

import com.mytetz.account.AccountRepository
import com.mytetz.account.AccountService
import com.mytetz.account.GoogleConfig
import com.mytetz.account.GoogleOAuth
import com.mytetz.account.MagicLinkService
import com.mytetz.account.MailSender
import com.mongodb.client.model.Filters
import com.mytetz.billing.BillingConfig
import com.mytetz.billing.BillingRepository
import com.mytetz.billing.BillingService
import com.mytetz.billing.SubscriptionStatus
import com.mytetz.quota.QuotaConfig
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.bson.Document
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val wireJson = Json { ignoreUnknownKeys = true }

/**
 * The sign-in gate this task adds, and the routes that put a learner behind it.
 *
 * The endpoints under test span three route registrations — `catalogRoutes`, `sessionRoutes` and
 * `authRoutes` — because the property this suite exists to pin is that the gate sits in exactly one
 * of them: an anonymous caller must keep reading the catalogue and opening a session, and must be
 * refused only at `POST /api/sessions/{id}/explain`.
 */
class AuthRoutesTest {

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
        /**
         * A second, independently-cookied client against the same running application. The trial
         * cap keys on the caller's IP bucket, and every client this helper builds resolves to the
         * same socket peer — see `authApp`'s own `ClientAddressConfig(trustedHeader = null)` — so
         * two clients this function returns are, for the cap's own purposes, "two visitors sharing
         * one address", exactly the scenario the cap exists to bound.
         */
        val newClient: () -> HttpClient,
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
    }

    /** A span that really does sit where it says it does, taken from the session's own root body. */
    private fun SessionView.explainBody(text: String = "behavior of matter"): String {
        val root = nodes.single { it.nodeId == rootNodeId }
        val body = explanations.getValue(root.explanationKey)
        val start = body.indexOf(text)
        require(start >= 0) { "fixture error: \"$text\" is not in the root body" }
        return """{"parentNodeId":"$rootNodeId","span":{"text":"$text","start":$start,"end":${start + text.length}},"verb":"EXPLAIN"}"""
    }

    /** A [MockEngine] that answers `{"success": false}` to every request — no socket, no Cloudflare account. */
    private fun rejectingTurnstileEngine(): MockEngine = MockEngine {
        respond(
            content = """{"success": false, "error-codes": ["invalid-input-response"]}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun defaultGoogleOAuth(): GoogleOAuth = GoogleOAuth(
        config = GoogleConfig(
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            redirectUri = "http://localhost/api/auth/google/callback",
        ),
        httpClient = HttpClient(CIO),
    )

    private fun authApp(
        googleOAuthFactory: () -> GoogleOAuth = { defaultGoogleOAuth() },
        // `BillingConfig`'s own default, and not `QuotaConfig.DEFAULT_DAILY_EXPLAINS`: the account
        // view's allowance comes from the trial, not from the quota module's daily default, and
        // the two constants no longer name the same number. The tests below that pin a value read
        // it from `BillingConfig` for that reason.
        trialGenerations: Int = BillingConfig.DEFAULT_TRIAL_GENERATIONS,
        // A settable clock, for the deletion-confirmation tests below: they sign in under one
        // reading and attempt a delete under a later one, entirely through this lambda closing
        // over a `var` the test itself owns. Every other test in this file never touches it, and
        // gets the real clock, exactly as before this parameter existed.
        clock: () -> Long = System::currentTimeMillis,
        turnstile: Turnstile = Turnstile(HttpClient(CIO), secretKey = null),
        block: suspend Scope.() -> Unit,
    ) = testApplication {
        val stack = TestFixtures.sessionApp()
        val accountRepository = AccountRepository(stack.database)
        // The same clock the route itself reads, so a session's own `createdAtEpochMillis` and the
        // freshness check in `POST /api/account/delete` are compared on one clock and not two —
        // otherwise a fake `clock` here would make every session look either always fresh or never
        // fresh, regardless of what a test actually simulates.
        val account = AccountService(accountRepository, clock = clock)
        val mailSender = CapturingMailSender()
        val magicLink = MagicLinkService(accountRepository, mailSender, baseUrl = "http://localhost")
        val billingRepository = BillingRepository(stack.database)
        val billing = BillingService(billingRepository, config = BillingConfig(trialGenerations = trialGenerations)) { clock() }

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            installErrorMapping()
            routing {
                catalogRoutes(
                    catalog = TestFixtures.seededCatalog(),
                    topicRequests = TestFixtures.topicRequests(),
                    cookies = TestFixtures.cookieConfig,
                    clientAddresses = ClientAddressConfig(trustedHeader = null),
                )
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
                    google = googleOAuthFactory,
                    cookies = TestFixtures.cookieConfig,
                    quotaRepository = stack.quotaRepository,
                    billing = billing,
                    turnstile = turnstile,
                    clientAddresses = ClientAddressConfig(trustedHeader = null),
                    clock = clock,
                )
            }
        }

        fun freshClient() = createClient { install(HttpCookies); followRedirects = false }
        Scope(freshClient(), account, mailSender, stack, billingRepository, newClient = ::freshClient).block()
    }

    // ------------------------------------------------------------------ the magic link

    @Test
    fun `a magic link request answers 204 for an unknown address`() = authApp {
        val response = client.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"unknown-${UUID.randomUUID()}@example.com"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `a magic link request answers 204 for a known address`() = authApp {
        val email = "known-${UUID.randomUUID()}@example.com"
        account.findOrCreateByEmail(email)

        val response = client.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `the two magic link answers are identical`() = authApp {
        val known = "known-identical-${UUID.randomUUID()}@example.com"
        account.findOrCreateByEmail(known)
        val unknown = "unknown-identical-${UUID.randomUUID()}@example.com"

        val knownResponse = client.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$known"}""")
        }
        val unknownResponse = client.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$unknown"}""")
        }

        assertEquals(knownResponse.status, unknownResponse.status)
        assertEquals(knownResponse.bodyAsText(), unknownResponse.bodyAsText())
    }

    @Test
    fun `consuming a link opens a session and redirects`() = authApp {
        val email = "consume-${UUID.randomUUID()}@example.com"
        client.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }

        val response = client.get("/api/auth/magic-link/${mailSender.tokenFor(email)}")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/", response.headers[HttpHeaders.Location])
        assertTrue(
            response.headers.getAll(HttpHeaders.SetCookie)?.any { it.contains(SESSION_COOKIE_NAME) } == true,
            "no session cookie was set on a successful consume",
        )
    }

    @Test
    fun `consuming a link twice redirects to the expired landing`() = authApp {
        val email = "twice-${UUID.randomUUID()}@example.com"
        client.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }
        val token = mailSender.tokenFor(email)
        client.get("/api/auth/magic-link/$token")

        val second = client.get("/api/auth/magic-link/$token")

        assertEquals(HttpStatusCode.Found, second.status)
        assertEquals("/auth?auth=expired", second.headers[HttpHeaders.Location])
    }

    @Test
    fun `consuming an unknown token redirects to the expired landing`() = authApp {
        val response = client.get("/api/auth/magic-link/not-a-real-token")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/auth?auth=expired", response.headers[HttpHeaders.Location])
    }

    // ------------------------------------------------------------------ Google

    @Test
    fun `the google callback refuses a mismatched state`() = authApp {
        client.get("/api/auth/google")

        val response = client.get("/api/auth/google/callback?state=not-the-real-state&code=some-code")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/auth?auth=failed", response.headers[HttpHeaders.Location])
        assertTrue(
            response.headers.getAll(HttpHeaders.SetCookie)?.none { it.contains(SESSION_COOKIE_NAME) } ?: true,
            "a refused callback still set a session cookie",
        )
    }

    @Test
    fun `the google callback opens a session on success`() {
        val email = "google-${UUID.randomUUID()}@example.com"
        val idToken = idTokenWith(sub = "g-sub-${UUID.randomUUID()}", email = email)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/token") { exchange ->
                val body = """{"id_token":"$idToken"}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            authApp(
                googleOAuthFactory = {
                    GoogleOAuth(
                        config = GoogleConfig(
                            clientId = "test-client-id",
                            clientSecret = "test-client-secret",
                            redirectUri = "http://localhost/api/auth/google/callback",
                        ),
                        httpClient = HttpClient(CIO),
                        tokenEndpoint = "http://127.0.0.1:${server.address.port}/token",
                    )
                },
            ) {
                val start = client.get("/api/auth/google")
                val state = Regex("state=([^&]+)").find(start.headers[HttpHeaders.Location].orEmpty())
                    ?.groupValues?.get(1) ?: error("fixture error: no state on the redirect")

                val response = client.get("/api/auth/google/callback?state=$state&code=test-code")

                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals("/", response.headers[HttpHeaders.Location])
                assertTrue(
                    response.headers.getAll(HttpHeaders.SetCookie)?.any { it.contains(SESSION_COOKIE_NAME) } == true,
                    "no session cookie was set on a successful google sign-in",
                )
            }
        } finally {
            server.stop(0)
        }
    }

    /**
     * Builds an ID token whose middle segment decodes to a JSON object holding [sub] and [email].
     * The header and the trailing "signature" are placeholders: `GoogleOAuth.parseIdToken` — reached
     * only inside `google().exchange`, on the loopback server's own answer — does not check either.
     */
    private fun idTokenWith(sub: String, email: String): String {
        val b64 = { s: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }
        val payload = """{"sub":"$sub","email":"$email","email_verified":true}"""
        return "${b64("""{"alg":"RS256"}""")}.${b64(payload)}.signature-not-verified"
    }

    // ------------------------------------------------------------------ the trail

    @Test
    fun `signing in carries an anonymous trail to the user`() = authApp {
        val created = createSession()

        val email = "trail-${UUID.randomUUID()}@example.com"
        signIn(email = email)

        val user = account.findOrCreateByEmail(email)
        assertEquals("user:${user.id}", stack.sessions.ownerOf(created.sessionId))

        // The read side, and not only the stored field. A fix round caught this exact gap: the
        // session document was correctly reassigned, but `GET /api/sessions/{id}` still resolved
        // the caller's anonymous principal and answered 404 — the learner's own reading session
        // vanished the moment they signed in.
        val response = client.get("/api/sessions/${created.sessionId}")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun `a session opened anonymously is explainable after signing in`() = authApp {
        // The unsafe order: the session is created before the sign-in that reassigns it.
        // `a signed-in explain reaches the pipeline` signs in first and then creates the session, so
        // it cannot catch a gate that resolves the wrong principal once `reassignPrincipal` has
        // already moved a pre-existing session onto the user.
        val created = createSession()

        signIn()

        val response = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(created.explainBody())
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("event: done"))
    }

    // ------------------------------------------------------------------ the trial

    @Test
    fun `signing in starts a trial`() = authApp {
        val before = System.currentTimeMillis()

        val email = signIn()
        val userId = account.findOrCreateByEmail(email).id

        val subscription = assertNotNull(billingRepository.find(userId))
        assertEquals(SubscriptionStatus.TRIALING, subscription.status)
        val trialEndsAt = assertNotNull(subscription.trialEndsAtEpochMillis)
        assertTrue(trialEndsAt > before, "a trial that has already ended admits nothing")
    }

    @Test
    fun `signing in twice leaves the first trial alone`() = authApp {
        val email = "twice-trial-${UUID.randomUUID()}@example.com"
        signIn(email = email)
        val userId = account.findOrCreateByEmail(email).id
        val first = assertNotNull(billingRepository.find(userId))

        // The same address, a second magic link, the way a learner who signs out and back in
        // really would.
        signIn(email = email)

        val second = assertNotNull(billingRepository.find(userId))
        assertEquals(first.trialEndsAtEpochMillis, second.trialEndsAtEpochMillis)
        assertEquals(first.createdAtEpochMillis, second.createdAtEpochMillis)
    }

    // ------------------------------------------------------------------ the gate

    @Test
    fun `an anonymous explain answers SIGN_IN_REQUIRED`() = authApp {
        val created = createSession()

        val response = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(created.explainBody())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("SIGN_IN_REQUIRED", wireJson.decodeFromString<ApiError>(response.bodyAsText()).code)
    }

    @Test
    fun `an anonymous explain with a bad span still answers SIGN_IN_REQUIRED`() = authApp {
        val created = createSession()

        val response = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"parentNodeId":"${created.rootNodeId}",""" +
                    """"span":{"text":"this text is not in the body at all","start":0,"end":5},"verb":"EXPLAIN"}""",
            )
        }

        // The leak test. If the span check ran first, a wrong guess would answer SPAN_MISMATCH and
        // tell an unauthenticated prober the span was checked at all.
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = wireJson.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("SIGN_IN_REQUIRED", error.code)
        assertNotEquals("SPAN_MISMATCH", error.code)
    }

    @Test
    fun `a signed-in explain reaches the pipeline`() = authApp {
        signIn()
        val created = createSession()

        val response = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(created.explainBody())
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("event: done"))
    }

    @Test
    fun `signing out clears the cookie and the next explain answers 401`() = authApp {
        signIn()
        val created = createSession()
        val first = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(created.explainBody())
        }
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())

        val signedOut = client.post("/api/auth/sign-out")
        assertEquals(HttpStatusCode.NoContent, signedOut.status)

        val second = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(created.explainBody())
        }

        assertEquals(HttpStatusCode.Unauthorized, second.status)
        assertEquals("SIGN_IN_REQUIRED", wireJson.decodeFromString<ApiError>(second.bodyAsText()).code)
    }

    // ------------------------------------------------------------------ what stays open

    @Test
    fun `the catalogue stays open to an anonymous caller`() = authApp {
        val response = client.get("/api/catalog/topics")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `creating a session stays open to an anonymous caller`() = authApp {
        val response = client.post("/api/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"topicSlug":"quantum-physics"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ------------------------------------------------------------------ the account view

    @Test
    fun `the account route answers 401 when signed out`() = authApp {
        val response = client.get("/api/account")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("SIGN_IN_REQUIRED", wireJson.decodeFromString<ApiError>(response.bodyAsText()).code)
    }

    @Test
    fun `the account route answers the view when signed in`() = authApp {
        val email = signIn()

        val response = client.get("/api/account")

        assertEquals(HttpStatusCode.OK, response.status)
        val view = wireJson.decodeFromString<AccountView>(response.bodyAsText())
        assertEquals(email, view.email)
        assertEquals(BillingConfig.DEFAULT_TRIAL_GENERATIONS, view.allowance)
        assertEquals(BillingConfig.DEFAULT_TRIAL_GENERATIONS, view.remaining)
    }

    @Test
    fun `the account view reports the entitlement allowance and not the quota default`() =
        authApp(trialGenerations = 40) {
            signIn()

            val response = client.get("/api/account")

            assertEquals(HttpStatusCode.OK, response.status)
            val view = wireJson.decodeFromString<AccountView>(response.bodyAsText())
            assertEquals(40, view.allowance)
            assertEquals(40, view.remaining)
            assertNotEquals(QuotaConfig.DEFAULT_DAILY_EXPLAINS, view.allowance)
        }

    @Test
    fun `the account route does not start a trial`() = authApp {
        val email = signIn()
        val userId = account.findOrCreateByEmail(email).id
        // Every real sign-in starts a trial in `completeSignIn`. Reaching past
        // `BillingRepository`, which has no delete method of its own, puts this learner back into
        // the state a real account created before this deployment is in: a live session cookie and
        // no subscription row.
        stack.database.getCollection<Document>("subscriptions").deleteOne(Filters.eq("_id", userId))
        assertNull(billingRepository.find(userId), "fixture error: the subscription row must be gone first")

        val response = client.get("/api/account")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertNull(billingRepository.find(userId), "GET /api/account started a trial for a learner who had none")
    }

    @Test
    fun `the account view counts a signed-in learner's spend`() = authApp {
        // The session is created before the sign-in, and deliberately: `POST /api/sessions` now
        // records spend under the effective principal too, and this database is fresh for this test
        // alone, so an anonymous seed generation for "quantum-physics" would otherwise land on the
        // user's own counter and be indistinguishable from the two explains this test means to
        // count. Creating the session first bills the seed to the anonymous principal, exactly as it
        // would for a real visitor reading a topic before they ever sign in.
        val created = createSession()
        signIn()
        // Two distinct spans, so both actually reach the model: a repeated span is a cache hit, and
        // a cache hit spends no allowance — the explain endpoint's own quota gate holds that
        // property, and this test would pin nothing if it collided with it.
        val first = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(created.explainBody("behavior of matter"))
        }
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val second = client.post("/api/sessions/${created.sessionId}/explain") {
            contentType(ContentType.Application.Json)
            setBody(created.explainBody("fundamental physical theory"))
        }
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())

        val response = client.get("/api/account")

        assertEquals(HttpStatusCode.OK, response.status)
        val view = wireJson.decodeFromString<AccountView>(response.bodyAsText())
        assertEquals(BillingConfig.DEFAULT_TRIAL_GENERATIONS - 2, view.remaining)
        assertNotNull(view.resetsAtEpochMillis, "a spent counter must report when it resets")
    }

    // ------------------------------------------------------------------ rate limiting

    @Test
    fun `a magic link request past the address limit answers 429`() = authApp {
        val email = "limited-${UUID.randomUUID()}@example.com"
        repeat(MAGIC_LINK_PER_ADDRESS) {
            val response = client.post("/api/auth/magic-link") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email"}""")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

        val refused = client.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertEquals("RATE_LIMITED", wireJson.decodeFromString<ApiError>(refused.bodyAsText()).code)
        assertNotNull(refused.headers[HttpHeaders.RetryAfter])
    }

    // ------------------------------------------------------------------ Turnstile

    @Test
    fun `a magic link request succeeds with no turnstile secret configured`() = authApp {
        // Every other test in this file signs in through this exact path with no secret set — this
        // one exists to say so directly, rather than leave it as an assumption every other test
        // happens to rely on.
        val response = client.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"turnstile-skip-${UUID.randomUUID()}@example.com"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `a magic link request is refused when turnstile is configured and the token is bad`() =
        authApp(turnstile = Turnstile(HttpClient(rejectingTurnstileEngine()), secretKey = "test-secret")) {
            val response = client.post("/api/auth/magic-link") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"turnstile-bad-${UUID.randomUUID()}@example.com","turnstileToken":"bad"}""")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("TURNSTILE_FAILED", wireJson.decodeFromString<ApiError>(response.bodyAsText()).code)
        }

    // ------------------------------------------------------------------ the trial cap

    @Test
    fun `a fourth trial from one ip bucket in a day is refused`() = authApp {
        repeat(BillingService.TRIAL_CAP_PER_IP_BUCKET) { i ->
            signIn(http = newClient(), email = "cap-$i-${UUID.randomUUID()}@example.com")
        }

        val refusedEmail = "cap-refused-${UUID.randomUUID()}@example.com"
        signIn(http = newClient(), email = refusedEmail)

        val userId = account.findOrCreateByEmail(refusedEmail).id
        assertNull(billingRepository.find(userId), "a fourth trial from one ip bucket must not be started")
    }

    @Test
    fun `a refused trial is offered checkout`() = authApp {
        repeat(BillingService.TRIAL_CAP_PER_IP_BUCKET) { i ->
            signIn(http = newClient(), email = "checkout-$i-${UUID.randomUUID()}@example.com")
        }

        val refusedClient = newClient()
        signIn(http = refusedClient, email = "checkout-refused-${UUID.randomUUID()}@example.com")

        // Not refused outright: the sign-in itself succeeded (signIn() above already asserts the
        // 302 it requires), and the caller can still read their own account.
        val response = refusedClient.get("/api/account")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val view = wireJson.decodeFromString<AccountView>(response.bodyAsText())
        assertEquals(0, view.allowance, "a capped sign-in must offer checkout, not a fresh trial allowance")
        assertEquals(0, view.remaining)
        assertEquals("NONE", view.status)
    }

    // ------------------------------------------------------------------ account deletion

    @Test
    fun `deleting an account removes the user and every session`() = authApp {
        // Created before the sign-in, the same order `signing in carries an anonymous trail to the
        // user` uses, so this learning session is reassigned onto the user and is really the
        // deleted account's own session — not an orphaned anonymous one the delete could never
        // have touched either way.
        val created = createSession()
        val email = signIn()

        val response = client.post("/api/account/delete")

        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
        assertNull(account.findByEmail(email), "the user row must be gone")
        assertNull(stack.sessions.load(created.sessionId), "the learning session must be gone")
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/account").status,
            "the session cookie must no longer resolve to anyone",
        )
    }

    @Test
    fun `deleting an account leaves every explanation`() = authApp {
        val created = createSession()
        signIn()
        val explanationKey = created.nodes.single { it.nodeId == created.rootNodeId }.explanationKey

        val response = client.post("/api/account/delete")

        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
        assertNotNull(
            stack.explanations.findByKey(explanationKey),
            "an explanation must survive its own principal's deletion",
        )
    }

    @Test
    fun `deleting an account needs a fresh confirmation`() {
        var now = 1_700_000_000_000L
        authApp(clock = { now }) {
            val email = signIn()
            // Well past any reasonable confirmation window — a learner who signed in a week ago and
            // left the tab open, not a boundary case.
            now += 7 * 24 * 60 * 60 * 1000L

            val response = client.post("/api/account/delete")

            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertEquals("CONFIRMATION_REQUIRED", wireJson.decodeFromString<ApiError>(response.bodyAsText()).code)
            assertNotNull(account.findByEmail(email), "a refused deletion must leave the account in place")
        }
    }

    @Test
    fun `a stale confirmation is refused`() {
        var now = 1_700_000_000_000L
        authApp(clock = { now }) {
            val email = signIn()
            // One millisecond past the exact boundary `AccountService.isFreshSession` allows —
            // pins the edge, rather than a margin so wide it could pass for any refusal at all.
            now += MagicLinkService.TTL_MILLIS + 1

            val response = client.post("/api/account/delete")

            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertEquals("CONFIRMATION_REQUIRED", wireJson.decodeFromString<ApiError>(response.bodyAsText()).code)
            assertNotNull(account.findByEmail(email))
        }
    }

    @Test
    fun `a deletion right at the confirmation boundary still succeeds`() {
        var now = 1_700_000_000_000L
        authApp(clock = { now }) {
            signIn()
            now += MagicLinkService.TTL_MILLIS

            val response = client.post("/api/account/delete")

            assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
        }
    }

    @Test
    fun `deleting an account while signed out answers SIGN_IN_REQUIRED`() = authApp {
        val response = client.post("/api/account/delete")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("SIGN_IN_REQUIRED", wireJson.decodeFromString<ApiError>(response.bodyAsText()).code)
    }
}
