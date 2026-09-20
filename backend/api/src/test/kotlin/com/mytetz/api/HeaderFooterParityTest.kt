package com.mytetz.api

import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.Verb
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.html.body
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #143 and issue #144: one header and one footer for every page.
 *
 * `AppShellComponent`'s own spec, `frontend/src/app/ui/app-shell.component.spec.ts`, asserts the
 * Angular side of the same rule: the nav holds Glossary then Guides, and the footer holds the six
 * links below. No one test can read both sides, so each file carries a comment that names the
 * other.
 *
 * [siteHeaderBar] and [siteFooter], in `TopicPageHtml.kt`, are the one place every Ktor page
 * shares this markup from: the topic page, the glossary page, the FAQ page, the how-it-works page
 * and the explanation page. This file renders each of those five page types for real, plus the
 * shared functions on their own, and compares each one against the 13 static guide pages the
 * finished Angular build ships under the `static` classpath resources — the same source
 * `GuidePagesTest` reads from. A guide page with a header or a footer written by hand that has
 * drifted from the rest of the site fails this test.
 */
class HeaderFooterParityTest {

    /** The nav links every header must hold, in order. No "Topics" and no "Catalogue": the
     * wordmark already opens `/`, so a second link to the same page is a duplicate the owner's
     * 2026-09-20 decision removes. */
    private val expectedNavLinks = listOf("Glossary" to "/glossary", "Guides" to "/guides")

    /** The account control's signed-out text and target. A Ktor page and a static guide page are
     * both a pure read with no session state, so the server can only ever render this, the same
     * default `AppShellComponent` renders before its own first `GET /api/account` answers. */
    private val expectedSignedOutAccountLink = "Sign in" to "/auth"

    /** The six footer links every page must hold, in order, with no "Catalogue" — the same list
     * `frontend/e2e/legal.spec.ts`'s own `FOOTER_LINKS` constant pins on the Angular side. */
    private val expectedFooterLinks = listOf(
        "Guides" to "/guides",
        "How it works" to "/how-it-works",
        "FAQ" to "/faq",
        "Privacy" to "/privacy",
        "Terms" to "/terms",
        "Imprint" to "/imprint",
    )

    private val anchor = Regex("""<a\b[^>]*href="([^"]*)"[^>]*>([^<]*)</a>""")

    /** The (text, href) pairs of every `<a>` between [openTag]'s first match and the next
     * [closeTag]. Independent of attribute order, so a class added in any position still parses. */
    private fun linksInside(html: String, openTag: Regex, closeTag: String): List<Pair<String, String>> {
        val open = openTag.find(html) ?: error("no match for $openTag in: $html")
        val start = open.range.last + 1
        val end = html.indexOf(closeTag, start).let { if (it == -1) html.length else it }
        val inner = html.substring(start, end)
        return anchor.findAll(inner).map { it.groupValues[2].trim() to it.groupValues[1] }.toList()
    }

    private fun navLinks(html: String): List<Pair<String, String>> =
        linksInside(html, Regex("""<nav\b[^>]*class="[^"]*\bbar__nav\b[^"]*"[^>]*>"""), "</nav>")

    private fun footerLinks(html: String): List<Pair<String, String>> =
        linksInside(html, Regex("""<footer\b[^>]*class="[^"]*\bfoot\b[^"]*"[^>]*>"""), "</footer>")

    private fun headerHtml(html: String): String =
        html.substringAfter("<header").let { "<header" + it.substringBefore("</header>") + "</header>" }

    /** The account control's own text and href. Not scanned as part of [navLinks]: it sits in
     * `.bar__right`, next to the nav, and it is the one header link whose text server-rendered
     * HTML can state correctly with no account state — see `TopicPageHtml.kt`'s own KDoc on
     * `siteHeaderBar`. */
    private fun accountLink(html: String): Pair<String, String> {
        val tag = Regex("""<a\b[^>]*id="site-header-account"[^>]*>([^<]*)</a>""").find(html)
            ?: error("no #site-header-account link found in: $html")
        val href = Regex("""href="([^"]*)"""").find(tag.value)?.groupValues?.get(1)
            ?: error("the site-header-account link carries no href")
        return tag.groupValues[1].trim() to href
    }

    /**
     * The status dot's own tag, found by its fixed id. A Ktor page and a static guide page are
     * both a pure read with no live health check of their own (see `siteHeaderBar`'s own KDoc),
     * so the server can only ever render the "checking" state — the same evidence a browser with
     * no JavaScript needs: this state, and never a claim of health the page has not confirmed.
     */
    private fun dotTag(html: String): String =
        Regex("""<span\b[^>]*id="site-header-dot"[^>]*>""").find(html)?.value
            ?: error("no #site-header-dot span found in: $html")

    private fun assertHeaderAndFooter(html: String, page: String) {
        assertEquals(expectedNavLinks, navLinks(html), "$page's header nav")
        assertEquals(expectedSignedOutAccountLink, accountLink(html), "$page's account control")
        assertEquals(expectedFooterLinks, footerLinks(html), "$page's footer")
        assertFalse("Catalogue" in headerHtml(html), "$page's header still names Catalogue")
        assertFalse(
            html.substringAfter("<footer").contains("Catalogue"),
            "$page's footer still names Catalogue",
        )
        assertFalse("Topics" in headerHtml(html), "$page's header still names Topics")
        assertFalse("bar__cta" in html, "$page still carries the removed Start a topic button")

        val dot = dotTag(html)
        assertTrue("""class="dot dot--checking"""" in dot, "$page's dot must start in the checking state: $dot")
        assertTrue("""aria-label="Backend: checking"""" in dot, "$page's dot must carry the checking label: $dot")
        assertTrue("""title="Backend: checking"""" in dot, "$page's dot must carry the checking title: $dot")
        assertTrue("""role="img"""" in dot, "$page's dot must carry role=img: $dot")
    }

    @Test
    fun `siteHeaderBar and siteFooter, rendered on their own, hold the shared header and footer`() {
        val html = createHTML().html { body { siteHeaderBar(); siteFooter() } }
        assertHeaderAndFooter(html, "the shared siteHeaderBar and siteFooter functions")
    }

    @Test
    fun `the topic page holds the shared header and footer`() = testApplication {
        application {
            routing {
                topicPageRoutes(
                    catalog = TestFixtures.seededCatalog(),
                    explanations = ExplanationRepository(
                        Mongo(MongoConfig(TestFixtures.connectionString, "test_api_header_footer_topic")).database,
                    ),
                    modelFamily = "fake-model",
                )
            }
        }
        assertHeaderAndFooter(client.get("/topics/quantum-physics").bodyAsText(), "the topic page")
    }

    @Test
    fun `the glossary page holds the shared header and footer`() = testApplication {
        application {
            routing {
                glossaryRoutes(
                    ExplanationRepository(
                        Mongo(MongoConfig(TestFixtures.connectionString, "test_api_header_footer_glossary")).database,
                    ),
                )
            }
        }
        assertHeaderAndFooter(client.get("/glossary").bodyAsText(), "the glossary page")
    }

    @Test
    fun `the explanation page holds the shared header and footer`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_header_footer_explain")).database,
        )
        runBlocking {
            explanations.insertIfAbsent(
                Explanation(
                    key = "abcdef012345" + "0".repeat(52), topicSlug = "quantum-physics", parentKey = "parent",
                    span = "wave function", spanSentence = "The wave function describes...", verb = Verb.EXPLAIN,
                    variant = 0, depth = 1, body = "A short explanation.", grounded = false, sources = emptyList(),
                    promptVersion = "v1", modelFamily = "fake-model", modelId = "fake-model",
                    inputTokens = 1, outputTokens = 1, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
                    published = true,
                ),
            )
        }
        application {
            routing {
                explanationPageRoutes(catalog = TestFixtures.seededCatalog(), explanations = explanations)
            }
        }
        val body = client.get("/topics/quantum-physics/explain/abcdef012345").bodyAsText()
        assertHeaderAndFooter(body, "the explanation page")
    }

    @Test
    fun `the FAQ page holds the shared header and footer`() = testApplication {
        application { routing { faqRoutes(billingConfig = com.mytetz.billing.BillingConfig()) } }
        assertHeaderAndFooter(client.get("/faq").bodyAsText(), "the FAQ page")
    }

    @Test
    fun `the how-it-works page holds the shared header and footer`() = testApplication {
        application { routing { howItWorksRoutes(modelId = { "fake-model" }) } }
        assertHeaderAndFooter(client.get("/how-it-works").bodyAsText(), "the how-it-works page")
    }

    /**
     * Every static guide page under `frontend/public/guides`, read from the `static` classpath
     * resources the Angular build copies there — the same source `GuidePagesTest` reads from.
     * Each page still writes its own header and footer by hand, so a page that has drifted fails
     * this test alone, naming the page in the failure message.
     */
    @Test
    fun `every static guide page holds the shared header and footer`() {
        assertFalse(GuidePages.paths.isEmpty(), "the guide list must not be empty")
        for (path in GuidePages.paths) {
            val html = javaClass.getResource("/static$path/index.html")?.readText()
                ?: error("$path/index.html was not found on the static classpath")
            assertHeaderAndFooter(html, path)
        }
    }
}
