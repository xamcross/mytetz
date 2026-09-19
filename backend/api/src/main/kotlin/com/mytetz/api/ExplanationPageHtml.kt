package com.mytetz.api

import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.main
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.unsafe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Everything [explanationPageHtml] needs to render one public explanation page.
 *
 * `ExplanationPageRoutes.kt` builds this only for an `EXPLAIN` node, so [span] is always the
 * phrase a learner selected, never null — a `SEED` node has no span and this page never renders
 * one. [published] decides only the `noindex` header (set by the route, not this function) and
 * the presence of the canonical tag (spec section 7.3). It never decides whether the page renders
 * at all: a curator has not rejected the text by leaving it unpublished, only not yet reviewed it.
 */
data class ExplanationPageView(
    val topicSlug: String,
    val topicTitle: String,
    val span: String,
    val body: String,
    val shortKey: String,
    val published: Boolean,
)

/**
 * Renders one public explanation page: the three-item `BreadcrumbList` trail from home to the
 * topic to this node (spec section 11), the span as the `<h1>`, and the body text.
 *
 * Every text value below reaches the page through `+value` or an attribute assignment, and never
 * through `unsafe { }` — `kotlinx.html`'s stream writer escapes both by default. The one `unsafe`
 * block wraps [jsonLdGraph]'s own output, which is JSON and must not be escaped as HTML;
 * [jsonLdGraph] is what makes that block safe, per `JsonLd.kt`'s own KDoc.
 *
 * This function has no field and no branch for [com.mytetz.graph.Explanation.media]: a
 * `VISUALIZE` node carries an SVG in that field, and this issue does not render media on a public
 * page. `ExplanationPageRoutes.kt` never builds this view for a non-`EXPLAIN` node in the first
 * place, so the omission here is a second, independent guard and not the only one.
 *
 * Round 2: the page reuses the same layout functions the topic page and `/how-it-works` reuse —
 * [commonHeadTags], [siteHeaderBar] and [siteFooter], from `TopicPageHtml.kt` — and not a second
 * copy of that markup (the earlier `PublicPageChrome.kt` this file first used is deleted). An
 * unpublished page passes `emitCanonicalTag = false` to [commonHeadTags]: see that function's own
 * KDoc for why a page still carries `og:url` in that case.
 */
fun HTML.explanationPageHtml(view: ExplanationPageView) {
    // Set before the first child, for the same reason TopicPageHtml.kt's own topicPageHtml sets it
    // first: kotlinx.html's stream writer writes the <html> start tag when the first child opens.
    lang = "en"

    val pageTitle = "${view.span} explained | mytetz"
    val canonical = "$SITE_URL/topics/${view.topicSlug}/explain/${view.shortKey}"

    head {
        commonHeadTags(
            pageTitle = pageTitle,
            description = view.body,
            canonical = canonical,
            ogType = "article",
            emitCanonicalTag = view.published,
        )

        script(type = "application/ld+json") {
            unsafe { +jsonLdGraph(listOf(explanationBreadcrumbJsonLd(view, canonical))) }
        }
    }
    body {
        siteHeaderBar()
        main(classes = "wrap") {
            p(classes = "crumb") {
                a(href = "/") { +"mytetz" }
                +" › "
                a(href = "/topics/${view.topicSlug}") { +view.topicTitle }
                +" › "
                +view.span
            }
            h1 { +view.span }
            p(classes = "answer") { +view.body }
        }
        siteFooter()
    }
}

/**
 * `BreadcrumbList` (https://schema.org/BreadcrumbList), the three-item trail spec section 11
 * requires: home, the topic, then this explanation. Built with `buildJsonObject`, in this file,
 * for the same reason `TopicPageHtml.kt`'s own node builders are: it needs this page's own data,
 * which `JsonLd.kt` does not know about.
 */
private fun explanationBreadcrumbJsonLd(view: ExplanationPageView, canonical: String): JsonObject = buildJsonObject {
    put("@type", "BreadcrumbList")
    put(
        "itemListElement",
        buildJsonArray {
            add(buildJsonObject { put("@type", "ListItem"); put("position", 1); put("name", "mytetz"); put("item", SITE_URL) })
            add(
                buildJsonObject {
                    put("@type", "ListItem")
                    put("position", 2)
                    put("name", view.topicTitle)
                    put("item", "$SITE_URL/topics/${view.topicSlug}")
                },
            )
            add(buildJsonObject { put("@type", "ListItem"); put("position", 3); put("name", view.span); put("item", canonical) })
        },
    )
}
