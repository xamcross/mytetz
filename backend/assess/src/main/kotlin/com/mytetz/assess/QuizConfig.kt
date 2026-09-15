package com.mytetz.assess

import com.mytetz.llm.LlmEffort

/**
 * The knobs a deployment may turn on quiz generation. Mirrors `com.mytetz.graph.GraphConfig`
 * exactly, including the fallback rule: a missing, unparseable or non-positive override falls back
 * to the default rather than throwing, because a typo in a deployment variable must not take the
 * server down.
 */
data class QuizConfig(
    val promptVersion: String = QuizPromptBuilder.VERSION,
    val maxOutputTokens: Long = resolveMaxOutputTokens(System.getenv(MAX_OUTPUT_TOKENS_ENV)),
    val effort: LlmEffort = resolveEffort(System.getenv(EFFORT_ENV)),
    val testMeMaxQuestions: Int = resolvePositiveInt(System.getenv(TEST_ME_MAX_QUESTIONS_ENV), DEFAULT_TEST_ME_MAX_QUESTIONS),
    val examMaxQuestions: Int = resolvePositiveInt(System.getenv(EXAM_MAX_QUESTIONS_ENV), DEFAULT_EXAM_MAX_QUESTIONS),
) {
    companion object {
        const val MAX_OUTPUT_TOKENS_ENV: String = "MYTETZ_QUIZ_MAX_OUTPUT_TOKENS"
        const val EFFORT_ENV: String = "MYTETZ_QUIZ_EFFORT"
        const val TEST_ME_MAX_QUESTIONS_ENV: String = "MYTETZ_TEST_ME_MAX_QUESTIONS"
        const val EXAM_MAX_QUESTIONS_ENV: String = "MYTETZ_EXAM_MAX_QUESTIONS"

        const val DEFAULT_MAX_OUTPUT_TOKENS: Long = 2000
        const val DEFAULT_TEST_ME_MAX_QUESTIONS: Int = 3
        const val DEFAULT_EXAM_MAX_QUESTIONS: Int = 8
        val DEFAULT_EFFORT: LlmEffort = LlmEffort.LOW

        internal fun resolveMaxOutputTokens(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_OUTPUT_TOKENS

        internal fun resolveEffort(raw: String?): LlmEffort =
            raw?.trim()?.uppercase()?.let { name -> LlmEffort.entries.firstOrNull { it.name == name } }
                ?: DEFAULT_EFFORT

        internal fun resolvePositiveInt(raw: String?, default: Int): Int =
            raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: default
    }
}
