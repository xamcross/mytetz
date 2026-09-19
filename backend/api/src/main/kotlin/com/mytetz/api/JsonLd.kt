package com.mytetz.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Stops a JSON-LD payload from ending its own `<script>` block early, and stops any consumer that
 * is not a JSON parser from reading the payload as HTML.
 *
 * A browser's HTML parser can end a `script` element's content before a JSON parser ever reads it.
 * A narrow fix — one that replaces only `</` — is not enough. After the literal text `<!--` and
 * then `<script`, the HTML standard's own tokenizer enters a further state, "script data double
 * escaped". In that state, the next `</script>` does not close the element the way a narrow fix
 * assumes. Reference:
 * https://html.spec.whatwg.org/multipage/scripting.html#restrictions-for-contents-of-script-elements
 *
 * This function removes the trigger instead. It replaces every `<` character with the six-character
 * JSON string escape: a backslash, the letter u, and the four hex digits 0, 0, 3, c. No state the
 * standard describes can start with no literal `<` character. [Json.parseToJsonElement] reads this
 * escape back as the plain character `<`, on its own, with no matching un-escape step anywhere in
 * this project.
 *
 * `>`, `&`, U+2028 (LINE SEPARATOR) and U+2029 (PARAGRAPH SEPARATOR) are escaped for two further
 * reasons:
 *
 * - `>` and `&` carry meaning for a consumer that is not JSON-aware — an old SGML tool, or a future
 *   code path that reads this payload as HTML text instead of JSON. The escape costs nothing: a
 *   JSON parser reads each one back as the plain character.
 * - U+2028 and U+2029 are legal inside a JSON string, but illegal inside a JavaScript string or
 *   template literal. This project never evaluates this payload as JavaScript. The escape defends a
 *   future change that copies this payload into a JavaScript string, against a defect this function
 *   can close for free today.
 *
 * **Deviation from the approved spec, recorded here.** Section 4.6 of
 * `docs/superpowers/specs/2026-09-19-public-surface-design.md` asks for a narrower rule: replace a
 * `/` with `\/` only when it follows a `<`. That rule does not stop the "script data double
 * escaped" state above, because the state starts on the literal text `<!--<script`, with no `/`
 * anywhere in the trigger. The plan
 * (`docs/superpowers/plans/2026-09-19-public-surface.md`, Task 1.1) already found this gap in an
 * earlier draft and corrected it to the rule this function implements. This function follows the
 * plan's corrected rule, and not the spec's narrower one, because the plan's rule is the only one
 * of the two the worked HTML-standard example above actually defeats.
 */
fun jsonLdScriptSafe(json: String): String = buildString(json.length) {
    for (ch in json) {
        when (ch) {
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            '&' -> append("\\u0026")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> append(ch)
        }
    }
}

/**
 * Builds one `{"@context": "https://schema.org", "@graph": [...]}` document out of one or more
 * schema.org node objects, serializes it with kotlinx.serialization, and makes the result safe to
 * place inside a `<script type="application/ld+json">` element with [jsonLdScriptSafe].
 *
 * Every JSON-LD document this project renders goes through this function. `Json.encodeToString`
 * escapes every character JSON itself requires escaped — the quote, the backslash, and each control
 * character from U+0000 to U+001F — which a hand-written string template does not do on its own.
 */
fun jsonLdGraph(nodes: List<JsonObject>): String {
    val document = buildJsonObject {
        put("@context", "https://schema.org")
        put("@graph", JsonArray(nodes))
    }
    return jsonLdScriptSafe(Json.encodeToString(JsonObject.serializer(), document))
}

/** [jsonLdGraph] for the common case of one single schema.org node. */
fun jsonLdDocument(node: JsonObject): String = jsonLdGraph(listOf(node))

/**
 * The `Organization` JSON-LD node, shared by `/` and `/how-it-works` (spec section 11).
 *
 * `sameAs` carries one entry today: the project's GitHub repository. Spec section 17 question 2
 * asks the owner for any other public profile. This ships with one entry until an answer arrives.
 * `sameAs` accepts a list, so a later addition only adds an entry.
 */
fun organizationJsonLd(): JsonObject = buildJsonObject {
    put("@type", "Organization")
    put("name", "mytetz")
    put("url", "https://mytetz.com")
    put("logo", "https://mytetz.com/icon.svg")
    putJsonArray("sameAs") { add(JsonPrimitive("https://github.com/xamcross/mytetz")) }
}
