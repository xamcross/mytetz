package com.mytetz.billing

import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger(Reconciliation::class.java)

/**
 * What Freemius reports for one subscription right now.
 *
 * [Reconciliation.reconcile] compares [status] and [currentPeriodEndsAtEpochMillis] against the
 * stored row and corrects the row when the two disagree. Both are fields a webhook event can
 * already change through [BillingService.apply] — a reconciliation sweep must never touch a
 * field a webhook cannot.
 *
 * [failedPayments] is different: [reconcile] never writes it to the stored row, and never reads
 * it to decide anything either. It travels with this type only so a `BILLING_DRIFT` log line can
 * carry it. A fetched [SubscriptionStatus.ACTIVE] applies the same way whether [failedPayments]
 * is zero or ten, by design — see [reconcile]'s own "fail-safe rule" — but an operator reading
 * the log still needs to tell a genuine renewal from a subscription sitting inside a dunning
 * retry window, and the status and the period end alone cannot say which.
 */
data class FreemiusSubscriptionState(
    val status: SubscriptionStatus,
    val currentPeriodEndsAtEpochMillis: Long?,
    val failedPayments: Int? = null,
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
     * disagrees with, subject to the fail-safe rule below. Returns how many rows were actually
     * written.
     *
     * [limit] is what stops a cold start under load from asking Freemius about every subscription
     * this deployment has ever seen in one burst. [BillingRepository.listNonTerminal] enforces
     * it; this function issues no request beyond the ones that bound allows.
     *
     * ## The fail-safe rule
     *
     * [fetchState] rests on a guessed mapping from Freemius's own response to
     * [FreemiusSubscriptionState] — unlike a webhook event, which Freemius signs. A wrong guess
     * here must not be able to take money-bearing access away from a learner who paid for it. Two
     * rules follow from that, and both are stricter than [BillingService.apply]'s own rules for a
     * signed webhook event:
     *
     * - **A period end never shortens.** The stored value and the fetched value are compared, and
     *   the later of the two is kept — never the fetched one outright. Contrast
     *   [BillingService.apply], which trusts a signed event's own period end completely.
     * - **A status change is written only when it grants access.** The one case this function
     *   trusts on [fetchState]'s word alone is a fetched [SubscriptionStatus.ACTIVE] — the same
     *   correction a `subscription.created` or a renewal webhook would have made, had it arrived.
     *   Every other disagreement — a downgrade to [SubscriptionStatus.CANCELLED],
     *   [SubscriptionStatus.PAST_DUE] or [SubscriptionStatus.EXPIRED], or any change while the
     *   status does not become `ACTIVE` — is logged and left for an operator to act on by hand.
     *
     * Every disagreement is logged under `BILLING_DRIFT`, naming the user, the stored status and
     * period end, the values Freemius reports, [FreemiusSubscriptionState.failedPayments], and
     * whether this call actually wrote the change — `applied=true` or `applied=false`. This is an
     * operator's only record that the mirror had gone stale, since an applied correction
     * overwrites the row with no other trace, and an unapplied one changes nothing at all unless a
     * human reads the log line. `failedPayments` is what lets that human tell an `applied=true`
     * genuine renewal from an `applied=true` subscription sitting inside a dunning retry window —
     * both fetch `ACTIVE`, and only the failure count tells them apart.
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

            // Never the fetched value outright: the later of the two stands, so a wrong or a
            // stale answer from fetchState can lengthen a learner's access but never shorten it.
            val proposedPeriodEnd = laterOf(state.currentPeriodEndsAtEpochMillis, subscription.currentPeriodEndsAtEpochMillis)

            val statusDrifted = state.status != subscription.status
            val periodEndDrifted = proposedPeriodEnd != subscription.currentPeriodEndsAtEpochMillis
            if (!statusDrifted && !periodEndDrifted) continue

            // The one status this function ever trusts on fetchState's word alone. See "The
            // fail-safe rule" above.
            val applied = state.status == SubscriptionStatus.ACTIVE

            log.warn(
                "BILLING_DRIFT user={} status {}->{} periodEnd {}->{} failedPayments={} applied={}",
                subscription.userId,
                subscription.status,
                state.status,
                subscription.currentPeriodEndsAtEpochMillis,
                proposedPeriodEnd,
                state.failedPayments,
                applied,
            )

            if (!applied) continue

            repository.upsert(
                subscription.copy(
                    status = state.status,
                    currentPeriodEndsAtEpochMillis = proposedPeriodEnd,
                    updatedAtEpochMillis = clock(),
                ),
            )
            corrected++
        }
        return corrected
    }

    /** The later of [fetched] and [stored]. A null on either side defers to the other. */
    private fun laterOf(fetched: Long?, stored: Long?): Long? = when {
        fetched == null -> stored
        stored == null -> fetched
        else -> maxOf(fetched, stored)
    }
}
