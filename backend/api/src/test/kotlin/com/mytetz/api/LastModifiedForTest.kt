package com.mytetz.api

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [lastModifiedFor] alone, with no route, no Mongo, and no HTTP call.
 *
 * [SitemapRoutesTest] proves the same function end to end, through the real route.
 */
class LastModifiedForTest {

    @Test
    fun `two null timestamps give no date`() {
        assertNull(lastModifiedFor(seedCreatedAtEpochMillis = null, reviewedAtEpochMillis = null))
    }

    @Test
    fun `only the seed timestamp gives that timestamp's UTC date`() {
        val seed = Instant.parse("2026-02-10T00:00:00Z").toEpochMilli()

        assertEquals(LocalDate.of(2026, 2, 10), lastModifiedFor(seed, reviewedAtEpochMillis = null))
    }

    @Test
    fun `only the review timestamp gives that timestamp's UTC date`() {
        val reviewed = Instant.parse("2026-03-05T00:00:00Z").toEpochMilli()

        assertEquals(
            LocalDate.of(2026, 3, 5),
            lastModifiedFor(seedCreatedAtEpochMillis = null, reviewedAtEpochMillis = reviewed),
        )
    }

    @Test
    fun `the later of the two timestamps wins, whichever argument carries it`() {
        val earlier = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val later = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()

        assertEquals(LocalDate.of(2026, 6, 1), lastModifiedFor(earlier, later))
        assertEquals(LocalDate.of(2026, 6, 1), lastModifiedFor(later, earlier))
    }

    @Test
    fun `a timestamp just after UTC midnight does not roll back to the day before`() {
        // A date built from the wrong time zone can shift this timestamp to 2026-01-01 in a zone
        // west of UTC. This test fails under that defect and passes only under a true UTC date.
        val justAfterMidnightUtc = Instant.parse("2026-01-02T00:15:00Z").toEpochMilli()

        assertEquals(LocalDate.of(2026, 1, 2), lastModifiedFor(justAfterMidnightUtc, reviewedAtEpochMillis = null))
    }
}
