package com.mytetz.api

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.mytetz.llm.FakeLlmClient
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private fun components(name: String) = Components(
        mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_$name")),
        cookies = TestFixtures.cookieConfig,
        // Never AnthropicLlmClient: its default constructor calls `AnthropicOkHttpClient.fromEnv()`,
        // which demands a real key at construction time and would put a paid call one slip away.
        llmFactory = { FakeLlmClient() },
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
        assertContains(indexNames(database, "principals"), "window_ttl")
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

        // `staticResources` with `default("index.html")` matches everything, so without a dedicated
        // catch-all a withdrawn or mistyped endpoint answers with the SPA shell and **200 OK**: a
        // client parsing ApiError gets a syntax error instead of a 404, and a monitor watching
        // status codes sees a healthy API.
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("NOT_FOUND"), "an /api path fell through to the SPA")
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
        components.catalog
        components.topicRequests
        components.quota
        assertEquals(0, built, "the model client was built for a catalogue-only path")

        components.sessions

        assertEquals(1, built, "the model client was not built when a session service was needed")
    }
}
