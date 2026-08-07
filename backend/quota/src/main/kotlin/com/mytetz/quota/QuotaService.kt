package com.mytetz.quota

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface QuotaDecision {
    data object Allowed : QuotaDecision
    data class PrincipalExceeded(val retryAfterSeconds: Long) : QuotaDecision
    data object SpendLimitReached : QuotaDecision
}

/**
 * Per-principal allowance, a per-day cost ledger, and the global spend breaker sitting on top of
 * both. Knows about principals and money; knows nothing about explanations. It meets
 * `ExplanationGraph` in the API layer, not in this module.
 *
 * ## The breaker is a trailing indicator, and by how much
 *
 * [checkGeneration] and [recordGeneration] are two calls, not one. They cannot be one: a
 * generation's cost is not knowable until it has been made, which is the whole reason
 * `Explanation.costMicros` is priced from real token counts after the stream ends. So between a
 * caller being told [QuotaDecision.Allowed] and its cost reaching the ledger, the breaker cannot
 * see that cost, and other callers are admitted against a ledger that understates the truth.
 *
 * The bound this leaves is:
 *
 * ```
 * final daily spend  <=  (ceiling - 1) + SUM(cost of every generation in flight when it tripped)
 * ```
 *
 * Concretely, at the defaults: the ceiling is 50_000_000 micros ($50/day), and one generation's
 * output is capped by `GraphConfig.maxOutputTokens` (4_000) at the dearest rate in `Pricing`
 * (25 micros/token) at 100_000 micros — $0.10, plus its input tokens. So each generation still in
 * flight when the ceiling is crossed is worth roughly a five-hundredth of the day's budget, and K
 * concurrent generations overshoot by about K x $0.10. At K = 100 that is $10 on top of $50.
 * The overshoot is therefore bounded by the API layer's concurrency, not by this class; nothing
 * here can make it zero, and a version that claimed to would be lying.
 *
 * It is taken at most once per day rather than accumulating: costs are non-negative (enforced by
 * [recordGeneration]) so a day's spend is monotone, and the ceiling is therefore crossed once.
 *
 * The same non-atomicity applies to [QuotaConfig.dailyExplains]: a principal issuing requests in
 * parallel can exceed their allowance by however many they get in flight before the first records.
 * What *is* guaranteed is that no generation is admitted once the ledger has crossed the ceiling,
 * and that N recorded generations are counted as exactly N — the recording side is atomic even
 * though the check-then-record pair is not.
 *
 * ## When Mongo is unavailable
 *
 * Nothing here catches anything: both methods propagate whatever the driver throws, and no
 * transaction spans [recordGeneration]'s three writes. That leaves the API layer a decision this
 * class deliberately does not make for it. The recommendation, so that Task 1.12 decides it rather
 * than invents it:
 *
 * - **[checkGeneration] should fail closed.** A quota check that could not be evaluated is not
 *   evidence that budget remains, and this component exists precisely to stop a runaway bill.
 *   Refuse the generation and serve whatever the cache can still serve.
 * - **[recordGeneration] should fail loudly but must not fail the request.** By the time it runs the
 *   money is already spent, so withholding the explanation wastes it. Log it as a spend-accounting
 *   gap — it is the one condition under which the ledger is known to understate reality.
 *
 * ## The allowance is a parameter and not a field
 *
 * A tier decides an allowance. This module must not learn what a tier is. The caller therefore
 * resolves an [Allowance] and gives it to this class. [QuotaConfig.defaultAllowance] keeps every
 * caller that has no tier unchanged.
 *
 * On [recordGeneration] the allowance is the THIRD parameter. `costMicros` stays the second.
 * `SessionRoutes` calls the method positionally. An allowance in the second position binds a cost
 * to an allowance. Both values are numbers. The compiler therefore reports nothing.
 */
class QuotaService(
    private val repository: QuotaRepository,
    private val config: QuotaConfig = QuotaConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    /** The ledger key is a UTC calendar day, so the budget resets at midnight UTC — not 24h after the first spend. */
    private fun today(): String = dayFormat.format(Instant.ofEpochMilli(clock()))

    /**
     * Gates GENERATION only. Callers must keep serving cache hits when this is not
     * [QuotaDecision.Allowed] — a cache hit costs nothing, and serving them is what keeps the site
     * usable during a spend incident.
     *
     * The spend check comes first on purpose. A principal who is over both limits is told the money
     * ran out, because a `Retry-After` derived from their personal window would promise generation
     * resuming at a time that has nothing to do with when it actually will.
     *
     * The verdict is a snapshot, not a reservation — see the class KDoc for the bound that leaves.
     */
    suspend fun checkGeneration(
        principalId: PrincipalId,
        allowance: Allowance = config.defaultAllowance,
    ): QuotaDecision {
        if (dailySpendMicros() >= config.globalDailyCostCeilingMicros) {
            return QuotaDecision.SpendLimitReached
        }

        val now = clock()
        val counter = repository.findCounter(principalId.value)

        if (counter == null || now >= counter.windowExpiresAtEpochMillis) return QuotaDecision.Allowed

        return if (counter.explainCount >= allowance.generations) {
            QuotaDecision.PrincipalExceeded(
                // Floored at 1: the true answer rounds to 0 inside the last second of the window,
                // and a Retry-After of 0 invites an immediate retry that would still be refused.
                retryAfterSeconds = ((counter.windowExpiresAtEpochMillis - now) / 1000).coerceAtLeast(1),
            )
        } else {
            QuotaDecision.Allowed
        }
    }

    /**
     * Records one generation and what it cost. Three single-document updates, no read: N concurrent
     * calls for the same principal leave a count of exactly N, whether they arrive on a fresh
     * counter or across a window rollover.
     *
     * **The order is load-bearing twice over**, because these are three independent round trips with
     * no transaction between them and no error handling around them.
     *
     * The global ledger goes first. If a later write then fails — a blip, a step-down, a timeout —
     * the money is already where the breaker will see it. This fails toward *over-reporting global
     * spend and under-charging one principal's allowance*, and that is the cheaper error for a
     * component whose whole job is to stop a runaway bill: the ledger is the artifact that protects
     * the account, a lost principal increment costs at most one free explanation, and a lost ledger
     * write is invisible until the invoice arrives. Writing the ledger last reverses that trade and
     * fails open on money.
     *
     * Then the rollover must precede the increment and never follow it —
     * [QuotaRepository.rollWindowIfExpired] explains why that is what keeps a straggling reset from
     * erasing a live count.
     */
    suspend fun recordGeneration(
        principalId: PrincipalId,
        costMicros: Long,
        allowance: Allowance = config.defaultAllowance,
    ) {
        // A negative cost would walk the ledger backwards and un-trip a breaker that had already
        // tripped. It is also the unstated premise of the class KDoc's "monotone within a UTC day".
        require(costMicros >= 0) { "costMicros must not be negative, was $costMicros" }

        val now = clock()
        repository.incrementLedger(today(), costMicros)
        repository.rollWindowIfExpired(principalId.value, now, allowance.windowMillis)
        repository.incrementCounter(principalId.value, now, allowance.windowMillis, costMicros)
    }

    suspend fun dailySpendMicros(): Long = repository.ledgerFor(today())?.costMicros ?: 0

    /**
     * Clears [principalId]'s counter when its stored window length no longer matches [allowance].
     *
     * A counter's window is set once, at creation, by [QuotaRepository.incrementCounter]'s own
     * `$setOnInsert`, and does not move again on its own. A learner's entitlement can still
     * change under it — a trial ending, a subscription starting — and the counter does not know.
     * Left alone, a learner who has just subscribed keeps the trial's own window and the count
     * against it, and can be refused against a new allowance for as long as that old window still
     * has left to run.
     *
     * An absent counter is a no-op; there is nothing to align. Call this before a check or a
     * record, never after — the check that follows must see the aligned window, not the stale one.
     */
    suspend fun alignWindow(principalId: PrincipalId, allowance: Allowance) {
        val counter = repository.findCounter(principalId.value) ?: return
        val storedWindowMillis = counter.windowExpiresAtEpochMillis - counter.windowStartEpochMillis
        if (storedWindowMillis != allowance.windowMillis) {
            repository.resetCounter(principalId.value)
        }
    }
}
