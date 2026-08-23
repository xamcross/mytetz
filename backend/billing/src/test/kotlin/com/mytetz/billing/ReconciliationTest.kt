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
        repository.upsert(activeSubscription("u1"))

        val corrected = Reconciliation.reconcile(repository, limit = 10) { subscription ->
            assertEquals("u1", subscription.userId)
            FreemiusSubscriptionState(SubscriptionStatus.CANCELLED, subscription.currentPeriodEndsAtEpochMillis)
        }

        assertEquals(1, corrected)
        val stored = assertNotNull(repository.find("u1"))
        assertEquals(SubscriptionStatus.CANCELLED, stored.status)
    }

    @Test
    fun `reconciliation logs BILLING_DRIFT on a change`() = runTest {
        repository.upsert(activeSubscription("u1"))
        val appender = attachAppender()

        try {
            Reconciliation.reconcile(repository, limit = 10) { subscription ->
                FreemiusSubscriptionState(SubscriptionStatus.CANCELLED, subscription.currentPeriodEndsAtEpochMillis)
            }
        } finally {
            detachAppender(appender)
        }

        assertTrue(
            appender.list.any { it.formattedMessage.contains("BILLING_DRIFT") },
            "a corrected row must be logged under BILLING_DRIFT",
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
            else FreemiusSubscriptionState(SubscriptionStatus.CANCELLED, null)
        }

        assertEquals(0, corrected)
        assertEquals(SubscriptionStatus.ACTIVE, repository.find("u1")?.status)
    }

    @Test
    fun `one row's lookup failure does not stop the sweep for the rest`() = runTest {
        repository.upsert(activeSubscription("u1"))
        repository.upsert(activeSubscription("u2"))

        val corrected = Reconciliation.reconcile(repository, limit = 10) { subscription ->
            if (subscription.userId == "u1") throw IllegalStateException("simulated Freemius outage")
            FreemiusSubscriptionState(SubscriptionStatus.CANCELLED, subscription.currentPeriodEndsAtEpochMillis)
        }

        assertEquals(1, corrected)
        assertEquals(SubscriptionStatus.ACTIVE, repository.find("u1")?.status, "the failed row must be untouched")
        assertEquals(SubscriptionStatus.CANCELLED, repository.find("u2")?.status)
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
