package com.mytetz.assess

import com.mytetz.llm.LlmEffort
import kotlin.test.Test
import kotlin.test.assertEquals

class QuizConfigTest {

    @Test
    fun `an unset override falls back to the default`() {
        assertEquals(QuizConfig.DEFAULT_MAX_OUTPUT_TOKENS, QuizConfig.resolveMaxOutputTokens(null))
        assertEquals(QuizConfig.DEFAULT_EFFORT, QuizConfig.resolveEffort(null))
        assertEquals(3, QuizConfig.resolvePositiveInt(null, 3))
    }

    @Test
    fun `a non-positive or unparseable override falls back to the default`() {
        assertEquals(QuizConfig.DEFAULT_MAX_OUTPUT_TOKENS, QuizConfig.resolveMaxOutputTokens("0"))
        assertEquals(QuizConfig.DEFAULT_MAX_OUTPUT_TOKENS, QuizConfig.resolveMaxOutputTokens("not-a-number"))
        assertEquals(3, QuizConfig.resolvePositiveInt("-1", 3))
    }

    @Test
    fun `a recognised override is kept`() {
        assertEquals(5000L, QuizConfig.resolveMaxOutputTokens("5000"))
        assertEquals(LlmEffort.HIGH, QuizConfig.resolveEffort("high"))
        assertEquals(7, QuizConfig.resolvePositiveInt("7", 3))
    }
}
