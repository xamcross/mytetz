package com.mytetz.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * [ready] reports whether `Components.bootstrap()` has finished — index creation and the catalogue
 * seed.
 *
 * It is reported separately from [mongo] and deliberately does **not** affect the status code. A
 * cold start is not an unhealthy machine, and 503 during one would fail fly's health check on every
 * scale-from-zero. It exists because bootstrap now runs in the background (see `Application.kt`), so
 * there is a brief window in which the catalogue may be empty; that window should be visible rather
 * than mysterious.
 */
// No default on `ready`, deliberately. kotlinx.serialization omits a property whose value equals its
// declared default (`encodeDefaults` is false unless configured otherwise), so `val ready: Boolean =
// true` disappears from the JSON in exactly the case that means "healthy" — a monitor would see
// `ready:false` during a cold start and then no field at all, and any check for `"ready":true` could
// never pass. A field that is sometimes absent is worse than no field.
@Serializable
data class HealthResponse(val status: String, val mongo: Boolean, val ready: Boolean)

fun Route.healthRoutes(mongoPing: suspend () -> Boolean, ready: () -> Boolean = { true }) {
    get("/api/health") {
        val ok = mongoPing()
        call.respond(
            if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(status = if (ok) "ok" else "degraded", mongo = ok, ready = ready()),
        )
    }
}
