package com.mytetz.quota

/**
 * How many generations a principal may make, and the length of the window they share.
 *
 * This type is the reason [QuotaService] still knows nothing about a tier, a trial or a
 * subscription. A caller resolves an allowance and passes it in; this module counts and refuses.
 * The `billing` module owns the question "which allowance", and it is a separate module for exactly
 * that reason.
 *
 * Both bounds are checked here and not at the call site. A count of zero refuses every generation
 * for ever, and a window of zero makes every stored window already expired — which makes
 * [QuotaRepository.rollWindowIfExpired] reset the counter on every call and removes the allowance
 * with nothing in the log to say so. The same failure, and the same reasoning, as [QuotaConfig].
 */
data class Allowance(val generations: Int, val windowMillis: Long) {

    init {
        require(generations > 0) { "generations must be positive, was $generations" }
        require(windowMillis > 0) { "windowMillis must be positive, was $windowMillis" }
    }
}
