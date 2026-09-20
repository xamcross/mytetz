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
 * any more.
 *
 * TEMPORARY EXCLUSION for issue #143 and issue #144. The shared header (`class="bar"`) and the
 * shared footer (`class="foot"`) still hold the link "Catalogue" until those two issues merge and
 * remove it. This test strips both elements before it searches, so it does not fail on that
 * known, short-lived text. The main session removes this exclusion once #143 and #144 merge.
 */
class NoCatalogueTextTest {

    private val catalogueWord = Regex("catalogue", RegexOption.IGNORE_CASE)

    private fun withoutHeaderAndFooter(html: String): String =
        html
            .replace(Regex("""<header class="bar">.*?</header>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<footer class="foot">.*?</footer>""", RegexOption.DOT_MATCHES_ALL), "")

    private fun assertNoCatalogueWord(label: String, html: String) {
        val body = withoutHeaderAndFooter(html)
        assertFalse(catalogueWord.containsMatchIn(body), "$label still holds the word \"catalogue\"")
    }

    @Test
    fun `no static guide page holds the word catalogue outside the header and the footer`() {
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
    fun `the FAQ page holds no word catalogue outside the header and the footer`() = testApplication {
        application { routing { faqRoutes(billingConfig = BillingConfig()) } }

        val html = client.get("/faq").bodyAsText()

        assertNoCatalogueWord("/faq", html)
    }

    @Test
    fun `the glossary page holds no word catalogue outside the header and the footer`() {
        val html = createHTML().html { glossaryHtml(emptyList()) }

        assertNoCatalogueWord("/glossary", html)
    }
}
