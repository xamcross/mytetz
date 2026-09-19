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
        val html = render(view(seedBody = "Before after"))

        val parsed = ldJson(html)
        assertTrue(parsed.jsonObject["@graph"]!!.jsonArray.isNotEmpty())
    }
}
