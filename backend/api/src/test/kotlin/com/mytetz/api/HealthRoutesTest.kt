package com.mytetz.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
}
