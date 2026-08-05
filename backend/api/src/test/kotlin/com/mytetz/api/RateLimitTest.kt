package com.mytetz.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitTest {

    @Test
    fun `a key may spend its allowance and no more`() {
        val limiter = FixedWindowRateLimiter(limit = 3, windowMillis = 1_000, clock = { 0 })

        repeat(3) { assertTrue(limiter.tryAcquire("1.2.3.4"), "refused within the allowance at attempt $it") }

        assertFalse(limiter.tryAcquire("1.2.3.4"))
    }

    @Test
    fun `one key exhausting its allowance does not spend another's`() {
        val limiter = FixedWindowRateLimiter(limit = 1, windowMillis = 1_000, clock = { 0 })
        limiter.tryAcquire("1.2.3.4")

        assertFalse(limiter.tryAcquire("1.2.3.4"))
        assertTrue(limiter.tryAcquire("5.6.7.8"), "a second caller was charged for the first's traffic")
    }

    @Test
    fun `the allowance returns when the window rolls`() {
        var now = 0L
        val limiter = FixedWindowRateLimiter(limit = 1, windowMillis = 1_000, clock = { now })
        limiter.tryAcquire("1.2.3.4")
        assertFalse(limiter.tryAcquire("1.2.3.4"))

        now = 999
        assertFalse(limiter.tryAcquire("1.2.3.4"), "the window ended early")

        now = 1_000
        assertTrue(limiter.tryAcquire("1.2.3.4"), "the window never ended")
    }

    @Test
    fun `the counter table is emptied on every roll`() {
        var now = 0L
        val limiter = FixedWindowRateLimiter(limit = 5, windowMillis = 1_000, clock = { now })
        repeat(50) { limiter.tryAcquire("key-$it") }
        assertEquals(50, limiter.trackedKeys)

        now = 1_000
        limiter.tryAcquire("fresh")

        assertEquals(1, limiter.trackedKeys)
    }

    // ------------------------------------------------------------------ the memory bound

    @Test
    fun `the counter table is bounded WITHIN a window, not merely cleared at the end of one`() {
        // The defect this closes: clearing on the roll bounds the table by "distinct keys seen in
        // one window", which is a bound only if distinct keys cost something. The window is 24
        // hours, so a caller producing a fresh key per request grew the map monotonically for a
        // full day — the same unbounded-growth defect the storage cap exists to prevent, moved
        // from disk to heap.
        //
        // `the counter table is emptied on every roll` above passes against that, because it only
        // ever checks the size AFTER a roll. This test never advances the clock.
        val limiter = FixedWindowRateLimiter(
            limit = 5,
            windowMillis = 24 * 60 * 60 * 1000,
            maxTrackedKeys = 100,
            clock = { 0 },
        )

        repeat(10_000) { limiter.tryAcquire("key-$it") }

        assertEquals(100, limiter.trackedKeys, "the table grew past its ceiling inside one window")
    }

    @Test
    fun `an unknown key is still served once the table is full`() {
        // Eviction rather than refusal, deliberately. Refusing an unrecognised key once the table is
        // full would let anyone churning keys switch the endpoint off for everybody for a whole
        // window — trading a memory bug for an availability bug. Churn may degrade how well the
        // limit works; it must not decide who gets served.
        val limiter = FixedWindowRateLimiter(limit = 1, windowMillis = 1_000, maxTrackedKeys = 2, clock = { 0 })

        limiter.tryAcquire("a")
        limiter.tryAcquire("b")
        limiter.tryAcquire("c")

        assertEquals(2, limiter.trackedKeys)
        assertTrue(limiter.tryAcquire("d"), "a new caller was refused because the table was full")
    }

    @Test
    fun `an active key survives eviction ahead of an idle one`() {
        val limiter = FixedWindowRateLimiter(limit = 4, windowMillis = 1_000, maxTrackedKeys = 2, clock = { 0 })
        limiter.tryAcquire("old")
        limiter.tryAcquire("recent")
        limiter.tryAcquire("old") // `old` is now the more recently used of the two

        limiter.tryAcquire("new") // evicts the least recently used, which is `recent`

        // `old` kept its count of 2, so it has 2 of its 4 left; had it been evicted and recreated it
        // would have 4. Insertion-ordered eviction would have dropped `old` and lost that count.
        assertTrue(limiter.tryAcquire("old"))
        assertTrue(limiter.tryAcquire("old"))
        assertFalse(limiter.tryAcquire("old"), "the surviving key's count was lost")
    }

    // ------------------------------------------------------------------ configuration

    @Test
    fun `a limit at zero is rejected rather than silently refusing every request`() {
        // Zero removes the feature rather than tightening the bound — every request refused, and
        // nothing in the logs to say the deployment is misconfigured rather than under attack. Same
        // reasoning as the `require`s in QuotaConfig and SessionLimits.
        assertTrue(
            runCatching { FixedWindowRateLimiter(limit = 0, windowMillis = 1_000) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    @Test
    fun `a non-positive window is rejected`() {
        assertTrue(
            runCatching { FixedWindowRateLimiter(limit = 1, windowMillis = 0) }.exceptionOrNull()
                is IllegalArgumentException,
            "a zero window was accepted, which makes every window already expired",
        )
    }

    @Test
    fun `a non-positive key ceiling is rejected`() {
        assertTrue(
            runCatching { FixedWindowRateLimiter(limit = 1, windowMillis = 1, maxTrackedKeys = 0) }
                .exceptionOrNull() is IllegalArgumentException,
            "a zero ceiling was accepted, which evicts every key as soon as it is written",
        )
    }
}

/**
 * The deployment chain is `browser -> Cloudflare (proxied) -> fly.io Anycast -> machine -> Ktor`
 * (`docs/deploy.md`), and every hop rewrites who the caller appears to be. These tests pin which
 * answer wins and, above all, that the **default** is one no caller can choose for itself.
 */
class ClientAddressTest {

    private fun route(config: ClientAddressConfig, block: suspend (HttpClient) -> Unit) = testApplication {
        application { routing { get("/who") { call.respondText(ClientAddress.of(call, config)) } } }
        block(client)
    }

    @Test
    fun `with trust explicitly disabled, a caller-supplied header is ignored`() = route(
        ClientAddressConfig(trustedHeader = null)
    ) { client ->
        val body = client.get("/who") {
            headers.append(HttpHeaders.XForwardedFor, "9.9.9.9")
            headers.append("CF-Connecting-IP", "9.9.9.9")
            headers.append(ClientAddress.FLY_CLIENT_IP, "9.9.9.9")
        }.bodyAsText()

        // A header no proxy is known to overwrite must not become a rate limiter's key, because then
        // the caller picks its own bucket per request. This mode is still available; it is just no
        // longer the default, since on this deployment it collapses everyone onto the fly proxy's
        // address. See `the DEFAULT configuration puts two callers in two buckets`.
        assertFalse(body.contains("9.9.9.9"), "a header was trusted while trust was disabled: $body")
    }

    @Test
    fun `the configured header wins when present`() = route(
        ClientAddressConfig(trustedHeader = "CF-Connecting-IP")
    ) { client ->
        val body = client.get("/who") { headers.append("CF-Connecting-IP", "203.0.113.7") }.bodyAsText()

        assertEquals("203.0.113.7", body)
    }

    @Test
    fun `only the first entry of a comma-separated forwarding list is used`() = route(
        ClientAddressConfig(trustedHeader = HttpHeaders.XForwardedFor)
    ) { client ->
        val body = client.get("/who") {
            headers.append(HttpHeaders.XForwardedFor, "203.0.113.7, 198.51.100.1, 10.0.0.1")
        }.bodyAsText()

        assertEquals("203.0.113.7", body)
    }

    @Test
    fun `a configured header that is absent or blank falls back to the socket peer`() = route(
        ClientAddressConfig(trustedHeader = "CF-Connecting-IP")
    ) { client ->
        val absent = client.get("/who").bodyAsText()
        val blank = client.get("/who") { headers.append("CF-Connecting-IP", "   ") }.bodyAsText()

        // Falling back to a constant would put every caller in one bucket, which silently converts a
        // per-caller limit into a global one. Both must resolve to something non-empty.
        assertTrue(absent.isNotEmpty(), "no address resolved when the header was absent")
        assertTrue(blank.isNotEmpty(), "no address resolved when the header was blank")
    }

    @Test
    fun `an oversized header value cannot be used to inflate a key`() = route(
        ClientAddressConfig(trustedHeader = "CF-Connecting-IP")
    ) { client ->
        // The value becomes a map key held for a whole window. Unbounded, it is a second way to
        // spend the memory the key ceiling exists to bound — kilobytes per entry, not tens of bytes.
        val body = client.get("/who") { headers.append("CF-Connecting-IP", "x".repeat(4_000)) }.bodyAsText()

        assertTrue(body.length <= ClientAddress.MAX_ADDRESS_LENGTH, "address not truncated: ${body.length}")
    }

    @Test
    fun `the trusted header name is resolved from the environment`() {
        assertEquals(ClientAddress.FLY_CLIENT_IP, ClientAddressConfig.resolveTrustedHeader(null))
        assertEquals(ClientAddress.FLY_CLIENT_IP, ClientAddressConfig.resolveTrustedHeader("   "))
        assertEquals("CF-Connecting-IP", ClientAddressConfig.resolveTrustedHeader(" CF-Connecting-IP "))
    }

    @Test
    fun `a misspelt header name is rejected rather than trusted`() {
        // The defect this closes. The resolver took any non-blank value as a header name. A
        // misspelt header is never present on a request, so `ClientAddress.of` fell back to the
        // socket peer. On this deployment the socket peer is the fly proxy: ONE KEY FOR THE WHOLE
        // INTERNET. That is the exact collapse that round 2 of Task 1.11 fixed, and one typo in
        // `fly.toml` re-created it. Nothing validated the name, nothing logged the resolved source,
        // and there is no metric that would show it.
        //
        // The fallback is the code default and not `none`, for the reason `ClientAddress` gives: a
        // coarse key beats no key. A deployment must not fail to start over a typo either.
        assertEquals(ClientAddress.FLY_CLIENT_IP, ClientAddressConfig.resolveTrustedHeader("CF-Conecting-IP"))
        assertEquals(ClientAddress.FLY_CLIENT_IP, ClientAddressConfig.resolveTrustedHeader("X-Forwarded-Fro"))
        assertEquals(ClientAddress.FLY_CLIENT_IP, ClientAddressConfig.resolveTrustedHeader("Authorization"))
    }

    @Test
    fun `a known header name is accepted whatever its case, and resolves to one spelling`() {
        // HTTP header names are case-insensitive and Ktor's header map is too, so the case a
        // deployment writes must not decide whether the name is recognised. One canonical spelling
        // comes back, so the startup log and the configuration cannot disagree about it.
        assertEquals("CF-Connecting-IP", ClientAddressConfig.resolveTrustedHeader("cf-connecting-ip"))
        assertEquals(ClientAddress.FLY_CLIENT_IP, ClientAddressConfig.resolveTrustedHeader("FLY-CLIENT-IP"))
        ClientAddress.KNOWN_HEADERS.forEach { known ->
            assertEquals(known, ClientAddressConfig.resolveTrustedHeader(known), "$known was rejected")
        }
    }

    @Test
    fun `trusting nothing has to be asked for explicitly`() {
        // Still reachable, because a deployment that is behind neither fly nor a proxy it controls
        // genuinely should trust no header. But it is no longer what you get by saying nothing.
        assertNull(ClientAddressConfig.resolveTrustedHeader("none"))
        assertNull(ClientAddressConfig.resolveTrustedHeader(" NONE "))
    }

    // ------------------------------------------------------------------ the shipped default

    @Test
    fun `the DEFAULT configuration puts two callers in two buckets`() = route(ClientAddressConfig()) { client ->
        // The defect this closes, and it was introduced by the fix for the previous round. Defaulting
        // to "trust no header" left `origin.remoteAddress` as the key — and neither ForwardedHeaders
        // nor XForwardedHeaders is installed, so that is the raw socket peer: the fly proxy on the
        // machine host. **One address for the entire internet.** The 10-per-day allowance became ten
        // per day for everybody, one stranger could spend it in a second, and unlike the storage cap
        // it does not self-heal because the window only rolls after a full 24 hours.
        //
        // Note the fixture: this test configures NOTHING. Every other test here names a header, and
        // the route-level test that certified the fix asserted `trackedKeys == 1` — which the
        // in-process client grants for free, for exactly the same reason production would.
        val first = client.get("/who") { headers.append(ClientAddress.FLY_CLIENT_IP, "203.0.113.1") }.bodyAsText()
        val second = client.get("/who") { headers.append(ClientAddress.FLY_CLIENT_IP, "203.0.113.2") }.bodyAsText()

        assertEquals("203.0.113.1", first)
        assertNotEquals(first, second, "the shipped default collapsed two callers onto one key")
    }

    @Test
    fun `the default header is one the caller cannot forge`() {
        // Fly's proxy SETS Fly-Client-IP rather than passing it through — "the IP address of the
        // client from the perspective of Fly Proxy" — so a caller cannot choose its own bucket with
        // it. It is coarse behind Cloudflare (it resolves to the CF edge, which fly's own docs say
        // explicitly), and coarse-but-unforgeable beats a single global bucket on every axis:
        // equally unforgeable, strictly finer. That is the trade the previous round got backwards.
        assertEquals(ClientAddress.FLY_CLIENT_IP, ClientAddressConfig().trustedHeader)
    }
}
