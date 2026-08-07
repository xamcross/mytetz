package com.mytetz.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class PricingTest {

    @Test
    fun `opus 5 charges 5 micros per input token and 25 per output token`() {
        val usage = LlmUsage(inputTokens = 1000, outputTokens = 200, 0, 0)

        assertEquals(1000 * 5 + 200 * 25, Pricing.costMicros("claude-opus-5", usage))
    }

    @Test
    fun `cache reads are a tenth of input and cache writes are 1_25x`() {
        val usage = LlmUsage(inputTokens = 0, outputTokens = 0, cacheReadInputTokens = 1000, cacheCreationInputTokens = 400)

        // 1000 * 0.5 + 400 * 6.25 = 500 + 2500
        assertEquals(3000, Pricing.costMicros("claude-opus-5", usage))
    }

    @Test
    fun `haiku is five times cheaper than opus`() {
        val usage = LlmUsage(1000, 1000, 0, 0)

        assertEquals(
            Pricing.costMicros("claude-opus-5", usage) / 5,
            Pricing.costMicros("claude-haiku-4-5", usage),
        )
    }

    @Test
    fun `an unknown model falls back to the most expensive rate rather than zero`() {
        val usage = LlmUsage(1000, 1000, 0, 0)

        assertEquals(
            Pricing.costMicros("claude-opus-5", usage),
            Pricing.costMicros("some-future-model", usage),
        )
    }

    @Test
    fun `sonnet 5 bills at the published rate`() {
        // $3 for each 1M input tokens and $15 for each 1M output tokens, in micro-dollars for one
        // token. If this test fails, the published rate moved. Recompute section 3 of the
        // monetization specification before you accept the change.
        val usage = LlmUsage(inputTokens = 1_000_000, outputTokens = 1_000_000)

        assertEquals(3_000_000L + 15_000_000L, Pricing.costMicros("claude-sonnet-5", usage))
    }

    @Test
    fun `one explanation on sonnet 5 costs about a cent`() {
        // The shape section 3.1 of the specification measures: ~1000 in, ~500 out.
        val usage = LlmUsage(inputTokens = 1_000, outputTokens = 500)

        assertEquals(10_500L, Pricing.costMicros("claude-sonnet-5", usage), "10 500 micro-dollars is \$0.0105")
    }
}
