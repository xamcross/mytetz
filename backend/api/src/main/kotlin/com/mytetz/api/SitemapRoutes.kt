package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.graph.ContentKey
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.GraphConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val SITE_URL = "https://mytetz.com"

/**
 * `GET /sitemap.xml`. Replaces the static file #32 shipped: a static file drifts from the
 * catalogue the moment an editor adds a topic, and #18 grows the catalogue past the 29 topics it
 * held on 2026-09-15.
 *
 * Lists the home page, one `<url>` for each published topic, and one `<url>` for each path in
 * [GuidePages.paths] — the one list [GuidePagesTest] already holds the shipped guide pages to. A
 * pure read: this function calls [CatalogService.listPublished] and [ExplanationRepository.findByKey]
 * only, the same rule [topicPageRoutes] follows for the same reason. Spec section 13.2's
 * shared-cache rule needs a response with no `Set-Cookie` header, so this route never calls
 * `Principals.resolve` and never touches a lazy model client. [SitemapRoutesTest] proves both with
 * a real test.
 *
 * Follows https://www.sitemaps.org/protocol.html: `<loc>` is required, `<lastmod>` is optional, and
 * one file may hold up to 50,000 URLs. The catalogue stays far below that limit even at the
 * 100-topic scale #18 targets.
 *
 * **`<lastmod>` for a topic, per spec sections 8.2 and 10.1.** Each published topic's `<url>` gets
 * a `<lastmod>` built from its seed explanation's [com.mytetz.graph.Explanation.createdAtEpochMillis],
 * read the same way [topicPageRoutes] reads it: [ContentKey.seed] and then
 * [ExplanationRepository.findByKey]. A topic with no stored seed yet gets no `<lastmod>` — this
 * route never generates one to fill the gap. [lastModifiedFor] also takes a review timestamp, for
 * #47's `Topic.reviewedAt`; this route passes `null` for it today, because that field does not
 * exist on `main` yet. Once #47 lands, the caller here should pass `topic.reviewedAt` in its place,
 * so the sitemap starts reporting the later of the two dates, exactly as spec section 8.2 asks.
 * The home page and every guide path get no `<lastmod>`: neither one has a seed explanation or a
 * `Topic` row, so no true last-modified date exists for either.
 *
 * **Database reads for one request.** One [CatalogService.listPublished] call, plus one
 * [ExplanationRepository.findByKey] call for each published topic the first call returns.
 * [ExplanationRepository] has no read-many-keys-at-once method today, so this route reads one key
 * at a time; the response's `Cache-Control: public, max-age=3600` header keeps that cost to at most
 * one request per hour per edge cache, which is why a plain loop is acceptable at the 100-topic
 * scale #18 targets, and not a reason to add a batch read method here.
 */
fun Route.sitemapRoutes(
    catalog: CatalogService,
    explanations: ExplanationRepository,
    modelFamily: String,
    graphConfig: GraphConfig = GraphConfig(),
) {
    get("/sitemap.xml") {
        val topics = catalog.listPublished(category = null, query = null)
        val topicUrls = topics.map { topic ->
            val seedKey = ContentKey.seed(topic.slug, graphConfig.promptVersion, modelFamily)
            val seed = explanations.findByKey(seedKey)
            SitemapUrl(
                loc = "$SITE_URL/topics/${topic.slug}",
                // `reviewedAtEpochMillis` is `null` until #47 adds `Topic.reviewedAt` — see this
                // function's own KDoc.
                lastmod = lastModifiedFor(seed?.createdAtEpochMillis, reviewedAtEpochMillis = null),
            )
        }

        val urls = buildList {
            add(SitemapUrl(loc = "$SITE_URL/"))
            addAll(topicUrls)
            addAll(GuidePages.paths.map { SitemapUrl(loc = SITE_URL + it) })
        }

        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(sitemapXml(urls), ContentType.Application.Xml)
    }
}

/**
 * One `<url>` entry: the required `<loc>`, and the optional `<lastmod>` — `null` when no true
 * last-modified date exists for this URL.
 */
internal data class SitemapUrl(val loc: String, val lastmod: LocalDate? = null)

/**
 * Builds one `sitemap.xml` document out of a plain list of [SitemapUrl] entries, with no catalogue
 * read and no database read of its own.
 *
 * A separate function, and not folded into [sitemapRoutes], on purpose: #48 adds one `<url>` per
 * published explanation page, capped at 100 pages. That change needs only its own list of
 * [SitemapUrl] entries, appended to the list [sitemapRoutes] already builds — this function itself
 * stays the same.
 */
internal fun sitemapXml(urls: List<SitemapUrl>): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""")
    append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
    for (url in urls) {
        append("<url><loc>")
        append(xmlEscape(url.loc))
        append("</loc>")
        if (url.lastmod != null) {
            append("<lastmod>")
            // LocalDate.toString() is ISO-8601's calendar-date form, "YYYY-MM-DD" — the exact form
            // https://www.sitemaps.org/protocol.html's own W3C-datetime rule asks for.
            append(url.lastmod)
            append("</lastmod>")
        }
        append("</url>")
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

/**
 * The later of [seedCreatedAtEpochMillis] and [reviewedAtEpochMillis], as a calendar date in UTC —
 * or `null` when both are `null`, because no true last-modified date exists yet.
 *
 * Spec section 8.2 of `docs/superpowers/specs/2026-09-19-public-surface-design.md`: "#46's sitemap
 * route should use the **later** of `reviewedAt` and the seed's `createdAtEpochMillis` for
 * `lastmod`, once this field exists... Before `reviewedAt` exists, #46 uses `createdAtEpochMillis`
 * alone, exactly as section 10.1 already decides." [sitemapRoutes] calls this with `null` for
 * [reviewedAtEpochMillis] until #47 adds `Topic.reviewedAt`; once that field exists, the caller
 * should pass `topic.reviewedAt` in its place, and this function needs no change of its own.
 *
 * A calendar date, and not a full timestamp, because
 * https://www.sitemaps.org/protocol.html accepts the shorter W3C-datetime forms, and a crawler
 * cares whether a page changed on a given day, not at which second.
 *
 * `atZone(ZoneOffset.UTC)`, and not the system default zone: a Gradle test runner and a production
 * container do not always share one default time zone, and a `<lastmod>` that depends on the
 * machine that renders it is not a true date.
 */
internal fun lastModifiedFor(seedCreatedAtEpochMillis: Long?, reviewedAtEpochMillis: Long?): LocalDate? {
    val latestEpochMillis = listOfNotNull(seedCreatedAtEpochMillis, reviewedAtEpochMillis).maxOrNull()
        ?: return null
    return Instant.ofEpochMilli(latestEpochMillis).atZone(ZoneOffset.UTC).toLocalDate()
}
