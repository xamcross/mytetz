package com.mytetz.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The route order from `Application.module()` — `/api/{...}` first, then [spaRoutes] — without
 * Mongo or a model. Both routes read only the classpath, which `:backend:api:processResources`
 * fills from the real Angular build (see `build.gradle.kts`), so this exercises the real shell.
 */
class SpaFallbackTest {

    private fun Route.wireUp() {
        route("/api/{...}") {
            handle { call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "no such endpoint")) }
        }
        spaRoutes()
    }

    @Test
    fun `a real Angular route answers the shell with status 200`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        assertEquals(HttpStatusCode.OK, client.get("/privacy").status)
    }

    @Test
    fun `a path no route matches answers the shell with status 404, not an empty body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        val shell = client.get("/privacy").bodyAsText()
        val response = client.get("/no-such-page")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(shell, response.bodyAsText(), "the 404 must still answer the SPA shell")
    }

    @Test
    fun `an unknown api path stays json, and does not fall through to the shell`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        val response = client.get("/api/no-such")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("NOT_FOUND"))
    }
}
