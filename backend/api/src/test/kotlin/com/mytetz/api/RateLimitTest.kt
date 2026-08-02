package com.mytetz.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitTest {

    @Test
    fun `a key may spend its allowance and no more`() {
        val limiter = FixedWindowRateLimiter(limit = 3, windowMillis = 1_000, clock = { 0 })

        repeat(3) { assertTrue(limiter.tryAcquire("anon:a"), "refused within the allowance at attempt $it") }

        assertFalse(limiter.tryAcquire("anon:a"))
    }

    @Test
    fun `one key exhausting its allowance does not spend another's`() {
        val limiter = FixedWindowRateLimiter(limit = 1, windowMillis = 1_000, clock = { 0 })
        limiter.tryAcquire("anon:a")

        assertFalse(limiter.tryAcquire("anon:a"))
        assertTrue(limiter.tryAcquire("anon:b"), "a second principal was charged for the first's traffic")
    }

    @Test
    fun `the allowance returns when the window rolls`() {
        var now = 0L
        val limiter = FixedWindowRateLimiter(limit = 1, windowMillis = 1_000, clock = { now })
        limiter.tryAcquire("anon:a")
        assertFalse(limiter.tryAcquire("anon:a"))

        now = 999
        assertFalse(limiter.tryAcquire("anon:a"), "the window ended early")

        now = 1_000
        assertTrue(limiter.tryAcquire("anon:a"), "the window never ended")
    }

    @Test
    fun `the counter table is emptied on every roll and does not accumulate`() {
        var now = 0L
        val limiter = FixedWindowRateLimiter(limit = 5, windowMillis = 1_000, clock = { now })
        repeat(50) { limiter.tryAcquire("anon:$it") }
        assertEquals(50, limiter.trackedKeys)

        now = 1_000
        limiter.tryAcquire("anon:fresh")

        // A per-key map that is never emptied is itself the unbounded-growth defect this class was
        // added to close, arriving one layer up. The bound on its size is "keys seen in one window",
        // and that only holds if the roll actually clears it.
        assertEquals(1, limiter.trackedKeys)
    }

    @Test
    fun `a limit at zero is rejected rather than silently refusing every request`() {
        // Zero removes the feature rather than tightening the bound — every request refused, and
        // nothing in the logs to say the deployment is misconfigured rather than under attack. Same
        // reasoning as the `require`s in QuotaConfig and SessionLimits.
        val error = runCatching { FixedWindowRateLimiter(limit = 0, windowMillis = 1_000) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException, "a zero limit was accepted")
    }

    @Test
    fun `a non-positive window is rejected`() {
        assertTrue(
            runCatching { FixedWindowRateLimiter(limit = 1, windowMillis = 0) }.exceptionOrNull()
                is IllegalArgumentException,
            "a zero window was accepted, which makes every window already expired",
        )
    }
}
