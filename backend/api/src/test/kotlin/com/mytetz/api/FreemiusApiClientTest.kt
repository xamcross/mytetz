package com.mytetz.api

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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
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
 */
class FreemiusApiClientTest {

    private val apiConfig = FreemiusApiConfig(apiKey = "test-api-key", productId = "prod-1")

    private fun subscription(freemiusSubscriptionId: String? = "fs-sub-1") = Subscription(
        userId = "u1",
        status = SubscriptionStatus.ACTIVE,
        currentPeriodEndsAtEpochMillis = 1_700_000_000_000L,
        freemiusSubscriptionId = freemiusSubscriptionId,
        createdAtEpochMillis = 1_700_000_000_000L,
        updatedAtEpochMillis = 1_700_000_000_000L,
    )

    private fun clientReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine)
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
        val client = FreemiusApiClient(HttpClient(engine), apiConfig)

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
        val client = FreemiusApiClient(
            clientReturning("""{"next_payment": "2026-09-01 00:00:00", "canceled_at": null, "failed_payments": 0}"""),
            apiConfig,
        )

        val state = client.fetchState(subscription())

        assertEquals(SubscriptionStatus.ACTIVE, state?.status)
        assertEquals(1_788_220_800_000L, state?.currentPeriodEndsAtEpochMillis, "2026-09-01 00:00:00 UTC")
    }

    @Test
    fun `a canceled_at derives CANCELLED, even with a future next_payment`() = runTest {
        val client = FreemiusApiClient(
            clientReturning("""{"next_payment": "2026-09-01 00:00:00", "canceled_at": "2026-08-01 00:00:00"}"""),
            apiConfig,
        )

        val state = client.fetchState(subscription())

        assertEquals(SubscriptionStatus.CANCELLED, state?.status)
    }

    @Test
    fun `failed payments with no cancellation derives PAST_DUE`() = runTest {
        val client = FreemiusApiClient(
            clientReturning("""{"next_payment": "2026-09-01 00:00:00", "canceled_at": null, "failed_payments": 2}"""),
            apiConfig,
        )

        val state = client.fetchState(subscription())

        assertEquals(SubscriptionStatus.PAST_DUE, state?.status)
    }

    @Test
    fun `no next payment and no cancellation derives EXPIRED`() = runTest {
        val client = FreemiusApiClient(
            clientReturning("""{"next_payment": null, "canceled_at": null, "failed_payments": 0}"""),
            apiConfig,
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
        val client = FreemiusApiClient(HttpClient(engine), apiConfig)

        val state = client.fetchState(subscription(freemiusSubscriptionId = null))

        assertNull(state)
        assertEquals(0, calls, "a row with nothing to ask about must not reach the network")
    }

    @Test
    fun `a non-2xx status answers null rather than throwing`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val client = FreemiusApiClient(HttpClient(engine), apiConfig)

        assertNull(client.fetchState(subscription()))
    }

    @Test
    fun `a body this class cannot decode answers null rather than throwing`() = runTest {
        val client = FreemiusApiClient(clientReturning("not json"), apiConfig)

        assertNull(client.fetchState(subscription()))
    }

    @Test
    fun `a request that does not complete answers null rather than throwing`() = runTest {
        val engine = MockEngine { throw IOException("simulated network failure") }
        val client = FreemiusApiClient(HttpClient(engine), apiConfig)

        assertNull(client.fetchState(subscription()))
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
