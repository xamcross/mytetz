package com.mytetz.api

import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One related topic, shown as a plain link under the seed. */
data class RelatedTopicView(val slug: String, val title: String)

/**
 * Everything [topicPageHtml] needs to render one topic page.
 *
 * [seedBody] is null when the graph holds no seed for this topic yet. See `TopicPageRoutes.kt`'s
 * own KDoc for why the route falls back to the summary alone, rather than generating one.
 */
data class TopicPageView(
    val slug: String,
    val title: String,
    val category: String,
    val summary: String,
    val seedBody: String?,
    val relatedTopics: List<RelatedTopicView>,
)

private const val SITE_URL = "https://mytetz.com"

/**
 * Renders one topic page.
 *
 * Every text value below reaches the page through `+value` or an attribute assignment, and never
 * through `unsafe { }` — `kotlinx.html`'s stream writer escapes both by default. The one `unsafe`
 * block in this function wraps [jsonLdGraph]'s own output, which is JSON and must not be escaped as
 * HTML; [jsonLdGraph] is what makes that block safe, per `JsonLd.kt`'s own KDoc. See
 * `docs/superpowers/specs/2026-09-19-public-surface-design.md` section 4.6.
 */
fun HTML.topicPageHtml(view: TopicPageView) {
    val pageTitle = pageTitleFor(view.title)
    val canonical = "$SITE_URL/topics/${view.slug}"

    head {
        title { +pageTitle }
        meta(name = "description", content = view.summary)
        link(rel = "canonical", href = canonical)
        meta(name = "og:url", content = canonical)
        meta(name = "og:title", content = pageTitle)
        meta(name = "og:description", content = view.summary)
        meta(name = "og:image", content = "$SITE_URL/og-image.png")
        script(type = "application/ld+json") {
            unsafe { +jsonLdGraph(listOf(learningResourceJsonLd(view, canonical), breadcrumbListJsonLd(view, canonical))) }
        }
    }
    body {
        h1 { +view.title }
        p { +view.category }
        p { +(view.seedBody ?: view.summary) }
        if (view.relatedTopics.isNotEmpty()) {
            ul {
                view.relatedTopics.forEach { related ->
                    li { a(href = "$SITE_URL/topics/${related.slug}") { +related.title } }
                }
            }
        }
    }
}

/** Keeps the title under 60 characters, per spec section 6.1. */
private fun pageTitleFor(topicTitle: String): String {
    val full = "$topicTitle explained simply | mytetz"
    return if (full.length <= 60) full else "$topicTitle | mytetz"
}

/**
 * `LearningResource` (https://schema.org/LearningResource), built with `buildJsonObject` and never
 * with a string template — see `JsonLd.kt`'s own KDoc (Task 1.1) for why. This is a page-specific
 * node object, built here rather than in `JsonLd.kt`, because it needs [TopicPageView]; `JsonLd.kt`
 * still owns the one substitution function ([jsonLdScriptSafe]) and the one graph-assembling
 * function ([jsonLdGraph]) that this function's caller uses.
 */
private fun learningResourceJsonLd(view: TopicPageView, canonical: String): JsonObject = buildJsonObject {
    put("@type", "LearningResource")
    put("@id", "$canonical#resource")
    put("name", view.title)
    put("description", view.summary)
    put("url", canonical)
}

/** `BreadcrumbList` (https://schema.org/BreadcrumbList), with the two required `ListItem` entries
 * per spec section 11: home, then this topic. */
private fun breadcrumbListJsonLd(view: TopicPageView, canonical: String): JsonObject = buildJsonObject {
    put("@type", "BreadcrumbList")
    put("itemListElement", buildJsonArray {
        add(buildJsonObject { put("@type", "ListItem"); put("position", 1); put("name", "mytetz"); put("item", SITE_URL) })
        add(buildJsonObject { put("@type", "ListItem"); put("position", 2); put("name", view.title); put("item", canonical) })
    })
}
