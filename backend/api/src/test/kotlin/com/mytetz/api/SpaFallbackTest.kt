package com.mytetz.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
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

    @Test
    fun `a hashed bundle answers with a year of immutable caching`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        // `angular.json` hashes every `main-*.js` name to its content, so the real name is not
        // known ahead of time. `:backend:api:processResources` copies this same directory into
        // the `static` classpath resources this route reads. See `build.gradle.kts`.
        val browserDir = File("../../frontend/dist/frontend/browser")
        val mainBundle = browserDir.listFiles { file -> file.name.startsWith("main-") && file.name.endsWith(".js") }
            ?.firstOrNull()
        assertTrue(mainBundle != null, "cannot find a main-*.js bundle under ${browserDir.absolutePath}")

        val response = client.get("/${mainBundle.name}")

        assertEquals(
            "public, max-age=31536000, immutable",
            response.headers[HttpHeaders.CacheControl],
        )
    }

    @Test
    fun `the shell answers with no-cache`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        assertEquals("no-cache", client.get("/").headers[HttpHeaders.CacheControl])
        // The 404 fallback answers the same shell content, so it must carry the same header.
        assertEquals("no-cache", client.get("/no-such-page").headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `every other static file answers with a day of public caching`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        assertEquals(
            "public, max-age=86400",
            client.get("/robots.txt").headers[HttpHeaders.CacheControl],
        )
    }

    @Test
    fun `a folder that holds an index html answers that page, and not the shell`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        val response = client.get("/guides")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.bodyAsText().contains("<h1>Study guides</h1>"),
            "/guides must answer the guide hub, and not the Angular shell",
        )
    }

    @Test
    fun `a folder path with a trailing slash answers the same page`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        val withoutSlash = client.get("/guides")
        val withSlash = client.get("/guides/")

        assertEquals(HttpStatusCode.OK, withSlash.status)
        assertEquals(withoutSlash.bodyAsText(), withSlash.bodyAsText())
    }

    /**
     * Pins one real guide URL, which is an acceptance criterion of #63. A rename of the folder
     * fails here. So does a change of case: the runtime serves these files from inside `api.jar`,
     * and `JarFileContent` is case-sensitive, so `/Guides/...` would answer 200 on a Windows
     * developer machine and 404 in production.
     */
    @Test
    fun `a nested index html answers with an hour of public caching`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        val response = client.get("/guides/how-to-study-on-your-own")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("public, max-age=3600", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `the header does not depend on the URL a visitor typed`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        // Three URLs, one file. Before this route resolved a folder index, `/guides` took the
        // 86400 branch on the name `guides`, and `/guides/index.html` took the `no-cache` branch
        // that belongs to the shell alone.
        val expected = "public, max-age=3600"
        assertEquals(expected, client.get("/guides").headers[HttpHeaders.CacheControl])
        assertEquals(expected, client.get("/guides/").headers[HttpHeaders.CacheControl])
        assertEquals(expected, client.get("/guides/index.html").headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `a folder with no index html still answers 404`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wireUp() }
        }

        // `static/fonts` holds the four woff2 files and no `index.html`.
        assertEquals(HttpStatusCode.NotFound, client.get("/fonts").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/fonts/").status)
    }
}
