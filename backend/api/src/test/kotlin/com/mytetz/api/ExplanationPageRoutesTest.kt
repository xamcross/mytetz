package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.Verb
import com.mytetz.llm.FakeLlmClient
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplanationPageRoutesTest {

    private data class Deps(val catalog: CatalogService, val explanations: ExplanationRepository)

    private fun explanation(
        key: String,
        published: Boolean,
        topicSlug: String = "quantum-physics",
        verb: Verb = Verb.EXPLAIN,
        span: String? = "wave function",
    ) = Explanation(
        key = key, topicSlug = topicSlug, parentKey = "parent", span = span,
        spanSentence = "The wave function describes...", verb = verb, variant = 0, depth = 1,
        body = "A short explanation of the wave function.", grounded = false, sources = emptyList(),
        promptVersion = "v1", modelFamily = "fake-model", modelId = "fake-model",
        inputTokens = 1, outputTokens = 1, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
        published = published,
    )

    private fun setUp(
        dbSuffix: String,
        explanationPublished: Boolean = true,
        topicPublished: Boolean = true,
    ): Pair<Deps, String> {
        val database = Mongo(MongoConfig(TestFixtures.connectionString, dbSuffix)).database
        val topics = TopicRepository(database)
        val explanations = ExplanationRepository(database)
        val key = "abcdef0123456789" + "0".repeat(48)
        runBlocking {
            topics.upsert(
                Topic(
                    slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s",
                    status = if (topicPublished) TopicStatus.PUBLISHED else TopicStatus.DRAFT,
                ),
            )
            explanations.insertIfAbsent(explanation(key, explanationPublished))
        }
        return Deps(CatalogService(topics), explanations) to key.take(12)
    }

    @Test
    fun `a published explanation under a published topic has no noindex header and a canonical tag`() = testApplication {
        val (deps, shortKey) = setUp("test_api_explain_page_pub_${System.nanoTime()}", explanationPublished = true)
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        val response = client.get("/topics/quantum-physics/explain/$shortKey")

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.headers[X_ROBOTS_TAG])
        assertTrue("rel=\"canonical\"" in response.bodyAsText())
    }

    @Test
    fun `an unpublished explanation under a published topic answers 200 with noindex, not 404`() = testApplication {
        // Spec section 7.3 decides this deliberately, against a stricter draft of this issue's own
        // brief that asked for one 404 across every case (see this issue's final report for the
        // point recorded in full): the interactive reader can link a learner to any node it
        // reaches, published or not, and a stable URL must not break once a curator has not yet
        // reviewed it.
        val (deps, shortKey) = setUp("test_api_explain_page_unpub_${System.nanoTime()}", explanationPublished = false)
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        val response = client.get("/topics/quantum-physics/explain/$shortKey")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(NOINDEX, response.headers[X_ROBOTS_TAG])
        assertTrue("A short explanation of the wave function." in response.bodyAsText(), "the page still renders the text")
        assertTrue("rel=\"canonical\"" !in response.bodyAsText(), "an unpublished page carries no canonical tag")
    }

    @Test
    fun `any explanation under an unpublished topic answers 404, the same shell as an unknown page`() = testApplication {
        val (deps, shortKey) = setUp(
            "test_api_explain_page_topic_unpub_${System.nanoTime()}",
            explanationPublished = true,
            topicPublished = false,
        )
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        val response = client.get("/topics/quantum-physics/explain/$shortKey")
        val unknownPathResponse = client.get("/topics/no-such-topic-at-all/explain/000000000000")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(unknownPathResponse.bodyAsText(), response.bodyAsText(), "both 404s must share the SPA shell body")
    }

    @Test
    fun `an unknown short key answers 404`() = testApplication {
        val (deps, _) = setUp("test_api_explain_page_unknown_${System.nanoTime()}")
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        assertEquals(HttpStatusCode.NotFound, client.get("/topics/quantum-physics/explain/000000000000").status)
    }

    @Test
    fun `a short key of the wrong shape answers 404 with no query — never a 500`() = testApplication {
        val (deps, _) = setUp("test_api_explain_page_badshape_${System.nanoTime()}")
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        // Too short, uppercase, and carrying a non-hex character: none of the three may reach
        // findByShortKeyPrefix.
        listOf("abc", "ABCDEF012345", "abcdef01234g").forEach { badShortKey ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/topics/quantum-physics/explain/$badShortKey").status,
                "short key '$badShortKey' must answer 404",
            )
        }
    }

    @Test
    fun `a short key naming a document under a different topic answers 404`() = testApplication {
        val database = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_explain_page_wrongtopic_${System.nanoTime()}")).database
        val topics = TopicRepository(database)
        val explanations = ExplanationRepository(database)
        val key = "abcdef0123456789" + "0".repeat(48)
        runBlocking {
            topics.upsert(Topic(slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s"))
            topics.upsert(Topic(slug = "special-relativity", title = "Special Relativity", category = "Physics", summary = "s"))
            explanations.insertIfAbsent(explanation(key, published = true, topicSlug = "special-relativity"))
        }
        application { routing { explanationPageRoutes(CatalogService(topics), explanations) } }

        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/topics/quantum-physics/explain/${key.take(12)}").status,
        )
    }

    @Test
    fun `a VISUALIZE node answers 404 on the explain path, even when published`() = testApplication {
        val database = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_explain_page_visualize_${System.nanoTime()}")).database
        val topics = TopicRepository(database)
        val explanations = ExplanationRepository(database)
        val key = "abcdef0123456789" + "0".repeat(48)
        runBlocking {
            topics.upsert(Topic(slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s"))
            explanations.insertIfAbsent(explanation(key, published = true, verb = Verb.VISUALIZE))
        }
        application { routing { explanationPageRoutes(CatalogService(topics), explanations) } }

        assertEquals(HttpStatusCode.NotFound, client.get("/topics/quantum-physics/explain/${key.take(12)}").status)
    }

    @Test
    fun `a short key naming two documents answers 404 for both, a collision`() = testApplication {
        val database = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_explain_page_collision_${System.nanoTime()}")).database
        val topics = TopicRepository(database)
        val explanations = ExplanationRepository(database)
        val sharedPrefix = "aaaaaaaaaaaa"
        runBlocking {
            topics.upsert(Topic(slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s"))
            explanations.insertIfAbsent(explanation(sharedPrefix + "1".repeat(52), published = true))
            explanations.insertIfAbsent(explanation(sharedPrefix + "2".repeat(52), published = true))
        }
        application { routing { explanationPageRoutes(CatalogService(topics), explanations) } }

        assertEquals(HttpStatusCode.NotFound, client.get("/topics/quantum-physics/explain/$sharedPrefix").status)
    }

    @Test
    fun `the response carries no Set-Cookie header`() = testApplication {
        val (deps, shortKey) = setUp("test_api_explain_page_cookie_${System.nanoTime()}")
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        assertNull(client.get("/topics/quantum-physics/explain/$shortKey").headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `the route calls no model and builds no model client`() = testApplication {
        var built = 0
        val fake = FakeLlmClient(modelFamily = "fake-model")
        val components = Components(
            mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_explain_page_no_model_${System.nanoTime()}")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { built++; fake },
            modelFamily = "fake-model",
        )
        val key = "abcdef0123456789" + "0".repeat(48)
        runBlocking {
            TopicRepository(components.mongo.database).upsert(
                Topic(slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s"),
            )
            components.explanations.insertIfAbsent(explanation(key, published = true))
        }
        application { routing { explanationPageRoutes(components.catalog, components.explanations) } }

        client.get("/topics/quantum-physics/explain/${key.take(12)}")

        assertEquals(0, fake.calls.size, "the route streamed a generation")
        assertEquals(0, fake.structuredCalls.size, "the route made a structured call")
        assertEquals(0, built, "the route forced the lazy model client to build")
    }

    // ------------------------------------------------------------- hostile input, on the real route

    @Test
    fun `a span with a double quote and a line break round-trips through the BreadcrumbList JSON-LD`() = testApplication {
        val database = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_explain_ldjson_${System.nanoTime()}")).database
        val topics = TopicRepository(database)
        val explanations = ExplanationRepository(database)
        val hostileSpan = "a \"quoted\" span\nwith a line break"
        val key = "abcdef0123456789" + "0".repeat(48)
        runBlocking {
            topics.upsert(Topic(slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s"))
            explanations.insertIfAbsent(explanation(key, published = true, span = hostileSpan))
        }
        application { routing { explanationPageRoutes(CatalogService(topics), explanations) } }

        val html = client.get("/topics/quantum-physics/explain/${key.take(12)}").bodyAsText()
        val block = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(block)
        val breadcrumb = parsed.jsonObject["@graph"]!!.jsonArray.first {
            it.jsonObject["@type"]?.jsonPrimitive?.content == "BreadcrumbList"
        }
        val lastItem = breadcrumb.jsonObject["itemListElement"]!!.jsonArray.last()
        assertEquals(hostileSpan, lastItem.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the page opens with html lang en`() = testApplication {
        val (deps, shortKey) = setUp("test_api_explain_page_lang_${System.nanoTime()}")
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        val body = client.get("/topics/quantum-physics/explain/$shortKey").bodyAsText()

        // `respondHtml` prefixes the body with `<!DOCTYPE html>`, so this checks `contains`, the
        // same way `TopicPageHtmlTest`'s own language test does, and not `startsWith`.
        assertTrue("<html lang=\"en\">" in body, body.take(200))
    }
}
