package com.mytetz.quota

/**
 * How many generations a principal may make, and the length of the window they share.
 *
 * This type keeps [QuotaService] free of a tier, a trial and a subscription. A caller resolves an
 * allowance. The caller then gives it to this module. This module counts. This module refuses.
 * The `billing` module owns the question "which allowance".
 *
 * This class checks both bounds. The call site does not.
 *
 * A count of zero refuses every generation for ever. A window of zero makes every stored window
 * already expired. [QuotaRepository.rollWindowIfExpired] then resets the counter on every call.
 * The allowance disappears. The log says nothing. [QuotaConfig] holds the same reasoning.
 */
data class Allowance(val generations: Int, val windowMillis: Long) {

    init {
        require(generations > 0) { "generations must be positive, was $generations" }
        require(windowMillis > 0) { "windowMillis must be positive, was $windowMillis" }
    }
}
