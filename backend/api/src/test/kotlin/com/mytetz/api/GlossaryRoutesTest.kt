package com.mytetz.api

import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.Verb
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlossaryRoutesTest {

    private fun explanation(
        key: String,
        span: String,
        published: Boolean,
        verb: Verb = Verb.EXPLAIN,
        topicSlug: String = "quantum-physics",
    ) = Explanation(
        key = key, topicSlug = topicSlug, parentKey = "p", span = span, spanSentence = "s",
        verb = verb, variant = 0, depth = 1, body = "b", grounded = false, sources = emptyList(),
        promptVersion = "v1", modelFamily = "fake-model", modelId = "fake-model",
        inputTokens = 1, outputTokens = 1, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
        published = published,
    )

    @Test
    fun `the glossary is empty on a fresh database`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_glossary_empty_${System.nanoTime()}")).database,
        )
        application { routing { glossaryRoutes(explanations) } }

        val response = client.get("/glossary")

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse("DefinedTerm" in response.bodyAsText())
    }

    @Test
    fun `the glossary lists only published EXPLAIN nodes, with DefinedTerm JSON-LD`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_glossary_${System.nanoTime()}")).database,
        )
        runBlocking {
            explanations.insertIfAbsent(explanation("k1", "wave function", published = true))
            explanations.insertIfAbsent(explanation("k2", "superposition", published = false))
            // A published SEED must never appear: a seed names no phrase.
            explanations.insertIfAbsent(explanation("k3", "seed span", published = true, verb = Verb.SEED))
            // A published VISUALIZE must never appear: it carries an SVG this issue does not render.
            explanations.insertIfAbsent(explanation("k4", "a diagram", published = true, verb = Verb.VISUALIZE))
        }
        application { routing { glossaryRoutes(explanations) } }

        val body = client.get("/glossary").bodyAsText()
        assertTrue("wave function" in body)
        assertFalse("superposition" in body)
        assertFalse("seed span" in body)
        assertFalse("a diagram" in body)
        assertTrue("DefinedTerm" in body)
    }

    @Test
    fun `each entry links to its explanation page by the twelve-character short key`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_glossary_link_${System.nanoTime()}")).database,
        )
        val fullKey = "abcdef0123456789" + "0".repeat(48)
        runBlocking {
            explanations.insertIfAbsent(explanation(fullKey, "wave function", published = true))
        }
        application { routing { glossaryRoutes(explanations) } }

        val body = client.get("/glossary").bodyAsText()
        assertTrue("""<a href="/topics/quantum-physics/explain/abcdef012345">""" in body)
    }

    @Test
    fun `a span with a quote and a backslash round-trips through the DefinedTerm JSON-LD`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_glossary_hostile_${System.nanoTime()}")).database,
        )
        val hostileSpan = "a \"quoted\" span with a \\ backslash"
        runBlocking {
            explanations.insertIfAbsent(explanation("k3", hostileSpan, published = true))
        }
        application { routing { glossaryRoutes(explanations) } }

        val html = client.get("/glossary").bodyAsText()
        val block = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(block)
        val term = parsed.jsonObject["@graph"]!!.jsonArray.first()
        assertEquals(hostileSpan, term.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the response carries no Set-Cookie header`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_glossary_cookie_${System.nanoTime()}")).database,
        )
        application { routing { glossaryRoutes(explanations) } }

        assertNull(client.get("/glossary").headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `the page opens with html lang en`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_glossary_lang_${System.nanoTime()}")).database,
        )
        application { routing { glossaryRoutes(explanations) } }

        assertTrue("<html lang=\"en\">" in client.get("/glossary").bodyAsText())
    }
}
