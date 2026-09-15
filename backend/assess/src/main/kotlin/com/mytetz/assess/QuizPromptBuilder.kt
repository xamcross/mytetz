package com.mytetz.assess

/**
 * The prompt and the JSON Schema for one quiz-generation call.
 *
 * One template for both [QuizKind]s, parameterised only by how many sources are in scope and how
 * many questions to ask for — Test Me passes one source, Exam passes every node's, in order. The
 * model is asked to answer only from the given material, and [QuizValidator] is what actually
 * enforces that a returned `sourceKey` belongs to the scope; the prompt is a suggestion.
 */
object QuizPromptBuilder {

    /** Bumped whenever this prompt or [inputSchema] changes; hashed into every [QuizContentKey]. */
    const val VERSION: String = "v1"

    const val TOOL_NAME: String = "submit_quiz_questions"
    const val TOOL_DESCRIPTION: String =
        "Submit the multiple-choice quiz questions you have written, one entry per question."

    fun system(): String =
        "You write short multiple-choice quiz questions that check whether a learner understood " +
            "material they already read. Every question must be answerable from the given " +
            "material alone. Write exactly four options: one correct, three plausible but wrong. " +
            "Write a one-sentence rationale for the correct option, using only the given material."

    /**
     * [sources] are the only material the questions may draw from. Each is labelled with its own
     * key, and the prompt names those keys as the only legal `sourceKey` values — the same rule
     * [QuizValidator] enforces afterwards in code.
     */
    fun user(sources: List<QuizSource>, maxQuestions: Int): String {
        val material = sources.joinToString("\n\n") { "[sourceKey: ${it.key}]\n${it.body}" }
        val keys = sources.joinToString(", ") { it.key }
        return "Write up to $maxQuestions multiple-choice questions from the material below. " +
            "Every question's sourceKey must be exactly one of: $keys.\n\n$material"
    }

    /** Appended to [previousPrompt] for the one allowed retry, after a first attempt produced nothing valid. */
    fun nudge(previousPrompt: String): String =
        "$previousPrompt\n\nYour previous attempt produced no usable question. Follow the schema " +
            "exactly: exactly four options, a correctIndex between 0 and 3, and a sourceKey that " +
            "is one of the keys given above."

    val requiredFields: List<String> = listOf("questions")

    fun inputSchema(): Map<String, Any> = mapOf(
        "questions" to mapOf(
            "type" to "array",
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "stem" to mapOf("type" to "string"),
                    "options" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "minItems" to 4,
                        "maxItems" to 4,
                    ),
                    "correctIndex" to mapOf("type" to "integer"),
                    "sourceKey" to mapOf("type" to "string"),
                    "rationale" to mapOf("type" to "string"),
                ),
                "required" to listOf("stem", "options", "correctIndex", "sourceKey", "rationale"),
            ),
        ),
    )
}
