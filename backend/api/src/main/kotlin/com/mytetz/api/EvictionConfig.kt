package com.mytetz.api

/**
 * The knobs a deployment may turn on explanation-store eviction. Both are read while the process
 * is starting, and [Components.evictExplanations] is the only reader.
 *
 * ## Why `maxRequestCount` breaks the usual fall-back rule
 *
 * Every other resolver in this codebase — `GraphConfig.resolveMaxOutputTokens`,
 * `QuotaConfig.resolveDailyExplains`, `SessionLimits.resolveMaxDepth` — rejects zero along with
 * the negatives, because zero would remove a bound. Here zero is the opposite: it is the default,
 * and it names the exact case eviction exists for, a document nobody has read since it was made.
 * [resolveMaxRequestCount] therefore accepts zero and rejects only a negative override.
 *
 * [resolveMaxAgeDays] keeps the usual rule. An age of zero would mark every document as old
 * enough the instant it is written, which removes the age bound rather than tightening it.
 */
data class EvictionConfig(
    val maxRequestCount: Long = resolveMaxRequestCount(System.getenv(MAX_REQUEST_COUNT_ENV)),
    val maxAgeDays: Long = resolveMaxAgeDays(System.getenv(MAX_AGE_DAYS_ENV)),
) {

    init {
        require(maxRequestCount >= 0) { "maxRequestCount must not be negative, was $maxRequestCount" }
        require(maxAgeDays > 0) { "maxAgeDays must be positive, was $maxAgeDays" }
    }

    companion object {

        const val MAX_REQUEST_COUNT_ENV: String = "MYTETZ_EVICTION_MAX_REQUEST_COUNT"
        const val MAX_AGE_DAYS_ENV: String = "MYTETZ_EVICTION_MAX_AGE_DAYS"

        const val DEFAULT_MAX_REQUEST_COUNT: Long = 0
        const val DEFAULT_MAX_AGE_DAYS: Long = 90

        /**
         * A missing or unparseable override falls back to the default. A negative override also
         * falls back, because a negative request count names no document. Zero is not rejected:
         * it is the default, and a caller that sets it explicitly means the same thing the
         * default already means.
         */
        internal fun resolveMaxRequestCount(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: DEFAULT_MAX_REQUEST_COUNT

        /**
         * A missing, unparseable or non-positive override falls back to the default rather than
         * throwing, the same rule [com.mytetz.graph.GraphConfig.resolveMaxOutputTokens] states.
         * Zero is rejected along with the negatives: it would mark every document as old enough
         * the instant it is written, which is a louder failure than the one the fall-back guards
         * against.
         */
        internal fun resolveMaxAgeDays(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_AGE_DAYS
    }
}
