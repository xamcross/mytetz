package com.mytetz.quota

/**
 * The knobs a deployment may turn on spend. Both overrides are read while the process is starting.
 */
data class QuotaConfig(
    val dailyExplains: Int = resolveDailyExplains(System.getenv(DAILY_EXPLAINS_ENV)),
    val windowMillis: Long = 86_400_000,
    /** USD micro-dollars. Default $50/day. */
    val globalDailyCostCeilingMicros: Long = resolveCostCeilingMicros(System.getenv(COST_CEILING_ENV)),
) {

    companion object {

        const val DAILY_EXPLAINS_ENV: String = "MYTETZ_DAILY_EXPLAINS"
        const val COST_CEILING_ENV: String = "MYTETZ_GLOBAL_DAILY_COST_CEILING_USD_MICROS"

        const val DEFAULT_DAILY_EXPLAINS: Int = 20
        const val DEFAULT_COST_CEILING_MICROS: Long = 50_000_000

        /**
         * A missing, unparseable or non-positive override falls back to the default rather than
         * throwing. These are read while the process is starting; a typo in a deployment
         * environment variable must not take the server down, and the default is the safe value —
         * the same reasoning, and the same shape, as `GraphConfig.resolveMaxOutputTokens`.
         */
        internal fun resolveDailyExplains(raw: String?): Int =
            raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_DAILY_EXPLAINS

        /**
         * Zero is rejected along with the negatives: a ceiling of 0 trips the breaker permanently
         * and takes generation offline, which is a louder failure than the one being guarded
         * against. A deployment that wants generation off should not be doing it by typo.
         */
        internal fun resolveCostCeilingMicros(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_COST_CEILING_MICROS
    }
}
