package com.mytetz.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.resolveResource
import io.ktor.server.request.path
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
        call.respond(shell)
    }
}
