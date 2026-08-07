package com.mytetz.billing

import com.mytetz.quota.Allowance

/** A day, in milliseconds. Every subscriber allowance shares this one window. */
private const val DAY_MILLIS = 86_400_000L

/**
 * The knobs a deployment may turn on the trial and the subscriber allowance.
 *
 * Each field reads its own override from the environment while the process starts. A missing,
 * an unparseable, or a non-positive override falls back to the default rather than throwing —
 * the same shape as `QuotaConfig.resolveDailyExplains`. A typo in a deployment environment
 * variable must not take the server down.
 */
data class BillingConfig(
    val trialGenerations: Int = resolvePositiveInt(System.getenv(TRIAL_GENERATIONS_ENV), DEFAULT_TRIAL_GENERATIONS),
    val trialDays: Int = resolvePositiveInt(System.getenv(TRIAL_DAYS_ENV), DEFAULT_TRIAL_DAYS),
    val graceDays: Int = resolvePositiveInt(System.getenv(GRACE_DAYS_ENV), DEFAULT_GRACE_DAYS),
    val subscriberDailyExplains: Int =
        resolvePositiveInt(System.getenv(SUBSCRIBER_DAILY_EXPLAINS_ENV), DEFAULT_SUBSCRIBER_DAILY_EXPLAINS),
) {

    init {
        // The resolvers above already reject a non-positive override. A caller who builds this
        // class directly bypasses them. A zero value then constructs without error. Entitlement.resolve
        // throws later, on the path that decides who pays. This guard moves that failure to
        // construction, where it is loud and early.
        require(trialGenerations > 0) { "trialGenerations must be positive, was $trialGenerations" }
        require(trialDays > 0) { "trialDays must be positive, was $trialDays" }
        require(graceDays > 0) { "graceDays must be positive, was $graceDays" }
        require(subscriberDailyExplains > 0) {
            "subscriberDailyExplains must be positive, was $subscriberDailyExplains"
        }
    }

    companion object {

        const val TRIAL_GENERATIONS_ENV: String = "MYTETZ_TRIAL_GENERATIONS"
        const val TRIAL_DAYS_ENV: String = "MYTETZ_TRIAL_DAYS"
        const val GRACE_DAYS_ENV: String = "MYTETZ_GRACE_DAYS"
        const val SUBSCRIBER_DAILY_EXPLAINS_ENV: String = "MYTETZ_SUBSCRIBER_DAILY_EXPLAINS"

        const val DEFAULT_TRIAL_GENERATIONS: Int = 40
        const val DEFAULT_TRIAL_DAYS: Int = 7
        const val DEFAULT_GRACE_DAYS: Int = 3
        const val DEFAULT_SUBSCRIBER_DAILY_EXPLAINS: Int = 25

        /**
         * Reads [raw] as a positive integer, or falls back to [default].
         *
         * A missing value, an unparseable value and a non-positive value all fall back the same
         * way. This mirrors `QuotaConfig.resolveDailyExplains`.
         */
        internal fun resolvePositiveInt(raw: String?, default: Int): Int =
            raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: default
    }
}

/**
 * What a learner may generate, or the fact that they may not.
 *
 * [Allowed] carries [Allowed.status] beside [Allowed.allowance]. A gate that reads both can tell
 * an exhausted trial from an exhausted subscription without a second read.
 *
 * This type has no `TrialExhausted` case. [Entitlement.resolve] is a pure function of one
 * subscription row and one timestamp. It has no way to know how much a learner has already
 * spent, so it cannot decide that a pool is spent. A gate that reads the quota check makes that
 * decision instead.
 */
sealed interface EntitlementDecision {
    data class Allowed(val allowance: Allowance, val status: SubscriptionStatus) : EntitlementDecision
    data object SubscriptionRequired : EntitlementDecision
}

/**
 * Decides what a learner may generate, from their subscription row alone.
 *
 * [resolve] holds no clock and reads no database. [Subscription] and `nowEpochMillis` are its
 * only inputs, so the same two inputs always give the same decision.
 *
 * A null date on a status that needs one gives [EntitlementDecision.SubscriptionRequired]. This
 * function never treats a missing date as a reason to allow.
 */
object Entitlement {

    fun resolve(subscription: Subscription?, nowEpochMillis: Long, config: BillingConfig): EntitlementDecision {
        if (subscription == null) return EntitlementDecision.SubscriptionRequired

        return when (subscription.status) {
            SubscriptionStatus.TRIALING -> resolveTrial(subscription, nowEpochMillis, config)
            SubscriptionStatus.ACTIVE -> subscriberAllowed(config, SubscriptionStatus.ACTIVE)
            SubscriptionStatus.CANCELLED -> resolveUntil(
                subscription.currentPeriodEndsAtEpochMillis,
                nowEpochMillis,
                config,
                SubscriptionStatus.CANCELLED,
            )
            SubscriptionStatus.PAST_DUE -> resolveUntil(
                subscription.graceEndsAtEpochMillis,
                nowEpochMillis,
                config,
                SubscriptionStatus.PAST_DUE,
            )
            SubscriptionStatus.EXPIRED -> EntitlementDecision.SubscriptionRequired
        }
    }

    /**
     * The trial gets its own path because its window spans the whole trial — from the row's own
     * creation to the row's own end — and not the fixed day every subscriber allowance shares.
     *
     * A trial whose end is at or before its own start gives [Allowance] a non-positive window,
     * which [Allowance] itself refuses. This function catches that row first, and reports
     * [EntitlementDecision.SubscriptionRequired] rather than let the exception through.
     */
    private fun resolveTrial(
        subscription: Subscription,
        nowEpochMillis: Long,
        config: BillingConfig,
    ): EntitlementDecision {
        val trialEndsAt = subscription.trialEndsAtEpochMillis ?: return EntitlementDecision.SubscriptionRequired
        if (nowEpochMillis >= trialEndsAt) return EntitlementDecision.SubscriptionRequired

        val windowMillis = trialEndsAt - subscription.createdAtEpochMillis
        if (windowMillis <= 0) return EntitlementDecision.SubscriptionRequired

        return EntitlementDecision.Allowed(Allowance(config.trialGenerations, windowMillis), SubscriptionStatus.TRIALING)
    }

    /** The shared shape behind [SubscriptionStatus.CANCELLED] and [SubscriptionStatus.PAST_DUE]. */
    private fun resolveUntil(
        deadlineEpochMillis: Long?,
        nowEpochMillis: Long,
        config: BillingConfig,
        status: SubscriptionStatus,
    ): EntitlementDecision {
        val deadline = deadlineEpochMillis ?: return EntitlementDecision.SubscriptionRequired
        if (nowEpochMillis >= deadline) return EntitlementDecision.SubscriptionRequired
        return subscriberAllowed(config, status)
    }

    private fun subscriberAllowed(config: BillingConfig, status: SubscriptionStatus): EntitlementDecision.Allowed =
        EntitlementDecision.Allowed(Allowance(config.subscriberDailyExplains, DAY_MILLIS), status)
}
