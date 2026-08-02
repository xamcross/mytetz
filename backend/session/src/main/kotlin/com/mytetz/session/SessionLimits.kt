package com.mytetz.session

/**
 * The knobs a deployment may turn on how far one session may grow.
 *
 * ## Why this is an injectable data class and not an `object` of constants
 *
 * The task brief specified `object SessionLimits { const val MAX_DEPTH: Int; … }` and then gave
 * bodies that call `System.getenv`, which a `const val` cannot do. Of the two shapes only one is
 * consistent with the rest of this codebase, and it is this one:
 *
 * 1. `GraphConfig` and `QuotaConfig` are both injectable data classes precisely so a test can vary
 *    them. An `object` reading the environment at class-initialisation cannot be varied at all —
 *    a test for "a session that has reached its depth limit" would have to build a twelve-deep
 *    tree, or set an environment variable for the whole JVM and hope no other test minds.
 * 2. A throw inside an `object`'s property initialiser surfaces as `ExceptionInInitializerError`
 *    at first touch, arbitrarily far from the misconfiguration that caused it — and on every
 *    subsequent touch as `NoClassDefFoundError`, which names nothing at all. A constructor
 *    failure names the class and the value.
 *
 * The cost is that the properties are `maxDepth`/`maxNodes`/`maxVariants` rather than
 * `MAX_DEPTH`/`MAX_NODES`/`MAX_VARIANTS`, and that Task 1.10 must take a `SessionLimits` parameter
 * (defaulting to `SessionLimits()`) instead of reaching for a global. That is the same shape
 * `SessionService` already uses for its clock and id factory.
 *
 * ## Where these are enforced
 *
 * Nowhere in this file, and nowhere else in Task 1.9 — deliberately. All three are admission
 * decisions about a *new* node, and the only place that decides to create one is
 * `SessionService.explain` (Task 1.10):
 *
 * - [maxNodes]    — refuse when `session.nodes.size >= maxNodes`.
 * - [maxDepth]    — refuse when `parent.depth + 1 > maxDepth`.
 * - [maxVariants] — refuse when the variant about to be used exceeds [maxVariants]; the variant
 *   itself comes from [ContextChain.highestVariant] `+ 1`.
 *
 * [SessionRepository.appendNode] deliberately enforces none of them. It is the write primitive; a
 * limit checked there would be checked after the model had already been paid for.
 */
data class SessionLimits(
    /** Links below the root, so a session may hold `maxDepth + 1` levels including the seed. */
    val maxDepth: Int = resolveMaxDepth(System.getenv(MAX_DEPTH_ENV)),
    val maxNodes: Int = resolveMaxNodes(System.getenv(MAX_NODES_ENV)),
    /**
     * The highest variant number a single (parent, span, verb) triple may reach. Variant 0 is the
     * original, so this permits `maxVariants` regenerations of it — see
     * [ContextChain.highestVariant] for why the numbering starts where it does.
     */
    val maxVariants: Int = resolveMaxVariants(System.getenv(MAX_VARIANTS_ENV)),
) {

    init {
        // The resolvers already reject a non-positive *override*, but a caller constructing this
        // directly bypasses them, and each of the three at zero removes a bound rather than
        // tightening it: maxDepth 0 refuses the first explain, maxNodes 0 refuses a session that
        // has only its seed, and maxVariants 0 refuses the first "explain it another way". All
        // three read to a learner as a broken feature, not as a misconfigured deployment.
        require(maxDepth > 0) { "maxDepth must be positive, was $maxDepth" }
        require(maxNodes > 0) { "maxNodes must be positive, was $maxNodes" }
        require(maxVariants > 0) { "maxVariants must be positive, was $maxVariants" }
    }

    companion object {

        const val MAX_DEPTH_ENV: String = "MYTETZ_MAX_DEPTH"
        const val MAX_NODES_ENV: String = "MYTETZ_MAX_SESSION_NODES"
        const val MAX_VARIANTS_ENV: String = "MYTETZ_MAX_VARIANTS"

        const val DEFAULT_MAX_DEPTH: Int = 12
        const val DEFAULT_MAX_NODES: Int = 200
        const val DEFAULT_MAX_VARIANTS: Int = 3

        /**
         * A missing, unparseable or non-positive override falls back to the default rather than
         * throwing. These are read while the process is starting; a typo in a deployment
         * environment variable must not take the server down, and the default is the safe value —
         * the same reasoning, and the same shape, as `GraphConfig.resolveMaxOutputTokens` and
         * `QuotaConfig.resolveDailyExplains`.
         */
        internal fun resolveMaxDepth(raw: String?): Int = resolvePositive(raw, DEFAULT_MAX_DEPTH)

        internal fun resolveMaxNodes(raw: String?): Int = resolvePositive(raw, DEFAULT_MAX_NODES)

        internal fun resolveMaxVariants(raw: String?): Int = resolvePositive(raw, DEFAULT_MAX_VARIANTS)

        /** Named per knob above so a knob that later needs its own rule has somewhere to put it. */
        private fun resolvePositive(raw: String?, default: Int): Int =
            raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: default
    }
}
