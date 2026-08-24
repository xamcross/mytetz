package com.mytetz.billing

import org.slf4j.LoggerFactory

/** One day, in milliseconds. [BillingService.startTrialIfAbsent] uses it to set a trial's own end date. */
private const val DAY_MILLIS = 86_400_000L

private val log = LoggerFactory.getLogger(BillingService::class.java)

/**
 * Every Freemius event type this deployment understands, mapped to the [SubscriptionStatus] it
 * moves a row to.
 *
 * The vendor's dashboard, and not its documentation, is the source for these exact strings. They
 * live in this one map so an operator who finds a wrong one has a single place to correct it,
 * rather than a search through [BillingService.apply].
 */
private val EVENT_TYPE_TO_STATUS: Map<String, SubscriptionStatus> = mapOf(
    "subscription.created" to SubscriptionStatus.ACTIVE,
    "subscription.renewal.retry" to SubscriptionStatus.ACTIVE,
    "subscription.renewal.failed" to SubscriptionStatus.PAST_DUE,
    "subscription.cancelled" to SubscriptionStatus.CANCELLED,
    "payment.refund" to SubscriptionStatus.EXPIRED,
    "payment.dispute.lost" to SubscriptionStatus.EXPIRED,
)

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
     * way. Returns null only when [ipBucket] is at its trial cap — see below.
     *
     * A row that already exists comes back untouched. This matters: a learner who signs out and
     * back in twice a day for a month must not get a fresh trial on every sign-in, or they would
     * never reach the end of one.
     *
     * [Subscription.trialEndsAtEpochMillis] sits [BillingConfig.trialDays] days ahead of
     * [Subscription.createdAtEpochMillis], so the window between the two is always positive and
     * [Entitlement.resolve] never refuses a fresh trial for that reason.
     *
     * ## The trial cap
     *
     * [ipBucket] identifies the caller's own IP bucket, resolved by the API layer through
     * `com.mytetz.api.ClientAddress`. A null [ipBucket] skips the cap entirely — every test in
     * this file that does not name the cap passes null and behaves exactly as it did before this
     * cap existed.
     *
     * A non-null [ipBucket] is checked against [TRIAL_CAP_PER_IP_BUCKET] fresh trials inside a
     * rolling [TRIAL_CAP_WINDOW_MILLIS] window, **before** this method reads or builds anything
     * about [userId]'s own row — a bucket that is already at its cap must not spend a row-read on
     * a trial it is about to refuse. [BillingRepository.tryRecordTrialStart] checks the count and
     * records the start as one atomic Mongo operation — see its own KDoc for why a separate read
     * and a separate `$inc` would let two concurrent callers behind one bucket both slip past the
     * same last slot.
     *
     * That atomicity bounds the bucket's own counter, but not the number of subscription rows this
     * method ever creates for one *user*: two concurrent sign-ins for the same new [userId] can
     * both pass the cap check and each record a start, while [insertIfAbsent] below still lets only
     * one of the two actually create a row — the same lost-race shape "The read, the insert, and
     * the loser" describes next. One bucket slot is then spent on a request that created no new
     * trial. That is the one direction this leaves open, and it is the safe one: a bucket's cap can
     * bind one sign-in earlier than strictly necessary; it can never admit more than
     * [TRIAL_CAP_PER_IP_BUCKET] trials.
     *
     * A capped caller is **not refused outright**: this method returns null, `AuthRoutes.kt`'s
     * `completeSignIn` still opens a session and signs the caller in, and the caller simply has no
     * subscription row — the same state `GET /api/account` already reports for a caller who has
     * none, and the same state that offers the subscribe panel instead of a wait message. Design
     * spec section 2's own three-layer defence names this: Turnstile is the first layer, this cap
     * is the second, and the global spend breaker is the third and final one.
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
    suspend fun startTrialIfAbsent(userId: String, ipBucket: String? = null): Subscription? {
        repository.find(userId)?.let { return it }

        val now = clock()
        if (ipBucket != null) {
            repository.rollTrialWindowIfExpired(ipBucket, now, TRIAL_CAP_WINDOW_MILLIS)
            val recorded = repository.tryRecordTrialStart(ipBucket, now, TRIAL_CAP_WINDOW_MILLIS, TRIAL_CAP_PER_IP_BUCKET)
            if (!recorded) {
                log.info(
                    "TRIAL_CAP_REACHED ipBucket={} already started {} trials today; " +
                        "this sign-in still succeeds, with no trial and checkout offered instead",
                    ipBucket,
                    TRIAL_CAP_PER_IP_BUCKET,
                )
                return null
            }
        }

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

    /**
     * Turns [event] into a change to the stored subscription row, or refuses it, and reports
     * which. Returns `true` only when a row changed.
     *
     * ## The order of the checks, and why it does not move
     *
     * Every check that a resend can pass on a later attempt runs **before** the event id is
     * consumed. A consumed id can never be replayed, so a refusal after that point is final. Three
     * checks sit ahead of it:
     *
     * - The type, against [EVENT_TYPE_TO_STATUS]. An operator who adds a missing type to the map
     *   can then replay the very same event by hand.
     * - The event's [FreemiusEvent.userReference].
     * - The row that reference names. A missing row can be a transient state, because a webhook
     *   can reach this server before the learner's own first sign-in does.
     *
     * Each of the three logs its own alert token and returns without consuming the id.
     *
     * Every check after the id is consumed can return `false` more than once for the same event,
     * because Freemius may resend it and the vendor documents no retry policy this class may rely
     * on. [BillingRepository.insertEventIfAbsent] is what stops a resend from changing the row a
     * second time.
     *
     * ## The ordering rule
     *
     * An event older than the row's own [Subscription.lastEventAtEpochMillis] is dropped, so a
     * renewal-failure webhook that arrives late cannot undo a renewal that already landed. The
     * comparison reads [Subscription.lastEventAtEpochMillis] and not
     * [Subscription.updatedAtEpochMillis]: this method is the only writer of that field, so both
     * sides of the comparison come from the vendor's clock. A null value means no event has landed
     * on this row yet, and a first event is therefore never dropped. Ordinary skew between the
     * server clock and the vendor clock would otherwise refuse a first payment.
     *
     * The rule is strict. An event that shares a millisecond with the last one is applied.
     *
     * A drop logs `BILLING_STALE_EVENT`. The event id is already consumed at that point, so the
     * vendor's own resend cannot recover the change. The log line is then the only record that a
     * paid event reached this server, and an operator needs it to correct the row by hand.
     */
    suspend fun apply(event: FreemiusEvent): Boolean {
        val status = EVENT_TYPE_TO_STATUS[event.type]
        if (status == null) {
            log.warn("BILLING_UNKNOWN_EVENT type={} id={}", event.type, event.id)
            return false
        }

        val userId = event.userReference
        if (userId == null) {
            log.warn("BILLING_UNKNOWN_USER event {} carries no userReference", event.id)
            return false
        }

        val stored = repository.find(userId)
        if (stored == null) {
            log.warn("BILLING_UNKNOWN_USER event {} names user {}, which has no row", event.id, userId)
            return false
        }

        if (!repository.insertEventIfAbsent(event.id, clock())) return false

        val lastEventAt = stored.lastEventAtEpochMillis
        if (lastEventAt != null && event.occurredAtEpochMillis < lastEventAt) {
            log.warn(
                "BILLING_STALE_EVENT id={} type={} occurredAt={} lastEventAt={}",
                event.id,
                event.type,
                event.occurredAtEpochMillis,
                lastEventAt,
            )
            return false
        }

        val moved = when (status) {
            // A new period end replaces the stored one. An event that carries none keeps the date
            // the row already holds. A null here would delete a good date, and
            // Entitlement.resolveActive reads a null period end on ACTIVE as a permanent allowance.
            SubscriptionStatus.ACTIVE -> stored.copy(
                status = SubscriptionStatus.ACTIVE,
                currentPeriodEndsAtEpochMillis =
                    event.periodEndsAtEpochMillis ?: stored.currentPeriodEndsAtEpochMillis,
                updatedAtEpochMillis = event.occurredAtEpochMillis,
            )
            SubscriptionStatus.PAST_DUE -> stored.copy(
                status = SubscriptionStatus.PAST_DUE,
                graceEndsAtEpochMillis = event.occurredAtEpochMillis + config.graceDays * DAY_MILLIS,
                updatedAtEpochMillis = event.occurredAtEpochMillis,
            )
            // CANCELLED keeps currentPeriodEndsAtEpochMillis: copy() carries a field forward
            // unless a new value is named, and no new value is named for it here.
            SubscriptionStatus.CANCELLED -> stored.copy(
                status = SubscriptionStatus.CANCELLED,
                updatedAtEpochMillis = event.occurredAtEpochMillis,
            )
            SubscriptionStatus.EXPIRED -> stored.copy(
                status = SubscriptionStatus.EXPIRED,
                updatedAtEpochMillis = event.occurredAtEpochMillis,
            )
            SubscriptionStatus.TRIALING -> return false // unreachable: EVENT_TYPE_TO_STATUS never maps here.
        }

        // Every applied event writes both vendor ids, and an event that carries neither keeps the
        // stored ones — the same rule as the period end above. Task 12's reconciliation asks
        // Freemius about each row, and it needs a vendor id to ask with.
        val updated = moved.copy(
            freemiusUserId = event.freemiusUserId ?: stored.freemiusUserId,
            freemiusSubscriptionId = event.freemiusSubscriptionId ?: stored.freemiusSubscriptionId,
            lastEventAtEpochMillis = event.occurredAtEpochMillis,
        )

        repository.upsert(updated)
        return true
    }

    companion object {

        /** How many fresh trials one IP bucket may start inside [TRIAL_CAP_WINDOW_MILLIS]. */
        const val TRIAL_CAP_PER_IP_BUCKET: Int = 3

        /** The trial cap's own window. A day, the same unit [DAY_MILLIS] already names. */
        const val TRIAL_CAP_WINDOW_MILLIS: Long = DAY_MILLIS
    }
}
