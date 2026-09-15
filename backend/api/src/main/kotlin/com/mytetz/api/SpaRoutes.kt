package com.mytetz.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.resolveResource
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The path values from `frontend/src/app/app.routes.ts`.
 *
 * `SpaRoutesConsistencyTest` reads that file and compares its list to this one. A change to one
 * file alone fails that test.
 */
internal object SpaRoutes {
    val paths: List<String> = listOf(
        "",
        "auth",
        "account",
        "learn/:sessionId",
        "privacy",
        "terms",
        "imprint",
    )

    /** Reports true when [requestPath] names an Angular route in [paths]. */
    fun matches(requestPath: String): Boolean {
        val requestSegments = requestPath.toSegments()
        return paths.any { it.toSegments().matchesSegments(requestSegments) }
    }

    private fun String.toSegments(): List<String> = split('/').filter(String::isNotEmpty)

    private fun List<String>.matchesSegments(requestSegments: List<String>): Boolean =
        size == requestSegments.size &&
            zip(requestSegments).all { (patternSegment, requestSegment) ->
                patternSegment.startsWith(":") || patternSegment == requestSegment
            }
}

private const val STATIC_PACKAGE = "static"

/** A hashed bundle name from `outputHashing: "all"` in `frontend/angular.json`. */
private val HASHED_BUNDLE = Regex("""(main|chunk)-.*\.js|styles-.*\.css""")

/**
 * The `Cache-Control` value for a static file named [fileName].
 *
 * A hashed bundle keeps one name for one content forever, so the browser and Cloudflare may keep
 * it for a year. `index.html` names the current deploy, so a shared cache must ask again on every
 * visit. Every other file — a font, an icon, `robots.txt`, `sitemap.xml`, `llms.txt` — is safe to
 * keep for a day.
 */
internal fun cacheControlFor(fileName: String): String = when {
    fileName == "index.html" -> "no-cache"
    HASHED_BUNDLE.matches(fileName) -> "public, max-age=31536000, immutable"
    else -> "public, max-age=86400"
}

/**
 * Serves the built Angular files, and gives a real 404 for a path that no route matches.
 *
 * Ktor 3.1.2 has no `fallback` hook for `staticResources`. Ktor adds one in 3.3. This function is
 * the workaround for the pinned version.
 *
 * A real static file, for example a script or a stylesheet, answers as itself. A path in
 * [SpaRoutes.paths] answers the shell with status 200. Every other path answers the shell with
 * status 404, so a search engine drops it rather than indexing an empty page as real content.
 */
fun Route.spaRoutes() {
    get("{spaFallbackPath...}") {
        val relativePath = call.parameters.getAll("spaFallbackPath")
            ?.joinToString("/")
            .orEmpty()
            .ifEmpty { "index.html" }

        val asset = call.resolveResource(relativePath, STATIC_PACKAGE)
        if (asset != null) {
            call.response.header(HttpHeaders.CacheControl, cacheControlFor(relativePath.substringAfterLast('/')))
            call.respond(asset)
            return@get
        }

        val shell = call.resolveResource("index.html", STATIC_PACKAGE)
        if (shell == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val status = if (SpaRoutes.matches(call.request.path())) HttpStatusCode.OK else HttpStatusCode.NotFound
        call.response.status(status)
        call.response.header(HttpHeaders.CacheControl, cacheControlFor("index.html"))
        call.respond(shell)
    }
}
