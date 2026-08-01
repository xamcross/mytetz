package com.mytetz.llm

/**
 * USD micro-dollars per token. $5 per million tokens == 5 micro-dollars per token.
 * Cache reads bill at ~0.1x input; 5-minute cache writes at 1.25x input.
 */
object Pricing {

    private data class Rate(val input: Double, val output: Double) {
        val cacheRead get() = input * 0.1
        val cacheWrite get() = input * 1.25
    }

    private val rates = mapOf(
        "claude-opus-5" to Rate(input = 5.0, output = 25.0),
        "claude-opus-4-8" to Rate(input = 5.0, output = 25.0),
        "claude-sonnet-5" to Rate(input = 3.0, output = 15.0),
        "claude-haiku-4-5" to Rate(input = 1.0, output = 5.0),
    )

    /** Unknown models bill at the most expensive known rate — never free. */
    private val fallback = rates.values.maxByOrNull { it.output }!!

    fun costMicros(modelId: String, usage: LlmUsage): Long {
        val rate = rates[modelId] ?: fallback
        val total = usage.inputTokens * rate.input +
            usage.outputTokens * rate.output +
            usage.cacheReadInputTokens * rate.cacheRead +
            usage.cacheCreationInputTokens * rate.cacheWrite
        return Math.round(total)
    }
}
