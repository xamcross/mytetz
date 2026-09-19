package com.mytetz.api

import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.MAX_PUBLISHED_EXPLANATIONS

/** One published explanation page, ready for a sitemap `<url>` entry. */
data class PublicExplanationUrl(val topicSlug: String, val shortKey: String, val createdAtEpochMillis: Long) {
    val path: String get() = "/topics/$topicSlug/explain/$shortKey"
}

/**
 * The list of public explanation page URLs for the sitemap (spec section 7.4), capped hard at
 * [cap] entries.
 *
 * **The join with `SitemapRoutes.kt`.** Issue #46 built `GET /sitemap.xml` on its own branch,
 * `issue-46-sitemap-route`, in parallel with this issue. This function is the piece Task 4.6 could
 * deliver before the two branches merged; `SitemapRoutes.kt`'s own `sitemapRoutes` function now
 * calls it and adds one `<url>` per [PublicExplanationUrl.path], with `<lastmod>` from
 * [createdAtEpochMillis] through [lastModifiedFor] — the same function `sitemapRoutes` already uses
 * for a topic's own `<lastmod>`.
 *
 * **Why the cap is enforced here too, and not only in the review script.**
 * `ExplanationRepository.findPublished` should already return at most [MAX_PUBLISHED_EXPLANATIONS]
 * rows, because `scripts/PublishTopExplanations.kt` is the only writer of `published = true` and it
 * enforces the same cap on the way in (see that file's own KDoc). This function enforces the cap
 * again, on the way out, as a second, independent line of defence: a later bug in the write path,
 * or a document a human edits by hand in Mongo, must not grow the sitemap past the cap this project
 * committed to for Google's scaled-content-abuse policy (spec section 7.4). When more than [cap]
 * documents are published, the highest-demand ones are kept — an arbitrary, order-dependent subset
 * would make two runs of this function disagree about which explanation pages are indexable.
 */
suspend fun publicExplanationSitemapEntries(
    explanations: ExplanationRepository,
    cap: Int = MAX_PUBLISHED_EXPLANATIONS,
): List<PublicExplanationUrl> =
    explanations.findPublished()
        .sortedByDescending { it.requestCount }
        .take(cap)
        .map {
            PublicExplanationUrl(
                topicSlug = it.topicSlug,
                shortKey = it.key.take(12),
                createdAtEpochMillis = it.createdAtEpochMillis,
            )
        }
