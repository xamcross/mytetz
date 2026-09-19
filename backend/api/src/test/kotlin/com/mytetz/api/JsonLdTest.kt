package com.mytetz.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JsonLdTest {

    // ------------------------------------------------------------- jsonLdScriptSafe

    @Test
    fun `a value carrying a closing script tag holds no closing script tag after escaping`() {
        val safe = jsonLdScriptSafe("""{"description":"</script><script>alert(1)</script>"}""")

        assertFalse("<" in safe, "a literal < character survived: $safe")
        val parsed = Json.parseToJsonElement(safe)
        assertEquals(
            "</script><script>alert(1)</script>",
            parsed.jsonObject["description"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the double-escaped-state attack does not survive as a literal sequence`() {
        // The HTML standard's own worked example: "<!--" then "<script" puts the parser into a
        // state where the NEXT "</script>" does not close the element. Reference:
        // https://html.spec.whatwg.org/multipage/scripting.html#restrictions-for-contents-of-script-elements
        val safe = jsonLdScriptSafe("""{"body":"<!--<script>"}""")

        assertFalse("<" in safe, "a literal < character survived: $safe")
        assertEquals(
            "<!--<script>",
            Json.parseToJsonElement(safe).jsonObject["body"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a value with no angle bracket is unchanged`() {
        val plain = """{"name":"mytetz"}"""

        assertEquals(plain, jsonLdScriptSafe(plain))
    }

    @Test
    fun `an ampersand and a line separator are escaped too`() {
        val safe = jsonLdScriptSafe("{\"x\":\"a & b \u2028 c\"}")

        assertFalse("&" in safe)
        assertFalse("\u2028" in safe)
        assertEquals("a & b \u2028 c", Json.parseToJsonElement(safe).jsonObject["x"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------- jsonLdGraph / jsonLdDocument

    @Test
    fun `jsonLdGraph produces a context and graph that Json can parse back to the same values`() {
        val hostile = "A title with a \" quote, a \\ backslash, a\nline break and a\ttab."
        val node = buildJsonObject {
            put("@type", "LearningResource")
            put("name", hostile)
        }

        val script = jsonLdGraph(listOf(node))
        val parsed = Json.parseToJsonElement(script)

        assertEquals("https://schema.org", parsed.jsonObject["@context"]!!.jsonPrimitive.content)
        assertEquals(hostile, parsed.jsonObject["@graph"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `jsonLdDocument wraps one node the same way`() {
        val node = buildJsonObject { put("@type", "Organization"); put("name", "mytetz") }

        val parsed = Json.parseToJsonElement(jsonLdDocument(node))

        assertEquals("mytetz", parsed.jsonObject["@graph"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `organizationJsonLd names the project's GitHub repository`() {
        val parsed = Json.parseToJsonElement(jsonLdDocument(organizationJsonLd()))

        val sameAs = parsed.jsonObject["@graph"]!!.jsonArray[0].jsonObject["sameAs"]!!
        assertEquals(JsonPrimitive("https://github.com/xamcross/mytetz"), sameAs.jsonArray[0])
    }
}
