package com.mytetz.graph

/**
 * The hard cap on indexable, published explanation pages (spec section 7.4 of
 * `docs/superpowers/specs/2026-09-19-public-surface-design.md`). It keeps the site inside Google's
 * scaled-content-abuse policy: a capped, human-reviewed set of pages is a curated set, not a
 * scaled one.
 *
 * Two independent call sites enforce this same value, on purpose: `scripts/PublishTopExplanations.kt`
 * (the write path — never publishes past the cap) and the sitemap's own list of public explanation
 * URLs (the read path — never lists more than the cap, even if a document is edited by hand). A bug
 * in one must not let the other exceed the cap.
 */
const val MAX_PUBLISHED_EXPLANATIONS: Int = 100
