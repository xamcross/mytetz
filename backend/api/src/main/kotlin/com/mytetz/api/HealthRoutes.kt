package com.mytetz.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val mongo: Boolean)

fun Route.healthRoutes(mongoPing: suspend () -> Boolean) {
    get("/api/health") {
        val ok = mongoPing()
        call.respond(
            if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(status = if (ok) "ok" else "degraded", mongo = ok),
        )
    }
}
