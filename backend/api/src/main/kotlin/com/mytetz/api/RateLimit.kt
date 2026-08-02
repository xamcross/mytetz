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
 * - **The socket peer** (`origin.remoteAddress`) is the fly proxy on the machine host — neither
 *   `ForwardedHeaders` nor `XForwardedHeaders` is installed, so this really is the raw peer. It is
 *   unforgeable and **useless**: it is a single address for the entire internet.
 * - **`Fly-Client-IP`** is *set* by fly's proxy — fly's own documentation calls it "the IP address
 *   of the client from the perspective of Fly Proxy", and says that with another reverse proxy in
 *   front it reflects that proxy's address. So it is unforgeable, and behind Cloudflare it is the CF
 *   edge: coarse, one bucket per edge.
 * - **`CF-Connecting-IP`** is the visitor's real address and is what you actually want — but it is
 *   trustworthy only for traffic that came through Cloudflare, and `mytetz.fly.dev` is reachable
 *   directly, so a request arriving that way can set it to anything.
 *
 * ## The default is `Fly-Client-IP`, and the reasoning that got this wrong once
 *
 * The first version of this class defaulted to trusting **no** header, on the grounds that no header
 * is both accurate and unforgeable. That is a true statement and the wrong dichotomy, because it
 * quietly treats "the socket peer" as the safe fallback — and the socket peer is one key for
 * everybody. With a 10-per-day allowance that is ten topic requests per day *for the whole
 * internet*, spendable by one stranger in a second, and it does not self-heal because the window
 * only rolls after 24 hours. The ceiling below is irrelevant when there is exactly one key. Trading
 * the feature's entire usefulness for safety that the fallback did not actually provide.
 *
 * `Fly-Client-IP` dominates the socket peer on every axis: equally unforgeable, strictly finer. So it
 * is the default. `CF-Connecting-IP` is finer still and is what this deployment sets in `fly.toml`,
 * accepting that someone reaching fly directly can forge it — an evasion bounded by the ceiling here
 * and by the eviction cap in `TopicRequestRepository`, and one that costs honest visitors nothing.
 * "Trust nothing" remains available for a deployment behind neither, by setting the variable to
 * `none`; it is simply no longer what you get by saying nothing.
 *
 * **This is why the ceiling in [FixedWindowRateLimiter] is not optional.** The memory bound must not
 * depend on this resolution being right, because a forged header makes it wrong.
 */
object ClientAddress {

    /** Set by fly's proxy, so a caller cannot choose it. See the note above. */
    const val FLY_CLIENT_IP: String = "Fly-Client-IP"

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
 * Which header carries the real client address on this deployment.
 *
 * Defaults to [ClientAddress.FLY_CLIENT_IP]. Null means "trust nothing the caller sent", and has to
 * be asked for explicitly with `none` — see [ClientAddress] for why that is not the default, and
 * what it cost when it was.
 */
data class ClientAddressConfig(
    val trustedHeader: String? = resolveTrustedHeader(System.getenv(CLIENT_IP_HEADER_ENV)),
) {
    companion object {

        const val CLIENT_IP_HEADER_ENV: String = "MYTETZ_CLIENT_IP_HEADER"

        /** The one value that means "no header at all". Anything else is taken as a header name. */
        const val NO_TRUSTED_HEADER: String = "none"

        /**
         * Unset or blank gives [ClientAddress.FLY_CLIENT_IP] — the coarsest key that is still a key,
         * rather than the socket peer, which is not one. A deployment that is behind neither fly nor
         * a proxy it controls sets `none`.
         */
        internal fun resolveTrustedHeader(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            return when {
                value.isEmpty() -> ClientAddress.FLY_CLIENT_IP
                value.equals(NO_TRUSTED_HEADER, ignoreCase = true) -> null
                else -> value
            }
        }
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
