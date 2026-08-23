package com.mytetz.billing

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.bson.Document
import org.slf4j.LoggerFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [Reconciliation.reconcile] never talks to Freemius itself — every test here supplies its own
 * `fetchState` lambda, the same seam [Components] fills with a real HTTP call in production. That
 * is what lets this whole suite run with no network and no Freemius account.
 *
 * Every scenario that writes a row uses a fetched [SubscriptionStatus.ACTIVE], because that is
 * the one status [Reconciliation.reconcile] ever trusts on [FreemiusSubscriptionState] alone —
 * see "The fail-safe rule" on that method. A scenario that fetches anything else exists to prove
 * the opposite: that the row is left alone and only logged.
 */
class ReconciliationTest {

    companion object {
        private const val T0 = 1_700_000_000_000L
        private const val DAY_MILLIS = 86_400_000L
    }

    private val database = MongoTestSupport.database("reconciliation")
    private val repository = BillingRepository(database)

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Document>("subscriptions").drop()
    }

    private fun activeSubscription(
        userId: String,
        periodEndsAt: Long = T0 + 30 * DAY_MILLIS,
        freemiusSubscriptionId: String? = "fs-sub-1",
    ) = Subscription(
        userId = userId,
        status = SubscriptionStatus.ACTIVE,
        currentPeriodEndsAtEpochMillis = periodEndsAt,
        freemiusSubscriptionId = freemiusSubscriptionId,
        createdAtEpochMillis = T0,
        updatedAtEpochMillis = T0,
    )

    /** A row that missed its renewal webhook: exactly the case reconciliation exists to fix. */
    private fun pastDueSubscription(
        userId: String,
        graceEndsAt: Long = T0 + 3 * DAY_MILLIS,
        freemiusSubscriptionId: String? = "fs-sub-1",
    ) = Subscription(
        userId = userId,
        status = SubscriptionStatus.PAST_DUE,
        graceEndsAtEpochMillis = graceEndsAt,
        freemiusSubscriptionId = freemiusSubscriptionId,
        createdAtEpochMillis = T0,
        updatedAtEpochMillis = T0,
    )

    private fun attachAppender(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(Reconciliation::class.java) as ch.qos.logback.classic.Logger).addAppender(appender)
        return appender
    }

    private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
        (LoggerFactory.getLogger(Reconciliation::class.java) as ch.qos.logback.classic.Logger)
            .detachAppender(appender)
    }

    // ------------------------------------------------------------------ reconcile

    @Test
    fun `reconciliation corrects a drifted mirror`() = runTest {
        repository.upsert(pastDueSubscription("u1"))
        val renewedPeriodEnd = T0 + 60 * DAY_MILLIS

        val corrected = Reconciliation.reconcile(repository, limit = 10) { subscription ->
            assertEquals("u1", subscription.userId)
            // A renewal webhook the mirror missed: exactly the correction the fail-safe rule
            // trusts on fetchState's word alone.
            FreemiusSubscriptionState(SubscriptionStatus.ACTIVE, renewedPeriodEnd)
        }

        assertEquals(1, corrected)
        val stored = assertNotNull(repository.find("u1"))
        assertEquals(SubscriptionStatus.ACTIVE, stored.status)
        assertEquals(renewedPeriodEnd, stored.currentPeriodEndsAtEpochMillis)
    }

    @Test
    fun `reconciliation logs BILLING_DRIFT on a change`() = runTest {
        repository.upsert(pastDueSubscription("u1"))
        val appender = attachAppender()

        try {
            Reconciliation.reconcile(repository, limit = 10) { subscription ->
                FreemiusSubscriptionState(SubscriptionStatus.ACTIVE, subscription.graceEndsAtEpochMillis)
            }
        } finally {
            detachAppender(appender)
        }

        val line = appender.list.firstOrNull { it.formattedMessage.contains("BILLING_DRIFT") }
        assertNotNull(line, "a corrected row must be logged under BILLING_DRIFT")
        assertTrue(line.formattedMessage.contains("applied=true"), "an applied correction must say so: ${line.formattedMessage}")
    }

    /**
     * Pinned for the review finding it fixes: an `applied=true` correction from a genuine
     * renewal and one from a subscription sitting inside a dunning retry window both fetch
     * `ACTIVE`, by design — see "The fail-safe rule". Without `failedPayments` on the log line an
     * operator cannot tell the two apart from the log alone, which is the whole point of logging
     * at all.
     */
    @Test
    fun `the BILLING_DRIFT line names failedPayments, so a dunning extension is not silent`() = runTest {
        repository.upsert(pastDueSubscription("u1"))
        val appender = attachAppender()

        try {
            Reconciliation.reconcile(repository, limit = 10) { subscription ->
                FreemiusSubscriptionState(
                    status = SubscriptionStatus.ACTIVE,
                    currentPeriodEndsAtEpochMillis = subscription.graceEndsAtEpochMillis,
                    failedPayments = 2,
                )
            }
        } finally {
            detachAppender(appender)
        }

        val line = appender.list.firstOrNull { it.formattedMessage.contains("BILLING_DRIFT") }
        assertNotNull(line, "a corrected row must be logged under BILLING_DRIFT")
        assertTrue(
            line.formattedMessage.contains("failedPayments=2"),
            "the log line must carry the failure count an operator needs to tell a dunning " +
                "extension from a genuine renewal: ${line.formattedMessage}",
        )
    }

    @Test
    fun `reconciliation is off unless the flag says true`() {
        assertFalse(Reconciliation.resolveReconcileOnBoot(null))
        assertFalse(Reconciliation.resolveReconcileOnBoot(""))
        assertFalse(Reconciliation.resolveReconcileOnBoot("false"))
        assertFalse(Reconciliation.resolveReconcileOnBoot("yes"), "only the exact word true turns it on")
        assertFalse(Reconciliation.resolveReconcileOnBoot("1"))

        assertTrue(Reconciliation.resolveReconcileOnBoot("true"))
        assertTrue(Reconciliation.resolveReconcileOnBoot("TRUE"))
        assertTrue(Reconciliation.resolveReconcileOnBoot("  true \n"), "a fly secret carries a trailing newline")
    }

    // ------------------------------------------------------------------ the fail-safe rule

    /**
     * Pinned for the review finding it fixes: `Reconciliation.kt` wrote the fetched period end
     * outright, with no guard — the sibling rule `BillingService.apply` already carries for a
     * signed webhook event. A mapping this task cannot fully confirm must never be able to take a
     * period end away from a learner who already has it.
     */
    @Test
    fun `reconciliation never shortens a stored period end`() = runTest {
        val storedPeriodEnd = T0 + 30 * DAY_MILLIS
        repository.upsert(activeSubscription("u1", periodEndsAt = storedPeriodEnd))
        val earlierFetchedPeriodEnd = T0 + 10 * DAY_MILLIS

        val corrected = Reconciliation.reconcile(repository, limit = 10) {
            FreemiusSubscriptionState(SubscriptionStatus.ACTIVE, earlierFetchedPeriodEnd)
        }

        assertEquals(0, corrected, "a status that already matches, with only a shorter period end fetched, is not a correction")
        assertEquals(storedPeriodEnd, repository.find("u1")?.currentPeriodEndsAtEpochMillis)
    }

    @Test
    fun `a downgrade is logged under BILLING_DRIFT but not applied`() = runTest {
        repository.upsert(activeSubscription("u1"))
        val appender = attachAppender()

        val corrected = try {
            Reconciliation.reconcile(repository, limit = 10) {
                FreemiusSubscriptionState(SubscriptionStatus.CANCELLED, null)
            }
        } finally {
            detachAppender(appender)
        }

        assertEquals(0, corrected, "a downgrade must never be written from fetchState's word alone")
        assertEquals(SubscriptionStatus.ACTIVE, repository.find("u1")?.status, "the row must be untouched")
        val line = appender.list.firstOrNull { it.formattedMessage.contains("BILLING_DRIFT") }
        assertNotNull(line, "a proposed downgrade must still be logged, for an operator to act on")
        assertTrue(line.formattedMessage.contains("applied=false"), "an unapplied correction must say so: ${line.formattedMessage}")
    }

    // ------------------------------------------------------------------ additions

    @Test
    fun `an undrifted row is left alone and logs nothing`() = runTest {
        val subscription = activeSubscription("u1")
        repository.upsert(subscription)
        val appender = attachAppender()

        val corrected = try {
            Reconciliation.reconcile(repository, limit = 10) { current ->
                FreemiusSubscriptionState(current.status, current.currentPeriodEndsAtEpochMillis)
            }
        } finally {
            detachAppender(appender)
        }

        assertEquals(0, corrected)
        assertFalse(appender.list.any { it.formattedMessage.contains("BILLING_DRIFT") })
        assertEquals(subscription, repository.find("u1"))
    }

    @Test
    fun `a row fetchState cannot answer for is skipped, not corrected`() = runTest {
        repository.upsert(activeSubscription("u1", freemiusSubscriptionId = null))

        val corrected = Reconciliation.reconcile(repository, limit = 10) { subscription ->
            // A row with no vendor id has nothing to ask Freemius about.
            if (subscription.freemiusSubscriptionId == null) null
            else FreemiusSubscriptionState(SubscriptionStatus.ACTIVE, null)
        }

        assertEquals(0, corrected)
        assertEquals(SubscriptionStatus.ACTIVE, repository.find("u1")?.status)
    }

    @Test
    fun `one row's lookup failure does not stop the sweep for the rest`() = runTest {
        repository.upsert(activeSubscription("u1"))
        repository.upsert(pastDueSubscription("u2"))

        val corrected = Reconciliation.reconcile(repository, limit = 10) { subscription ->
            if (subscription.userId == "u1") throw IllegalStateException("simulated Freemius outage")
            FreemiusSubscriptionState(SubscriptionStatus.ACTIVE, subscription.graceEndsAtEpochMillis)
        }

        assertEquals(1, corrected)
        assertEquals(SubscriptionStatus.ACTIVE, repository.find("u1")?.status, "the failed row must be untouched")
        assertEquals(SubscriptionStatus.ACTIVE, repository.find("u2")?.status)
    }

    @Test
    fun `reconciliation is bounded by the limit it is given`() = runTest {
        repository.upsert(activeSubscription("u1"))
        repository.upsert(activeSubscription("u2"))
        var calls = 0

        Reconciliation.reconcile(repository, limit = 1) { subscription ->
            calls++
            FreemiusSubscriptionState(SubscriptionStatus.CANCELLED, subscription.currentPeriodEndsAtEpochMillis)
        }

        assertEquals(1, calls, "a limit of 1 must ask Freemius about at most one row")
    }
}
