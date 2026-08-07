package com.mytetz.billing

/** One day, in milliseconds. [BillingService.startTrialIfAbsent] uses it to set a trial's own end date. */
private const val DAY_MILLIS = 86_400_000L

/**
 * Starts a trial on sign-in, and answers what a learner may generate right now.
 *
 * [Entitlement] makes the decision from one subscription row and one clock reading. This class is
 * the one place that reads and writes that row for the API layer.
 */
class BillingService(
    private val repository: BillingRepository,
    private val config: BillingConfig = BillingConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Inserts a TRIALING row for [userId] when none exists yet, and returns the stored row either
     * way.
     *
     * A row that already exists comes back untouched. This matters: a learner who signs out and
     * back in twice a day for a month must not get a fresh trial on every sign-in, or they would
     * never reach the end of one.
     *
     * [Subscription.trialEndsAtEpochMillis] sits [BillingConfig.trialDays] days ahead of
     * [Subscription.createdAtEpochMillis], so the window between the two is always positive and
     * [Entitlement.resolve] never refuses a fresh trial for that reason.
     */
    suspend fun startTrialIfAbsent(userId: String): Subscription {
        repository.find(userId)?.let { return it }

        val now = clock()
        val trial = Subscription(
            userId = userId,
            status = SubscriptionStatus.TRIALING,
            trialEndsAtEpochMillis = now + config.trialDays * DAY_MILLIS,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        repository.upsert(trial)
        return trial
    }

    /** What [userId] may generate right now, from their stored subscription row alone. */
    suspend fun entitlementFor(userId: String): EntitlementDecision =
        Entitlement.resolve(repository.find(userId), clock(), config)
}
