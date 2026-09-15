package com.mytetz.assess

import com.mytetz.llm.LlmClient
import com.mytetz.llm.Pricing
import com.mytetz.llm.StructuredRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Nothing valid survived generation and the one retry.
 *
 * This class stores nothing when it raises. A later request for the same scope starts a clean
 * generation. `com.mytetz.graph.GenerationFailedException` gives the same guarantee for a prose
 * answer. This class gives it for a JSON answer.
 */
class QuizUnavailableException(message: String) : Exception(message)

@Serializable
private data class RawQuizQuestionWire(
    val stem: String = "",
    val options: List<String> = emptyList(),
    val correctIndex: Int = -1,
    val sourceKey: String = "",
    val rationale: String = "",
)

@Serializable
private data class RawQuizResponse(val questions: List<RawQuizQuestionWire> = emptyList())

private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

/**
 * This class gets or generates a quiz template. It mirrors `com.mytetz.graph.ExplanationGraph`.
 *
 * This class has no concept of a user, a principal, or a session. It answers one question for
 * everyone in the same way: "what is the quiz over these scope keys?"
 *
 * This class holds no per-key lock, unlike `ExplanationGraph`. Quiz traffic is a small part of
 * explain traffic. `QuizRepository.insertIfAbsent` uses a unique `_id`. It still stores exactly
 * one document per key, even under concurrent requests. A concurrent duplicate request costs one
 * extra paid generation. It never stores a wrong or a duplicate document. This is a deliberate
 * simplification. Add the lock on the day quiz traffic makes the extra cost visible.
 */
class QuizService(
    private val repository: QuizRepository,
    private val llm: LlmClient,
    private val validator: QuizValidator,
    private val config: QuizConfig = QuizConfig(),
) {

    fun keyFor(scopeKeys: List<String>, kind: QuizKind): String =
        QuizContentKey.derive(scopeKeys, kind, config.promptVersion, llm.modelFamily)

    /**
     * A cache hit costs nothing and calls no model.
     *
     * A cache miss generates a quiz once. If that attempt yields zero valid questions, this
     * method generates a quiz one more time, with a corrective nudge in the prompt.
     *
     * [onSpend] fires the instant each call's cost is known. It fires before validation and
     * before persistence. `com.mytetz.session.SessionService.create` uses the same callback, for
     * the same reason: a call can be billed and then produce nothing valid. A caller that reads
     * the cost off a return value would never see that cost.
     *
     * This method throws [QuizUnavailableException] when nothing valid survives either attempt.
     * The caller still hears about both attempts' cost through [onSpend] before that exception.
     */
    suspend fun getOrGenerate(
        scopeKeys: List<String>,
        kind: QuizKind,
        sources: List<QuizSource>,
        onSpend: suspend (Long) -> Unit,
    ): QuizTemplate {
        val key = keyFor(scopeKeys, kind)
        repository.findByKey(key)?.let { cached ->
            repository.incrementRequestCount(key)
            return cached
        }

        val maxQuestions = if (kind == QuizKind.TEST_ME) config.testMeMaxQuestions else config.examMaxQuestions

        var totalInputTokens = 0L
        var totalOutputTokens = 0L
        var totalCostMicros = 0L
        val spend: suspend (Long) -> Unit = { cost -> totalCostMicros += cost; onSpend(cost) }

        var attempt = attemptGeneration(sources, scopeKeys, maxQuestions, nudge = false, spend)
        totalInputTokens += attempt.inputTokens
        totalOutputTokens += attempt.outputTokens
        var questions = attempt.questions

        if (questions.isEmpty()) {
            attempt = attemptGeneration(sources, scopeKeys, maxQuestions, nudge = true, spend)
            totalInputTokens += attempt.inputTokens
            totalOutputTokens += attempt.outputTokens
            questions = attempt.questions
        }

        if (questions.isEmpty()) {
            throw QuizUnavailableException("no valid quiz question could be generated for scope $scopeKeys")
        }

        val template = QuizTemplate(
            key = key,
            kind = kind,
            scopeKeys = scopeKeys,
            questions = questions,
            promptVersion = config.promptVersion,
            modelFamily = llm.modelFamily,
            modelId = llm.modelId,
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens,
            costMicros = totalCostMicros,
            requestCount = 0,
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        return repository.insertIfAbsent(template)
    }

    private data class GenerationAttempt(val questions: List<QuizQuestion>, val inputTokens: Long, val outputTokens: Long)

    private suspend fun attemptGeneration(
        sources: List<QuizSource>,
        scopeKeys: List<String>,
        maxQuestions: Int,
        nudge: Boolean,
        spend: suspend (Long) -> Unit,
    ): GenerationAttempt {
        val basePrompt = QuizPromptBuilder.user(sources, maxQuestions)
        val prompt = if (nudge) QuizPromptBuilder.nudge(basePrompt) else basePrompt

        val result = llm.structured(
            StructuredRequest(
                system = QuizPromptBuilder.system(),
                userPrompt = prompt,
                toolName = QuizPromptBuilder.TOOL_NAME,
                toolDescription = QuizPromptBuilder.TOOL_DESCRIPTION,
                inputSchema = QuizPromptBuilder.inputSchema(),
                requiredFields = QuizPromptBuilder.requiredFields,
                maxTokens = config.maxOutputTokens,
                effort = config.effort,
            )
        )
        // The cost leaves here, before parsing and before validation. Either step can find no
        // usable question in a call that still cost real tokens. See GraphChunk.Spent for the
        // same rule, in ExplanationGraph.
        spend(Pricing.costMicros(llm.modelId, result.usage))

        val raw = try {
            LENIENT_JSON.decodeFromString<RawQuizResponse>(result.json).questions
        } catch (e: SerializationException) {
            emptyList()
        }

        val questions = raw.mapIndexedNotNull { index, candidate ->
            val questionId = "${if (nudge) "retry" else "gen"}-$index-${UUID.randomUUID()}"
            val verdict = validator.validate(
                RawQuizQuestion(candidate.stem, candidate.options, candidate.correctIndex, candidate.sourceKey, candidate.rationale),
                scopeKeys,
                questionId,
            )
            (verdict as? QuizValidationResult.Valid)?.question
        }

        return GenerationAttempt(questions, result.usage.inputTokens, result.usage.outputTokens)
    }

    fun startAttempt(
        principalId: String,
        sessionId: String,
        template: QuizTemplate,
        idFactory: () -> String = { UUID.randomUUID().toString() },
        clock: () -> Long = System::currentTimeMillis,
    ): QuizAttempt = QuizAttempt(
        id = idFactory(),
        principalId = principalId,
        sessionId = sessionId,
        templateId = template.key,
        answers = emptyList(),
        score = null,
        total = template.questions.size,
        createdAtEpochMillis = clock(),
        submittedAtEpochMillis = null,
    )

    /**
     * This method scores [answers] against [template]. It returns [attempt], updated with the
     * result.
     *
     * A question with no matching answer counts as wrong. This method does not exclude it.
     * [attempt.total] is fixed at creation time to the size of [template.questions]. A partial
     * submission cannot raise a learner's own denominator by answering fewer questions.
     */
    fun score(
        attempt: QuizAttempt,
        template: QuizTemplate,
        answers: List<AnsweredQuestion>,
        clock: () -> Long = System::currentTimeMillis,
    ): QuizAttempt {
        val chosenByQuestion = answers.associateBy { it.questionId }
        val correct = template.questions.count { question ->
            chosenByQuestion[question.questionId]?.chosenIndex == question.correctIndex
        }
        return attempt.copy(answers = answers, score = correct, submittedAtEpochMillis = clock())
    }
}
