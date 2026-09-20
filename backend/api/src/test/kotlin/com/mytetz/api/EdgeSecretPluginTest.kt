package com.mytetz.api

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The logger name `installEdgeSecret` writes under. Matches `BillingRoutesTest`'s own pattern. */
private const val EDGE_SECRET_LOGGER = "com.mytetz.api.EdgeSecret"

/**
 * A secret long enough to pass [EdgeSecretConfig]'s own length check, for every "check is on" test.
 *
 * Holds a letter in each case, so `.uppercase()` in `on, a value that differs only in case does
 * not pass` actually produces a different string. An all-digit secret would not.
 */
private const val TEST_SECRET = "MyTetzEdge-Secret-0123456789-aBc"

/**
 * `installEdgeSecret`, issue #68.
 *
 * This uses a stub app, on the model of `NoIndexPluginTest`. None of these tests need Mongo or a
 * model. Each test needs only the plugin and the paths it inspects.
 */
class EdgeSecretPluginTest {

    private fun Route.wireUp(calls: AtomicInteger) {
        get("/api/health") { call.respond(HttpStatusCode.OK, "ok") }
        get("/api/probe") {
            calls.incrementAndGet()
            call.respond(HttpStatusCode.OK, "probe")
        }
        post("/api/billing/webhook") {
            calls.incrementAndGet()
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // -------------------------------------------------- off means unchanged

    @Test
    fun `off with no secret gives an api request with no header the same answer as today`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = null))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, calls.get(), "the handler must still run while the check is off")
    }

    // -------------------------------------------------- on: the four header cases

    @Test
    fun `on, no header gives 403`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe")

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `on, a wrong header gives 403`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe") { header(EDGE_SECRET_HEADER, "wrong-value") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `on, the correct header value passes`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe") { header(EDGE_SECRET_HEADER, TEST_SECRET) }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `on, the header name in another letter case still passes`() = testApplication {
        // Header names have no case. "x-mytetz-edge" and "X-Mytetz-Edge" name the same header.
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe") { header("x-mytetz-edge", TEST_SECRET) }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `on, a value that differs only in case does not pass`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe") { header(EDGE_SECRET_HEADER, TEST_SECRET.uppercase()) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -------------------------------------------------- health stays open

    @Test
    fun `on, api health answers 200 with no header`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // -------------------------------------------------- the webhook is not an exception

    @Test
    fun `on, the webhook route with no edge header gives 403 and its handler never runs`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.post("/api/billing/webhook")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        // The handler never ran, so the signature check inside it never ran either.
        assertEquals(0, calls.get())
    }

    // -------------------------------------------------- a refusal reaches no handler

    @Test
    fun `a refused request never reaches the route handler`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        client.get("/api/probe")

        assertEquals(0, calls.get())
    }

    // -------------------------------------------------- configuration error

    @Test
    fun `a secret of 31 characters stops the start, and the message does not hold the value`() {
        val tooShort = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d" // 31 characters
        assertEquals(31, tooShort.length)

        val error = assertFailsWith<IllegalArgumentException> {
            EdgeSecretConfig(secret = tooShort)
        }

        assertFalse(error.message.orEmpty().contains(tooShort), "the message must not hold the value")
        assertTrue(error.message.orEmpty().contains(EdgeSecretConfig.EDGE_SECRET_ENV), "the message must name the variable")
    }

    // -------------------------------------------------- nothing sensitive is logged

    @Test
    fun `a refused request logs no header value and no secret`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val appender = attachAppender()
        try {
            client.get("/api/probe") { header(EDGE_SECRET_HEADER, "a-guessed-value") }
        } finally {
            detachAppender(appender)
        }

        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.none { it.contains("a-guessed-value") }, "the log held the caller's header value: $lines")
        assertTrue(lines.none { it.contains(TEST_SECRET) }, "the log held the real secret: $lines")
    }

    // ------------------------------------------------------------------ log capture
    //
    // This uses the same technique as `BillingRoutesTest`. A `ListAppender` attaches straight to
    // the plugin's own logger. Each test detaches it again once it reads the log, so one test's
    // appender never sees another test's log line.

    private fun attachAppender(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(EDGE_SECRET_LOGGER) as ch.qos.logback.classic.Logger).addAppender(appender)
        return appender
    }

    private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
        (LoggerFactory.getLogger(EDGE_SECRET_LOGGER) as ch.qos.logback.classic.Logger).detachAppender(appender)
    }
}
