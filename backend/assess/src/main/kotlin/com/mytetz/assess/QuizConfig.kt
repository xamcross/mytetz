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
    /**
     * How many of a session's most recent nodes an exam may cover.
     *
     * A session holds up to `SessionLimits.DEFAULT_MAX_NODES` nodes, each with a body up to 600
     * characters long. With no cap, an exam prompt grows with the whole session, so a long session
     * can send close to 120KB of source text on one exam request. This bounds that prompt, and so
     * bounds its cost.
     *
     * `QuizRoutes.kt`'s `scopeFor` takes only the last [examMaxSources] nodes, by array position,
     * because the most recent material is what an exam should test. It is also the cheapest choice:
     * it bounds the prompt without a second call to decide which nodes matter more than others.
     */
    val examMaxSources: Int = resolvePositiveInt(System.getenv(EXAM_MAX_SOURCES_ENV), DEFAULT_EXAM_MAX_SOURCES),
) {
    companion object {
        const val MAX_OUTPUT_TOKENS_ENV: String = "MYTETZ_QUIZ_MAX_OUTPUT_TOKENS"
        const val EFFORT_ENV: String = "MYTETZ_QUIZ_EFFORT"
        const val TEST_ME_MAX_QUESTIONS_ENV: String = "MYTETZ_TEST_ME_MAX_QUESTIONS"
        const val EXAM_MAX_QUESTIONS_ENV: String = "MYTETZ_EXAM_MAX_QUESTIONS"
        const val EXAM_MAX_SOURCES_ENV: String = "MYTETZ_EXAM_MAX_SOURCES"

        const val DEFAULT_MAX_OUTPUT_TOKENS: Long = 2000
        const val DEFAULT_TEST_ME_MAX_QUESTIONS: Int = 3
        const val DEFAULT_EXAM_MAX_QUESTIONS: Int = 8
        const val DEFAULT_EXAM_MAX_SOURCES: Int = 20
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
