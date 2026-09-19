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
 * The LINE SEPARATOR character, code point U+2028, built here from its numeric value with
 * [Char] and never spelled out as a source-code escape sequence in this file. An editor, or a tool
 * that generates source text, can turn such an escape sequence into the real character with no
 * visible difference on screen — exactly the defect
 * `` `the file holds no literal line separator character` `` exists to catch in the file under
 * test. Building the value from its number here sidesteps that same risk in this test file.
 */
private val LINE_SEPARATOR = Char(0x2028)

class ExplanationPageHtmlTest {

    private fun render(view: ExplanationPageView): String = createHTML().html { explanationPageHtml(view) }

    private fun ldJson(html: String) = Json.parseToJsonElement(
        Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
    )

    private fun view(
        span: String = "wave function",
        body: String = "The wave function describes the state of a quantum system.",
        published: Boolean = true,
    ) = ExplanationPageView(
        topicSlug = "quantum-physics",
        topicTitle = "Quantum Physics",
        span = span,
        body = body,
        shortKey = "abcdef012345",
        published = published,
    )

    @Test
    fun `the page opens with html lang en, as the topic page does`() {
        val html = render(view())

        assertTrue(html.startsWith("<html lang=\"en\">"), "page did not open with <html lang=\"en\">: ${html.take(40)}")
    }

    @Test
    fun `a published page carries a canonical tag`() {
        val html = render(view(published = true))

        assertTrue(
            """<link href="https://mytetz.com/topics/quantum-physics/explain/abcdef012345" rel="canonical">""" in html,
        )
    }

    @Test
    fun `an unpublished page carries no canonical tag`() {
        val html = render(view(published = false))

        assertFalse("rel=\"canonical\"" in html)
    }

    @Test
    fun `the page reuses the guides stylesheet, the viewport tag, the header and the footer`() {
        val html = render(view())

        assertTrue("""<meta name="viewport" content="width=device-width, initial-scale=1">""" in html)
        assertTrue("""<link href="/guides/guides.css" rel="stylesheet">""" in html)
        assertTrue("""class="bar"""" in html)
        assertTrue("""class="foot"""" in html)
    }

    // ------------------------------------------------------------- hostile input

    @Test
    fun `a span carrying a script tag is escaped in the HTML body, not executed`() {
        val html = render(view(span = "</script><script>alert(1)</script>"))

        assertFalse("</script><script>alert(1)</script>" in html.substringAfter("<body"))
        assertTrue("&lt;/script&gt;&lt;script&gt;alert(1)&lt;/script&gt;" in html)
    }

    @Test
    fun `a body carrying an image-onerror payload is escaped in the HTML body`() {
        val html = render(view(body = "\"><img src=x onerror=alert(1)>"))

        assertFalse("<img src=x onerror=alert(1)>" in html)
        assertTrue("&lt;img" in html)
    }

    @Test
    fun `a topic title carrying a script tag is escaped in the HTML body`() {
        val html = createHTML().html {
            explanationPageHtml(view().copy(topicTitle = "</script><script>alert(1)</script>"))
        }

        assertFalse("</script><script>alert(1)</script>" in html.substringAfter("<body"))
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
    }

    @Test
    fun `the file holds no literal line separator character`() {
        val source = java.io.File(
            "src/main/kotlin/com/mytetz/api/ExplanationPageHtml.kt",
        ).readText()

        assertFalse(LINE_SEPARATOR in source, "the file must write U+2028 as its escape text, not a literal character")
    }

    // ------------------------------------------------------------- the JSON-LD block

    @Test
    fun `the JSON-LD block holds no literal angle bracket, whatever the span contains`() {
        val html = render(view(span = "End the tag. </script><script>alert(1)</script>"))

        val block = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        assertFalse("<" in block, "a literal < character survived inside the JSON-LD block: $block")
    }

    @Test
    fun `the JSON-LD block carries a three-item BreadcrumbList, home then topic then explanation`() {
        val parsed = ldJson(render(view()))

        val breadcrumb = parsed.jsonObject["@graph"]!!.jsonArray.first {
            it.jsonObject["@type"]?.jsonPrimitive?.content == "BreadcrumbList"
        }
        val items = breadcrumb.jsonObject["itemListElement"]!!.jsonArray
        assertEquals(3, items.size)
        assertEquals("wave function", items.last().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a span with a double quote and a line break round-trips through the JSON-LD block`() {
        val hostile = "a \"quoted\" span\nwith a line break"
        val html = render(view(span = hostile))

        val parsed = ldJson(html)
        val breadcrumb = parsed.jsonObject["@graph"]!!.jsonArray.first {
            it.jsonObject["@type"]?.jsonPrimitive?.content == "BreadcrumbList"
        }
        val lastItem = breadcrumb.jsonObject["itemListElement"]!!.jsonArray.last()
        assertEquals(hostile, lastItem.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the U+2028 line separator round-trips through the JSON-LD block, escaped`() {
        // U+2028 is legal, literal HTML text, so it may still appear in the page's ordinary text
        // (the h1, the crumb): kotlinx.html's own text escaping only escapes the HTML-special
        // characters `<`, `>` and `&`. Inside the JSON-LD `<script>` block it must be escaped,
        // because it is illegal inside a JavaScript string — see jsonLdScriptSafe's own KDoc. This
        // test checks the script block alone, and not the whole page.
        val hostile = "a line" + LINE_SEPARATOR + "separator"
        val html = render(view(span = hostile))

        val block = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        assertFalse(LINE_SEPARATOR in block, "a literal U+2028 survived inside the JSON-LD block: $block")

        val parsed = ldJson(html)
        val breadcrumb = parsed.jsonObject["@graph"]!!.jsonArray.first {
            it.jsonObject["@type"]?.jsonPrimitive?.content == "BreadcrumbList"
        }
        assertEquals(hostile, breadcrumb.jsonObject["itemListElement"]!!.jsonArray.last().jsonObject["name"]!!.jsonPrimitive.content)
    }
}
