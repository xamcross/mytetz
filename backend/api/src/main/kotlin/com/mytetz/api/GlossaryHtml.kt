package com.mytetz.api

import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SITE_URL = "https://mytetz.com"
private const val GLOSSARY_DESCRIPTION =
    "Short, reviewed answers to phrases learners have asked about on mytetz."

/** One glossary entry: a published `EXPLAIN` node's span, and the page it links to. */
data class GlossaryEntryView(val span: String, val topicSlug: String, val shortKey: String)

/**
 * Renders `/glossary`: every published `EXPLAIN` node, by its span, each with `DefinedTerm`
 * JSON-LD (https://schema.org/DefinedTerm, spec section 7.4). [entries] is already filtered to
 * published `EXPLAIN` nodes by `GlossaryRoutes.kt` — this function renders whatever list it is
 * given, published or not, so an empty list here means an empty database, exactly as issue #48's
 * own acceptance criterion asks.
 *
 * Every text value below reaches the page through `+value` or an attribute assignment, and the one
 * `unsafe { }` block wraps [jsonLdGraph]'s own output — see `JsonLd.kt`'s own KDoc. The page reuses
 * the same layout as the topic page and the explanation page, through [publicHeadBasics],
 * [publicHeaderBar] and [publicFooterBar].
 */
fun HTML.glossaryHtml(entries: List<GlossaryEntryView>) {
    // Set before the first child — see TopicPageHtml.kt's own note on why the order matters.
    lang = "en"

    val canonical = "$SITE_URL/glossary"

    head {
        publicHeadBasics()
        title { +"Glossary | mytetz" }
        meta(name = "description", content = GLOSSARY_DESCRIPTION)
        link(rel = "canonical", href = canonical)

        meta { attributes["property"] = "og:type"; attributes["content"] = "website" }
        meta { attributes["property"] = "og:site_name"; attributes["content"] = "mytetz" }
        meta { attributes["property"] = "og:url"; attributes["content"] = canonical }
        meta { attributes["property"] = "og:title"; attributes["content"] = "Glossary | mytetz" }
        meta { attributes["property"] = "og:description"; attributes["content"] = GLOSSARY_DESCRIPTION }
        meta { attributes["property"] = "og:image"; attributes["content"] = "$SITE_URL/og-image.png" }

        if (entries.isNotEmpty()) {
            script(type = "application/ld+json") {
                unsafe { +jsonLdGraph(entries.map { definedTermJsonLd(it) }) }
            }
        }
    }
    body {
        publicHeaderBar()
        main(classes = "wrap") {
            h1 { +"Glossary" }
            if (entries.isEmpty()) {
                p {
                    +"The glossary has no entry yet. An entry appears here after a person has "
                    +"reviewed it. Go to the "
                    a(href = "/") { +"catalogue" }
                    +" to start with a topic."
                }
            } else {
                ul {
                    entries.forEach { entry ->
                        li { a(href = "/topics/${entry.topicSlug}/explain/${entry.shortKey}") { +entry.span } }
                    }
                }
            }
        }
        publicFooterBar()
    }
}

/**
 * `DefinedTerm` (https://schema.org/DefinedTerm), built with `buildJsonObject` — the same pattern
 * `TopicPageHtml.kt`'s and `ExplanationPageHtml.kt`'s node builders use, and never a hand-written
 * string template.
 */
private fun definedTermJsonLd(entry: GlossaryEntryView): JsonObject = buildJsonObject {
    put("@type", "DefinedTerm")
    put("name", entry.span)
    put("url", "$SITE_URL/topics/${entry.topicSlug}/explain/${entry.shortKey}")
}
