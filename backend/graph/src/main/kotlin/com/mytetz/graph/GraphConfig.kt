package com.mytetz.graph

import com.mytetz.llm.LlmEffort

/**
 * The knobs a deployment may turn on generation. Everything here is part of *what the model is
 * asked and how much it may spend*, never part of who is asking.
 *
 * [promptVersion] is the config-level copy of [PromptBuilder.VERSION] rather than a direct read of
 * it, because it is hashed into every content key: a deployment that needs to invalidate the whole
 * store (or pin an old one) can do so without editing the prompt itself.
 */
data class GraphConfig(
    val promptVersion: String = PromptBuilder.VERSION,
    /**
     * Caps thinking AND response text together on Claude Opus 5, where adaptive thinking is on by
     * default. A ceiling sized to the prose alone truncates mid-answer. Length is enforced by
     * [ExplanationValidator]; billing is on actual output, so headroom is free.
     */
    val maxOutputTokens: Long = resolveMaxOutputTokens(System.getenv(MAX_OUTPUT_TOKENS_ENV)),
    val effort: LlmEffort = resolveEffort(System.getenv(EFFORT_ENV)),
) {

    companion object {

        const val MAX_OUTPUT_TOKENS_ENV: String = "MYTETZ_MAX_OUTPUT_TOKENS"
        const val EFFORT_ENV: String = "MYTETZ_EFFORT"

        const val DEFAULT_MAX_OUTPUT_TOKENS: Long = 4000
        val DEFAULT_EFFORT: LlmEffort = LlmEffort.LOW

        /**
         * A missing, unparseable or non-positive override falls back to the default rather than
         * throwing. These are read while the process is starting; a typo in a deployment
         * environment variable must not take the server down, and the default is the safe value —
         * the same reasoning, and the same shape, as `AnthropicLlmClient.resolveTimeoutSeconds`.
         */
        internal fun resolveMaxOutputTokens(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_OUTPUT_TOKENS

        /** An unrecognised effort name falls back to the cheapest setting, never the dearest. */
        internal fun resolveEffort(raw: String?): LlmEffort =
            raw?.trim()?.uppercase()?.let { name -> LlmEffort.entries.firstOrNull { it.name == name } }
                ?: DEFAULT_EFFORT
    }
}
