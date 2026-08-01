package com.mytetz.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HealthResponse(val status: String, val mongo: Boolean)

fun Route.healthRoutes(mongoPing: suspend () -> Boolean) {
    get("/api/health") {
        val ok = mongoPing()
        call.respondText(
            text = Json.encodeToString(HealthResponse(status = if (ok) "ok" else "degraded", mongo = ok)),
            contentType = ContentType.Application.Json,
            status = if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }
}
