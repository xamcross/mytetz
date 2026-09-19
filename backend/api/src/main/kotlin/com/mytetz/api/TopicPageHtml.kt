package com.mytetz.api

import kotlinx.html.BODY
import kotlinx.html.HEAD
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.header
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.noScript
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.span
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

internal const val SITE_URL = "https://mytetz.com"

/** The stylesheet every guide page already links. A topic page reuses it, and not a copy, so one
 * edit reaches every page outside the Angular application. See `guides.css`'s own header comment.
 *
 * `internal`, and not `private`: `HowItWorksRoutes.kt`'s renderer shares this layout too, through
 * [commonHeadTags]. */
internal const val GUIDES_STYLESHEET = "/guides/guides.css"

/**
 * The head tags every page of this layout shares: the character set, the viewport, the page
 * title, the description, the canonical tag, the stylesheet, and the Open Graph and Twitter Card
 * tags.
 *
 * `TopicPageHtml.kt` and `HowItWorksRoutes.kt` both call this function, so one edit reaches both
 * pages. [ogType] carries the one Open Graph field that differs between the two: `"article"` for a
 * topic page, `"website"` for a page with no single author date, the same value
 * `frontend/src/index.html` and `frontend/public/guides/index.html` already use for a
 * non-article page.
 */
internal fun HEAD.commonHeadTags(pageTitle: String, description: String, canonical: String, ogType: String) {
    meta(charset = "utf-8")
    meta(name = "viewport", content = "width=device-width, initial-scale=1")
    title { +pageTitle }
    meta(name = "description", content = description)
    link(rel = "canonical", href = canonical)
    link(rel = "stylesheet", href = GUIDES_STYLESHEET)

    // The DSL's `meta(name = …)` writes a `name` attribute. Open Graph tags need `property`
    // exactly as a guide page already writes them — see frontend/public/guides/index.html — so
    // the six og:* tags below set the attribute by hand.
    meta { attributes["property"] = "og:type"; attributes["content"] = ogType }
    meta { attributes["property"] = "og:site_name"; attributes["content"] = "mytetz" }
    meta { attributes["property"] = "og:url"; attributes["content"] = canonical }
    meta { attributes["property"] = "og:title"; attributes["content"] = pageTitle }
    meta { attributes["property"] = "og:description"; attributes["content"] = description }
    meta { attributes["property"] = "og:image"; attributes["content"] = "$SITE_URL/og-image.png" }
    meta(name = "twitter:card", content = "summary_large_image")
}

/** The 64px header bar every guide page and every page of this layout shares. */
internal fun BODY.siteHeaderBar() {
    header(classes = "bar") {
        a(href = "/", classes = "bar__mark") { +"mytetz" }
        nav(classes = "bar__nav") {
            attributes["aria-label"] = "Main"
            a(href = "/") { +"Catalogue" }
            a(href = "/guides") { +"Guides" }
        }
    }
}

/** The footer every guide page and every page of this layout shares. */
internal fun BODY.siteFooter() {
    footer(classes = "foot") {
        div(classes = "foot__inner") {
            nav(classes = "foot__links") {
                attributes["aria-label"] = "Footer"
                a(href = "/") { +"Catalogue" }
                a(href = "/guides") { +"Guides" }
                a(href = "/privacy") { +"Privacy" }
                a(href = "/terms") { +"Terms" }
                a(href = "/imprint") { +"Imprint" }
            }
        }
    }
}

/**
 * Renders one topic page.
 *
 * Every text value below reaches the page through `+value` or an attribute assignment, and never
 * through `unsafe { }` — `kotlinx.html`'s stream writer escapes both by default. The one `unsafe`
 * block in this function wraps [jsonLdGraph]'s own output, which is JSON and must not be escaped as
 * HTML; [jsonLdGraph] is what makes that block safe, per `JsonLd.kt`'s own KDoc. See
 * `docs/superpowers/specs/2026-09-19-public-surface-design.md` section 4.6.
 *
 * The page reuses the header bar, the `<main class="wrap">` layout and the footer that every guide
 * page under `/guides` already carries, and the same `guides.css` file, so a learner who reaches
 * this page from a catalogue tile sees a real page and not bare browser text. See
 * `frontend/public/guides/index.html` for the page this layout is read from.
 *
 * The "Start with this topic" control is a `<button>`, an empty alert paragraph, and a `<noscript>`
 * sentence, all rendered here through the normal escaped DSL calls. Its behaviour lives in one
 * external file, `frontend/public/topic-start.js`, loaded with `defer` — never an inline `<script>`
 * — so the one `unsafe { }` block on this page stays the JSON-LD block alone.
 */
fun HTML.topicPageHtml(view: TopicPageView) {
    // Set before the first child. The stream writer of `kotlinx.html` writes the `<html>` start
    // tag when the first child opens, and an attribute that is set after that point is lost.
    lang = "en"

    val pageTitle = pageTitleFor(view.title)
    val canonical = "$SITE_URL/topics/${view.slug}"

    head {
        commonHeadTags(pageTitle = pageTitle, description = view.summary, canonical = canonical, ogType = "article")

        script(type = "application/ld+json") {
            unsafe { +jsonLdGraph(listOf(learningResourceJsonLd(view, canonical), breadcrumbListJsonLd(view, canonical))) }
        }

        // The one script this page loads for real behaviour, and the only one that is not the
        // JSON-LD block above. External and deferred: no inline script, so no second `unsafe { }`
        // use anywhere on this page.
        script(src = "/topic-start.js") { attributes["defer"] = "defer" }
    }
    body {
        siteHeaderBar()

        main(classes = "wrap") {
            p(classes = "crumb") {
                a(href = "/") { +"mytetz" }
                +" › "
                +view.title
            }

            span(classes = "topic__eyebrow") { +view.category }
            h1 { +view.title }
            p(classes = "answer") { +(view.seedBody ?: view.summary) }

            section(classes = "start") {
                h2 { +"Start with this topic" }
                button {
                    attributes["type"] = "button"
                    attributes["id"] = "topic-start-button"
                    attributes["class"] = "start__cta"
                    attributes["data-topic-slug"] = view.slug
                    +"Start with this topic"
                }
                p {
                    attributes["id"] = "topic-start-error"
                    attributes["class"] = "start__error"
                    attributes["role"] = "alert"
                }
                noScript {
                    p { +"The Start with this topic button needs JavaScript." }
                }
            }

            if (view.relatedTopics.isNotEmpty()) {
                section {
                    h2 { +"Related topics" }
                    ul(classes = "start__list") {
                        view.relatedTopics.forEach { related ->
                            // A relative link: this page's own canonical tag, and the JSON-LD
                            // above, are the only values that carry the absolute mytetz.com URL.
                            li { a(href = "/topics/${related.slug}") { +related.title } }
                        }
                    }
                }
            }
        }

        siteFooter()
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
