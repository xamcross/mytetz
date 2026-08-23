package com.mytetz.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Turnstile] against a [MockEngine]: no socket, and no Cloudflare account.
 *
 * The endpoint, the field names and the `success` response field are confirmed against
 * Cloudflare's own server-side validation documentation — see [Turnstile]'s own KDoc — so
 * `the request Ktor sends matches the confirmed contract` is the test that matters most here, the
 * same reasoning `FreemiusApiClientTest` states for its own request-shape test.
 */
class TurnstileTest {

    private fun clientReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        val engine = MockEngine {
            respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HttpClient(engine)
    }

    @Test
    fun `turnstile is skipped when no secret is set`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                content = """{"success": false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val turnstile = Turnstile(HttpClient(engine), secretKey = null)

        val result = turnstile.verify(token = "irrelevant-with-no-secret-configured")

        assertTrue(result, "verification must be skipped when no secret is configured")
        assertEquals(0, calls, "a skipped verification must never call Cloudflare")
    }

    @Test
    fun `turnstile refuses a bad token when a secret is set`() = runTest {
        val turnstile = Turnstile(
            clientReturning("""{"success": false, "error-codes": ["invalid-input-response"]}"""),
            secretKey = "test-secret",
        )

        val result = turnstile.verify(token = "a-bad-token")

        assertFalse(result)
    }

    @Test
    fun `turnstile accepts a good token when a secret is set`() = runTest {
        val turnstile = Turnstile(
            clientReturning("""{"success": true, "challenge_ts": "2026-08-18T00:00:00Z", "hostname": "mytetz.com"}"""),
            secretKey = "test-secret",
        )

        val result = turnstile.verify(token = "a-good-token")

        assertTrue(result)
    }

    @Test
    fun `a null token is refused without a network call when a secret is set`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("""{"success": true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val turnstile = Turnstile(HttpClient(engine), secretKey = "test-secret")

        assertFalse(turnstile.verify(token = null))
        assertFalse(turnstile.verify(token = "   "))
        assertEquals(0, calls, "an absent token can never be a real answer; nothing to ask Cloudflare about")
    }

    @Test
    fun `the request Ktor sends matches the confirmed contract`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = """{"success": true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val turnstile = Turnstile(HttpClient(engine), secretKey = "the-secret")

        turnstile.verify(token = "the-token", remoteIp = "203.0.113.7")

        val request = requireNotNull(captured) { "no request reached the mock engine" }
        assertEquals("https://challenges.cloudflare.com/turnstile/v0/siteverify", request.url.toString())
        val body = (request.body as io.ktor.http.content.TextContent).text
        assertTrue(body.contains("secret=the-secret"))
        assertTrue(body.contains("response=the-token"))
        assertTrue(body.contains("remoteip=203.0.113.7"))
    }

    @Test
    fun `a non-2xx status is refused`() = runTest {
        val turnstile = Turnstile(clientReturning("", HttpStatusCode.InternalServerError), secretKey = "test-secret")

        assertFalse(turnstile.verify(token = "a-token"))
    }

    @Test
    fun `a request that does not complete is refused, not thrown`() = runTest {
        val engine = MockEngine { throw IOException("simulated network failure") }
        val turnstile = Turnstile(HttpClient(engine), secretKey = "test-secret")

        assertFalse(turnstile.verify(token = "a-token"))
    }

    @Test
    fun `an unparseable body is refused`() = runTest {
        val turnstile = Turnstile(clientReturning("not json"), secretKey = "test-secret")

        assertFalse(turnstile.verify(token = "a-token"))
    }
}
