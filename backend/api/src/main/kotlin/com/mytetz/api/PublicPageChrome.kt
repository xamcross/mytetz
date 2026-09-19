package com.mytetz.api

import kotlinx.html.BODY
import kotlinx.html.HEAD
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.header
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.nav

/** The stylesheet every guide page already links. A public page reuses it, and not a copy, so one
 * edit reaches every page outside the Angular application. See `guides.css`'s own header comment. */
private const val GUIDES_STYLESHEET = "/guides/guides.css"

/**
 * The `<head>` tags every public page in this plan shares: the charset, the viewport tag and the
 * shared stylesheet. See the plan's own "Corrections after phase 1", gap 2.
 *
 * `TopicPageHtml.kt` carries its own copy of these three tags, written in phase 1 before this
 * function existed. This function does not replace that copy: issue #48 keeps its own change to
 * `TopicPageHtml.kt` to one thing, the "Popular questions" list (spec section 6.3), so that file's
 * layout code stays untouched while issue #47 edits the same file at the same time. A later task
 * can fold `TopicPageHtml.kt` onto this function once both changes have landed.
 *
 * A page's own title, description, canonical tag (or its absence), Open Graph tags and JSON-LD
 * block differ per page, so each renderer writes those itself, after calling this function.
 */
internal fun HEAD.publicHeadBasics() {
    meta(charset = "utf-8")
    meta(name = "viewport", content = "width=device-width, initial-scale=1")
    link(rel = "stylesheet", href = GUIDES_STYLESHEET)
}

/** The header bar every public page in this plan shares with a guide page and with the topic page
 * (`TopicPageHtml.kt`'s own copy). See [publicHeadBasics]'s own KDoc for why this is a new,
 * separate function and not a change to that file. */
internal fun BODY.publicHeaderBar() {
    header(classes = "bar") {
        a(href = "/", classes = "bar__mark") { +"mytetz" }
        nav(classes = "bar__nav") {
            attributes["aria-label"] = "Main"
            a(href = "/") { +"Catalogue" }
            a(href = "/guides") { +"Guides" }
        }
    }
}

/**
 * The footer every public page in this plan shares. See [publicHeadBasics]'s own KDoc.
 *
 * This carries the same five links as `TopicPageHtml.kt`'s own footer, in the same order, and not
 * a sixth "Glossary" link — the topic page's footer does not have one either, and this issue does
 * not add one there, so the two footers must keep matching, character for character.
 */
internal fun BODY.publicFooterBar() {
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
