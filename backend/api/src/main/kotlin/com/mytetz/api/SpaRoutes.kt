package com.mytetz.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
 * The `Cache-Control` value for the static resource at [resourcePath].
 *
 * The value keys on the **resolved** path, and never on the URL a visitor typed. One file
 * therefore answers with one header, whether a request reads `/guides`, `/guides/` or
 * `/guides/index.html`.
 *
 * - The root `index.html` names the current deploy, so a shared cache must ask again on every
 *   visit.
 * - A hashed bundle keeps one name for one content forever, so a cache may keep it for a year.
 * - Any other `index.html` is a static content page, for example a guide under `/guides`. It
 *   carries no hashed name, so an edit reaches a visitor only when the cache expires. One hour
 *   keeps the page fresh without a request on every visit.
 * - Every other file — a font, an icon, `robots.txt`, `sitemap.xml`, `llms.txt` — is safe to keep
 *   for a day. One caveat: `guides.css` carries no hash either, so a change to it can reach a
 *   visitor up to a day after a change to the page that links it. Rename the file when a change
 *   to it must arrive with the page.
 */
internal fun cacheControlFor(resourcePath: String): String = when {
    resourcePath == "index.html" -> "no-cache"
    resourcePath.endsWith("/index.html") -> "public, max-age=3600"
    HASHED_BUNDLE.matches(resourcePath.substringAfterLast('/')) -> "public, max-age=31536000, immutable"
    else -> "public, max-age=86400"
}

/**
 * Answers [call] with the Angular shell, at [status].
 *
 * [spaRoutes]'s own unmatched-path branch uses this for its 404. `TopicPageRoutes.kt` uses the
 * same function for an unknown or an unpublished slug, so a learner who opens a bad `/topics/`
 * link sees the same page a bad link anywhere else on the site already shows, and not a second,
 * blank-bodied 404 that this project renders nowhere else.
 */
internal suspend fun respondSpaShell(call: ApplicationCall, status: HttpStatusCode) {
    val shell = call.resolveResource("index.html", STATIC_PACKAGE)
    if (shell == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    call.response.status(status)
    call.response.header(HttpHeaders.CacheControl, cacheControlFor("index.html"))
    call.respond(shell)
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
        // `trim('/')` folds `/guides/` onto `/guides`. The tailcard keeps the empty last segment
        // of a path that ends in a slash, and `resolveResource` rejects such a path outright.
        // Both URLs then answer one file, and each page's own canonical tag names the one URL a
        // search engine should keep.
        val requestedPath = call.parameters.getAll("spaFallbackPath")
            ?.joinToString("/")
            .orEmpty()
            .trim('/')
            .ifEmpty { "index.html" }

        // Ktor 3.1.2's `resolveResource` returns null for a path that names a folder: it tests
        // `isFile` for the `file` protocol and for the `jar` protocol, and it never appends
        // `index.html`. A clean URL such as `/guides/how-to-study-on-your-own` therefore needs the
        // second lookup. `staticResources(index = "index.html")` would do this, but 3.1.2 gives it
        // no `fallback` hook, so an unknown path under it would lose the shell — see this file's
        // own doc comment.
        val resolved = listOf(requestedPath, "$requestedPath/index.html")
            .firstNotNullOfOrNull { path ->
                call.resolveResource(path, STATIC_PACKAGE)?.let { path to it }
            }

        if (resolved != null) {
            val (resolvedPath, asset) = resolved
            call.response.header(HttpHeaders.CacheControl, cacheControlFor(resolvedPath))
            call.respond(asset)
            return@get
        }

        val status = if (SpaRoutes.matches(call.request.path())) HttpStatusCode.OK else HttpStatusCode.NotFound
        respondSpaShell(call, status)
    }
}
