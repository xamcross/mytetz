package com.mytetz.assess

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class QuizKind { TEST_ME, EXAM }

/** One explanation body, keyed by its content key, ready to go into a quiz prompt. */
data class QuizSource(val key: String, val body: String)

/**
 * One validated question, ready to be stored.
 *
 * The fields correctIndex and sourceKey never leave this module.
 * The browser uses QuizQuestionView from the :backend:api module instead.
 */
@Serializable
data class QuizQuestion(
    val questionId: String,
    val stem: String,
    val options: List<String>,
    val correctIndex: Int,
    /** A member of the scopeKeys from the QuizTemplate this question was drawn from.
     * Enforcement is performed by QuizValidator. */
    val sourceKey: String,
    val rationale: String,
)

/**
 * A content-addressed quiz template, similar to com.mytetz.graph.Explanation.
 *
 * The template is immutable except for requestCount.
 * A second request for the same scopeKeys, kind, promptVersion, and modelFamily gets cached.
 * The cache hit does not call the model.
 */
@Serializable
data class QuizTemplate(
    @SerialName("_id") val key: String,
    val kind: QuizKind,
    /** Explanation content keys, in the order the quiz was scoped over.
     * Order is part of the identity.
     * QuizContentKey.derive hashes the order.
     * A different order produces a different quiz.
     * This is correct: Exam covers every node in the session, in order. */
    val scopeKeys: List<String>,
    val questions: List<QuizQuestion>,
    val promptVersion: String,
    val modelFamily: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costMicros: Long,
    val requestCount: Long,
    val createdAtEpochMillis: Long,
)

@Serializable
data class AnsweredQuestion(val questionId: String, val chosenIndex: Int)

/**
 * No attempt exists with this id. Or an attempt exists, but a different principal owns it.
 *
 * This class gives one answer for both cases. `com.mytetz.session.SessionNotFoundException` uses
 * the same reasoning. A guessed id must not become a way to find out which ids are real.
 */
class QuizAttemptNotFoundException(val attemptId: String) : Exception("no such quiz attempt: $attemptId")

/**
 * One learner's attempt at one QuizTemplate.
 *
 * The fields score and submittedAtEpochMillis are null until QuizService.score records answers.
 * `QuizRoutes.kt` refuses a second submission once submittedAtEpochMillis is set. It answers
 * 409 ALREADY_ANSWERED, and it does not call QuizService.score again. This class itself still
 * lets a caller overwrite both fields, because this class enforces no rule of its own; the route
 * is where the rule lives.
 */
@Serializable
data class QuizAttempt(
    @SerialName("_id") val id: String,
    val principalId: String,
    val sessionId: String,
    val templateId: String,
    val answers: List<AnsweredQuestion>,
    val score: Int?,
    val total: Int,
    val createdAtEpochMillis: Long,
    val submittedAtEpochMillis: Long?,
)
