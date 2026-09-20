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

/**
 * The LINE SEPARATOR character, code point U+2028, built here from its numeric value with [Char]
 * and never spelled out as a source-code escape sequence in this file — see this file's own
 * `` `the file holds no literal line separator character` `` test, which this constant helps keep
 * honest: an editor, or a tool that generates source text, can turn such an escape sequence into
 * the real character with no visible difference on screen.
 */
private val LINE_SEPARATOR = Char(0x2028)

class GlossaryHtmlTest {

    private fun render(entries: List<GlossaryEntryView>): String = createHTML().html { glossaryHtml(entries) }

    @Test
    fun `the page opens with html lang en`() {
        val html = render(emptyList())

        assertTrue(html.startsWith("<html lang=\"en\">"), html.take(40))
    }

    @Test
    fun `an empty glossary carries no DefinedTerm and states there is nothing yet`() {
        val html = render(emptyList())

        assertFalse("DefinedTerm" in html)
        assertTrue("""<link href="https://mytetz.com/glossary" rel="canonical">""" in html)
    }

    @Test
    fun `an empty glossary links to the dashboard`() {
        val html = render(emptyList())

        assertTrue("no entry yet" in html, "the empty state must say there is no entry yet")
        assertTrue("review" in html, "the empty state must say entries come after a review")
        assertTrue(
            """<a href="/">dashboard</a>""" in html,
            "the empty state must link to the dashboard, by that word",
        )
    }

    @Test
    fun `the page lists each entry with a link to its explanation page`() {
        val html = render(
            listOf(
                GlossaryEntryView(span = "wave function", topicSlug = "quantum-physics", shortKey = "abcdef012345"),
                GlossaryEntryView(span = "superposition", topicSlug = "quantum-physics", shortKey = "fedcba987654"),
            ),
        )

        assertTrue("""<a href="/topics/quantum-physics/explain/abcdef012345">""" in html)
        assertTrue("wave function" in html)
        assertTrue("""<a href="/topics/quantum-physics/explain/fedcba987654">""" in html)
        assertTrue("superposition" in html)
    }

    @Test
    fun `the page reuses the guides stylesheet, the viewport tag, the header and the footer`() {
        val html = render(emptyList())

        assertTrue("""<meta name="viewport" content="width=device-width, initial-scale=1">""" in html)
        assertTrue("""<link href="$GUIDES_STYLESHEET" rel="stylesheet">""" in html)
        assertTrue("""class="bar"""" in html)
        assertTrue("""class="foot"""" in html)
    }

    /**
     * Issue #144 gives this link the class `foot__link`, the same class `AppShellComponent`'s
     * own footer link carries. The assertion below changed from
     * `<a href="/how-it-works">How it works</a>` (no class) to the string below, to match —
     * `kotlinx.html` writes the `href` `a(...)` sets by name before the `class` its `classes`
     * parameter adds.
     */
    @Test
    fun `the footer holds the how-it-works link, the same shared footer the topic page uses`() {
        val html = render(emptyList())

        assertTrue("""<a href="/how-it-works" class="foot__link">How it works</a>""" in html)
    }

    // ------------------------------------------------------------- hostile input

    @Test
    fun `a span carrying a script tag is escaped in the HTML body, not executed`() {
        val html = render(listOf(GlossaryEntryView("</script><script>alert(1)</script>", "quantum-physics", "abcdef012345")))

        assertFalse("</script><script>alert(1)</script>" in html.substringAfter("<body"))
        assertTrue("&lt;/script&gt;&lt;script&gt;alert(1)&lt;/script&gt;" in html)
    }

    @Test
    fun `a span carrying an image-onerror payload is escaped in the HTML body`() {
        val html = render(listOf(GlossaryEntryView("\"><img src=x onerror=alert(1)>", "quantum-physics", "abcdef012345")))

        assertFalse("<img src=x onerror=alert(1)>" in html)
        assertTrue("&lt;img" in html)
    }

    @Test
    fun `the file holds no literal line separator character`() {
        val source = java.io.File("src/main/kotlin/com/mytetz/api/GlossaryHtml.kt").readText()

        assertFalse(LINE_SEPARATOR in source, "the file must write U+2028 as its escape text, not a literal character")
    }

    // ------------------------------------------------------------- the JSON-LD block

    @Test
    fun `each entry becomes a DefinedTerm node, and the block holds no literal angle bracket`() {
        val hostile = "</script><script>alert(1)</script>"
        val html = render(listOf(GlossaryEntryView(hostile, "quantum-physics", "abcdef012345")))

        val block = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        assertFalse("<" in block, "a literal < character survived inside the JSON-LD block: $block")

        val parsed = Json.parseToJsonElement(block)
        val term = parsed.jsonObject["@graph"]!!.jsonArray.first()
        assertEquals("DefinedTerm", term.jsonObject["@type"]!!.jsonPrimitive.content)
        assertEquals(hostile, term.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a span with a quote and a backslash round-trips through the DefinedTerm JSON-LD`() {
        val hostile = "a \"quoted\" span with a \\ backslash"
        val html = render(listOf(GlossaryEntryView(hostile, "quantum-physics", "abcdef012345")))

        val block = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        val parsed = Json.parseToJsonElement(block)
        val term = parsed.jsonObject["@graph"]!!.jsonArray.first()
        assertEquals(hostile, term.jsonObject["name"]!!.jsonPrimitive.content)
    }
}
