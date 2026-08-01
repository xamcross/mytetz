package com.mytetz.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRoutesTest {
    @Test
    fun `health reports ok when mongo pings`() = testApplication {
        application { routing { healthRoutes(mongoPing = { true }) } }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"mongo\":true"))
    }

    @Test
    fun `health reports 503 when mongo is unreachable`() = testApplication {
        application { routing { healthRoutes(mongoPing = { false }) } }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }
}
