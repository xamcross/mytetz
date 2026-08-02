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
 * The same non-atomicity applies to [QuotaConfig.dailyExplains]: a principal issuing requests in
 * parallel can exceed their allowance by however many they get in flight before the first records.
 * What *is* guaranteed is that no generation is admitted once the ledger has crossed the ceiling,
 * and that N recorded generations are counted as exactly N — the recording side is atomic even
 * though the check-then-record pair is not.
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
    suspend fun checkGeneration(principalId: PrincipalId): QuotaDecision {
        if (dailySpendMicros() >= config.globalDailyCostCeilingMicros) {
            return QuotaDecision.SpendLimitReached
        }

        val now = clock()
        val counter = repository.findCounter(principalId.value)

        if (counter == null || now >= counter.windowExpiresAtEpochMillis) return QuotaDecision.Allowed

        return if (counter.explainCount >= config.dailyExplains) {
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
     * The rollover must be attempted before the increment, never after — [QuotaRepository.rollWindowIfExpired]
     * explains why that ordering is what keeps a straggling reset from erasing a live count.
     */
    suspend fun recordGeneration(principalId: PrincipalId, costMicros: Long) {
        val now = clock()
        repository.rollWindowIfExpired(principalId.value, now, config.windowMillis)
        repository.incrementCounter(principalId.value, now, config.windowMillis, costMicros)
        repository.incrementLedger(today(), costMicros)
    }

    suspend fun dailySpendMicros(): Long = repository.ledgerFor(today())?.costMicros ?: 0
}
