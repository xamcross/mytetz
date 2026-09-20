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
import kotlinx.html.svg
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
 * One popular question under a topic: a published `EXPLAIN` node's span, and the twelve-character
 * short key of its public explanation page. Issue #48, spec section 6.3.
 */
data class PopularQuestionView(val span: String, val shortKey: String)

/**
 * Everything [topicPageHtml] needs to render one topic page.
 *
 * [seedBody] is null when the graph holds no seed for this topic yet. See `TopicPageRoutes.kt`'s
 * own KDoc for why the route falls back to the summary alone, rather than generating one.
 *
 * [popularQuestions] defaults to empty, so every existing caller of this data class keeps
 * compiling with no change. `TopicPageRoutes.kt` fills it from
 * `ExplanationRepository.findPublishedByTopic`; it stays empty until this topic has at least one
 * published explanation (spec section 6.3), and [topicPageHtml] then renders no "Popular
 * questions" section at all, rather than an empty one.
 */
data class TopicPageView(
    val slug: String,
    val title: String,
    val category: String,
    val summary: String,
    val seedBody: String?,
    val relatedTopics: List<RelatedTopicView>,
    val popularQuestions: List<PopularQuestionView> = emptyList(),
    /**
     * When a person last confirmed this topic's text, in epoch milliseconds, or null when nobody
     * has yet. Null renders no "Last reviewed" line and no `dateModified` field — a missing date
     * must never show a false one. See `docs/superpowers/specs/2026-09-19-public-surface-design.md`
     * section 8.
     */
    val reviewedAt: Long? = null,
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
 * `TopicPageHtml.kt`, `HowItWorksRoutes.kt`, `ExplanationPageHtml.kt` and `GlossaryHtml.kt` all
 * call this function, so one edit reaches every page of this layout. [ogType] carries the one
 * Open Graph field that differs between callers: `"article"` for a topic page or an explanation
 * page, `"website"` for a page with no single author date, the same value
 * `frontend/src/index.html` and `frontend/public/guides/index.html` already use for a
 * non-article page.
 *
 * [emitCanonicalTag] defaults to `true`. `ExplanationPageRoutes.kt` passes `false` for an
 * unpublished `EXPLAIN` node: spec section 7.3 states a page must carry `og:url` — a visitor and a
 * crawler both still need to know this page's own address — but no `<link rel="canonical">`, so
 * that an unreviewed text is never offered to a search index as the one true copy of the page's
 * own URL.
 */
internal fun HEAD.commonHeadTags(
    pageTitle: String,
    description: String,
    canonical: String,
    ogType: String,
    emitCanonicalTag: Boolean = true,
) {
    meta(charset = "utf-8")
    meta(name = "viewport", content = "width=device-width, initial-scale=1")
    title { +pageTitle }
    meta(name = "description", content = description)
    if (emitCanonicalTag) link(rel = "canonical", href = canonical)
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

    // The one script that fills in the account control of siteHeaderBar() below, for every page
    // of this layout. External and deferred, never inline: see site-header.js's own header
    // comment for the full rule set. Placed in commonHeadTags, and not in siteHeaderBar() itself,
    // so it loads once per page and not once per call of the header function.
    script(src = "/site-header.js") { attributes["defer"] = "defer" }
}

/**
 * The mark's fixed inner shapes: two coral and teal bars and one elbow stroke, on a 32-unit grid.
 * Copied from `frontend/src/app/ui/logo-mark.component.ts`, whose own KDoc states the design.
 *
 * A constant string, with no variable part, and never built from a value this page receives:
 * `kotlinx.html`'s SVG tag builds no `rect` or `path` child of its own, so this is the one
 * `unsafe { }` block on this page that does not wrap [jsonLdGraph]'s output. Each colour is a CSS
 * custom property `guides.css` defines under `:root`, the same tokens the Angular mark paints
 * with, so the two marks stay the same colour with no value repeated here.
 */
private const val LOGO_MARK_INNER_SVG = """<rect x="0" y="0" width="32" height="32" rx="10" fill="var(--mt-chip)" /><rect x="5" y="7" width="17" height="6" rx="3" fill="var(--mt-coral)" /><path d="M9 15V22H13" fill="none" stroke="var(--mt-teal)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /><rect x="13" y="19" width="14" height="6" rx="3" fill="var(--mt-teal)" />"""

/**
 * The 64px header bar every page of this layout shares: the topic page, the glossary page, the
 * FAQ page, the how-it-works page, the explanation page, and, by hand, each of the 13 static guide
 * pages under `frontend/public/guides`.
 *
 * A Ktor page is a pure read (issue #45): it never calls `Principals.resolve` and it sets no
 * cookie, so this function alone can only ever render a visitor with no account: the "Sign in"
 * link, to `/auth`. `frontend/public/site-header.js`, loaded once per page in [commonHeadTags],
 * reads `GET /api/account` after the page loads and rewrites `#site-header-account` to "Account"
 * for a signed-in learner — the same order `AppShellComponent` itself renders in, before its own
 * first account answer arrives.
 *
 * `HeaderFooterParityTest` proves this function and every static guide page agree.
 * `frontend/src/app/ui/app-shell.component.spec.ts` proves the Angular side of the same list.
 *
 * Issue #143, corrected on review: the status dot next to the account control can carry a real
 * meaning on a public page after all. `GET /api/health` needs no sign-in and takes no session
 * state (see `HealthRoutesTest`'s own cookie test), so the same one request `app.ts` sends once
 * at start-up also works from a public page. The server always renders the "checking" state —
 * the state `app.ts` itself starts in, before its own first answer arrives — because a Ktor page
 * is a pure read (issue #45) and cannot know the backend's own health before the page ships.
 * `frontend/public/site-header.js` sends `GET /api/health` once per page view, independently of
 * its own `GET /api/account` call, and rewrites `#site-header-dot`'s class, `aria-label` and
 * `title` from the answer, with the same four states and the same four labels
 * `status-dot.component.ts` uses. A page with no JavaScript keeps the "checking" ring: an honest
 * state for a page that never asked the question, and not a claim of health it cannot back up.
 */
internal fun BODY.siteHeaderBar() {
    header(classes = "bar") {
        div(classes = "bar__left") {
            a(href = "/", classes = "bar__mark") {
                attributes["aria-label"] = "mytetz"
                svg(classes = "bar__mark-icon") {
                    attributes["viewBox"] = "0 0 32 32"
                    attributes["aria-hidden"] = "true"
                    attributes["focusable"] = "false"
                    unsafe { +LOGO_MARK_INNER_SVG }
                }
                // Issue #133's own rule on the application mark: the text goes out of view below
                // 360px, and the link's own aria-label keeps its name for a screen reader at
                // every width. See guides.css's own `.bar__mark-text` rule.
                span(classes = "bar__mark-text") { +"mytetz" }
            }
            nav(classes = "bar__nav") {
                attributes["aria-label"] = "Main"
                a(href = "/glossary", classes = "bar__link") { +"Glossary" }
                a(href = "/guides", classes = "bar__link") { +"Guides" }
            }
        }
        div(classes = "bar__right") {
            a(href = "/auth", classes = "bar__link bar__account") {
                attributes["id"] = "site-header-account"
                +"Sign in"
            }
            span(classes = "bar__count") { attributes["id"] = "site-header-count" }
            span(classes = "dot dot--checking") {
                attributes["id"] = "site-header-dot"
                attributes["role"] = "img"
                attributes["aria-label"] = "Backend: checking"
                attributes["title"] = "Backend: checking"
            }
        }
    }
}

/**
 * The footer every page of this layout shares, with the same six links and the same look as
 * `AppShellComponent`'s own `.foot`: Guides, How it works, FAQ, Privacy, Terms and Imprint, no
 * "Catalogue", and no underline. Flat `<a>` children of `<footer>`, with no wrapping `<nav>` or
 * `<div>` — the same shape `AppShellComponent`'s own footer has — so `.foot`'s own centred,
 * wrapping flex row lays out these links exactly as it lays out the Angular footer's links.
 */
internal fun BODY.siteFooter() {
    footer(classes = "foot") {
        attributes["aria-label"] = "Footer"
        a(href = "/guides", classes = "foot__link") { +"Guides" }
        a(href = "/how-it-works", classes = "foot__link") { +"How it works" }
        a(href = "/faq", classes = "foot__link") { +"FAQ" }
        a(href = "/privacy", classes = "foot__link") { +"Privacy" }
        a(href = "/terms", classes = "foot__link") { +"Terms" }
        a(href = "/imprint", classes = "foot__link") { +"Imprint" }
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
 * The "Start with this topic" control is a `<button>`, an empty alert paragraph, a hidden script
 * hint paragraph, and a `<noscript>` sentence, all rendered here through the normal escaped DSL
 * calls. Its behaviour lives in one external file, `frontend/public/topic-start.js`, loaded with
 * `defer` — never an inline `<script>` — so the one `unsafe { }` block on this page stays the
 * JSON-LD block alone.
 *
 * Issue #161: the button ships `disabled`. A learner cannot click it before `topic-start.js`
 * attaches its own click handler, so a click during that gap — a slow connection, or a script
 * request that never finishes — starts nothing, rather than reaching a dead handler. The script
 * hint paragraph carries a fixed sentence for the case the script never runs at all: `guides.css`'s
 * own `.start__script-hint` rule keeps it out of sight for a few seconds, with no script of any
 * kind, and reveals it only if nothing has hidden it by then. `topic-start.js` hides it, and enables
 * the button, the moment its own click handler is live — see that file's own header comment.
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
            // No date means no line, and never a false one — see TopicPageView.reviewedAt's own KDoc.
            view.reviewedAt?.let { reviewedAt -> p(classes = "topic__reviewed") { +"Last reviewed ${isoDate(reviewedAt)}" } }

            section(classes = "start") {
                h2 { +"Start with this topic" }
                button {
                    attributes["type"] = "button"
                    attributes["id"] = "topic-start-button"
                    attributes["class"] = "start__cta"
                    attributes["data-topic-slug"] = view.slug
                    attributes["disabled"] = "disabled"
                    +"Start with this topic"
                }
                p {
                    attributes["id"] = "topic-start-error"
                    attributes["class"] = "start__error"
                    attributes["role"] = "alert"
                }
                p {
                    attributes["id"] = "topic-start-script-hint"
                    attributes["class"] = "start__script-hint"
                    attributes["role"] = "status"
                    +"This button needs a script that did not load. Load the page again."
                }
                noScript {
                    p { +"The Start with this topic button needs JavaScript." }
                }
            }

            // Spec section 6.3: this list, when it has any entry, comes before "Related topics".
            // Issue #48's own change to this file stays this one section — see this data class's
            // own KDoc and PublicPageChrome.kt's own note on why the rest of the file is untouched.
            if (view.popularQuestions.isNotEmpty()) {
                section {
                    h2 { +"Popular questions" }
                    ul(classes = "start__list") {
                        view.popularQuestions.forEach { question ->
                            li {
                                a(href = "/topics/${view.slug}/explain/${question.shortKey}") { +question.span }
                            }
                        }
                    }
                    // Round 2: a link to the full glossary, after the list. This section only
                    // renders when at least one question is published, so this link never leads
                    // a visitor to the glossary's own empty state.
                    p { a(href = "/glossary") { +"See every published answer in the glossary" } }
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
    view.reviewedAt?.let { put("dateModified", isoDate(it)) }
}

/**
 * Formats [epochMillis] as a plain UTC calendar date, `yyyy-MM-dd`.
 *
 * Both the visible "Last reviewed" text and the JSON-LD `dateModified` field call this one
 * function, so the two always name the same day. UTC, and not the server's own time zone: a date
 * a machine in one region renders must read the same on a machine in another region.
 */
internal fun isoDate(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()

/** `BreadcrumbList` (https://schema.org/BreadcrumbList), with the two required `ListItem` entries
 * per spec section 11: home, then this topic. */
private fun breadcrumbListJsonLd(view: TopicPageView, canonical: String): JsonObject = buildJsonObject {
    put("@type", "BreadcrumbList")
    put("itemListElement", buildJsonArray {
        add(buildJsonObject { put("@type", "ListItem"); put("position", 1); put("name", "mytetz"); put("item", SITE_URL) })
        add(buildJsonObject { put("@type", "ListItem"); put("position", 2); put("name", view.title); put("item", canonical) })
    })
}
