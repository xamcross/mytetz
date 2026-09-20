package com.mytetz.api

import com.mytetz.llm.AnthropicLlmClient
import com.mytetz.llm.FakeLlmClient
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HowItWorksRoutesTest {

    private fun ldJson(html: String) = Json.parseToJsonElement(
        Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
    )

    @Test
    fun `the html element states the language of the page`() {
        // Same requirement as the topic page — see TopicPageHtmlTest's own test of this rule.
        var html = ""
        testApplication {
            application { routing { howItWorksRoutes(modelId = { "claude-sonnet-5" }) } }
            html = client.get("/how-it-works").bodyAsText()
        }

        assertTrue(html.contains("<html lang=\"en\">"), html.take(200))
    }

    @Test
    fun `the page states which model writes the text, with no javascript`() = testApplication {
        application { routing { howItWorksRoutes(modelId = { "claude-sonnet-5" }) } }

        val response = client.get("/how-it-works")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("claude-sonnet-5" in body)
        // Issue #143 adds site-header.js in commonHeadTags, so this page's script-tag count
        // changed from 1 (the JSON-LD block) to 2 (the JSON-LD block and site-header.js).
        val scriptTags = Regex("<script[ >]").findAll(body).count()
        assertEquals(
            2,
            scriptTags,
            "expected exactly two <script> tags (the JSON-LD block and site-header.js): $body",
        )
    }

    @Test
    fun `the Organization JSON-LD block parses, and names the project's GitHub repository`() = testApplication {
        application { routing { howItWorksRoutes(modelId = { "claude-sonnet-5" }) } }

        val parsed = ldJson(client.get("/how-it-works").bodyAsText())

        val organization = parsed.jsonObject["@graph"]!!.jsonArray.first {
            it.jsonObject["@type"]?.jsonPrimitive?.content == "Organization"
        }
        assertEquals(
            "https://github.com/xamcross/mytetz",
            organization.jsonObject["sameAs"]!!.jsonArray[0].jsonPrimitive.content,
        )
    }

    @Test
    fun `the page carries a canonical tag and og tags with the property attribute`() = testApplication {
        application { routing { howItWorksRoutes(modelId = { "claude-sonnet-5" }) } }

        val body = client.get("/how-it-works").bodyAsText()

        assertTrue(
            """<link href="https://mytetz.com/how-it-works" rel="canonical">""" in body,
            "canonical tag not found: $body",
        )
        assertTrue("""<meta property="og:type" content="website">""" in body, body)
        assertTrue("""<meta property="og:url" content="https://mytetz.com/how-it-works">""" in body, body)
    }

    /**
     * Issue #144 gives this link the class `foot__link`, the same class `AppShellComponent`'s
     * own footer link carries. The assertion below changed from
     * `<a href="/how-it-works">How it works</a>` (no class) to the string below, to match —
     * `kotlinx.html` writes the `href` `a(...)` sets by name before the `class` its `classes`
     * parameter adds.
     */
    @Test
    fun `the footer links to itself, the same as every other page`() = testApplication {
        application { routing { howItWorksRoutes(modelId = { "claude-sonnet-5" }) } }

        val body = client.get("/how-it-works").bodyAsText()

        assertTrue("""<a href="/how-it-works" class="foot__link">How it works</a>""" in body, body)
    }

    @Test
    fun `the response carries no Set-Cookie header`() = testApplication {
        application { routing { howItWorksRoutes(modelId = { "claude-sonnet-5" }) } }

        assertNull(client.get("/how-it-works").headers[HttpHeaders.SetCookie])
    }

    /**
     * Proves the rule `Components.modelFamily`'s own KDoc states for a public page: reading the
     * model id must never build a real model client. Wires a real [Components] with a counting
     * `llmFactory`, side by side with the route, exactly the way `Application.kt` wires the route
     * with `resolveModelForLogging(System.getenv(AnthropicLlmClient.MODEL_ID_ENV))` — a function
     * that never touches `Components.llm` at all. A regression that changed the route to read
     * `components.llm.modelId` instead would build the [FakeLlmClient] below and fail this test.
     */
    @Test
    fun `the route reads the model id with no model client built`() = testApplication {
        var built = 0
        Components(
            mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_how_it_works_no_model")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { built++; FakeLlmClient() },
        )
        application {
            routing {
                howItWorksRoutes(modelId = { resolveModelForLogging(System.getenv(AnthropicLlmClient.MODEL_ID_ENV)) })
            }
        }

        val body = client.get("/how-it-works").bodyAsText()

        assertTrue(AnthropicLlmClient.DEFAULT_MODEL in body)
        assertEquals(0, built, "the how-it-works route built a model client")
    }
}
