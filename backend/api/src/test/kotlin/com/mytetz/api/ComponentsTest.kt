package com.mytetz.api

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.mytetz.llm.FakeLlmClient
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
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
        llm = FakeLlmClient(),
    )

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

        // Any request will do; it is what forces the module to be built.
        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        // The recorded carry-forward, closed here. If `module()` builds Components but never calls
        // bootstrap(), every assertion above still passes and production still has no indexes.
        assertContains(indexNames(components.mongo.database, "principals"), "window_ttl")
        assertContains(indexNames(components.mongo.database, "sessions"), "principal_recent")
    }

    @Test
    fun `module serves the catalogue and maps its errors`() = testApplication {
        application { module(components("routes")) }

        val listing = client.get("/api/catalog/topics")
        val missing = client.get("/api/catalog/topics/not-a-real-topic")

        assertEquals(HttpStatusCode.OK, listing.status)
        assertTrue(listing.bodyAsText().contains("quantum-physics"))
        // Proves installErrorMapping() is wired into the module and not merely into the tests: an
        // unmapped NotFoundException would surface as a bodyless 404 or a 500.
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("NOT_FOUND"))
    }
}
