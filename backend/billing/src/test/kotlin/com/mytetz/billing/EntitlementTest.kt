package com.mytetz.billing

import com.mytetz.quota.Allowance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
}
