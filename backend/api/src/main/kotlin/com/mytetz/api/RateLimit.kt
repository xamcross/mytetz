package com.mytetz.api

/**
 * A fixed-window request allowance, per key, held in memory.
 *
 * ## What it is for
 *
 * `POST /api/topic-requests` is unauthenticated and writes to the database. `TopicRequestRepository`
 * bounds what that can do to *storage* — the collection cannot grow past its cap — but a cap alone
 * still lets one client fill every slot with junk and destroy the demand signal for everybody else.
 * This bounds how fast one principal may try.
 *
 * ## Why in memory, and what that honestly does not cover
 *
 * The alternative is a Mongo counter with a TTL index, the shape `QuotaRepository` already uses.
 * That would survive restarts and be shared across instances, at the cost of a round trip on every
 * request to a public endpoint and a second collection to reason about. It is not worth it here, and
 * the reasons are worth writing down rather than discovering later:
 *
 * - **This deployment is one machine**, scaled to zero (`min_machines_running = 0` in `fly.toml`).
 *   "Shared across instances" currently means shared with nothing.
 * - **The consequence of a reset is one extra allowance**, not a lost invariant. Nothing here
 *   protects money — [com.mytetz.quota.QuotaService] does that, and it is *deliberately* in Mongo.
 *
 * The limitations, stated plainly rather than implied:
 *
 * 1. **It resets on restart**, and on this deployment a restart happens on the first request after
 *    an idle period. A patient attacker gets a fresh allowance per wake.
 * 2. **It is per instance.** The moment `min_machines_running` goes above 1, the effective limit is
 *    the configured one times the instance count.
 * 3. **It is keyed on a principal, and principals are free.** A cookie costs one round trip to mint,
 *    so an attacker willing to discard cookies multiplies their allowance. The storage cap in
 *    `TopicRequestRepository` is what actually holds in that case; this makes it expensive rather
 *    than impossible.
 *
 * A fixed window rather than a sliding one for the same reason `QuotaService` uses one: it needs no
 * per-request history, so its memory is bounded by the number of distinct keys seen in a single
 * window and not by traffic. That bound only holds because [tryAcquire] clears the table on the
 * roll, which is pinned by a test.
 */
class FixedWindowRateLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        // Both remove the bound rather than tighten it. A limit of 0 refuses every request, which
        // reads to a learner as a broken feature and not as a misconfigured deployment; a window of
        // 0 makes every window already expired, so the table clears on every call and the allowance
        // silently disappears. Same shape as the `require`s in QuotaConfig and SessionLimits.
        require(limit > 0) { "limit must be positive, was $limit" }
        require(windowMillis > 0) { "windowMillis must be positive, was $windowMillis" }
    }

    private val counts = HashMap<String, Int>()
    private var windowStart = 0L

    /** How many keys are currently tracked. A test seam: the memory bound is the whole design. */
    val trackedKeys: Int get() = synchronized(counts) { counts.size }

    /** True if [key] had allowance left, and spends one. False leaves the count untouched. */
    fun tryAcquire(key: String): Boolean = synchronized(counts) {
        val now = clock()
        if (now - windowStart >= windowMillis) {
            windowStart = now
            counts.clear()
        }

        val spent = (counts[key] ?: 0) + 1
        if (spent > limit) return@synchronized false
        counts[key] = spent
        true
    }
}
