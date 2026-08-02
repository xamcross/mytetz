package com.mytetz.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRoutesTest {
    @Test
    fun `health reports ok when mongo pings`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { healthRoutes(mongoPing = { true }) }
        }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"mongo\":true"))
    }

    @Test
    fun `health reports 503 when mongo is unreachable`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { healthRoutes(mongoPing = { false }) }
        }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `readiness is always present in the body, in both states`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(mongoPing = { true }, ready = { true })
                route("/not-ready") { healthRoutes(mongoPing = { true }, ready = { false }) }
            }
        }

        val ready = client.get("/api/health").bodyAsText()
        val booting = client.get("/not-ready/api/health").bodyAsText()

        // kotlinx.serialization omits a property equal to its declared default, so giving `ready` a
        // default of `true` made the field vanish in precisely the healthy case — a monitor would
        // see it only while it was false. Both states must be on the wire.
        assertTrue(ready.contains("\"ready\":true"), "readiness missing when ready: $ready")
        assertTrue(booting.contains("\"ready\":false"), "readiness missing while booting: $booting")
    }

    @Test
    fun `bootstrap not having finished does not make the machine look unhealthy`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { healthRoutes(mongoPing = { true }, ready = { false }) }
        }

        // A cold start is not a failure. Returning 503 here would fail fly's health check on every
        // scale-from-zero, which on this deployment is every idle period.
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
    }
}
