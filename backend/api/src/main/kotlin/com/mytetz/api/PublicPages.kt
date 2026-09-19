package com.mytetz.api

/**
 * The public path of every Ktor-rendered page that is not a topic page and not a guide page.
 *
 * [sitemapRoutes] adds one `<url>` for each path here, with no `<lastmod>`: none of these pages
 * has a `Topic` row or a seed explanation, so no true last-modified date exists for any of them.
 *
 * `/how-it-works` (issue #47) and `/faq` (issue #121) are Ktor pages with no `Topic` row of their
 * own — the same reason [GuidePages.paths] is the one list its own callers read.
 */
internal object PublicPages {
    val paths: List<String> = listOf(
        "/how-it-works",
        "/faq",
    )
}
