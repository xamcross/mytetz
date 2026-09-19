package com.mytetz.api

import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val GLOSSARY_DESCRIPTION =
    "Short, reviewed answers to phrases learners have asked about on mytetz."

/** One glossary entry: a published `EXPLAIN` node's span, and the page it links to. */
data class GlossaryEntryView(val span: String, val topicSlug: String, val shortKey: String)

/**
 * Renders `/glossary`: every published `EXPLAIN` node, by its span, each with `DefinedTerm`
 * JSON-LD (https://schema.org/DefinedTerm, spec section 7.4). [entries] is already filtered to
 * published `EXPLAIN` nodes by `GlossaryRoutes.kt` — this function renders whatever list it is
 * given, published or not, so an empty list here means an empty database, exactly as issue #48's
 * own acceptance criterion asks. An empty list renders a real empty state instead — see the `else`
 * branch below — never an error.
 *
 * Every text value below reaches the page through `+value` or an attribute assignment, and the one
 * `unsafe { }` block wraps [jsonLdGraph]'s own output — see `JsonLd.kt`'s own KDoc.
 *
 * Round 2: the page reuses [commonHeadTags], [siteHeaderBar] and [siteFooter] from
 * `TopicPageHtml.kt`, the same functions the topic page, the explanation page and `/how-it-works`
 * share, and not a second copy of that markup (the earlier `PublicPageChrome.kt` this file first
 * used is deleted).
 */
fun HTML.glossaryHtml(entries: List<GlossaryEntryView>) {
    // Set before the first child — see TopicPageHtml.kt's own note on why the order matters.
    lang = "en"

    val canonical = "$SITE_URL/glossary"

    head {
        commonHeadTags(
            pageTitle = "Glossary | mytetz",
            description = GLOSSARY_DESCRIPTION,
            canonical = canonical,
            ogType = "website",
        )

        if (entries.isNotEmpty()) {
            script(type = "application/ld+json") {
                unsafe { +jsonLdGraph(entries.map { definedTermJsonLd(it) }) }
            }
        }
    }
    body {
        siteHeaderBar()
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
        siteFooter()
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
