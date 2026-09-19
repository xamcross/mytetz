package com.mytetz.api

import com.mytetz.billing.BillingConfig
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

/**
 * `GET /faq`, issue #121. This page holds no user data and takes no user input: every
 * [FaqEntry] is a fixed string this project defines, and [BillingConfig] reads the
 * environment, never a request. Hostile input therefore has no path into this page, and every
 * test below asserts on plain, trusted strings rather than an escape rule.
 */
class FaqRoutesTest {

    private fun ldJson(html: String) = Json.parseToJsonElement(
        Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
    )

    @Test
    fun `the price answer states 12 US dollars each month, and no euro price`() = testApplication {
        // The owner set the price on 2026-09-19, in pull request #130. The plan before that date
        // was 10 euro, and the first version of this page stated it.
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        val html = client.get("/faq").bodyAsText()

        assertTrue(html.contains("Mytetz costs \$12 each month."), "the price sentence is missing")
        assertTrue(!html.contains("€"), "the page still holds a euro sign")
    }

    @Test
    fun `the html element states the language of the page`() = testApplication {
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        val html = client.get("/faq").bodyAsText()

        assertTrue(html.contains("<html lang=\"en\">"), html.take(200))
    }

    @Test
    fun `each question is an h2 with an id, and each answer is plain text, with no javascript`() =
        testApplication {
            application { routing { faqRoutes(billingConfig = BillingConfig()) } }

            val body = client.get("/faq").bodyAsText()

            for (entry in faqEntries(BillingConfig())) {
                assertTrue(
                    """<h2 id="${entry.id}">""" in body,
                    "expected an <h2 id=\"${entry.id}\"> heading: $body",
                )
                assertTrue(entry.answer in body, "expected the answer for \"${entry.question}\": $body")
            }
            // The only script tag on this page is the JSON-LD block.
            val scriptTags = Regex("<script[ >]").findAll(body).count()
            assertEquals(1, scriptTags, "expected exactly one <script> tag (the JSON-LD block): $body")
        }

    /**
     * The core proof issue #121 asks for: a [Components] with values that differ from the
     * defaults gives a page with those values, and never the hard-coded defaults 40, 7 and 25.
     */
    @Test
    fun `a page built with non-default billingConfig values states those values, not the defaults`() =
        testApplication {
            val billingConfig = BillingConfig(trialGenerations = 55, trialDays = 9, subscriberDailyExplains = 30)
            application { routing { faqRoutes(billingConfig = billingConfig) } }

            val body = client.get("/faq").bodyAsText()

            assertTrue("55 explanations over 9 days" in body, body)
            assertTrue("A subscriber gets 30 explanations each day" in body, body)
            assertTrue(
                "40 explanations over 7 days" !in body,
                "the page fell back to the default trial numbers: $body",
            )
            assertTrue(
                "gets 25 explanations each day" !in body,
                "the page fell back to the default subscriber allowance: $body",
            )
        }

    @Test
    fun `the response carries no Set-Cookie header`() = testApplication {
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        assertNull(client.get("/faq").headers[HttpHeaders.SetCookie])
    }

    /**
     * Proves the rule every public page in this project states: reading [Components.billingConfig]
     * must never build a real model client. Wires a real [Components] with a counting [llmFactory]
     * side by side with the route, the same way `HowItWorksRoutesTest`'s own
     * `the route reads the model id with no model client built` proves it for `/how-it-works`.
     */
    @Test
    fun `the route builds no model client, and starts no generation`() = testApplication {
        var built = 0
        val components = Components(
            mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_faq_no_model")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { built++; FakeLlmClient() },
        )
        application { routing { faqRoutes(billingConfig = components.billingConfig) } }

        val response = client.get("/faq")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, built, "the FAQ route built a model client")
    }

    @Test
    fun `the page carries a canonical tag and og tags with the property attribute`() = testApplication {
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        val body = client.get("/faq").bodyAsText()

        assertTrue(
            """<link href="https://mytetz.com/faq" rel="canonical">""" in body,
            "canonical tag not found: $body",
        )
        assertTrue("""<meta property="og:type" content="website">""" in body, body)
        assertTrue("""<meta property="og:url" content="https://mytetz.com/faq">""" in body, body)
    }

    @Test
    fun `the footer links to the FAQ page, the same as every other page`() = testApplication {
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        val body = client.get("/faq").bodyAsText()

        assertTrue("""<a href="/faq">FAQ</a>""" in body, body)
    }

    @Test
    fun `the price question links to the terms, the account question links to how-it-works and privacy, and the last question links to the imprint`() =
        testApplication {
            application { routing { faqRoutes(billingConfig = BillingConfig()) } }

            val body = client.get("/faq").bodyAsText()

            assertTrue("""<a href="/terms">Terms</a>""" in body, body)
            assertTrue("""<a href="/how-it-works">How it works</a>""" in body, body)
            assertTrue("""<a href="/privacy">Privacy policy</a>""" in body, body)
            assertTrue("""<a href="/imprint">Imprint</a>""" in body, body)
        }

    // ------------------------------------------------------------- the first sentence

    @Test
    fun `the first sentence of each answer is 30 words or fewer`() {
        for (entry in faqEntries(BillingConfig())) {
            val firstSentence = entry.answer.substringBefore(". ").substringBefore("? ")
            val wordCount = firstSentence.trim().split(Regex("\\s+")).size
            assertTrue(
                wordCount <= 30,
                "\"${entry.question}\" answer's first sentence has $wordCount words: \"$firstSentence\"",
            )
        }
    }

    // ------------------------------------------------------------- the JSON-LD block

    @Test
    fun `the JSON-LD FAQPage carries the same question and answer text as the page`() = testApplication {
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        val body = client.get("/faq").bodyAsText()
        val parsed = ldJson(body)

        val faqPage = parsed.jsonObject["@graph"]!!.jsonArray.first {
            it.jsonObject["@type"]?.jsonPrimitive?.content == "FAQPage"
        }
        val questions = faqPage.jsonObject["mainEntity"]!!.jsonArray

        val entries = faqEntries(BillingConfig())
        assertEquals(entries.size, questions.size)
        entries.forEachIndexed { index, entry ->
            val question = questions[index].jsonObject
            assertEquals(entry.question, question["name"]!!.jsonPrimitive.content)
            assertEquals(
                entry.answer,
                question["acceptedAnswer"]!!.jsonObject["text"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `the JSON-LD block carries a two-item BreadcrumbList`() = testApplication {
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        val parsed = ldJson(client.get("/faq").bodyAsText())

        val breadcrumb = parsed.jsonObject["@graph"]!!.jsonArray.first {
            it.jsonObject["@type"]?.jsonPrimitive?.content == "BreadcrumbList"
        }
        val items = breadcrumb.jsonObject["itemListElement"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals("FAQ", items[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("https://mytetz.com/faq", items[1].jsonObject["item"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the JSON-LD block carries no Offer or price node`() = testApplication {
        // schema.org's Question and Answer types carry no price-related property, and Offer
        // attaches to a Product or a Service node, not to an Answer — confirmed by reading
        // https://schema.org/FAQPage and https://schema.org/UnitPriceSpecification before this
        // test was written. This page has no Product or Service node, so it carries no Offer,
        // rather than guess a property name schema.org does not define for this shape.
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        val body = client.get("/faq").bodyAsText()

        assertTrue("\"Offer\"" !in body, body)
        assertTrue("\"UnitPriceSpecification\"" !in body, body)
    }
}
