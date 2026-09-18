package com.mytetz.assess

/** An untrusted candidate question, exactly as decoded from the model's JSON answer. */
data class RawQuizQuestion(
    val stem: String,
    val options: List<String>,
    val correctIndex: Int,
    val sourceKey: String,
    val rationale: String,
)

sealed interface QuizValidationResult {
    data class Valid(val question: QuizQuestion) : QuizValidationResult
    data class Invalid(val reason: String) : QuizValidationResult
}

/**
 * The rule the whole feature rests on — "questions may only cover material presented earlier" —
 * survives prompt drift only because it is enforced here. The prompt is a suggestion; this is the
 * rule. Every check below rejects rather than repairs: a candidate that fails is dropped, never
 * coerced into something that looks valid.
 */
class QuizValidator(private val requiredOptionCount: Int = 4) {

    fun validate(candidate: RawQuizQuestion, scopeKeys: List<String>, questionId: String): QuizValidationResult {
        if (candidate.sourceKey !in scopeKeys) {
            return QuizValidationResult.Invalid("sourceKey '${candidate.sourceKey}' is not a member of the scope")
        }
        if (candidate.options.size != requiredOptionCount) {
            return QuizValidationResult.Invalid(
                "expected $requiredOptionCount options, got ${candidate.options.size}"
            )
        }
        if (candidate.correctIndex !in candidate.options.indices) {
            return QuizValidationResult.Invalid("correctIndex ${candidate.correctIndex} is out of range")
        }
        if (candidate.stem.isBlank()) return QuizValidationResult.Invalid("empty stem")
        if (candidate.rationale.isBlank()) return QuizValidationResult.Invalid("empty rationale")
        if (candidate.options.any { it.isBlank() }) return QuizValidationResult.Invalid("an option is empty")

        return QuizValidationResult.Valid(
            QuizQuestion(
                questionId = questionId,
                stem = candidate.stem.trim(),
                options = candidate.options,
                correctIndex = candidate.correctIndex,
                sourceKey = candidate.sourceKey,
                rationale = candidate.rationale.trim(),
            )
        )
    }
}
