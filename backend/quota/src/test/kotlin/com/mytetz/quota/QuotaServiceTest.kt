package com.mytetz.quota

import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bson.Document
import org.testcontainers.containers.MongoDBContainer
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaServiceTest {

    companion object {
        private val container = MongoDBContainer("mongo:7").apply { start() }
        private val client = MongoClient.create(container.connectionString)

        /** 2023-11-14T22:13:20Z. Deliberately mid-day UTC so a small nudge stays on the same ledger day. */
        private const val T0 = 1_700_000_000_000L
        private const val DAY_MILLIS = 86_400_000L
        private const val CONCURRENCY = 16
    }

    private val database = client.getDatabase("test_quota")
    private val repository = QuotaRepository(database)

    private var now = T0
    private val config = QuotaConfig(
        dailyExplains = 3,
        windowMillis = DAY_MILLIS,
        globalDailyCostCeilingMicros = 1_000,
    )
    private val service = QuotaService(repository, config) { now }

    private val alice = PrincipalId.anonymous("alice")
    private val bob = PrincipalId.anonymous("bob")

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<PrincipalCounter>("principals").drop()
        database.getCollection<CostLedgerEntry>("costLedger").drop()
        repository.ensureIndexes()
        now = T0
    }

    private fun rawPrincipal(id: PrincipalId) = database.getCollection<Document>("principals")
        .find(Filters.eq("_id", id.value))

    // ------------------------------------------------------------------ identity

    @Test
    fun `anonymous and user principals occupy separate id namespaces`() {
        assertEquals("anon:7", PrincipalId.anonymous("7").value)
        assertEquals("user:7", PrincipalId.user("7").value)
        // Not cosmetic. The anonymous id is a client-supplied UUID and the user id comes from the
        // account store; without the prefix a visitor who sends `7` as their "UUID" would spend a
        // signed-in learner's allowance, and the counter is keyed on this string alone.
        assertNotEquals(PrincipalId.anonymous("7"), PrincipalId.user("7"))
    }

    // ------------------------------------------------------------------ per-principal allowance

    @Test
    fun `a fresh principal is allowed and has spent nothing`() = runTest {
        assertIs<QuotaDecision.Allowed>(service.checkGeneration(alice))
        assertEquals(0, service.dailySpendMicros(), "an empty ledger must read as zero, not fail")
        assertNull(repository.findCounter(alice.value), "checking must not create a counter")
    }

    @Test
    fun `a principal is blocked only once the daily allowance is exhausted`() = runTest {
        repeat(2) { service.recordGeneration(alice, costMicros = 1) }
        // The boundary is what matters: dailyExplains is 3, so the third generation must still be
        // admitted. `repeat(3)` alone cannot tell `>=` from `>` from `>= limit - 1`.
        assertIs<QuotaDecision.Allowed>(service.checkGeneration(alice))

        service.recordGeneration(alice, costMicros = 1)

        assertIs<QuotaDecision.PrincipalExceeded>(service.checkGeneration(alice))
        assertEquals(3, repository.findCounter(alice.value)?.explainCount)
        assertEquals(3, repository.findCounter(alice.value)?.costMicros, "per-principal cost is accumulated too")
    }

    @Test
    fun `retryAfterSeconds counts down the remaining window`() = runTest {
        repeat(3) { service.recordGeneration(alice, costMicros = 1) }

        val atStart = service.checkGeneration(alice)
        assertIs<QuotaDecision.PrincipalExceeded>(atStart)
        // The brief asserted only `> 0`, which `coerceAtLeast(1)` grants for free — it holds even
        // if the value is a constant unrelated to the clock. Pin the arithmetic instead.
        assertEquals(86_400, atStart.retryAfterSeconds)

        now += 400_000
        val later = service.checkGeneration(alice)
        assertIs<QuotaDecision.PrincipalExceeded>(later)
        assertEquals(86_000, later.retryAfterSeconds, "must be measured from the window's expiry, not a constant")

        // Inside the last second the true answer rounds to 0; a Retry-After of 0 invites an
        // immediate retry that would still be refused, so the floor of 1 is deliberate.
        now = T0 + DAY_MILLIS - 500
        val nearExpiry = service.checkGeneration(alice)
        assertIs<QuotaDecision.PrincipalExceeded>(nearExpiry)
        assertEquals(1, nearExpiry.retryAfterSeconds)
    }

    @Test
    fun `quotas are per principal`() = runTest {
        repeat(3) { service.recordGeneration(alice, 1) }

        assertIs<QuotaDecision.PrincipalExceeded>(service.checkGeneration(alice))
        assertIs<QuotaDecision.Allowed>(service.checkGeneration(bob))

        // And exhausting bob must not release alice: two independent counters, not one shared one
        // that happens to be keyed by the most recent caller.
        repeat(3) { service.recordGeneration(bob, 1) }

        assertIs<QuotaDecision.PrincipalExceeded>(service.checkGeneration(bob))
        assertIs<QuotaDecision.PrincipalExceeded>(service.checkGeneration(alice))
        assertEquals(3, repository.findCounter(alice.value)?.explainCount)
        assertEquals(3, repository.findCounter(bob.value)?.explainCount)
    }

    // ------------------------------------------------------------------ the window

    @Test
    fun `the window rolls over at its expiry and resets the count`() = runTest {
        repeat(3) { service.recordGeneration(alice, 1) }

        now = T0 + DAY_MILLIS - 1
        assertIs<QuotaDecision.PrincipalExceeded>(service.checkGeneration(alice), "still inside the window")

        now = T0 + DAY_MILLIS
        assertIs<QuotaDecision.Allowed>(service.checkGeneration(alice), "the expiry instant is outside the window")

        // Allowing the check is half the job. If recording kept incrementing the stale counter the
        // learner would be refused again on their very next request, so prove the count reset.
        service.recordGeneration(alice, costMicros = 7)
        val rolled = repository.findCounter(alice.value)
        assertEquals(1, rolled?.explainCount, "the rolled window starts a fresh count")
        assertEquals(7, rolled?.costMicros, "the rolled window starts a fresh cost")
        assertEquals(T0 + DAY_MILLIS, rolled?.windowStartEpochMillis)
        assertEquals(T0 + 2 * DAY_MILLIS, rolled?.windowExpiresAtEpochMillis)

        repeat(2) { service.recordGeneration(alice, 1) }
        assertIs<QuotaDecision.PrincipalExceeded>(service.checkGeneration(alice), "the fresh allowance is the same size")
    }

    @Test
    fun `the window is anchored to its first generation, not the most recent one`() = runTest {
        service.recordGeneration(alice, 1)

        now = T0 + 3_600_000
        service.recordGeneration(alice, 1)
        now = T0 + 7_200_000
        service.recordGeneration(alice, 1)

        val counter = repository.findCounter(alice.value)
        assertEquals(T0, counter?.windowStartEpochMillis, "later generations must not move the window start")
        assertEquals(T0 + DAY_MILLIS, counter?.windowExpiresAtEpochMillis)

        // A window re-anchored on every generation is a sliding window: a learner spending steadily
        // would never reach an expiry and would be locked out permanently after their first three.
        now = T0 + DAY_MILLIS
        assertIs<QuotaDecision.Allowed>(service.checkGeneration(alice))
    }

    // ------------------------------------------------------------------ the global breaker

    @Test
    fun `the global breaker trips exactly at the daily ceiling`() = runTest {
        service.recordGeneration(bob, costMicros = 999)
        assertIs<QuotaDecision.Allowed>(service.checkGeneration(alice), "one micro-dollar below the ceiling")

        service.recordGeneration(bob, costMicros = 1)

        assertEquals(1_000, service.dailySpendMicros())
        // Reaching the ceiling trips it; the brief's 1_500-vs-1_000 could not tell `>=` from `>`.
        // And bob's spend gates alice: the breaker is global, not per principal.
        assertIs<QuotaDecision.SpendLimitReached>(service.checkGeneration(alice))
    }

    @Test
    fun `the breaker outranks the per-principal verdict`() = runTest {
        repeat(3) { service.recordGeneration(alice, costMicros = 400) }

        // Alice is over both limits. The caller must be told the money ran out, not that she
        // personally may retry in 24 hours — Task 1.12 turns these into different responses, and a
        // Retry-After derived from her window would be a lie about when generation resumes.
        assertIs<QuotaDecision.SpendLimitReached>(service.checkGeneration(alice))
    }

    @Test
    fun `the breaker resets at midnight UTC, not 24 hours after the spend`() = runTest {
        // 2023-11-14T23:59:59.999Z
        now = 1_700_006_399_999
        service.recordGeneration(bob, costMicros = 1_500)
        assertIs<QuotaDecision.SpendLimitReached>(service.checkGeneration(alice))

        now += 1 // 2023-11-15T00:00:00.000Z — a new UTC day, one millisecond later.

        assertIs<QuotaDecision.Allowed>(service.checkGeneration(alice), "the ledger is keyed by UTC calendar day")
        assertEquals(0, service.dailySpendMicros())

        // The converse, which a rolling-24h ledger would get wrong in the other direction: moving
        // the clock forward inside the same day must NOT forgive the spend.
        now = 1_700_006_399_999
        assertIs<QuotaDecision.SpendLimitReached>(service.checkGeneration(alice))
        now += 3_600_000 // still 2023-11-15, an hour in
        assertIs<QuotaDecision.Allowed>(service.checkGeneration(alice))
        now = 1_700_006_399_999 - 3_600_000 // back to 2023-11-14, an hour before the spend
        assertIs<QuotaDecision.SpendLimitReached>(service.checkGeneration(alice), "same UTC day, same ledger entry")
    }

    @Test
    fun `spend accumulates additively across principals`() = runTest {
        service.recordGeneration(alice, 300)
        service.recordGeneration(bob, 250)

        assertEquals(550, service.dailySpendMicros())
        // The generation count is the audit trail that says how the money went — nothing else
        // asserts it, so a ledger that silently stopped counting would go unnoticed.
        assertEquals(2, repository.ledgerFor("2023-11-14")?.generations)
    }

    // ------------------------------------------------------------------ the trailing bound

    @Test
    fun `spend overshoots the ceiling by whatever generation was in flight`() = runTest {
        val inFlight = (1..4).map { PrincipalId.anonymous("flight-$it") }

        // Every one of these is admitted, because none of their cost exists yet: a generation's
        // cost is not knowable until it has been made. This is check-then-record, not a reservation.
        inFlight.forEach { assertIs<QuotaDecision.Allowed>(service.checkGeneration(it)) }

        inFlight.forEach { service.recordGeneration(it, costMicros = 400) }

        // 1_600 landed against a 1_000 ceiling. The overshoot is exactly the in-flight cost the
        // check could not see, and it is the bound QuotaService's KDoc quantifies. Asserted so that
        // any future code or comment claiming check-then-record is atomic breaks a test.
        assertEquals(1_600, service.dailySpendMicros())
        assertTrue(service.dailySpendMicros() > config.globalDailyCostCeilingMicros)

        // What does hold: once the ledger has crossed, nothing further is admitted.
        assertIs<QuotaDecision.SpendLimitReached>(service.checkGeneration(PrincipalId.anonymous("late")))
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    fun `concurrent generations for a fresh principal are each counted`() = runTest {
        coroutineScope {
            (1..CONCURRENCY).map { async(Dispatchers.IO) { service.recordGeneration(alice, costMicros = 5) } }.awaitAll()
        }

        // Read-then-write loses every caller but one here: all of them see no counter and all of
        // them write a count of 1. A learner's real allowance would be whatever they can reach by
        // issuing requests in parallel, and the money they spent would never enter the ledger.
        val counter = repository.findCounter(alice.value)
        assertEquals(CONCURRENCY, counter?.explainCount)
        assertEquals(CONCURRENCY * 5L, counter?.costMicros)
        assertEquals(CONCURRENCY * 5L, service.dailySpendMicros())
        assertEquals(CONCURRENCY.toLong(), repository.ledgerFor("2023-11-14")?.generations)
    }

    @Test
    fun `concurrent generations across a window rollover are each counted`() = runTest {
        service.recordGeneration(alice, costMicros = 5)
        now = T0 + DAY_MILLIS

        coroutineScope {
            (1..CONCURRENCY).map { async(Dispatchers.IO) { service.recordGeneration(alice, costMicros = 5) } }.awaitAll()
        }

        // Same lost-update shape as above, reached by the other branch: every caller sees an
        // expired window and every caller writes a fresh counter of 1.
        val counter = repository.findCounter(alice.value)
        assertEquals(CONCURRENCY, counter?.explainCount)
        assertEquals(CONCURRENCY * 5L, counter?.costMicros, "the pre-rollover cost must not be carried over")
        assertEquals(T0 + DAY_MILLIS, counter?.windowStartEpochMillis)
    }

    @Test
    fun `exactly one concurrent caller rolls an expired window`() = runTest {
        service.recordGeneration(alice, costMicros = 5)
        val later = T0 + DAY_MILLIS

        // Each caller carries its own `now`, as separate requests arriving milliseconds apart
        // really do. Passing one shared instant would let this test pass for the wrong reason: the
        // resets would write byte-identical documents, and Mongo reports modifiedCount 0 for a
        // no-op write, so even a reset with no filter at all would look like a single winner.
        val rolled = coroutineScope {
            (0 until CONCURRENCY).map { i ->
                async(Dispatchers.IO) { repository.rollWindowIfExpired(alice.value, later + i, DAY_MILLIS) }
            }.awaitAll()
        }

        // The reset is a conditional write, not a read-then-write: the window predicate lives in
        // the update's own filter, so the loser's reset never happens and cannot erase a count that
        // a winner has already begun accumulating.
        assertEquals(1, rolled.count { it }, "more than one reset means counts can be erased mid-window")
        assertEquals(0, repository.findCounter(alice.value)?.explainCount, "the pre-roll count is cleared once")
    }

    // ------------------------------------------------------------------ the TTL index

    @Test
    fun `the window expiry is stored as a BSON date under a TTL index`() = runTest {
        service.recordGeneration(alice, costMicros = 1)

        val index = database.getCollection<Document>("principals").listIndexes().toList()
            .single { it.getString("name") == "window_ttl" }
        assertEquals(setOf("windowExpiresAt"), index.get("key", Document::class.java).keys)
        assertEquals(0L, (index["expireAfterSeconds"] as Number).toLong(), "0 means 'expire at the stored instant'")

        val raw = rawPrincipal(alice).firstOrNull()
        assertNotNull(raw)
        // The whole point of the index. MongoDB's TTL monitor acts only on a field holding a BSON
        // Date; against a Long it does nothing at all — no error and no reaping — so `principals`
        // would grow by one document per anonymous visitor and never shrink.
        // https://www.mongodb.com/docs/manual/core/index-ttl/
        assertIs<Date>(raw["windowExpiresAt"], "a non-Date here makes the TTL index a silent no-op")
        assertEquals(T0 + DAY_MILLIS, (raw["windowExpiresAt"] as Date).time)

        // ...and the value the service compares against is still epoch millis, because TTL is a
        // cleanup mechanism and not a correctness one: the monitor runs only about once a minute.
        assertEquals(T0 + DAY_MILLIS, repository.findCounter(alice.value)?.windowExpiresAtEpochMillis)
    }

    // ------------------------------------------------------------------ config

    @Test
    fun `QuotaConfig falls back to its safe defaults for an unusable override`() {
        assertEquals(20, QuotaConfig.resolveDailyExplains(null))
        assertEquals(5, QuotaConfig.resolveDailyExplains("5"))
        assertEquals(5, QuotaConfig.resolveDailyExplains("  5  "))
        // A typo in a deployment environment variable must not take the server down at startup,
        // and the default is the safe value.
        assertEquals(20, QuotaConfig.resolveDailyExplains("twenty"))
        assertEquals(20, QuotaConfig.resolveDailyExplains(""))
        assertEquals(20, QuotaConfig.resolveDailyExplains("0"))
        assertEquals(20, QuotaConfig.resolveDailyExplains("-1"))

        assertEquals(50_000_000, QuotaConfig.resolveCostCeilingMicros(null))
        assertEquals(2_000_000, QuotaConfig.resolveCostCeilingMicros("2000000"))
        assertEquals(2_000_000, QuotaConfig.resolveCostCeilingMicros(" 2000000 "))
        assertEquals(50_000_000, QuotaConfig.resolveCostCeilingMicros("fifty dollars"))
        assertEquals(50_000_000, QuotaConfig.resolveCostCeilingMicros(""))
        // A ceiling of 0 would trip the breaker permanently and take generation offline; a negative
        // one is meaningless. Both fall back rather than being honoured.
        assertEquals(50_000_000, QuotaConfig.resolveCostCeilingMicros("0"))
        assertEquals(50_000_000, QuotaConfig.resolveCostCeilingMicros("-1"))
    }
}
