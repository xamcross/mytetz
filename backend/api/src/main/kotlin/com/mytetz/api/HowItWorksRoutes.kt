package com.mytetz.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.ul
import kotlinx.html.unsafe

private const val HOW_IT_WORKS_CANONICAL = "$SITE_URL/how-it-works"
private const val HOW_IT_WORKS_TITLE = "How mytetz writes an explanation | mytetz"
private const val HOW_IT_WORKS_DESCRIPTION =
    "A language model writes every explanation on mytetz. This page names the model, the known " +
        "limits, and how to report a problem."

/** The only contact this page can name today: the project's own public GitHub repository. See
 * this file's own KDoc, "Why the contact is a GitHub link and not an email address". */
private const val GITHUB_URL = "https://github.com/xamcross/mytetz"

/**
 * `GET /how-it-works`. States, in plain text: a model writes every explanation, which model, the
 * known limits, and a contact — the transparency signals Google's own guidance on AI-generated
 * content names: https://developers.google.com/search/blog/2023/02/google-search-and-ai-content
 *
 * ## Why the contact is a GitHub link and not an email address
 *
 * Issue #47 asks for "a contact". `frontend/src/app/legal/imprint-page.component.ts` and
 * `frontend/src/app/legal/privacy-page.component.ts` both carry a `[owner text]` placeholder for
 * every contact detail — no file in this repository publishes a real email address, a name or a
 * postal address for mytetz. Inventing one would put a false fact on the one page whose purpose
 * is honesty about how this site works. `JsonLd.kt`'s own `organizationJsonLd()` already publishes
 * one true, working contact channel: the project's GitHub repository, `sameAs`'d from every page
 * that carries the `Organization` node. This page reuses that same fact as its contact, and names
 * no person.
 *
 * ## Why "a person reviews each explanation before publication" is not stated here
 *
 * Issue #47 step 1 asks this page to state "that a person reads each seed before publication".
 * That is not true today, confirmed by reading `Components.prewarm()` and
 * `CatalogService.seedFromResource()`: a topic is `PUBLISHED` straight from `topics.json`, with
 * no draft gate, and `Components.bootstrap()` then pre-warms its seed automatically, on every
 * boot, with no review step in between. Issue #18 (closed 2026-09-19) asked for one check: "the
 * implementer reads every generated seed from the live store... after the pre-warm". That was a
 * one-time check for that issue's own catalogue growth, run after the seed was already live. It
 * is not a repeatable gate the code enforces for every topic. This page states the true, narrower
 * fact instead, under "The known limits": no person checks a new explanation before mytetz shows
 * it to a reader. See this task's own report for the full evidence.
 *
 * The `Organization` JSON-LD reuses `organizationJsonLd()` and `jsonLdDocument()` from `JsonLd.kt`
 * — the same node `/` carries — rather than building a second copy.
 */
fun Route.howItWorksRoutes(modelId: () -> String) {
    get("/how-it-works") {
        call.respondHtml(HttpStatusCode.OK) { howItWorksHtml(modelId()) }
    }
}

/**
 * Renders the how-it-works page.
 *
 * Every text value below reaches the page through `+value`, and never through `unsafe { }` —
 * `TopicPageHtml.kt`'s own rule for the topic page. The one `unsafe` block wraps
 * [jsonLdDocument]'s own output, which [JsonLd.kt] already made safe to embed. This page shares
 * [commonHeadTags], [siteHeaderBar] and [siteFooter] with `TopicPageHtml.kt`, so the two pages
 * carry one layout and not two copies of it.
 */
private fun HTML.howItWorksHtml(modelId: String) {
    // Set before the first child — see TopicPageHtml.kt's own note on why.
    lang = "en"

    head {
        commonHeadTags(
            pageTitle = HOW_IT_WORKS_TITLE,
            description = HOW_IT_WORKS_DESCRIPTION,
            canonical = HOW_IT_WORKS_CANONICAL,
            ogType = "website",
        )
        script(type = "application/ld+json") {
            unsafe { +jsonLdDocument(organizationJsonLd()) }
        }
    }
    body {
        siteHeaderBar()

        main(classes = "wrap") {
            p(classes = "crumb") {
                a(href = "/") { +"mytetz" }
                +" › "
                +"How it works"
            }

            h1 { +"How mytetz writes an explanation" }

            p(classes = "answer") {
                +"A language model writes every explanation on this site. The current model is "
                +modelId
                +"."
            }

            h2 { +"The known limits" }
            ul {
                li { +"The model can make a mistake." }
                li { +"The model can state a claim with no source." }
                li { +"No person checks a new explanation before mytetz shows it to a reader." }
            }

            h2 { +"The review date" }
            p { +"Each topic page shows the date a person last reviewed its text, when mytetz has that date." }

            h2 { +"Contact" }
            p {
                +"Report a problem with an explanation on "
                a(href = GITHUB_URL) { +"GitHub" }
                +"."
            }
        }

        siteFooter()
    }
}
