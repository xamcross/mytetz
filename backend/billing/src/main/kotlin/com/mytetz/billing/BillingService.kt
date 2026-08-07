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
     *
     * ## The read, the insert, and the loser
     *
     * The read above and the insert below are two round trips, not one. Two concurrent sign-ins
     * for the same user can both read no row, and both build a fresh [Subscription] from two
     * different clock readings. [BillingRepository.insertIfAbsent] lets only one of the two inserts
     * win; this method then re-reads and returns the **stored** row for the loser, never the row
     * the loser itself built. Returning the loser's own row would disagree with the database the
     * instant this method returns, and — once a webhook can activate a subscription concurrently
     * with a sign-in — [BillingRepository.upsert]'s `replaceOne` in place of [insertIfAbsent] would
     * let a losing sign-in downgrade an already-paying customer back to [SubscriptionStatus.TRIALING].
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
        if (repository.insertIfAbsent(trial)) return trial

        return requireNotNull(repository.find(userId)) {
            "the insert for $userId lost a race, and the winner's own row is now missing too"
        }
    }

    /** What [userId] may generate right now, from their stored subscription row alone. */
    suspend fun entitlementFor(userId: String): EntitlementDecision =
        Entitlement.resolve(repository.find(userId), clock(), config)

    /**
     * [userId]'s stored subscription row, or null when none exists yet.
     *
     * A read, and only a read — unlike [startTrialIfAbsent], this method never inserts a row.
     * `GET /api/account` needs exactly this: a route that only reads must never start a trial for
     * a learner who does not have one, or opening the account page becomes the same thing as
     * signing in.
     */
    suspend fun subscriptionFor(userId: String): Subscription? = repository.find(userId)
}
