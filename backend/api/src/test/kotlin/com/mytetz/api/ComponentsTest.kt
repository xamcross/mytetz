package com.mytetz.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.mytetz.account.GoogleConfig
import com.mytetz.account.GoogleOAuth
import com.mytetz.account.LoggingMailSender
import com.mytetz.account.MailSender
import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.Verb
import com.mytetz.llm.FakeLlmClient
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import com.mytetz.session.LearningSession
import com.mytetz.session.SessionNode
import com.mytetz.session.SessionRepository
import com.mytetz.session.SpanSelection
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Components.bootstrap()` is the **first production caller of `ensureIndexes()` anywhere in this
 * system**. Every repository grew one over Tasks 1.2–1.10, and until this task every one of them was
 * called only from a test — which means a deployed instance had no quota TTL index (so the
 * `principals` collection grew one document per anonymous visitor for ever), no explanation demand
 * indexes, no session indexes and no catalogue browse index.
 *
 * So "bootstrap exists" is not the thing worth testing. The two things worth testing are that it
 * covers *every* repository, and that `Application.module()` actually calls it — a bootstrap nothing
 * invokes leaves the system in exactly the state this task was meant to fix, and nothing else in the
 * suite would notice.
 */
class ComponentsTest {

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

    private fun components(name: String, migrateOnBoot: Boolean = false) = Components(
        mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_$name")),
        cookies = TestFixtures.cookieConfig,
        // Never AnthropicLlmClient: its default constructor calls `AnthropicOkHttpClient.fromEnv()`,
        // which demands a real key at construction time and would put a paid call one slip away.
        llmFactory = { FakeLlmClient() },
        migrateOnBoot = migrateOnBoot,
    )

    /**
     * Bootstrap runs in the background now (see `Application.kt`), so a test that asserts on its
     * effects has to wait for it rather than assume it finished before the first response.
     */
    private suspend fun awaitReady(client: io.ktor.client.HttpClient) {
        repeat(200) {
            if (client.get("/api/health").bodyAsText().contains("\"ready\":true")) return
            kotlinx.coroutines.delay(50)
        }
        throw AssertionError("bootstrap did not complete within 10s")
    }

    private suspend fun indexNames(database: MongoDatabase, collection: String): List<String> =
        database.getCollection<Document>(collection).listIndexes().toList().map { it.getString("name") }

    @Test
    fun `the migration is off unless the flag says otherwise`() {
        assertFalse(Components.resolveMigrateOnBoot(null))
        assertFalse(Components.resolveMigrateOnBoot(""))
        assertFalse(Components.resolveMigrateOnBoot("false"))
        assertFalse(Components.resolveMigrateOnBoot("yes"), "only the exact word true turns it on")
        assertFalse(Components.resolveMigrateOnBoot("1"))
    }

    @Test
    fun `the migration is on for the exact word true`() {
        assertTrue(Components.resolveMigrateOnBoot("true"))
        assertTrue(Components.resolveMigrateOnBoot("TRUE"))
        assertTrue(Components.resolveMigrateOnBoot("  true \n"), "a fly secret carries a trailing newline")
    }

    @Test
    fun `bootstrap creates every index in the system`() = runTest {
        val components = components("bootstrap")

        components.bootstrap()

        val database = components.mongo.database
        // One assertion per repository. A repository added later without a line in bootstrap() is
        // exactly the omission this test exists to catch, and it is invisible everywhere else.
        assertContains(indexNames(database, "topics"), "browse")
        assertContains(indexNames(database, "topicRequests"), "demand")
        assertContains(indexNames(database, "explanations"), "topic_demand")
        assertContains(indexNames(database, "explanations"), "created_at")
        assertContains(indexNames(database, "sessions"), "principal_recent")
        assertContains(indexNames(database, "sessions"), "by_topic")
        assertContains(indexNames(database, "sessions"), "by_explanation_key")
        assertContains(indexNames(database, "sessions"), "session_ttl")
        assertContains(indexNames(database, "principals"), "window_ttl")
        // AccountRepository, added by this task. `accountRepository.ensureIndexes()` was wired into
        // `bootstrap()` with no assertion here — the exact gap this test's own KDoc names. The two
        // TTL indexes matter most: without them `magicLinkTokens` and `authSessions` grow for ever.
        assertContains(indexNames(database, "users"), "email_unique")
        assertContains(indexNames(database, "users"), "google_sub_unique")
        assertContains(indexNames(database, "magicLinkTokens"), "token_ttl")
        assertContains(indexNames(database, "authSessions"), "session_ttl")
        assertContains(indexNames(database, "authSessions"), "by_user")
        // QuizRepository, added by this task. It creates no index on `quizTemplates` beyond the
        // default `_id` index, so this test asserts nothing about that collection.
        assertContains(indexNames(database, "quizAttempts"), "principal_recent")
        assertContains(indexNames(database, "quizAttempts"), "by_session")
    }

    @Test
    fun `the quota TTL index is a real TTL index and not an ordinary one`() = runTest {
        val components = components("ttl")

        components.bootstrap()

        val index = assertNotNull(
            components.mongo.database.getCollection<Document>("principals").listIndexes().toList()
                .firstOrNull { it.getString("name") == "window_ttl" },
        )
        // The whole point of the index. Without `expireAfterSeconds` it is an ordinary ascending
        // index that reaps nothing, and `principals` grows by one document per anonymous visitor for
        // ever — silently, because an index that exists looks like an index that works.
        assertEquals(0L, (index["expireAfterSeconds"] as Number).toLong())
    }

    @Test
    fun `the session TTL index is a real TTL index and not an ordinary one`() = runTest {
        val components = components("session-ttl")

        components.bootstrap()

        val index = assertNotNull(
            components.mongo.database.getCollection<Document>("sessions").listIndexes().toList()
                .firstOrNull { it.getString("name") == "session_ttl" },
        )
        // Without `expireAfterSeconds` this is an ordinary ascending index that reaps nothing, and
        // an anonymous visitor's session grows the `sessions` collection for ever — see
        // `SessionRepository.ensureIndexes`.
        assertEquals(0L, (index["expireAfterSeconds"] as Number).toLong())
    }

    @Test
    fun `bootstrap seeds the catalogue and is safe to run twice`() = runTest {
        val components = components("seed")

        components.bootstrap()
        val first = components.catalog.listPublished(null, null)
        // Every boot runs this, and on a scale-to-zero deployment a boot is any request after an
        // idle period. Running it twice must be indistinguishable from running it once.
        components.bootstrap()
        val second = components.catalog.listPublished(null, null)

        assertTrue(first.size >= 20, "the catalogue was not seeded: ${first.size} topics")
        assertEquals(first, second, "a second boot changed the catalogue")
    }

    @Test
    fun `module runs bootstrap, so a deployed instance has its indexes`() = testApplication {
        val components = components("module")
        application { module(components) }

        val response = client.get("/api/health")
        awaitReady(client)

        assertEquals(HttpStatusCode.OK, response.status)
        // The recorded carry-forward, closed here. If `module()` builds Components but never calls
        // bootstrap(), every assertion above still passes and production still has no indexes.
        assertContains(indexNames(components.mongo.database, "principals"), "window_ttl")
        assertContains(indexNames(components.mongo.database, "sessions"), "principal_recent")
    }

    @Test
    fun `health answers while bootstrap is still running`() = testApplication {
        // `module()` runs to completion before the engine accepts a single connection, so anything
        // blocking inside it is time during which NOTHING is served — including the endpoint whose
        // entire job is to report that the database is unreachable. With no `serverSelectionTimeoutMS`
        // configured, the driver's 30s default makes that window 30 seconds long during an outage,
        // against a health check with a 10s grace and a 5s timeout.
        //
        // A bootstrap fast enough to finish before the first request cannot tell a blocking
        // implementation from a non-blocking one, which is why this holds it open on a latch.
        val gate = java.util.concurrent.CountDownLatch(1)
        val components = object : Components(
            mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_slow_boot")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { FakeLlmClient() },
        ) {
            override suspend fun bootstrap() {
                withContext(Dispatchers.IO) { gate.await() }
                super.bootstrap()
            }
        }
        // Safety valve, so a blocking implementation fails this test instead of hanging the suite.
        // It also makes the failure legible: the mutant reaches the assertions with ready == true.
        Thread { Thread.sleep(2_000); gate.countDown() }.apply { isDaemon = true }.start()

        application { module(components) }
        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"mongo\":true"))
        assertTrue(
            response.bodyAsText().contains("\"ready\":false"),
            "health was not served until bootstrap had finished: ${response.bodyAsText()}",
        )

        gate.countDown()
        awaitReady(client)
    }

    @Test
    fun `module serves the catalogue and maps its errors`() = testApplication {
        application { module(components("routes")) }
        awaitReady(client)

        val listing = client.get("/api/catalog/topics")
        val missing = client.get("/api/catalog/topics/not-a-real-topic")

        assertEquals(HttpStatusCode.OK, listing.status)
        assertTrue(listing.bodyAsText().contains("quantum-physics"))
        // Proves installErrorMapping() is wired into the module and not merely into the tests: an
        // unmapped not-found would surface as a bodyless 404 or a 500.
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("NOT_FOUND"))
    }

    @Test
    fun `HEAD on a real endpoint is not swallowed by the api catch-all`() = testApplication {
        application { module(components("head")) }
        awaitReady(client)

        val response = client.head("/api/health")

        // Regression introduced by the `/api/{...}` tailcard: `get { }` does not answer HEAD, so
        // HEAD /api/health fell through to the catch-all and 404'd. Uptime monitors routinely use
        // HEAD, so the endpoint would have looked down while being perfectly healthy.
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `HEAD on an unknown api path is still a 404`() = testApplication {
        application { module(components("head_404")) }
        awaitReady(client)

        assertEquals(HttpStatusCode.NotFound, client.head("/api/nope").status)
    }

    @Test
    fun `an unknown api path is a json 404, not the single-page app with a 200`() = testApplication {
        application { module(components("api_404")) }
        awaitReady(client)

        val response = client.get("/api/does-not-exist")

        // `spaRoutes()` matches every path, so without a dedicated catch-all a withdrawn or
        // mistyped endpoint answers with the SPA shell and **200 OK**: a client parsing ApiError
        // gets a syntax error instead of a 404, and a monitor watching status codes sees a healthy
        // API.
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("NOT_FOUND"), "an /api path fell through to the SPA")
    }

    @Test
    fun `module boots and serves the catalogue even when the model client cannot build`() = testApplication {
        // The regression this exists for: `sessionRoutes(sessions = components.sessions, …)` reads a
        // lazy whose chain ends at `AnthropicLlmClient()`, which demands ANTHROPIC_API_KEY in its
        // constructor. Evaluating it while `module()` is being configured means a missing or
        // freshly-rotated key stops the process booting — taking /api/health and topic browsing,
        // which need no model at all, down with it. The key is currently absent from fly secrets, so
        // this is the difference between "sessions unavailable" and "does not start".
        //
        // The rule changed once `Components.prewarm` began to run on every boot: this factory IS
        // now called, from inside `prewarm`'s own guard, exactly as `reconcile` calls
        // `freemiusApiClientFactory` inside its own guard. The point of this test is that a build
        // failure there still does not take the boot down. See `Components.prewarm`'s own KDoc.
        //
        // Invisible to every other test in the suite, because they all inject a FakeLlmClient. This
        // one injects a factory that always fails, to prove the guard holds.
        application {
            module(
                Components(
                    mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_no_model")),
                    cookies = TestFixtures.cookieConfig,
                    llmFactory = { error("simulated: the model client cannot build") },
                )
            )
        }

        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        // Not just the routing path: `bootstrap()` runs in the background and its failures are
        // swallowed and logged, so a future version of it that touched `sessions` unguarded would
        // leave both assertions above passing while the instance came up with no indexes and no
        // catalogue. Waiting for readiness is what makes this cover the boot as well as the wiring.
        awaitReady(client)
        assertEquals(HttpStatusCode.OK, client.get("/api/catalog/topics").status)
    }

    @Test
    fun `the real module routes the session endpoints ahead of the api catch-all and the spa`() = testApplication {
        // Every SessionRoutesTest builds its own routing block, so nothing else establishes that
        // these paths survive contact with the rest of `module()`. Both things below them match
        // broadly: `route("/api/{...}")` is a tailcard over every method, and `spaRoutes()` is a
        // tailcard over every path — and the `/api` catch-all exists precisely because
        // "specificity will handle it" was wrong once already.
        //
        // A bespoke `Components` rather than the `components(name)` helper: the explain gate now
        // needs a real sign-in (see `SessionRoutes.kt`), and that needs `magicLink` to build without
        // depending on the real `MYTETZ_PUBLIC_BASE_URL` process environment or a mail provider.
        lateinit var mailSender: CapturingMailSender
        val components = Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_session_routes")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { FakeLlmClient() },
            mailSenderFactory = { CapturingMailSender().also { mailSender = it } },
            publicBaseUrl = { "http://localhost" },
        )
        application { module(components) }
        // The catalogue is seeded in the background, and `POST /api/sessions` refuses a topic the
        // catalogue does not have — so without this the route answers 400 for the right reason and
        // the test fails for the wrong one.
        awaitReady(client)

        // A cookie jar, because the routes enforce ownership: `Principals.resolve` mints a fresh
        // principal for every cookie-less request, so the default test client would create a session
        // as one learner and then be refused it as another. That the plain client gets a 404 here is
        // the ownership check working through the real module, and it is asserted below.
        // `followRedirects = false` so the magic-link consume below reports its own 302 rather than
        // whatever following it lands on.
        val learner = createClient { install(HttpCookies); followRedirects = false }

        // The explain route now gates on sign-in. A real round trip through the real `authRoutes`,
        // the same wiring `module()` installs in production, rather than a shortcut into `Components`.
        val email = "learner-${UUID.randomUUID()}@example.com"
        val requested = learner.post("/api/auth/magic-link") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }
        assertEquals(HttpStatusCode.NoContent, requested.status, "magic-link request failed: ${requested.bodyAsText()}")
        val consumed = learner.get("/api/auth/magic-link/${mailSender.tokenFor(email)}")
        assertEquals(HttpStatusCode.Found, consumed.status, "sign-in did not redirect: ${consumed.bodyAsText()}")

        val created = learner.post("/api/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"topicSlug":"quantum-physics"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status, "POST /api/sessions fell through: ${created.bodyAsText()}")
        assertTrue(created.bodyAsText().contains("rootNodeId"), "the SPA shell answered instead of the route")

        val id = Regex("\"sessionId\":\"([^\"]+)\"").find(created.bodyAsText())!!.groupValues[1]
        assertEquals(HttpStatusCode.OK, learner.get("/api/sessions/$id").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/sessions/$id").status,
            "ownership is not enforced when the routes are wired by the real module",
        )

        // The explain route is a POST, and `route("/api/{...}") { handle { … } }` answers every
        // method — so if the tailcard out-ranked it this would be a JSON 404 rather than a stream.
        val explain = learner.post("/api/sessions/$id/explain") {
            contentType(ContentType.Application.Json)
            setBody("""{"parentNodeId":"nope","span":{"text":"x","start":0,"end":1},"verb":"EXPLAIN"}""")
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            explain.status,
            "the explain route did not run; the catch-all or the SPA answered: ${explain.bodyAsText()}",
        )
    }

    /**
     * The one test in this file for the Commons wiring itself. [CommonsClientTest] already proves
     * the client's own request and its own acceptance rules; this proves only the seam —
     * `commonsClientFactory`'s default builds a real client, and `graph`'s own `commonsLookup`
     * lambda really calls it — the same division of labour the Freemius tests already draw between
     * [CommonsClientTest] and `ComponentsTest`'s own reconciliation tests.
     *
     * Drives `components.sessions` directly, service to service, not through the HTTP routes:
     * nothing about this test is about routing, and Task 9's own `SessionRoutesTest` covers the
     * wire shape. Reads the persisted [com.mytetz.graph.Explanation] back through a second
     * [ExplanationRepository] built on the same database — the same reach-past-the-service pattern
     * the eviction tests above already use.
     */
    @Test
    fun `Components wires the real Commons client into ExplanationGraph through the port`() = runTest {
        val wiredImageUrl = "https://upload.wikimedia.org/thumb/wired-example.jpg"
        val engine = MockEngine {
            respond(
                content = """
                    {"query":{"pages":[{"title":"File:Wired.jpg","imageinfo":[
                        {"thumburl":"$wiredImageUrl",
                         "descriptionurl":"https://commons.wikimedia.org/wiki/File:Wired.jpg",
                         "mime":"image/jpeg",
                         "extmetadata":{"LicenseShortName":{"value":"CC0"}}}
                    ]}]}}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val components = Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_commons_wiring")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = {
                FakeLlmClient().apply {
                    nextStructuredJson = """
                        {"explanation":"A short valid sentence about the span, long enough to pass.",
                         "svg":"<svg><circle cx=\"1\" cy=\"1\" r=\"1\"/></svg>"}
                    """.trimIndent()
                }
            },
            commonsClientFactory = { CommonsClient(HttpClient(engine)) },
        )
        components.catalog.seedFromResource()

        val created = components.sessions.create("anon:commons-wiring-test", "quantum-physics", anonymous = true) {}
        val plan = components.sessions.prepare(
            sessionId = created.session.id,
            parentNodeId = created.session.rootNodeId,
            selection = SpanSelection("A", 0, 1),
            verb = Verb.VISUALIZE,
            requestedVariant = null,
        )
        components.sessions.explain(plan).toList()

        val explanations = ExplanationRepository(components.mongo.database)
        val stored = explanations.findByKey(plan.contentKey)
        assertEquals(wiredImageUrl, stored?.media?.image?.imageUrl, "the real CommonsClient never reached ExplanationGraph")
    }

    @Test
    fun `the model client is not built unless something needs a model`() {
        var built = 0
        val components = Components(
            mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_lazy")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { built++; FakeLlmClient() },
        )

        // Nothing this slice registers touches the model. Building it eagerly means a missing or
        // freshly-rotated ANTHROPIC_API_KEY takes down topic browsing, which needs no model at all —
        // `AnthropicOkHttpClient.fromEnv()` demands the key during construction.
        //
        // This test reads properties directly, and it does not call `bootstrap()`. `bootstrap()`
        // now builds the model client on every run, because `prewarm` needs it — see the test
        // named "bootstrap builds the model client once for pre-warm, even when the migration is
        // off", below, and `Components.prewarm`'s own KDoc for the rule that changed.
        components.catalog
        components.topicRequests
        components.quota
        assertEquals(0, built, "the model client was built for a catalogue-only path")

        components.sessions

        assertEquals(1, built, "the model client was not built when a session service was needed")
    }

    @Test
    fun `bootstrap builds the model client once for pre-warm, even when the migration is off`() = runTest {
        var clientBuilds = 0

        val components = Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_no_migrate")),
            cookies = TestFixtures.cookieConfig,
            // The rule changed: `migrate()` no longer forces the lazy model client on its own, but
            // `prewarm()` now runs on every boot and reads `sessions` to reach it — see
            // `Components.prewarm`'s own KDoc for why a published topic's seed must not wait on
            // the migration flag. A build count of one proves `prewarm` ran exactly once; `llm` is
            // itself `by lazy`, so the factory runs once no matter how many topics it pre-warms.
            llmFactory = { clientBuilds++; FakeLlmClient() },
            migrateOnBoot = false,
        )

        components.bootstrap()

        assertEquals(1, clientBuilds, "bootstrap must pre-warm, and so build the model client, even with the flag off")
    }

    @Test
    fun `bootstrap builds no mail sender and no google client`() = runTest {
        var mailBuilds = 0
        var googleBuilds = 0

        val components = Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_no_auth_creds")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { FakeLlmClient() },
            // Neither factory reads an environment variable here, on purpose: this test must pass
            // on a machine with no MYTETZ_MAIL_MODE and no Google client configured at all.
            mailSenderFactory = { mailBuilds++; LoggingMailSender() },
            googleOAuthFactory = {
                googleBuilds++
                GoogleOAuth(
                    GoogleConfig("test-client-id", "test-client-secret", "https://example.com/api/auth/google/callback"),
                    HttpClient(CIO),
                )
            },
        )

        components.bootstrap()

        assertEquals(0, mailBuilds, "bootstrap must not build a mail sender")
        assertEquals(0, googleBuilds, "bootstrap must not build a google client")
    }

    @Test
    fun `the migration removes a stranded explanation and pre-warms every seed`() = runTest {
        val components = components("migrate", migrateOnBoot = true)
        val explanations = components.mongo.database.getCollection<Document>("explanations")
        explanations.drop()

        // A document from a model family nobody runs any more. No key a caller can compute finds it.
        explanations.insertOne(
            Document()
                .append("_id", "stranded")
                .append("topicSlug", "quantum-physics")
                .append("modelFamily", "claude-opus-5")
                .append("body", "an unreachable body"),
        )

        components.bootstrap()

        assertEquals(
            0,
            explanations.countDocuments(Filters.eq("modelFamily", "claude-opus-5")),
            "the stranded document is gone",
        )
        assertTrue(
            explanations.countDocuments(Filters.eq("modelFamily", "fake-model")) >= 20,
            "every published topic in the catalogue now has a seed under the current family",
        )
    }

    @Test
    fun `one topic's failed generation does not stop the migration for the rest, and the summary still reports`() =
        runTest {
            val fakeLlm = FakeLlmClient()
            // A 601-character body clears every other validator rule and trips only the
            // 600-character cap — the ordinary way one real generation fails, not a contrived one.
            fakeLlm.bodyByPromptSubstring["Topic: General Relativity"] = "a".repeat(601)

            val components = Components(
                mongo = Mongo(
                    MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_migrate_partial"),
                ),
                cookies = TestFixtures.cookieConfig,
                llmFactory = { fakeLlm },
                migrateOnBoot = true,
            )
            val explanations = components.mongo.database.getCollection<Document>("explanations")
            explanations.drop()

            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger("com.mytetz.api.Components") as ch.qos.logback.classic.Logger
            logger.addAppender(appender)
            try {
                // Must not throw. One topic's failure escaping this call would prove the loop
                // still stops on it, and the summary log line below would never run either.
                components.bootstrap()
            } finally {
                logger.detachAppender(appender)
            }

            val published = components.catalog.listPublished(category = null, query = null)

            assertEquals(
                0L,
                explanations.countDocuments(Filters.eq("topicSlug", "general-relativity")),
                "the failing topic kept no seed",
            )
            assertEquals(
                (published.size - 1).toLong(),
                explanations.countDocuments(Filters.eq("modelFamily", "fake-model")),
                "every topic except the failing one still got a seed",
            )
            assertTrue(
                explanations.countDocuments(Filters.eq("topicSlug", "special-relativity")) >= 1,
                "the topic after the failing one in sort order still got its seed",
            )

            val warning = assertNotNull(
                appender.list.firstOrNull {
                    it.level == Level.WARN && it.formattedMessage.contains("general-relativity")
                },
                "the failing topic's slug was not logged at WARN: ${appender.list.map { it.formattedMessage }}",
            )
            assertNotNull(warning.throwableProxy, "the failure log line did not carry the exception")

            val summary = assertNotNull(
                appender.list.lastOrNull { it.formattedMessage.contains("pre-warmed") },
                "no MIGRATION summary line was logged",
            )
            assertTrue(
                summary.formattedMessage.contains("1 failed"),
                "the summary did not name the failure count: ${summary.formattedMessage}",
            )
        }

    @Test
    fun `pre-warm runs even when the migration flag is off, and leaves one seed per published topic`() = runTest {
        val components = Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_prewarm_no_migrate")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { FakeLlmClient() },
            migrateOnBoot = false,
        )
        val explanations = components.mongo.database.getCollection<Document>("explanations")
        explanations.drop()

        components.bootstrap()

        val published = components.catalog.listPublished(category = null, query = null)
        assertEquals(
            published.size.toLong(),
            explanations.countDocuments(Filters.eq("modelFamily", "fake-model")),
            "every published topic must have a seed, even with the migration flag off",
        )
    }

    @Test
    fun `pre-warm with the migration flag off never touches a document of another model family`() = runTest {
        val components = Components(
            mongo = Mongo(
                MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_prewarm_keeps_stranded"),
            ),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { FakeLlmClient() },
            migrateOnBoot = false,
        )
        val explanations = components.mongo.database.getCollection<Document>("explanations")
        explanations.drop()
        // A document from a model family nobody runs any more. Only `migrate()` may remove one,
        // and `migrate()` does not run when the flag is off.
        explanations.insertOne(
            Document()
                .append("_id", "stranded")
                .append("topicSlug", "quantum-physics")
                .append("modelFamily", "claude-opus-5")
                .append("body", "an unreachable body"),
        )

        components.bootstrap()

        assertEquals(
            1,
            explanations.countDocuments(Filters.eq("modelFamily", "claude-opus-5")),
            "pre-warm deleted a document of another model family; only migrate() may do that",
        )
        val published = components.catalog.listPublished(category = null, query = null)
        assertEquals(
            published.size.toLong(),
            explanations.countDocuments(Filters.eq("modelFamily", "fake-model")),
            "pre-warm must still seed every published topic under the current family",
        )
    }

    @Test
    fun `bootstrap does not throw when the model client cannot build for pre-warm, and the catalogue is still seeded`() =
        runTest {
            val components = Components(
                mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_prewarm_no_key")),
                cookies = TestFixtures.cookieConfig,
                llmFactory = { error("no model credential configured") },
                migrateOnBoot = false,
            )

            components.bootstrap() // must not throw, with no model credential configured

            assertTrue(
                components.catalog.listPublished(category = null, query = null).isNotEmpty(),
                "the catalogue must be seeded even when the model client cannot build",
            )
        }

    @Test
    fun `a second boot's pre-warm calls the model zero times, because every seed already exists`() = runTest {
        val fakeLlm = FakeLlmClient()
        val components = Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_prewarm_idempotent")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { fakeLlm },
            migrateOnBoot = false,
        )

        components.bootstrap()
        val callsAfterFirstBoot = fakeLlm.calls.size
        assertTrue(callsAfterFirstBoot > 0, "the first boot must generate at least one seed")

        components.bootstrap()

        assertEquals(
            callsAfterFirstBoot,
            fakeLlm.calls.size,
            "the second boot's pre-warm must call the model zero more times",
        )
    }

    @Test
    fun `bootstrap runs reconciliation with no Freemius credential and does not throw`() = runTest {
        // The missing credential is simulated through the factory, not through the real
        // environment: a developer's shell that happens to export FREEMIUS_API_KEY and
        // FREEMIUS_PRODUCT_ID must not change this test's outcome. Components.reconcile catches
        // exactly this failure before the sweep starts, so bootstrap must complete regardless; see
        // that method's own KDoc for why.
        val components = Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_reconcile_no_creds")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { FakeLlmClient() },
            reconcileOnBoot = true,
            freemiusApiClientFactory = { error("${FreemiusApiConfig.API_KEY_ENV} is not set") },
        )

        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("com.mytetz.api.Components") as ch.qos.logback.classic.Logger
        logger.addAppender(appender)
        try {
            components.bootstrap()
        } finally {
            logger.detachAppender(appender)
        }

        assertTrue(
            appender.list.any { it.formattedMessage.contains("RECONCILE_SKIPPED") },
            "reconciliation must log why it skipped the sweep, with no Freemius API credential set",
        )
        assertTrue(
            appender.list.none { it.formattedMessage.contains("RECONCILE corrected") },
            "with no credential, the sweep must never start at all",
        )
    }

    /**
     * The one test in this file that exercises the real seam: `freemiusApiClientFactory`'s own
     * default, `{ FreemiusApiClient(HttpClient(CIO), FreemiusApiConfig()) }`, and not a test
     * override. Every test above it proves the *guard* works by injecting a substitute; this one
     * proves `Components`' own default wiring — the one a real deployment actually runs — never
     * takes a boot down, in the one environment this test cannot control: whichever the developer
     * or CI runner happens to provide.
     *
     * It asserts nothing about `FREEMIUS_API_KEY` or `FREEMIUS_PRODUCT_ID`, in either direction,
     * for that reason — a developer's shell that exports both, or neither, must leave this test
     * green either way. The database is a fresh one with no subscription rows, so
     * `Reconciliation.reconcile` finds nothing non-terminal to ask about and this test makes no
     * network call whichever branch the default credential resolution takes.
     */
    @Test
    fun `bootstrap's default Freemius wiring never crashes boot, whatever the environment holds`() = runTest {
        val components = Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_reconcile_default_wiring")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { FakeLlmClient() },
            reconcileOnBoot = true,
        )

        components.bootstrap() // must not throw, whatever FREEMIUS_API_KEY / FREEMIUS_PRODUCT_ID hold
    }

    // ------------------------------------------------------------------ evictExplanations

    private fun daysAgo(days: Long): Long = System.currentTimeMillis() - days * 86_400_000L

    /** A document old enough and quiet enough to be an eviction candidate, unless overridden. */
    private fun oldExplanation(
        key: String,
        verb: Verb = Verb.EXPLAIN,
        requestCount: Long = 0,
        createdAtEpochMillis: Long = daysAgo(100),
    ) = Explanation(
        key = key,
        topicSlug = "quantum-physics",
        parentKey = null,
        span = null,
        spanSentence = null,
        verb = verb,
        variant = 0,
        depth = 0,
        body = "body for $key",
        grounded = false,
        sources = emptyList(),
        promptVersion = "v1",
        modelFamily = "fake-model",
        modelId = "fake-model",
        inputTokens = 10,
        outputTokens = 20,
        costMicros = 100,
        requestCount = requestCount,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    @Test
    fun `evictExplanations removes an old, unread, unreferenced document`() = runTest {
        val components = components("evict_basic")
        val explanations = ExplanationRepository(components.mongo.database)
        explanations.insertIfAbsent(oldExplanation("stale"))

        components.evictExplanations()

        assertNull(explanations.findByKey("stale"), "an old, unread, unreferenced document must go")
    }

    @Test
    fun `evictExplanations never removes a seed`() = runTest {
        val components = components("evict_seed")
        val explanations = ExplanationRepository(components.mongo.database)
        explanations.insertIfAbsent(oldExplanation("seed", verb = Verb.SEED))

        components.evictExplanations()

        assertNotNull(explanations.findByKey("seed"), "a seed must survive eviction")
    }

    @Test
    fun `evictExplanations never removes a document a session still references`() = runTest {
        val components = components("evict_referenced")
        val explanations = ExplanationRepository(components.mongo.database)
        val sessions = SessionRepository(components.mongo.database)
        explanations.insertIfAbsent(oldExplanation("referenced"))
        sessions.insert(
            LearningSession(
                id = "s1",
                principalId = "anon:alice",
                topicSlug = "quantum-physics",
                rootNodeId = "n0",
                currentNodeId = "n0",
                nodes = listOf(SessionNode("n0", null, "referenced", "", Verb.SEED, 0, 0, daysAgo(100))),
                startedAtEpochMillis = daysAgo(100),
                lastActiveAtEpochMillis = daysAgo(100),
            ),
        )

        components.evictExplanations()

        assertNotNull(explanations.findByKey("referenced"), "a referenced document must survive eviction")
    }

    @Test
    fun `evictExplanations goes past a full page of referenced candidates to reach an unreferenced one behind it`() =
        runTest {
            val components = components("evict_past_referenced_page")
            val explanations = ExplanationRepository(components.mongo.database)
            val sessions = SessionRepository(components.mongo.database)

            // Three old, unread, REFERENCED documents — more than a page holds, at pageSize = 2
            // below. The oldest one first, so they sort ahead of the unreferenced document.
            val referencedKeys = listOf("ref-0", "ref-1", "ref-2")
            referencedKeys.forEachIndexed { i, key ->
                explanations.insertIfAbsent(oldExplanation(key, createdAtEpochMillis = daysAgo(100) + i))
            }
            sessions.insert(
                LearningSession(
                    id = "s1",
                    principalId = "anon:alice",
                    topicSlug = "quantum-physics",
                    rootNodeId = "n0",
                    currentNodeId = "n0",
                    nodes = referencedKeys.mapIndexed { i, key ->
                        SessionNode("n$i", if (i == 0) null else "n${i - 1}", key, "", Verb.SEED, 0, i, daysAgo(100))
                    },
                    startedAtEpochMillis = daysAgo(100),
                    lastActiveAtEpochMillis = daysAgo(100),
                ),
            )
            // One old, unread, UNREFERENCED document, newer than all three above — it sorts
            // behind them, on the far side of the first page.
            explanations.insertIfAbsent(oldExplanation("unreferenced", createdAtEpochMillis = daysAgo(99)))

            components.evictExplanations(pageSize = 2)

            assertNull(
                explanations.findByKey("unreferenced"),
                "the job must read a second page rather than stop after a page with nothing to remove",
            )
            referencedKeys.forEach {
                assertNotNull(explanations.findByKey(it), "a referenced document must still survive")
            }
        }

    @Test
    fun `evictExplanations carries its position across calls, so a later run reaches what an earlier one could not`() =
        runTest {
            val components = components("evict_carries_position")
            val explanations = ExplanationRepository(components.mongo.database)
            val sessions = SessionRepository(components.mongo.database)

            // Six old, unread, REFERENCED documents — more than one run of pageSize = 2 and
            // maxPagesPerRun = 2 can read (four documents) in a single call.
            val referencedKeys = (0 until 6).map { "ref-$it" }
            referencedKeys.forEachIndexed { i, key ->
                explanations.insertIfAbsent(oldExplanation(key, createdAtEpochMillis = daysAgo(100) + i))
            }
            sessions.insert(
                LearningSession(
                    id = "s1",
                    principalId = "anon:alice",
                    topicSlug = "quantum-physics",
                    rootNodeId = "n0",
                    currentNodeId = "n0",
                    nodes = referencedKeys.mapIndexed { i, key ->
                        SessionNode("n$i", if (i == 0) null else "n${i - 1}", key, "", Verb.SEED, 0, i, daysAgo(100))
                    },
                    startedAtEpochMillis = daysAgo(100),
                    lastActiveAtEpochMillis = daysAgo(100),
                ),
            )
            // One old, unread, UNREFERENCED document, newer than all six above.
            explanations.insertIfAbsent(oldExplanation("unreferenced", createdAtEpochMillis = daysAgo(99)))

            components.evictExplanations(pageSize = 2, maxPagesPerRun = 2)
            assertNotNull(
                explanations.findByKey("unreferenced"),
                "the first call reads only the six referenced documents; it must not reach this one yet",
            )

            components.evictExplanations(pageSize = 2, maxPagesPerRun = 2)

            assertNull(
                explanations.findByKey("unreferenced"),
                "a later call must resume where the previous one stopped, and reach this document",
            )
            referencedKeys.forEach {
                assertNotNull(explanations.findByKey(it), "a referenced document must still survive")
            }
        }

    @Test
    fun `evictExplanations never removes a recent document`() = runTest {
        val components = components("evict_recent")
        val explanations = ExplanationRepository(components.mongo.database)
        explanations.insertIfAbsent(oldExplanation("recent", createdAtEpochMillis = daysAgo(1)))

        components.evictExplanations()

        assertNotNull(explanations.findByKey("recent"), "a recent document must survive eviction")
    }

    @Test
    fun `evictExplanations logs what it removed and what it scanned`() = runTest {
        val components = components("evict_log")
        val explanations = ExplanationRepository(components.mongo.database)
        explanations.insertIfAbsent(oldExplanation("stale"))
        // A seed. The candidate query excludes it, so it must not inflate the scanned count.
        explanations.insertIfAbsent(oldExplanation("seed", verb = Verb.SEED))

        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("com.mytetz.api.Components") as ch.qos.logback.classic.Logger
        logger.addAppender(appender)
        try {
            components.evictExplanations()
        } finally {
            logger.detachAppender(appender)
        }

        val event = assertNotNull(
            appender.list.firstOrNull { it.level == Level.INFO && it.formattedMessage.contains("EVICTION") },
            "no EVICTION line was logged: ${appender.list.map { it.formattedMessage }}",
        )
        assertEquals("EVICTION removed=1 scanned=1", event.formattedMessage)
    }

    @Test
    fun `a failure inside evictExplanations does not fail the whole boot`() = runTest {
        // Eviction is housekeeping. A learner-facing incident there must not take /api/health and
        // topic browsing down with it, the same reasoning `reconcile()` guards its own risky step
        // for.
        val components = object : Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_evict_boot_failure")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { FakeLlmClient() },
        ) {
            override suspend fun evictExplanations(pageSize: Int, maxPagesPerRun: Int) {
                error("eviction blew up")
            }
        }

        components.bootstrap() // must not throw
    }

    @Test
    fun `a failure inside evictExplanations at boot is logged under the same token the daily loop uses`() = runTest {
        val components = object : Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_evict_boot_log")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { FakeLlmClient() },
        ) {
            override suspend fun evictExplanations(pageSize: Int, maxPagesPerRun: Int) {
                error("eviction blew up")
            }
        }

        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("com.mytetz.api.Components") as ch.qos.logback.classic.Logger
        logger.addAppender(appender)
        try {
            components.bootstrap()
        } finally {
            logger.detachAppender(appender)
        }

        val event = assertNotNull(
            appender.list.firstOrNull { it.level == Level.ERROR && it.formattedMessage.contains(EVICTION_LOOP_FAILED_TOKEN) },
            "no $EVICTION_LOOP_FAILED_TOKEN line was logged: ${appender.list.map { it.formattedMessage }}",
        )
        assertNotNull(event.throwableProxy, "the failure log line did not carry the exception")
    }
}
