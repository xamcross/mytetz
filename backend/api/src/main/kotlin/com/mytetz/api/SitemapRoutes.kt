package com.mytetz.api

import com.mytetz.catalog.CatalogService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val SITE_URL = "https://mytetz.com"

/**
 * `GET /sitemap.xml`. Replaces the static file #32 shipped: a static file drifts from the
 * catalogue the moment an editor adds a topic, and #18 grows the catalogue past the 29 topics it
 * held on 2026-09-15.
 *
 * Lists the home page, one `<url>` for each published topic, and one `<url>` for each path in
 * [GuidePages.paths] — the one list [GuidePagesTest] already holds the shipped guide pages to. A
 * pure read: this function calls [CatalogService.listPublished] only, the same rule
 * [topicPageRoutes] follows for the same reason. Spec section 13.2's shared-cache rule needs a
 * response with no `Set-Cookie` header, so this route never calls `Principals.resolve` and never
 * touches a lazy model client. [SitemapRoutesTest] proves both with a real test.
 *
 * Follows https://www.sitemaps.org/protocol.html: `<loc>` is required, `<lastmod>` is optional, and
 * one file may hold up to 50,000 URLs. The catalogue stays far below that limit even at the
 * 100-topic scale #18 targets.
 *
 * **No `<lastmod>` yet, a recorded deviation from the approved spec.** Spec section 8.2 of
 * `docs/superpowers/specs/2026-09-19-public-surface-design.md` asks this route to set `<lastmod>`
 * from the seed explanation's `createdAtEpochMillis` until #47 adds `Topic.reviewedAt`. This route
 * omits `<lastmod>` instead, for the reason spec section 8.1 itself gives for adding `reviewedAt`
 * in the first place: a model migration regenerates every seed
 * (`2026-08-07-monetization-design.md`, section 13), which resets `createdAtEpochMillis` for every
 * topic on that one day, whether or not a person reviewed the new text. A sitemap that then claims
 * a same-day change for every URL states something false to a crawler, on the one field a crawler
 * uses to decide whether to re-read a page at all. A false date is worse than no date. Once #47
 * adds `Topic.reviewedAt`, this route should set `<lastmod>` from it, and only from it, for a topic
 * that carries one.
 */
fun Route.sitemapRoutes(catalog: CatalogService) {
    get("/sitemap.xml") {
        val topics = catalog.listPublished(category = null, query = null)
        val urls = buildList {
            add("$SITE_URL/")
            addAll(topics.map { "$SITE_URL/topics/${it.slug}" })
            addAll(GuidePages.paths.map { SITE_URL + it })
        }

        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(sitemapXml(urls), ContentType.Application.Xml)
    }
}

/**
 * Builds one `sitemap.xml` document out of a plain list of absolute URLs, with no catalogue read
 * and no database read of its own.
 *
 * A separate function, and not folded into [sitemapRoutes], on purpose: #48 adds one `<url>` per
 * published explanation page, capped at 100 pages. That change needs only its own list of
 * explanation URLs, appended to the list [sitemapRoutes] already builds — this function itself
 * stays the same.
 */
internal fun sitemapXml(urls: List<String>): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""")
    append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
    for (url in urls) {
        append("<url><loc>")
        append(xmlEscape(url))
        append("</loc></url>")
    }
    append("</urlset>")
}

/**
 * Escapes the five characters XML 1.0 reserves inside element text: `&`, `<`, `>`, `"` and `'`.
 *
 * A topic's slug comes from the catalogue, and not from a fixed, hand-checked list, so a slug that
 * carries one of these characters must not break the `<loc>` element it sits inside. This function
 * escapes each character on its own single pass over [value], so no earlier substitution's own `&`
 * character is ever escaped a second time. [SitemapRoutesTest] proves the result with a hostile
 * slug, parsed back with a namespace-aware XML parser.
 */
internal fun xmlEscape(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}
