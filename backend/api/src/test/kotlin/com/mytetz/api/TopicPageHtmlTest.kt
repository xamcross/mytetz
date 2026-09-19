package com.mytetz.api

import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicPageHtmlTest {

    private fun render(view: TopicPageView): String = createHTML().html { topicPageHtml(view) }

    /** Pulls the JSON-LD `<script>` block's text out of a rendered page and parses it. No
     * un-escaping step runs first: [jsonLdScriptSafe]'s substitutions are ordinary JSON `\u00XX`
     * escapes, and [Json.parseToJsonElement] already resolves those on its own. */
    private fun ldJson(html: String) = Json.parseToJsonElement(
        Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
    )

    private fun view(
        title: String = "Special Relativity",
        summary: String = "How space and time trade off for an observer in motion.",
        seedBody: String? = "Special relativity says two observers can disagree about time.",
        related: List<RelatedTopicView> = listOf(RelatedTopicView("quantum-physics", "Quantum Physics")),
    ) = TopicPageView(
        slug = "special-relativity",
        title = title,
        category = "Physics",
        summary = summary,
        seedBody = seedBody,
        relatedTopics = related,
    )

    @Test
    fun `the html element states the language of the page`() {
        // A screen reader selects its voice from this attribute, and a search engine reads it. The
        // first version of this page had none, and no test saw it: the live page showed it on
        // 2026-09-19.
        val html = render(view())
        assertTrue(html.contains("<html lang=\"en\">"), html.take(200))
    }

    @Test
    fun `a title carrying a script tag is escaped in the HTML body, not executed`() {
        val html = render(view(title = "<script>alert(1)</script>"))

        assertFalse("<script>alert(1)</script>" in html, "the hostile title was not escaped: $html")
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
    }

    @Test
    fun `a related-topic title is escaped in the HTML body`() {
        val html = render(view(related = listOf(RelatedTopicView("x", "<img src=x onerror=alert(1)>"))))

        assertFalse("<img src=x onerror=alert(1)>" in html)
        assertTrue("&lt;img" in html)
    }

    @Test
    fun `a null seed renders the summary and no seed paragraph`() {
        val html = render(view(seedBody = null))

        assertTrue(view().summary in html)
    }

    // ------------------------------------------------------------- the JSON-LD block

    @Test
    fun `the JSON-LD block holds no literal angle bracket, whatever the summary contains`() {
        val html = render(view(summary = "End the tag. </script><script>alert(1)</script>"))

        val block = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        assertFalse("<" in block, "a literal < character survived inside the JSON-LD block: $block")
    }

    @Test
    fun `a title with a double quote, a backslash, a line break and a tab round-trips through the JSON-LD block`() {
        val hostile = "A \"title\" with a \\ backslash,\na line break and\ta tab."
        val html = render(view(title = hostile))

        val parsed = ldJson(html)
        val learningResource = parsed.jsonObject["@graph"]!!.jsonArray.first {
            it.jsonObject["@type"]?.jsonPrimitive?.content == "LearningResource"
        }
        assertEquals(hostile, learningResource.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the double-escaped-state attack inside the summary parses back to the original text`() {
        // https://html.spec.whatwg.org/multipage/scripting.html#restrictions-for-contents-of-script-elements
        val hostile = "<!--<script>"
        val html = render(view(summary = hostile))

        val parsed = ldJson(html)
        val learningResource = parsed.jsonObject["@graph"]!!.jsonArray.first {
            it.jsonObject["@type"]?.jsonPrimitive?.content == "LearningResource"
        }
        assertEquals(hostile, learningResource.jsonObject["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the JSON-LD block carries a LearningResource and a two-item BreadcrumbList`() {
        val parsed = ldJson(render(view()))

        val graph = parsed.jsonObject["@graph"]!!.jsonArray
        assertTrue(graph.any { it.jsonObject["@type"]?.jsonPrimitive?.content == "LearningResource" })
        val breadcrumb = graph.first { it.jsonObject["@type"]?.jsonPrimitive?.content == "BreadcrumbList" }
        assertEquals(2, breadcrumb.jsonObject["itemListElement"]!!.jsonArray.size)
    }

    // ------------------------------------------------------------- U+2028, another hostile case

    @Test
    fun `a line separator in the seed body does not break the JSON-LD block`() {
        val html = render(view(seedBody = "Before\u2028after"))

        val parsed = ldJson(html)
        assertTrue(parsed.jsonObject["@graph"]!!.jsonArray.isNotEmpty())
    }

    // ------------------------------------------------------------- the start control

    /**
     * `frontend/e2e/topic-pages.spec.ts`'s own `topicPageFixture` function holds this same button,
     * alert paragraph and noscript text, with `special-relativity` swapped for a real slug. Change
     * one and change the other, or that suite tests a fixture the backend no longer builds.
     */
    @Test
    fun `the start control holds the button with the escaped slug, the alert paragraph and the noscript text`() {
        val html = render(view(related = emptyList()))

        assertTrue(
            """<button type="button" id="topic-start-button" class="start__cta" data-topic-slug="special-relativity">""" in html,
            "the start button, with its data-topic-slug attribute, was not found: $html",
        )
        assertTrue(
            """<p id="topic-start-error" class="start__error" role="alert"></p>""" in html,
            "the alert paragraph was not found: $html",
        )
        assertTrue("<noscript>" in html, "no noscript block was found: $html")
        assertTrue(
            "needs JavaScript" in html,
            "the noscript text does not say the control needs JavaScript: $html",
        )
    }

    @Test
    fun `a slug with a quote in it is escaped in the button's data attribute`() {
        // Real slugs never carry a quote \u2014 this proves the DSL's own attribute escaping runs on
        // this value too, and not only on learner-facing text.
        val view = view().copy(slug = "special\"-relativity")

        val html = render(view)

        assertFalse("""data-topic-slug="special"-relativity"""" in html, "the quote broke out of the attribute: $html")
        assertTrue("""data-topic-slug="special&quot;-relativity"""" in html)
    }

    // ------------------------------------------------------------- Open Graph

    @Test
    fun `every og tag uses the property attribute, and the page names its type, site and card`() {
        val html = render(view())

        assertTrue("""<meta property="og:type" content="article">""" in html, html)
        assertTrue("""<meta property="og:site_name" content="mytetz">""" in html, html)
        assertTrue("""<meta property="og:url" content="https://mytetz.com/topics/special-relativity">""" in html, html)
        assertTrue("""<meta property="og:title" content=""" in html, html)
        assertTrue("""<meta property="og:description" content=""" in html, html)
        assertTrue("""<meta property="og:image" content="https://mytetz.com/og-image.png">""" in html, html)
        assertTrue("""<meta name="twitter:card" content="summary_large_image">""" in html, html)
        // The DSL's own meta(name = …) call writes a name attribute — asserting its absence here
        // is what tells apart "the og tags use property" from "the html happens to also contain
        // the text og:type somewhere".
        assertFalse("""<meta name="og:""" in html, "an og:* tag used name instead of property: $html")
    }

    // ------------------------------------------------------------- the review date

    @Test
    fun `a reviewed topic shows the review date and dateModified`() {
        val html = render(view().copy(reviewedAt = 1_700_000_000_000L))

        assertTrue("Last reviewed" in html)
        assertTrue("\"dateModified\"" in html)
    }

    @Test
    fun `an unreviewed topic shows neither`() {
        val html = render(view().copy(reviewedAt = null))

        assertFalse("Last reviewed" in html)
        assertFalse("\"dateModified\"" in html)
    }

    @Test
    fun `the review date renders in UTC`() {
        // 1_700_000_000_000 ms is 2023-11-14T22:13:20Z. A local, non-UTC formatter would print a
        // different calendar day depending on the machine's own time zone.
        val html = render(view().copy(reviewedAt = 1_700_000_000_000L))

        assertTrue("2023-11-14" in html, html)
    }

    // ------------------------------------------------------------- the footer

    @Test
    fun `the footer links to the how-it-works page`() {
        val html = render(view())

        assertTrue("""<a href="/how-it-works">How it works</a>""" in html, html)
    }

    @Test
    fun `the page loads exactly one external script, and exactly one other script, the JSON-LD one`() {
        val html = render(view())

        val scriptSrcCount = Regex("""<script src="/topic-start\.js" defer[^>]*></script>""").findAll(html).count()
        assertEquals(1, scriptSrcCount, "expected exactly one topic-start.js script tag: $html")

        val totalScriptTags = Regex("<script[ >]").findAll(html).count()
        assertEquals(2, totalScriptTags, "expected exactly two <script tags in total (JSON-LD and topic-start.js): $html")
    }
}
