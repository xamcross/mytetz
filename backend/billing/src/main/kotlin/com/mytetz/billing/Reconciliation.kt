package com.mytetz.billing

import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger(Reconciliation::class.java)

/**
 * What Freemius reports for one subscription right now.
 *
 * [Reconciliation.reconcile] compares this against the stored row and corrects the row when the
 * two disagree. This type carries only the two fields a webhook event can already change through
 * [BillingService.apply] — a reconciliation sweep must never touch a field a webhook cannot.
 */
data class FreemiusSubscriptionState(
    val status: SubscriptionStatus,
    val currentPeriodEndsAtEpochMillis: Long?,
)

/**
 * Corrects a subscription mirror that has drifted from Freemius.
 *
 * A webhook delivery can fail before it reaches this server, and Freemius documents no retry
 * policy this task could confirm. So the stored mirror can go stale with no signal at all.
 * [reconcile] is the second line of defence: it reads the mirror directly and asks Freemius,
 * rather than waiting for an event that may never arrive.
 *
 * `:backend:billing` holds no HTTP client and calls Freemius for nothing on its own. [fetchState]
 * in [reconcile] is a `suspend (Subscription) -> FreemiusSubscriptionState?` the caller supplies —
 * the same division of labour `Components` uses for the checkout route's email-to-user lookup. A
 * subscription with no `freemiusSubscriptionId` to ask about, and a lookup that fails, both
 * answer null; either way the sweep skips that row rather than guessing, and the next scheduled
 * run tries again.
 */
object Reconciliation {

    const val RECONCILE_ON_BOOT_ENV: String = "MYTETZ_RECONCILE_ON_BOOT"

    /**
     * Only the exact word `true` turns reconciliation on.
     *
     * The same rule, for the same reason, as `Components.resolveMigrateOnBoot`: an unrecognised
     * value must keep a network sweep off, never turn one on by accident.
     */
    fun resolveReconcileOnBoot(raw: String?): Boolean =
        raw?.trim()?.equals("true", ignoreCase = true) == true

    /**
     * Reads up to [limit] non-terminal subscriptions and corrects every row [fetchState]
     * disagrees with. Returns how many rows were corrected.
     *
     * [limit] is what stops a cold start under load from asking Freemius about every subscription
     * this deployment has ever seen in one burst. [BillingRepository.listNonTerminal] enforces
     * it; this function issues no request beyond the ones that bound allows.
     *
     * Every correction is logged under `BILLING_DRIFT`, naming the user, the stored status and
     * period end, and the values Freemius reports — an operator's only record that the mirror had
     * gone stale, since the row itself is overwritten with no other trace.
     *
     * A [fetchState] failure for one row is logged and skipped. One learner's lookup failing must
     * not stop the sweep for every other learner in the batch — the same shape
     * `Components.migrate` uses for one topic's failed generation.
     */
    suspend fun reconcile(
        repository: BillingRepository,
        limit: Int,
        clock: () -> Long = System::currentTimeMillis,
        fetchState: suspend (Subscription) -> FreemiusSubscriptionState?,
    ): Int {
        var corrected = 0
        for (subscription in repository.listNonTerminal(limit)) {
            val state = try {
                fetchState(subscription)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("reconciliation could not read Freemius's state for user {}", subscription.userId, e)
                null
            } ?: continue

            val statusDrifted = state.status != subscription.status
            val periodEndDrifted = state.currentPeriodEndsAtEpochMillis != subscription.currentPeriodEndsAtEpochMillis
            if (!statusDrifted && !periodEndDrifted) continue

            log.warn(
                "BILLING_DRIFT user={} status {}->{} periodEnd {}->{}",
                subscription.userId,
                subscription.status,
                state.status,
                subscription.currentPeriodEndsAtEpochMillis,
                state.currentPeriodEndsAtEpochMillis,
            )
            repository.upsert(
                subscription.copy(
                    status = state.status,
                    currentPeriodEndsAtEpochMillis = state.currentPeriodEndsAtEpochMillis,
                    updatedAtEpochMillis = clock(),
                ),
            )
            corrected++
        }
        return corrected
    }
}
