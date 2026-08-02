package com.mytetz.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * Which caller a request is attributed to, for rate limiting.
 *
 * ## Why this is not the principal
 *
 * The obvious key is the anonymous principal — it is right there, and every request has one. It is
 * also **worthless as a rate-limit key**, because `Principals.resolve` mints a fresh principal for
 * any request arriving without a valid cookie. A caller that simply discards cookies gets a new key
 * per request, so a per-principal limit limits nobody except honest browsers, and the limiter's
 * table grows by one entry per request for the length of its window. That was a real defect in the
 * first cut of this task, not a hypothetical.
 *
 * ## What can actually be trusted, given this deployment
 *
 * `docs/deploy.md` records the chain: `browser -> Cloudflare (proxied, Full (strict)) -> fly.io
 * Anycast -> machine in fra -> Ktor :8080`. Every hop rewrites who the caller appears to be, and
 * they are not equally trustworthy:
 *
 * - **The socket peer** (`origin.remoteAddress`) is the fly proxy, not the visitor. Unforgeable, and
 *   nearly useless alone: it groups much of the internet into one bucket.
 * - **`Fly-Client-IP`** is written by fly's proxy from the peer *it* saw. Unforgeable, but behind
 *   Cloudflare that peer is a Cloudflare edge, so it groups everyone sharing an edge.
 * - **`CF-Connecting-IP`** is the visitor's real address and is what you actually want — but it is
 *   trustworthy only for traffic that came through Cloudflare. `mytetz.fly.dev` is reachable
 *   directly, and a request arriving that way can set the header to anything.
 *
 * No header here is both accurate and unforgeable, so this does not pretend otherwise. The header is
 * **opt-in by name** ([ClientAddressConfig.trustedHeader]) and the default is the peer — because a
 * wrong default is worse than a coarse one: it would hand every caller a switch for choosing its own
 * rate-limit bucket. The operator opts in once they have decided which proxy they are behind.
 *
 * **This is why the ceiling in [FixedWindowRateLimiter] is not optional.** The memory bound must not
 * depend on this resolution being right, because on a direct-to-fly request it can be wrong.
 */
object ClientAddress {

    /**
     * Long enough for an IPv6 address with a zone and a port, short enough that a caller cannot use
     * the key itself to spend memory: the resolved value is held in a map for a whole window.
     */
    const val MAX_ADDRESS_LENGTH: Int = 64

    fun of(call: ApplicationCall, config: ClientAddressConfig): String {
        val forwarded = config.trustedHeader
            ?.let { call.request.headers[it] }
            // `X-Forwarded-For` is a list, oldest first, so the client is the first entry. How many
            // entries are trustworthy depends entirely on how many proxies rewrite it — see above.
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return (forwarded ?: call.request.origin.remoteAddress).take(MAX_ADDRESS_LENGTH)
    }
}

/**
 * Which header, if any, carries the real client address on this deployment.
 *
 * Null — the default — means "trust nothing the caller sent". See [ClientAddress] for why that is
 * the safe default rather than the timid one.
 */
data class ClientAddressConfig(
    val trustedHeader: String? = resolveTrustedHeader(System.getenv(CLIENT_IP_HEADER_ENV)),
) {
    companion object {

        const val CLIENT_IP_HEADER_ENV: String = "MYTETZ_CLIENT_IP_HEADER"

        /** Unset or blank means no header is trusted. There is deliberately no default header name. */
        internal fun resolveTrustedHeader(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * A fixed-window request allowance, per key, held in memory.
 *
 * ## What it is for
 *
 * `POST /api/topic-requests` is unauthenticated and writes to the database. `TopicRequestRepository`
 * bounds what that can do to *storage*; this bounds how fast one caller may try.
 *
 * ## Two bounds, and the second is the load-bearing one
 *
 * [limit] bounds requests per key per window. [maxTrackedKeys] bounds the **table itself**, and it
 * exists because the first version of this class did not have it and was therefore the very
 * unbounded-growth defect it was written to prevent, relocated from disk to heap: the table was
 * cleared only when the window rolled, the window is 24 hours, and nothing made distinct keys
 * expensive. "Bounded by the number of distinct keys seen in one window" is a bound only if keys
 * cost something, and [ClientAddress] explains why, on a direct-to-fly request, they may not.
 *
 * The ceiling is hard and does not depend on key resolution being correct. On a `shared-cpu-1x` with
 * `-XX:MaxRAMPercentage=75.0` and `-XX:+UseSerialGC` (see `Dockerfile`) there is no margin for
 * discovering this the other way.
 *
 * **Full means evict, never refuse.** Refusing an unrecognised key once the table is full would let
 * anyone churning keys turn the endpoint off for everybody for a whole window — trading a memory bug
 * for an availability bug. Eviction is least-recently-used, so a caller making steady real use of
 * the endpoint outlives a flood of one-shot keys. Churn may degrade how well the limit works; it
 * must not decide who gets served.
 *
 * ## What this honestly does not cover
 *
 * The alternative is a Mongo counter with a TTL index, the shape `QuotaRepository` already uses.
 * That would survive restarts and be shared across instances, at the cost of a round trip on every
 * request to a public endpoint. It is not worth it here: this deployment is one machine scaled to
 * zero, so "shared across instances" means shared with nothing, and the consequence of a reset is
 * one extra allowance rather than a lost invariant — nothing here protects money.
 * [com.mytetz.quota.QuotaService] does that, and it is *deliberately* in Mongo.
 *
 * So, plainly:
 *
 * 1. **It resets on restart**, and on this deployment a restart is the first request after an idle
 *    period.
 * 2. **It is per instance.** Above `min_machines_running = 1` the effective limit multiplies by the
 *    instance count.
 * 3. **A caller who can forge the trusted header can still churn keys.** They cannot exhaust memory
 *    (the ceiling) and cannot deny service (eviction), but they can dilute the limit. The storage
 *    cap in `TopicRequestRepository` is what holds in that case.
 */
class FixedWindowRateLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        // Each removes a bound rather than tightening it. A limit of 0 refuses every request, which
        // reads as a broken feature and not as a misconfigured deployment; a window of 0 makes every
        // window already expired, so the table clears on every call and the allowance silently
        // disappears; a ceiling of 0 evicts every key as soon as it is written, doing the same thing
        // more quietly. Same shape as the `require`s in QuotaConfig and SessionLimits.
        require(limit > 0) { "limit must be positive, was $limit" }
        require(windowMillis > 0) { "windowMillis must be positive, was $windowMillis" }
        require(maxTrackedKeys > 0) { "maxTrackedKeys must be positive, was $maxTrackedKeys" }
    }

    /**
     * Access-ordered, so `removeEldestEntry` drops the least *recently used* key rather than the
     * first one inserted. Insertion order would evict a caller who has been using the endpoint all
     * window in favour of one that arrived once — exactly backwards under a flood.
     */
    private val counts = object : LinkedHashMap<String, Int>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > maxTrackedKeys
    }

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

    companion object {
        /** ~10k entries of a length-bounded key: low single-digit megabytes at the very most. */
        const val DEFAULT_MAX_TRACKED_KEYS: Int = 10_000

        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
