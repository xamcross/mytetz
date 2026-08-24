package com.mytetz.account

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives `ResendMailSender` against a real `HttpServer` on 127.0.0.1, the same way
 * `AnthropicLlmClientTest` drives the Anthropic SDK against one. Nothing here reaches the network
 * beyond loopback, and no API key is required to run this suite.
 */
class MailSenderTest {

    /** An `/emails` endpoint that always answers with [status] and [body]. */
    private fun mailServer(status: Int, body: String, onRequest: (String) -> Unit = {}): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/emails") { exchange ->
                onRequest(exchange.requestBody.use { it.readBytes() }.decodeToString())
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    /**
     * A client whose base URL points at [server]. `ResendMailSender` calls only the relative path
     * `/emails`, so this one setting is what redirects it away from the real Resend API.
     */
    private fun clientFor(server: HttpServer): HttpClient = HttpClient(CIO) {
        defaultRequest { url("http://127.0.0.1:${server.address.port}") }
    }

    // ------------------------------------------------------------------ MailConfig.resolveMode

    @Test
    fun `an unset mail mode is refused`() {
        assertFailsWith<IllegalStateException> { MailConfig.resolveMode(null) }
        assertFailsWith<IllegalStateException> { MailConfig.resolveMode("") }
        assertFailsWith<IllegalStateException> { MailConfig.resolveMode("   ") }
    }

    @Test
    fun `an unrecognised mail mode is refused`() {
        assertFailsWith<IllegalStateException> { MailConfig.resolveMode("smtp") }
        assertFailsWith<IllegalStateException> { MailConfig.resolveMode("resendish") }
        assertFailsWith<IllegalStateException> { MailConfig.resolveMode("logging") }
    }

    @Test
    fun `resend and log are accepted in any case`() {
        assertEquals("resend", MailConfig.resolveMode("resend"))
        assertEquals("resend", MailConfig.resolveMode("RESEND"))
        assertEquals("resend", MailConfig.resolveMode("  Resend  "))
        assertEquals("log", MailConfig.resolveMode("log"))
        assertEquals("log", MailConfig.resolveMode("LOG"))
        assertEquals("log", MailConfig.resolveMode("  Log  "))
    }

    // ------------------------------------------------------------------ MailConfig construction

    @Test
    fun `MailConfig holds the mode, key and sender it is given`() {
        val config = MailConfig(mode = "resend", apiKey = "re_test_key", from = "noreply@mytetz.example")

        assertEquals("resend", config.mode)
        assertEquals("re_test_key", config.apiKey)
        assertEquals("noreply@mytetz.example", config.from)
    }

    // ------------------------------------------------------------------ ResendMailSender

    @Test
    fun `a 200 from the provider sends without raising`() = runBlocking {
        val server = mailServer(200, """{"id":"4ef9350c-drill-test"}""")
        try {
            val sender = ResendMailSender(
                apiKey = "re_test_key_never_sent_anywhere_real",
                from = "noreply@mytetz.example",
                httpClient = clientFor(server),
            )
            // No assertion beyond "this does not throw": a 2xx answer is the whole contract here.
            sender.sendMagicLink("learner@example.com", "https://mytetz.example/magic?t=abc123")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a 401 from the provider raises and the message holds no key`() = runBlocking {
        val apiKey = "re_live_do_not_leak_this_value_9f8e7d6c5b4a"
        // A real Resend error body echoes the offending key back, which is exactly the second
        // route the class's KDoc warns about: the body is the hazard, not only the header.
        val body = """{"statusCode":401,"name":"validation_error","message":"Invalid API Key: $apiKey"}"""
        val server = mailServer(401, body)
        try {
            val sender = ResendMailSender(
                apiKey = apiKey,
                from = "noreply@mytetz.example",
                httpClient = clientFor(server),
            )

            val failure = assertFailsWith<MailSendFailedException> {
                sender.sendMagicLink("learner@example.com", "https://mytetz.example/magic?t=abc123")
            }

            val message = failure.message.orEmpty()
            assertFalse(apiKey in message, "the API key leaked into the exception message: $message")
            assertFalse(body in message, "the response body leaked into the exception message: $message")
            assertTrue("401" in message, "the status code should be named in the message, was: $message")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a 500 from the provider raises`() = runBlocking {
        val server = mailServer(500, """{"statusCode":500,"name":"internal_server_error","message":"oops"}""")
        val failure: MailSendFailedException
        try {
            val sender = ResendMailSender(
                apiKey = "re_test_key_never_sent_anywhere_real",
                from = "noreply@mytetz.example",
                httpClient = clientFor(server),
            )
            failure = assertFailsWith<MailSendFailedException> {
                sender.sendMagicLink("learner@example.com", "https://mytetz.example/magic?t=abc123")
            }
        } finally {
            server.stop(0)
        }
        assertTrue("500" in failure.message.orEmpty(), "the status code should be named in the message, was: ${failure.message}")
    }

    @Test
    fun `a transport failure raises without leaking the key`() = runBlocking {
        // A port with nothing listening: opened, read, and closed immediately, so the connection
        // this test drives is refused rather than merely slow.
        val deadPort = ServerSocket(0).use { it.localPort }
        val apiKey = "re_live_do_not_leak_this_value_transport_fail_2f3e4d5c"
        val client = HttpClient(CIO) {
            defaultRequest { url("http://127.0.0.1:$deadPort") }
        }

        val sender = ResendMailSender(
            apiKey = apiKey,
            from = "noreply@mytetz.example",
            httpClient = client,
        )

        val raised = assertFailsWith<MailSendFailedException> {
            sender.sendMagicLink("learner@example.com", "https://mytetz.example/magic?t=abc123")
        }

        // The whole chain, not only the top message: default logging prints the cause's message
        // and the full stack trace too, and either one is a place the engine's own exception type
        // could otherwise carry a request header back out.
        val rendered = StringWriter().also { w ->
            PrintWriter(w).use { raised.printStackTrace(it) }
        }.toString()
        assertFalse(apiKey in rendered, "the API key leaked into the exception chain: $rendered")
    }

    // ------------------------------------------------------------------ LoggingMailSender

    @Test
    fun `the logging sender writes the link`() = runBlocking {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(MAIL_SENDER_LOGGER) as ch.qos.logback.classic.Logger
        logger.addAppender(appender)

        try {
            LoggingMailSender().sendMagicLink("learner@example.com", "https://mytetz.example/magic?t=xyz789")
        } finally {
            logger.detachAppender(appender)
        }

        val event = assertNotNull(
            appender.list.firstOrNull { it.formattedMessage.contains("MAGIC_LINK_LOGGED") },
            "no MAGIC_LINK_LOGGED entry was written; saw: ${appender.list.map { it.formattedMessage }}",
        )
        assertEquals(Level.INFO, event.level)
        assertTrue(event.formattedMessage.contains("https://mytetz.example/magic?t=xyz789"))
    }
}
