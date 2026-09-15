package com.mytetz.api

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.header

/** The header name that tells a crawler not to index a page. */
internal const val X_ROBOTS_TAG: String = "X-Robots-Tag"

/** The value [X_ROBOTS_TAG] carries on a page this app does not want indexed. */
internal const val NOINDEX: String = "noindex"

/**
 * The fly.io hostname. A crawler must not index it: it serves the same content as `mytetz.com`, so
 * an indexed copy is a duplicate, and `docs/deploy.md` section 5 uses it to tell a Cloudflare fault
 * from an app fault, so it must keep answering `/api/health` directly rather than redirect.
 */
internal const val FLY_HOST: String = "mytetz.fly.dev"

/**
 * The path prefixes that a crawler must not index, on any host.
 *
 * `/learn/` holds a session URL, which only its owner can read (see `SessionRoutes.kt`); a visitor
 * without that session sees an error page. `/account` is the signed-in account page. `/auth` is the
 * sign-in landing page. None of the three is a page a search result should send a stranger to.
 */
private val NOINDEX_PATH_PREFIXES: List<String> = listOf("/learn/", "/account", "/auth")

/**
 * Reports true when a request for [path] on [host] must carry [NOINDEX].
 *
 * A path under `/api/` never qualifies. That check runs first and wins over the other two, so a
 * `mytetz.fly.dev` request for `/api/health` stays exactly as it is today — see the KDoc on
 * [FLY_HOST] for why that matters.
 */
internal fun shouldNoIndex(path: String, host: String): Boolean {
    if (path.startsWith("/api/")) return false
    if (host.equals(FLY_HOST, ignoreCase = true)) return true
    return NOINDEX_PATH_PREFIXES.any { path.startsWith(it) }
}

/**
 * Sets [X_ROBOTS_TAG] on every response [shouldNoIndex] names.
 *
 * The check runs in the `Plugins` phase, ahead of routing, so the header is set the same way for a
 * `200` from a real route and a `404` from the SPA fallback. Reference:
 * https://developers.google.com/search/docs/crawling-indexing/robots-meta-tag
 */
fun Application.installNoIndex() {
    intercept(ApplicationCallPipeline.Plugins) {
        if (shouldNoIndex(call.request.path(), call.request.host())) {
            call.response.header(X_ROBOTS_TAG, NOINDEX)
        }
    }
}
