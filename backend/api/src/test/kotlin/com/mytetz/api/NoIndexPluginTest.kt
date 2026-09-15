package com.mytetz.api

import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A stub app that stands in for `Application.module()`'s route set, since none of these tests need
 * Mongo or a model — only [installNoIndex] and the path it inspects.
 */
private fun Route.wireUp() {
    get("/api/health") { call.respond(HttpStatusCode.OK, "ok") }
    get("/learn/{sessionId}") { call.respond(HttpStatusCode.OK, "shell") }
    get("/account") { call.respond(HttpStatusCode.OK, "shell") }
    get("/auth") { call.respond(HttpStatusCode.OK, "shell") }
    get("/") { call.respond(HttpStatusCode.OK, "shell") }
    get("/privacy") { call.respond(HttpStatusCode.OK, "shell") }
}

class NoIndexPluginTest {

    @Test
    fun `a session URL under learn carries noindex`() = testApplication {
        application {
            installNoIndex()
            routing { wireUp() }
        }

        assertEquals(NOINDEX, client.get("/learn/abc123").headers[X_ROBOTS_TAG])
    }

    @Test
    fun `the account page carries noindex`() = testApplication {
        application {
            installNoIndex()
            routing { wireUp() }
        }

        assertEquals(NOINDEX, client.get("/account").headers[X_ROBOTS_TAG])
    }

    @Test
    fun `the sign-in landing page carries noindex`() = testApplication {
        application {
            installNoIndex()
            routing { wireUp() }
        }

        assertEquals(NOINDEX, client.get("/auth").headers[X_ROBOTS_TAG])
    }

    @Test
    fun `every page on the fly dev host carries noindex`() = testApplication {
        application {
            installNoIndex()
            routing { wireUp() }
        }

        val response = client.get("/") { headers.append(HttpHeaders.Host, "mytetz.fly.dev") }

        assertEquals(NOINDEX, response.headers[X_ROBOTS_TAG])
    }

    @Test
    fun `an api response on the fly dev host is unchanged`() = testApplication {
        application {
            installNoIndex()
            routing { wireUp() }
        }

        val response = client.get("/api/health") { headers.append(HttpHeaders.Host, "mytetz.fly.dev") }

        assertNull(response.headers[X_ROBOTS_TAG], "an /api/* response must never carry noindex")
    }

    @Test
    fun `a public page on mytetz dot com carries no such header`() = testApplication {
        application {
            installNoIndex()
            routing { wireUp() }
        }

        val response = client.get("/privacy") { headers.append(HttpHeaders.Host, "mytetz.com") }

        assertNull(response.headers[X_ROBOTS_TAG])
    }
}
