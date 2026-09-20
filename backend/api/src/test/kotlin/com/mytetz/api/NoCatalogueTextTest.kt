package com.mytetz.api

import com.mytetz.billing.BillingConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards issue #145. No text for a learner or a crawler names the page at `/` the "catalogue"
 * any more. The search covers the full page, with its header and its footer: issue #143 and issue
 * #144 removed the link "Catalogue" from the two.
 */
class NoCatalogueTextTest {

    private val catalogueWord = Regex("catalogue", RegexOption.IGNORE_CASE)

    private fun assertNoCatalogueWord(label: String, html: String) {
        assertFalse(catalogueWord.containsMatchIn(html), "$label still holds the word \"catalogue\"")
    }

    @Test
    fun `no static guide page holds the word catalogue`() {
        assertTrue(GuidePages.paths.isNotEmpty(), "the guide list must not be empty")
        for (path in GuidePages.paths) {
            val html = javaClass.getResource("/static$path/index.html")?.readText()
            assertTrue(html != null, "$path did not ship a static file")
            assertNoCatalogueWord(path, html!!)
        }
    }

    @Test
    fun `llms txt holds no word catalogue`() {
        val text = javaClass.getResource("/static/llms.txt")?.readText()
        assertTrue(text != null, "llms.txt did not ship")
        assertFalse(catalogueWord.containsMatchIn(text!!), "llms.txt still holds the word \"catalogue\"")
    }

    @Test
    fun `the FAQ page holds no word catalogue`() = testApplication {
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        val html = client.get("/faq").bodyAsText()

        assertNoCatalogueWord("/faq", html)
    }

    @Test
    fun `the glossary page holds no word catalogue`() {
        val html = createHTML().html { glossaryHtml(emptyList()) }

        assertNoCatalogueWord("/glossary", html)
    }
}
