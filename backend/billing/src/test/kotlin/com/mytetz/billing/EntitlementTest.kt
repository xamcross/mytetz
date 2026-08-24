package com.mytetz.billing

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mytetz.quota.Allowance
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [Entitlement.resolve] decides who pays, so every boundary here is crossed at one millisecond
 * either side. A sample in the middle of a window would prove nothing about the edge.
 */
class EntitlementTest {

    companion object {
        /** 2023-11-14T22:13:20Z. An arbitrary anchor; only the offsets from it matter. */
        private const val CREATED = 1_700_000_000_000L
        private const val DAY_MILLIS = 86_400_000L
        private val config = BillingConfig(
            trialGenerations = 40,
            trialDays = 7,
            graceDays = 3,
            subscriberDailyExplains = 25,
        )
    }

    private fun subscription(
        status: SubscriptionStatus,
        trialEndsAt: Long? = null,
        currentPeriodEndsAt: Long? = null,
        graceEndsAt: Long? = null,
        createdAt: Long = CREATED,
    ) = Subscription(
        userId = "u1",
        status = status,
        trialEndsAtEpochMillis = trialEndsAt,
        currentPeriodEndsAtEpochMillis = currentPeriodEndsAt,
        graceEndsAtEpochMillis = graceEndsAt,
        updatedAtEpochMillis = createdAt,
        createdAtEpochMillis = createdAt,
    )

    // ------------------------------------------------------------------ no subscription

    @Test
    fun `a null subscription requires a subscription`() {
        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(null, CREATED, config))
    }

    // ------------------------------------------------------------------ trialing

    @Test
    fun `a trial one millisecond before its end is allowed`() {
        val trialEndsAt = CREATED + 7 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.TRIALING, trialEndsAt = trialEndsAt)

        val decision = Entitlement.resolve(row, trialEndsAt - 1, config)

        assertIs<EntitlementDecision.Allowed>(decision)
        assertEquals(Allowance(config.trialGenerations, trialEndsAt - CREATED), decision.allowance)
    }

    @Test
    fun `a trial exactly at its end requires a subscription`() {
        val trialEndsAt = CREATED + 7 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.TRIALING, trialEndsAt = trialEndsAt)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, trialEndsAt, config))
    }

    @Test
    fun `a trial one millisecond after its end requires a subscription`() {
        val trialEndsAt = CREATED + 7 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.TRIALING, trialEndsAt = trialEndsAt)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, trialEndsAt + 1, config))
    }

    @Test
    fun `a trial with no end date requires a subscription`() {
        val row = subscription(SubscriptionStatus.TRIALING, trialEndsAt = null)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, CREATED, config))
    }

    @Test
    fun `a trial whose end is at its start requires a subscription`() {
        // trialEndsAt equals createdAt, so the window is zero. `now` sits one millisecond before
        // both, so the row clears the "still trialing" check and reaches the window guard itself.
        val row = subscription(SubscriptionStatus.TRIALING, trialEndsAt = CREATED, createdAt = CREATED)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, CREATED - 1, config))
    }

    @Test
    fun `a trial whose end is before its start requires a subscription`() {
        // trialEndsAt sits a day before createdAt, so the window is negative. `now` sits one
        // millisecond before trialEndsAt. The row clears the "still trialing" check first, then
        // reaches the window guard. Allowance refuses a non-positive window on its own. This guard
        // catches the row first. It reports SubscriptionRequired instead.
        val row = subscription(SubscriptionStatus.TRIALING, trialEndsAt = CREATED, createdAt = CREATED + DAY_MILLIS)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, CREATED - 1, config))
    }

    // ------------------------------------------------------------------ active

    @Test
    fun `an active subscription gets the subscriber allowance`() {
        val row = subscription(SubscriptionStatus.ACTIVE)

        val decision = Entitlement.resolve(row, CREATED, config)

        assertEquals(
            EntitlementDecision.Allowed(Allowance(25, DAY_MILLIS), SubscriptionStatus.ACTIVE),
            decision,
        )
    }

    // No arrange step changed on this test, or on `every allowed decision carries its status`
    // below: both already build an ACTIVE row with no currentPeriodEndsAt, which the grace
    // change below still allows unconditionally.

    @Test
    fun `an active subscription one millisecond before its grace ends is allowed`() {
        val periodEnd = CREATED + 30 * DAY_MILLIS
        val graceEnd = periodEnd + config.graceDays * DAY_MILLIS
        val row = subscription(SubscriptionStatus.ACTIVE, currentPeriodEndsAt = periodEnd)

        val decision = Entitlement.resolve(row, graceEnd - 1, config)

        assertIs<EntitlementDecision.Allowed>(decision)
        assertEquals(SubscriptionStatus.ACTIVE, decision.status)
    }

    @Test
    fun `an active subscription exactly at its grace end requires a subscription`() {
        val periodEnd = CREATED + 30 * DAY_MILLIS
        val graceEnd = periodEnd + config.graceDays * DAY_MILLIS
        val row = subscription(SubscriptionStatus.ACTIVE, currentPeriodEndsAt = periodEnd)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, graceEnd, config))
    }

    @Test
    fun `an active subscription with no period end is allowed`() {
        val row = subscription(SubscriptionStatus.ACTIVE, currentPeriodEndsAt = null)

        // A year past CREATED, and not CREATED itself: a resolver that quietly derived some
        // finite cutoff from a null period end, instead of treating null as "always allowed",
        // could still pass a check made only at `now = CREATED`.
        val decision = Entitlement.resolve(row, CREATED + 365 * DAY_MILLIS, config)

        assertIs<EntitlementDecision.Allowed>(decision)
        assertEquals(SubscriptionStatus.ACTIVE, decision.status)
    }

    @Test
    fun `an active subscription with no period end raises the operator alert`() {
        // This branch allows a learner for ever. The alert token is the only signal an operator
        // gets. A rename of the token removes that alert, and every other test here stays green.
        val row = subscription(SubscriptionStatus.ACTIVE, currentPeriodEndsAt = null)
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(Entitlement::class.java) as ch.qos.logback.classic.Logger
        logger.addAppender(appender)

        try {
            Entitlement.resolve(row, CREATED, config)
        } finally {
            logger.detachAppender(appender)
        }

        val logged = assertNotNull(
            appender.list.firstOrNull { it.formattedMessage.contains("BILLING_NO_PERIOD_END") },
            "a missing period end was not logged: ${appender.list.map { it.formattedMessage }}",
        )
        assertTrue(logged.formattedMessage.contains("u1"), "the log line must name the user")
    }

    // ------------------------------------------------------------------ cancelled

    @Test
    fun `a cancelled subscription is allowed until its period ends`() {
        val periodEnd = CREATED + 30 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.CANCELLED, currentPeriodEndsAt = periodEnd)

        val decision = Entitlement.resolve(row, periodEnd - 1, config)

        assertEquals(
            EntitlementDecision.Allowed(Allowance(config.subscriberDailyExplains, DAY_MILLIS), SubscriptionStatus.CANCELLED),
            decision,
        )
    }

    @Test
    fun `a cancelled subscription exactly at its period end requires a subscription`() {
        val periodEnd = CREATED + 30 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.CANCELLED, currentPeriodEndsAt = periodEnd)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, periodEnd, config))
    }

    @Test
    fun `a cancelled subscription one millisecond after its period ends requires a subscription`() {
        val periodEnd = CREATED + 30 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.CANCELLED, currentPeriodEndsAt = periodEnd)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, periodEnd + 1, config))
    }

    @Test
    fun `a cancelled subscription with no period end requires a subscription`() {
        val row = subscription(SubscriptionStatus.CANCELLED, currentPeriodEndsAt = null)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, CREATED, config))
    }

    // ------------------------------------------------------------------ past due

    @Test
    fun `a past-due subscription is allowed inside the grace`() {
        val graceEnd = CREATED + 3 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.PAST_DUE, graceEndsAt = graceEnd)

        val decision = Entitlement.resolve(row, graceEnd - 1, config)

        assertEquals(
            EntitlementDecision.Allowed(Allowance(config.subscriberDailyExplains, DAY_MILLIS), SubscriptionStatus.PAST_DUE),
            decision,
        )
    }

    @Test
    fun `a past-due subscription exactly at the grace end requires a subscription`() {
        val graceEnd = CREATED + 3 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.PAST_DUE, graceEndsAt = graceEnd)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, graceEnd, config))
    }

    @Test
    fun `a past-due subscription one millisecond after the grace requires a subscription`() {
        val graceEnd = CREATED + 3 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.PAST_DUE, graceEndsAt = graceEnd)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, graceEnd + 1, config))
    }

    @Test
    fun `a past-due subscription with no grace end requires a subscription`() {
        val row = subscription(SubscriptionStatus.PAST_DUE, graceEndsAt = null)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, CREATED, config))
    }

    // ------------------------------------------------------------------ expired

    @Test
    fun `an expired subscription requires a subscription`() {
        val row = subscription(SubscriptionStatus.EXPIRED)

        assertEquals(EntitlementDecision.SubscriptionRequired, Entitlement.resolve(row, CREATED, config))
    }

    // ------------------------------------------------------------------ the trial window, and configuration

    @Test
    fun `the trial window spans the whole trial and not a day`() {
        val trialEndsAt = CREATED + 10 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.TRIALING, trialEndsAt = trialEndsAt, createdAt = CREATED)

        val decision = Entitlement.resolve(row, CREATED, config)

        assertIs<EntitlementDecision.Allowed>(decision)
        assertEquals(trialEndsAt - CREATED, decision.allowance.windowMillis)
    }

    @Test
    fun `a configured trial size reaches the allowance`() {
        val customConfig = BillingConfig(trialGenerations = 999, trialDays = 7, graceDays = 3, subscriberDailyExplains = 25)
        val trialEndsAt = CREATED + 7 * DAY_MILLIS
        val row = subscription(SubscriptionStatus.TRIALING, trialEndsAt = trialEndsAt)

        val decision = Entitlement.resolve(row, CREATED, customConfig)

        assertIs<EntitlementDecision.Allowed>(decision)
        assertEquals(999, decision.allowance.generations)
    }

    @Test
    fun `every allowed decision carries its status`() {
        val trialEndsAt = CREATED + 7 * DAY_MILLIS
        val periodEnd = CREATED + 30 * DAY_MILLIS
        val graceEnd = CREATED + 3 * DAY_MILLIS

        val trialing = Entitlement.resolve(subscription(SubscriptionStatus.TRIALING, trialEndsAt = trialEndsAt), CREATED, config)
        val active = Entitlement.resolve(subscription(SubscriptionStatus.ACTIVE), CREATED, config)
        val cancelled =
            Entitlement.resolve(subscription(SubscriptionStatus.CANCELLED, currentPeriodEndsAt = periodEnd), CREATED, config)
        val pastDue = Entitlement.resolve(subscription(SubscriptionStatus.PAST_DUE, graceEndsAt = graceEnd), CREATED, config)

        assertIs<EntitlementDecision.Allowed>(trialing)
        assertIs<EntitlementDecision.Allowed>(active)
        assertIs<EntitlementDecision.Allowed>(cancelled)
        assertIs<EntitlementDecision.Allowed>(pastDue)
        assertEquals(SubscriptionStatus.TRIALING, trialing.status)
        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertEquals(SubscriptionStatus.CANCELLED, cancelled.status)
        assertEquals(SubscriptionStatus.PAST_DUE, pastDue.status)
    }

    // ------------------------------------------------------------------ config

    @Test
    fun `a missing override falls back to the default`() {
        assertEquals(40, BillingConfig.resolvePositiveInt(null, 40))
    }

    @Test
    fun `an unparseable override falls back to the default`() {
        assertEquals(40, BillingConfig.resolvePositiveInt("seven", 40))
    }

    @Test
    fun `a zero override falls back to the default`() {
        assertEquals(40, BillingConfig.resolvePositiveInt("0", 40))
    }

    @Test
    fun `a negative override falls back to the default`() {
        assertEquals(40, BillingConfig.resolvePositiveInt("-1", 40))
    }

    @Test
    fun `a positive override is used`() {
        assertEquals(5, BillingConfig.resolvePositiveInt("5", 40))
    }

    @Test
    fun `the environment variable names are the ones an operator sets`() {
        assertEquals("MYTETZ_TRIAL_GENERATIONS", BillingConfig.TRIAL_GENERATIONS_ENV)
        assertEquals("MYTETZ_TRIAL_DAYS", BillingConfig.TRIAL_DAYS_ENV)
        assertEquals("MYTETZ_GRACE_DAYS", BillingConfig.GRACE_DAYS_ENV)
        assertEquals("MYTETZ_SUBSCRIBER_DAILY_EXPLAINS", BillingConfig.SUBSCRIBER_DAILY_EXPLAINS_ENV)
    }
}
