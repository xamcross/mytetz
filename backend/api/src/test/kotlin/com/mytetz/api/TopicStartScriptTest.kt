package com.mytetz.api

import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds `/topic-start.js` to the same proof [GuidePagesTest] already gives every guide page: the
 * real, built Angular output copies every file under `frontend/public` into the `static` classpath
 * resources, so `spaRoutes()` serves this file the same way it serves a guide page's own
 * `index.html`. See `Application.kt`'s own `processResources` task for the copy step.
 */
class TopicStartScriptTest {

    @Test
    fun `GET topic-start js answers 200 with a JavaScript content type`() = testApplication {
        application { routing { spaRoutes() } }

        val response = client.get("/topic-start.js")

        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        assertTrue(
            "javascript" in contentType.lowercase(),
            "expected a JavaScript content type, got: '$contentType'",
        )
    }

    /**
     * Issue #143's own account-control script, `frontend/public/site-header.js`, served the same
     * way this file's own test above proves for `topic-start.js`.
     */
    @Test
    fun `GET site-header js answers 200 with a JavaScript content type`() = testApplication {
        application { routing { spaRoutes() } }

        val response = client.get("/site-header.js")

        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        assertTrue(
            "javascript" in contentType.lowercase(),
            "expected a JavaScript content type, got: '$contentType'",
        )
    }
}
