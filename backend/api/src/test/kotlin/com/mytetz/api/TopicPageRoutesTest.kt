package com.mytetz.api

import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ContentKey
import com.mytetz.graph.Explanation
import com.mytetz.graph.GraphConfig
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicPageRoutesTest {

    private fun components(modelFamily: String = "fake-model") = Components(
        mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_topic_page_${System.nanoTime()}")),
        cookies = TestFixtures.cookieConfig,
        llmFactory = { FakeLlmClient(modelFamily = modelFamily) },
        modelFamily = modelFamily,
    )

    @Test
    fun `an unknown slug answers 404`() = testApplication {
        val c = components()
        application { routing { topicPageRoutes(c.catalog, c.explanations, c.modelFamily) } }

        assertEquals(HttpStatusCode.NotFound, client.get("/topics/no-such-topic").status)
    }

    @Test
    fun `a draft topic answers 404, the same as the JSON endpoint`() = testApplication {
        val c = components()
        runBlocking {
            TopicRepository(c.mongo.database).upsert(
                Topic(slug = "draft-topic", title = "Draft", category = "Physics", summary = "s", status = TopicStatus.DRAFT)
            )
        }
        application { routing { topicPageRoutes(c.catalog, c.explanations, c.modelFamily) } }

        assertEquals(HttpStatusCode.NotFound, client.get("/topics/draft-topic").status)
    }

    @Test
    fun `a published topic with a stored seed renders the title, the seed, and a canonical tag`() = testApplication {
        val c = components()
        runBlocking {
            TopicRepository(c.mongo.database).upsert(
                Topic(slug = "special-relativity", title = "Special Relativity", category = "Physics", summary = "About time and space.")
            )
            val key = ContentKey.seed("special-relativity", GraphConfig().promptVersion, c.modelFamily)
            c.explanations.insertIfAbsent(
                Explanation(
                    key = key, topicSlug = "special-relativity", parentKey = null, span = null, spanSentence = null,
                    verb = Verb.SEED, variant = 0, depth = 0, body = "Two observers can disagree about time.",
                    grounded = false, sources = emptyList(), promptVersion = GraphConfig().promptVersion,
                    modelFamily = c.modelFamily, modelId = "fake-model", inputTokens = 1, outputTokens = 1,
                    costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
                )
            )
        }
        application { routing { topicPageRoutes(c.catalog, c.explanations, c.modelFamily) } }

        val response = client.get("/topics/special-relativity")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("Special Relativity" in body)
        assertTrue("Two observers can disagree about time." in body)
        // kotlinx.html's LINK tag builder writes href before rel, confirmed by running this test
        // and reading the rendered body — the plan's own literal attribute order does not match.
        assertTrue(
            """<link href="https://mytetz.com/topics/special-relativity" rel="canonical">""" in body,
            "canonical tag not found, body was: $body",
        )
    }

    @Test
    fun `a published topic with no stored seed renders the summary and no seed`() = testApplication {
        val c = components()
        runBlocking {
            TopicRepository(c.mongo.database).upsert(
                Topic(slug = "no-seed-yet", title = "No Seed Yet", category = "Physics", summary = "The fallback summary.")
            )
        }
        application { routing { topicPageRoutes(c.catalog, c.explanations, c.modelFamily) } }

        val body = client.get("/topics/no-seed-yet").bodyAsText()
        assertTrue("The fallback summary." in body)
    }

    @Test
    fun `the response carries no Set-Cookie header`() = testApplication {
        val c = components()
        runBlocking {
            TopicRepository(c.mongo.database).upsert(
                Topic(slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s")
            )
        }
        application { routing { topicPageRoutes(c.catalog, c.explanations, c.modelFamily) } }

        assertNull(client.get("/topics/quantum-physics").headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `the route calls no model, whether or not a seed exists`() = testApplication {
        var built = 0
        val fake = FakeLlmClient(modelFamily = "fake-model")
        val c = Components(
            mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_topic_page_no_model")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { built++; fake },
            modelFamily = "fake-model",
        )
        runBlocking {
            TopicRepository(c.mongo.database).upsert(
                Topic(slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s")
            )
        }
        application { routing { topicPageRoutes(c.catalog, c.explanations, c.modelFamily) } }

        client.get("/topics/quantum-physics")

        assertEquals(0, fake.calls.size, "the route streamed a generation")
        assertEquals(0, fake.structuredCalls.size, "the route made a structured call")
        assertEquals(0, built, "the route forced the lazy model client to build")
    }

    // ------------------------------------------------------------- hostile input, end to end

    @Test
    fun `a hostile topic title, summary and seed render with no unescaped script tag or attribute break`() = testApplication {
        val c = components()
        val hostileTitle = "Special </script><script>alert(1)</script> Relativity"
        val hostileSummary = "\">img src=x onerror=alert(1)>"
        val hostileSeed = "A seed with a line separator\u2028and a closing tag </script><script>alert(2)</script>"
        runBlocking {
            TopicRepository(c.mongo.database).upsert(
                Topic(slug = "hostile-topic", title = hostileTitle, category = "Physics", summary = hostileSummary)
            )
            val key = ContentKey.seed("hostile-topic", GraphConfig().promptVersion, c.modelFamily)
            c.explanations.insertIfAbsent(
                Explanation(
                    key = key, topicSlug = "hostile-topic", parentKey = null, span = null, spanSentence = null,
                    verb = Verb.SEED, variant = 0, depth = 0, body = hostileSeed,
                    grounded = false, sources = emptyList(), promptVersion = GraphConfig().promptVersion,
                    modelFamily = c.modelFamily, modelId = "fake-model", inputTokens = 1, outputTokens = 1,
                    costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
                )
            )
        }
        application { routing { topicPageRoutes(c.catalog, c.explanations, c.modelFamily) } }

        val body = client.get("/topics/hostile-topic").bodyAsText()

        // The raw text of the response body must hold no unescaped <script> and no unescaped
        // attribute break. The JSON-LD block escapes < as <, so this check on the raw body
        // (not a parsed DOM) is the real assertion the security rules ask for.
        assertFalse("<script>alert(1)</script>" in body, "the hostile title was not escaped: $body")
        assertFalse("<script>alert(2)</script>" in body, "the hostile seed was not escaped: $body")
        assertFalse("\"><img" in body, "the hostile summary broke an attribute: $body")
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in body)
    }

    @Test
    fun `an unpublished topic under an unknown category still answers 404 for the JSON endpoint's same reason`() = testApplication {
        // Pins the exact rule TopicPageRoutes.kt's own KDoc states: no distinguishable answer
        // between "no such topic" and "not published" — CatalogRoutes.kt:108-109's own rule,
        // applied identically here.
        val c = components()
        runBlocking {
            TopicRepository(c.mongo.database).upsert(
                Topic(slug = "withdrawn-topic", title = "Withdrawn", category = "Physics", summary = "s", status = TopicStatus.DRAFT)
            )
        }
        application { routing { topicPageRoutes(c.catalog, c.explanations, c.modelFamily) } }

        val unknown = client.get("/topics/never-existed")
        val unpublished = client.get("/topics/withdrawn-topic")

        assertEquals(unknown.status, unpublished.status)
        assertEquals(HttpStatusCode.NotFound, unpublished.status)
    }
}
