package com.mytetz.api

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mytetz.billing.Subscription
import com.mytetz.billing.SubscriptionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FreemiusApiClient] against a [MockEngine]: no socket, no vendor account, and the request Ktor
 * actually builds is inspected directly rather than reconstructed from what a loopback server
 * received.
 *
 * The endpoint, the base url and the `Authorization: Bearer` scheme this class builds are
 * confirmed against the vendor's own published SDK source — see [FreemiusApiClient]'s own KDoc —
 * so `the request Ktor sends matches the confirmed contract` is the one test in this file that
 * matters most: every other test here is about how a response is read, not about where it goes.
 *
 * Every client here is built with [FIXED_NOW] as its clock. [deriveState] decides `ACTIVE` by
 * comparing a fetched date against "now", so a test built on the machine's real clock would pass
 * or fail depending on when it happened to run — exactly the fragility a later review round found
 * in a different test in this slice.
 */
class FreemiusApiClientTest {

    companion object {
        /** 2023-11-14T22:13:20Z. Arbitrary; only whether a fixture date sits before or after it matters. */
        private const val FIXED_NOW = 1_700_000_000_000L
    }

    private val apiConfig = FreemiusApiConfig(apiKey = "test-api-key", productId = "prod-1")

    private fun subscription(freemiusSubscriptionId: String? = "fs-sub-1") = Subscription(
        userId = "u1",
        status = SubscriptionStatus.ACTIVE,
        currentPeriodEndsAtEpochMillis = 1_700_000_000_000L,
        freemiusSubscriptionId = freemiusSubscriptionId,
        createdAtEpochMillis = 1_700_000_000_000L,
        updatedAtEpochMillis = 1_700_000_000_000L,
    )

    private fun clientReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK): FreemiusApiClient {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return FreemiusApiClient(HttpClient(engine), apiConfig, clock = { FIXED_NOW })
    }

    @Test
    fun `the request Ktor sends matches the confirmed contract`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = """{"next_payment": "2026-09-01 00:00:00", "canceled_at": null}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = FreemiusApiClient(HttpClient(engine), apiConfig, clock = { FIXED_NOW })

        client.fetchState(subscription(freemiusSubscriptionId = "fs-sub-42"))

        val request = requireNotNull(captured) { "no request reached the mock engine" }
        assertEquals(
            "https://fast-api.freemius.com/v1/products/prod-1/subscriptions/fs-sub-42.json",
            request.url.toString(),
        )
        assertEquals("Bearer test-api-key", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `an active subscription with no cancellation and no failures derives ACTIVE`() = runTest {
        val client = clientReturning(
            """{"next_payment": "2026-09-01 00:00:00", "canceled_at": null, "failed_payments": 0}""",
        )

        val state = client.fetchState(subscription())

        assertEquals(SubscriptionStatus.ACTIVE, state?.status)
        assertEquals(1_788_220_800_000L, state?.currentPeriodEndsAtEpochMillis, "2026-09-01 00:00:00 UTC")
    }

    @Test
    fun `a canceled_at derives CANCELLED, even with a future next_payment`() = runTest {
        val client = clientReturning(
            """{"next_payment": "2026-09-01 00:00:00", "canceled_at": "2026-08-01 00:00:00"}""",
        )

        val state = client.fetchState(subscription())

        assertEquals(SubscriptionStatus.CANCELLED, state?.status)
    }

    /**
     * Pinned for the review finding it fixes: this test used to fetch a **future**
     * `next_payment` alongside a positive `failed_payments` and expect `PAST_DUE`, which pinned
     * the exact branch order `deriveState` no longer uses. `failed_payments` is only checked once
     * a fetched `next_payment` is absent or already in the past — see [deriveState]'s own KDoc for
     * why a cumulative-looking count must not out-rank a payment that is actually scheduled.
     */
    @Test
    fun `failed payments with a next_payment already in the past derives PAST_DUE`() = runTest {
        val client = clientReturning(
            """{"next_payment": "2023-01-01 00:00:00", "canceled_at": null, "failed_payments": 2}""",
        )

        val state = client.fetchState(subscription())

        assertEquals(SubscriptionStatus.PAST_DUE, state?.status)
    }

    /**
     * The scenario `deriveState`'s reordering exists for: a learner who failed a payment and then
     * renewed. `failed_payments` never resets on this fixture — the vendor documents no rule that
     * says it does — so the only signal available that the learner is current again is the future
     * `next_payment`, and that must win.
     */
    @Test
    fun `a renewed subscription derives ACTIVE despite a stale failed_payments count`() = runTest {
        val client = clientReturning(
            """{"next_payment": "2026-09-01 00:00:00", "canceled_at": null, "failed_payments": 3}""",
        )

        val state = client.fetchState(subscription())

        assertEquals(SubscriptionStatus.ACTIVE, state?.status, "a future next_payment must win over a stale failure count")
    }

    @Test
    fun `no next payment and no cancellation derives EXPIRED`() = runTest {
        val client = clientReturning(
            """{"next_payment": null, "canceled_at": null, "failed_payments": 0}""",
        )

        val state = client.fetchState(subscription())

        assertEquals(SubscriptionStatus.EXPIRED, state?.status)
    }

    @Test
    fun `a subscription with no vendor id makes no request and answers null`() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val client = FreemiusApiClient(HttpClient(engine), apiConfig, clock = { FIXED_NOW })

        val state = client.fetchState(subscription(freemiusSubscriptionId = null))

        assertNull(state)
        assertEquals(0, calls, "a row with nothing to ask about must not reach the network")
    }

    @Test
    fun `a non-2xx status answers null rather than throwing`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val client = FreemiusApiClient(HttpClient(engine), apiConfig, clock = { FIXED_NOW })

        assertNull(client.fetchState(subscription()))
    }

    @Test
    fun `a body this class cannot decode answers null rather than throwing`() = runTest {
        val client = clientReturning("not json")

        assertNull(client.fetchState(subscription()))
    }

    @Test
    fun `a request that does not complete answers null rather than throwing`() = runTest {
        val engine = MockEngine { throw IOException("simulated network failure") }
        val client = FreemiusApiClient(HttpClient(engine), apiConfig, clock = { FIXED_NOW })

        assertNull(client.fetchState(subscription()))
    }

    /**
     * Pinned for the review finding it fixes: the failure log used to pass the caught exception
     * straight to `log.warn`, which prints that exception's own message — and a JSON decode
     * failure's message quotes a snippet of the body it failed to parse. Confirmed here with a
     * body shaped like the fixture the finding named: a value from a field the vendor's schema
     * documents as personal data.
     */
    @Test
    fun `an undecodable body's contents never reach the log`() = runTest {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("com.mytetz.api.FreemiusApiClient") as ch.qos.logback.classic.Logger
        logger.addAppender(appender)

        val client = clientReturning("""{"ip": "203.0.113.42", this is not valid json""")
        try {
            client.fetchState(subscription())
        } finally {
            logger.detachAppender(appender)
        }

        assertTrue(
            appender.list.none { it.formattedMessage.contains("203.0.113.42") },
            "the response body must never reach the log, directly or through a caught exception's message",
        )
    }

    // ------------------------------------------------------------------ FreemiusApiConfig

    @Test
    fun `a missing Freemius API key fails construction and names the variable`() {
        val error = kotlin.test.assertFailsWith<IllegalStateException> {
            FreemiusApiConfig(productId = "prod-1")
        }

        assertTrue(error.message.orEmpty().contains(FreemiusApiConfig.API_KEY_ENV))
    }

    @Test
    fun `a FreemiusApiConfig never prints its api key`() {
        val config = FreemiusApiConfig(apiKey = "super-secret", productId = "prod-1")

        val printed = config.toString()

        assertTrue(!printed.contains("super-secret"), "the api key reached toString")
        assertTrue(printed.contains("prod-1"), "toString must still name the field that is not secret")
    }
}
