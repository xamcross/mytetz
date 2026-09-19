package com.mytetz.api

import com.mytetz.billing.BillingConfig
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
import kotlinx.html.main
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.unsafe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val FAQ_CANONICAL = "$SITE_URL/faq"
private const val FAQ_TITLE = "Frequently asked questions | mytetz"
private const val FAQ_DESCRIPTION =
    "Plain answers about mytetz: the price, the trial, cancellation, sign-in, and what a " +
        "reader can read with no account."

/**
 * The monthly subscription price, in euro, for one reader.
 *
 * No configuration value holds this number. The price lives in the Freemius dashboard, and the
 * only source this project holds for it today is
 * `docs/superpowers/specs/2026-08-07-monetization-design.md:31`, "€10 each month". The owner
 * confirms this figure against the live Freemius dashboard in the pull request of issue #121,
 * before merge — see that issue's own "Owner steps", step 2. One named constant, next to the
 * renderer that uses it, so a later price change touches one line.
 */
internal const val FAQ_PRICE_EUR_PER_MONTH: Int = 10

/**
 * One "See ..." link placed under an answer paragraph, kept apart from [FaqEntry.answer] so the
 * visible paragraph and the JSON-LD `Answer.text` stay one string, with no risk that a link's own
 * words drift into one copy and not the other.
 */
data class FaqLink(val href: String, val label: String)

/**
 * One question and its answer.
 *
 * [answer] is the one string [faqHtml] renders as a plain paragraph, under an `<h2 id="[id]">`
 * heading, and the one string [faqPageJsonLd] places in the matching `Answer.text`. One list of
 * [FaqEntry] feeds both, through [faqEntries], so a reader and an answer engine can never read two
 * different answers for the one question — `FaqRoutesTest`'s own
 * `the JSON-LD FAQPage carries the same question and answer text as the page` pins this.
 */
data class FaqEntry(val id: String, val question: String, val answer: String, val link: FaqLink? = null)

/**
 * The questions this page answers, and the one place a number from [billingConfig] enters the
 * page text.
 *
 * [billingConfig] is the same [BillingConfig] instance [Components.billing] reads — see
 * `Components.billingConfig`'s own KDoc — so the trial length, the trial pool and the subscriber
 * allowance stated here can never disagree with the values the product actually enforces. This
 * function reads [billingConfig] once and computes no fallback of its own.
 *
 * Issue #121's own evidence table lists an eleventh question, "Which topics exist, and how does a
 * learner ask for a topic?" No control in
 * `frontend/src/app/catalog/catalog-page.component.ts` lets a learner ask for a topic today, so
 * this list leaves the question out rather than answer half of it. See this issue's own report
 * for the confirmation.
 */
internal fun faqEntries(billingConfig: BillingConfig): List<FaqEntry> = listOf(
    FaqEntry(
        id = "what-is-mytetz",
        question = "What is mytetz?",
        answer = "Mytetz is a web app that explains a topic in short, plain text. A reader " +
            "picks a topic and reads it. A highlight on any phrase gives more detail.",
    ),
    FaqEntry(
        id = "how-does-a-session-work",
        question = "How does a session work?",
        answer = "A reader opens a topic, highlights a phrase, and picks a verb: Explain it, " +
            "Dig deeper, Broader picture, Side view, or Show me a diagram.",
    ),
    FaqEntry(
        id = "is-mytetz-free",
        question = "Is mytetz free? What does the trial include?",
        answer = "A new reader gets a free trial with no credit card: " +
            "${billingConfig.trialGenerations} explanations over ${billingConfig.trialDays} days.",
    ),
    FaqEntry(
        id = "how-much-does-mytetz-cost",
        question = "How much does mytetz cost?",
        // The trial takes no card, so nothing bills a learner when the trial ends. A learner
        // subscribes through the checkout link of `POST /api/billing/checkout`. An earlier text
        // said that Freemius bills the subscriber "once the trial ends", which a reader takes as
        // an automatic payment. The review of issue #121 corrected it.
        answer = "Mytetz costs €$FAQ_PRICE_EUR_PER_MONTH each month. A learner subscribes through " +
            "the Freemius checkout. The trial needs no card, so no payment starts when the trial ends.",
        link = FaqLink(href = "/terms", label = "Terms"),
    ),
    FaqEntry(
        id = "how-many-explanations-does-a-subscriber-get",
        question = "How many explanations does a subscriber get?",
        answer = "A subscriber gets ${billingConfig.subscriberDailyExplains} explanations each " +
            "day. The count resets every day.",
    ),
    FaqEntry(
        id = "how-does-a-learner-cancel",
        question = "How does a learner cancel?",
        answer = "A subscriber opens the account page and selects Manage subscription to reach " +
            "the Freemius customer portal.",
    ),
    FaqEntry(
        id = "does-a-reader-need-an-account",
        question = "Does a reader need an account?",
        answer = "A reader reads the catalogue, a topic page and its seed text with no " +
            "account. A highlight in the reader needs a sign-in. A learner signs in with an " +
            "email magic link or with Google.",
    ),
    FaqEntry(
        id = "who-writes-the-explanations",
        question = "Who writes the explanations? Can an explanation be wrong?",
        answer = "A language model writes every explanation. It can make a mistake.",
        link = FaqLink(href = "/how-it-works", label = "How it works"),
    ),
    FaqEntry(
        id = "what-happens-to-the-data-of-a-learner",
        question = "What happens to the data of a learner, and how does a learner delete the account?",
        answer = "A learner can delete the account on the account page. That removes the account, " +
            "each reading session and each quiz attempt. An explanation stays on the site, because " +
            "other learners read the same text. It holds no personal data.",
        link = FaqLink(href = "/privacy", label = "Privacy policy"),
    ),
    FaqEntry(
        id = "who-runs-mytetz",
        question = "Who runs mytetz?",
        answer = "See the imprint for who runs mytetz.",
        link = FaqLink(href = "/imprint", label = "Imprint"),
    ),
)

/**
 * `GET /faq`. States the price, the trial, the subscriber allowance, cancellation, sign-in, and
 * what a reader can read with no account — the answers an answer engine and a search engine both
 * need for a query that names the brand.
 *
 * A pure read, deliberately, the same as `TopicPageRoutes.kt` and `HowItWorksRoutes.kt`: this
 * route never calls `Principals.resolve`, never sets a cookie, and never starts a generation. It
 * holds no user data and takes no request parameter, so hostile input has no path into this page
 * at all — every [FaqEntry] is a fixed string [faqEntries] defines, and [billingConfig] comes from
 * the environment the process already trusts. See `FaqRoutesTest`'s own
 * `the route builds no model client, and starts no generation`.
 */
fun Route.faqRoutes(billingConfig: BillingConfig) {
    get("/faq") {
        call.respondHtml(HttpStatusCode.OK) { faqHtml(faqEntries(billingConfig)) }
    }
}

/**
 * Renders the FAQ page.
 *
 * Every text value below reaches the page through `+value` or an attribute assignment, and never
 * through `unsafe { }` — the same rule `TopicPageHtml.kt` and `HowItWorksRoutes.kt` state. The one
 * `unsafe` block wraps [jsonLdGraph]'s own output, already made safe to embed by `JsonLd.kt`. This
 * page shares [commonHeadTags], [siteHeaderBar] and [siteFooter] with every other page of this
 * layout.
 */
private fun HTML.faqHtml(entries: List<FaqEntry>) {
    // Set before the first child — see TopicPageHtml.kt's own note on why.
    lang = "en"

    head {
        commonHeadTags(
            pageTitle = FAQ_TITLE,
            description = FAQ_DESCRIPTION,
            canonical = FAQ_CANONICAL,
            ogType = "website",
        )
        script(type = "application/ld+json") {
            unsafe { +jsonLdGraph(listOf(faqPageJsonLd(entries), faqBreadcrumbListJsonLd())) }
        }
    }
    body {
        siteHeaderBar()

        main(classes = "wrap") {
            p(classes = "crumb") {
                a(href = "/") { +"mytetz" }
                +" › "
                +"FAQ"
            }

            h1 { +"Frequently asked questions" }

            entries.forEach { entry ->
                h2 { attributes["id"] = entry.id; +entry.question }
                p(classes = "answer") { +entry.answer }
                entry.link?.let { link -> p { a(href = link.href) { +link.label } } }
            }
        }

        siteFooter()
    }
}

/**
 * `FAQPage` (https://schema.org/FAQPage), built with `buildJsonObject` and never with a string
 * template — see `JsonLd.kt`'s own KDoc for why. Each [FaqEntry] becomes one `Question` node with
 * one `Answer` child, and [FaqEntry.answer] is the exact string [faqHtml] also renders, so the two
 * never disagree.
 *
 * This node carries no `Offer` and no price. schema.org's `Question` and `Answer` types define no
 * price-related property, and `Offer` attaches to a `Product` or a `Service` node through
 * `priceSpecification` — confirmed by reading https://schema.org/FAQPage and
 * https://schema.org/UnitPriceSpecification before this function was written. This page has no
 * `Product` or `Service` node, so it states the price only in the visible answer text, rather than
 * guess a property name schema.org does not define for this shape.
 */
private fun faqPageJsonLd(entries: List<FaqEntry>): JsonObject = buildJsonObject {
    put("@type", "FAQPage")
    put("@id", "$FAQ_CANONICAL#faq")
    put("mainEntity", buildJsonArray {
        entries.forEach { entry ->
            add(
                buildJsonObject {
                    put("@type", "Question")
                    put("name", entry.question)
                    put(
                        "acceptedAnswer",
                        buildJsonObject {
                            put("@type", "Answer")
                            put("text", entry.answer)
                        },
                    )
                },
            )
        }
    })
}

/** `BreadcrumbList` (https://schema.org/BreadcrumbList), with the two required `ListItem` entries:
 * home, then this page. Mirrors `TopicPageHtml.kt`'s own `breadcrumbListJsonLd`. */
private fun faqBreadcrumbListJsonLd(): JsonObject = buildJsonObject {
    put("@type", "BreadcrumbList")
    put("itemListElement", buildJsonArray {
        add(buildJsonObject { put("@type", "ListItem"); put("position", 1); put("name", "mytetz"); put("item", SITE_URL) })
        add(buildJsonObject { put("@type", "ListItem"); put("position", 2); put("name", "FAQ"); put("item", FAQ_CANONICAL) })
    })
}
