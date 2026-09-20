package com.mytetz.billing

import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bson.Document
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillingRepositoryTest {

    companion object {
        /** 2100-01-01T00:00:00Z. Ahead of the real container clock, so the real-time TTL monitor
         * never reaps a document this suite writes. */
        private const val FUTURE = 4_102_444_800_000L
    }

    private val database = MongoTestSupport.database("billing")
    private val repository = BillingRepository(database)

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Document>("subscriptions").drop()
        database.getCollection<Document>("billingEvents").drop()
        repository.ensureIndexes()
    }

    private fun subscription(
        userId: String,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        trialEndsAt: Long? = null,
        currentPeriodEndsAt: Long? = null,
        graceEndsAt: Long? = null,
    ) = Subscription(
        userId = userId,
        status = status,
        trialEndsAtEpochMillis = trialEndsAt,
        currentPeriodEndsAtEpochMillis = currentPeriodEndsAt,
        graceEndsAtEpochMillis = graceEndsAt,
        updatedAtEpochMillis = FUTURE,
        createdAtEpochMillis = FUTURE,
    )

    // ------------------------------------------------------------------ subscriptions

    @Test
    fun `a subscription round-trips`() = runTest {
        val inserted = subscription("u1", status = SubscriptionStatus.TRIALING, trialEndsAt = FUTURE + 1)
        repository.upsert(inserted)

        val found = repository.find("u1")

        assertEquals(inserted, found)
    }

    @Test
    fun `upsert replaces an existing row`() = runTest {
        repository.upsert(subscription("u1", status = SubscriptionStatus.TRIALING))

        repository.upsert(subscription("u1", status = SubscriptionStatus.ACTIVE))

        assertEquals(SubscriptionStatus.ACTIVE, repository.find("u1")?.status)
        assertEquals(1L, database.getCollection<Document>("subscriptions").countDocuments(), "replace must not add a row")
    }

    @Test
    fun `an unknown user gives null`() = runTest {
        assertNull(repository.find("no-such-user"))
    }

    // ------------------------------------------------------------------ deleteForUser

    @Test
    fun `deleteForUser removes the stored row`() = runTest {
        repository.upsert(subscription("u1"))

        repository.deleteForUser("u1")

        assertNull(repository.find("u1"))
    }

    @Test
    fun `deleteForUser leaves every other row untouched`() = runTest {
        repository.upsert(subscription("u1"))
        repository.upsert(subscription("u2"))

        repository.deleteForUser("u1")

        assertNotNull(repository.find("u2"))
    }

    @Test
    fun `deleteForUser on an unknown user changes nothing, and does not throw`() = runTest {
        repository.deleteForUser("no-such-user")

        assertNull(repository.find("no-such-user"))
    }

    // ------------------------------------------------------------------ findByFreemiusUserId

    @Test
    fun `findByFreemiusUserId finds the row that carries it`() = runTest {
        repository.upsert(subscription("u1").copy(freemiusUserId = "fs-1"))
        repository.upsert(subscription("u2").copy(freemiusUserId = "fs-2"))

        val found = repository.findByFreemiusUserId("fs-1")

        assertEquals("u1", found?.userId)
    }

    @Test
    fun `findByFreemiusUserId gives null when no row carries it`() = runTest {
        repository.upsert(subscription("u1").copy(freemiusUserId = "fs-1"))

        assertNull(repository.findByFreemiusUserId("no-such-freemius-user"))
    }

    // ------------------------------------------------------------------ billing events

    @Test
    fun `a fresh event id is accepted`() = runTest {
        val accepted = repository.insertEventIfAbsent("evt-1", FUTURE)

        assertTrue(accepted)
    }

    @Test
    fun `a duplicate event id is refused`() = runTest {
        repository.insertEventIfAbsent("evt-2", FUTURE)

        val second = repository.insertEventIfAbsent("evt-2", FUTURE)

        assertFalse(second)
        assertEquals(1L, database.getCollection<Document>("billingEvents").countDocuments(), "a refused insert must not add a row")
    }

    @Test
    fun `the event receipt time is stored as a BSON Date`() = runTest {
        repository.insertEventIfAbsent("evt-3", FUTURE)

        val raw = database.getCollection<Document>("billingEvents").find(Filters.eq("_id", "evt-3")).firstOrNull()

        assertNotNull(raw)
        assertIs<Date>(raw["receivedAt"], "a non-Date here makes the TTL index a silent no-op")
        assertEquals(FUTURE, (raw["receivedAt"] as Date).time)
    }

    // ------------------------------------------------------------------ listNonTerminal

    @Test
    fun `listNonTerminal excludes expired rows`() = runTest {
        repository.upsert(subscription("u1", status = SubscriptionStatus.TRIALING))
        repository.upsert(subscription("u2", status = SubscriptionStatus.EXPIRED))
        repository.upsert(subscription("u3", status = SubscriptionStatus.PAST_DUE))

        val nonTerminal = repository.listNonTerminal(10)

        assertEquals(setOf("u1", "u3"), nonTerminal.map { it.userId }.toSet())
    }

    // ------------------------------------------------------------------ ensureIndexes

    @Test
    fun `ensureIndexes creates an index on freemiusUserId`() = runTest {
        val indexNames = database.getCollection<Document>("subscriptions")
            .listIndexes()
            .toList()
            .map { it.getString("name") }

        assertTrue(
            indexNames.contains("by_freemius_user_id"),
            "findByFreemiusUserId needs this index, or it scans the whole collection: $indexNames",
        )
    }
}
