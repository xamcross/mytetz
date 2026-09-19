package com.mytetz.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [EvictionConfig.resolveMaxRequestCount] and [EvictionConfig.resolveMaxAgeDays] against every
 * shape a deployment variable can take, the same table `QuotaConfig`'s own test runs for
 * `resolveDailyExplains` and `resolveCostCeilingMicros`.
 *
 * `resolveMaxRequestCount` differs from every other resolver in this codebase on one point: `0`
 * is the default AND a legal override, because a document nobody has read since it was made is
 * exactly the case the eviction rule targets. `resolveMaxAgeDays` follows the ordinary rule
 * instead, because an age of `0` would mark every document as old enough the moment it is
 * written.
 */
class EvictionConfigTest {

    @Test
    fun `resolveMaxRequestCount falls back to its default for an unusable override, and accepts zero`() {
        assertEquals(0, EvictionConfig.resolveMaxRequestCount(null))
        assertEquals(5, EvictionConfig.resolveMaxRequestCount("5"))
        assertEquals(5, EvictionConfig.resolveMaxRequestCount("  5  "))
        // A typo in a deployment environment variable must not take the server down at startup,
        // and the default is the safe value.
        assertEquals(0, EvictionConfig.resolveMaxRequestCount("five"))
        assertEquals(0, EvictionConfig.resolveMaxRequestCount(""))
        // 0 is a legal value: it is also the default, and the only setting in this codebase
        // where that is true.
        assertEquals(0, EvictionConfig.resolveMaxRequestCount("0"))
        assertEquals(0, EvictionConfig.resolveMaxRequestCount("-1"))
    }

    @Test
    fun `resolveMaxAgeDays falls back to its default for an unusable override, and refuses zero`() {
        assertEquals(90, EvictionConfig.resolveMaxAgeDays(null))
        assertEquals(30, EvictionConfig.resolveMaxAgeDays("30"))
        assertEquals(30, EvictionConfig.resolveMaxAgeDays("  30  "))
        assertEquals(90, EvictionConfig.resolveMaxAgeDays("thirty"))
        assertEquals(90, EvictionConfig.resolveMaxAgeDays(""))
        // 0 would mark every document as old enough the instant it is written, so it falls back
        // like a negative value does.
        assertEquals(90, EvictionConfig.resolveMaxAgeDays("0"))
        assertEquals(90, EvictionConfig.resolveMaxAgeDays("-1"))
    }
}
