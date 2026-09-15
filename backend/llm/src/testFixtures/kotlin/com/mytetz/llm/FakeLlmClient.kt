package com.mytetz.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeLlmClient(
    override val modelId: String = "fake-model",
    override val modelFamily: String = "fake-model",
) : LlmClient {

    var nextBody: String = "A placeholder explanation of the highlighted span."
    var nextStopReason: String? = "end_turn"
    var failWith: Throwable? = null
    val calls = mutableListOf<LlmRequest>()

    /** Set per-prompt bodies to simulate context-dependent answers. */
    val bodyByPromptSubstring = linkedMapOf<String, String>()

    var nextStructuredJson: String = """{"questions":[]}"""
    var structuredFailWith: Throwable? = null
    val structuredCalls = mutableListOf<StructuredRequest>()
    var structuredUsage: LlmUsage = LlmUsage(inputTokens = 100, outputTokens = 50)

    /** Set per-prompt JSON answers, exactly like [bodyByPromptSubstring] — a test that drives the
     * one-retry-with-a-nudge path sets one answer for the first prompt and another for the nudged
     * one, keyed on text each prompt alone contains. */
    val structuredJsonByPromptSubstring = linkedMapOf<String, String>()

    /**
     * Runs once, after the first delta, so a test can change the world **while a generation is in
     * flight** — the store, the session document, anything.
     *
     * That window is not reachable any other way. Several properties in this system are only about
     * what happens between the model being called and its result being recorded: an explanation
     * persisted by another instance mid-stream (`ExplanationGraph`'s supersede path), or a session
     * that disappears before `appendNode` can write the learner's step, which is the case in which
     * tokens have been bought and the spend ledger must still hear about it.
     *
     * `ExplanationGraphTest` has carried a private `ProbeLlmClient` for exactly this since Task 1.7;
     * this hoists the one hook it needed so a second suite does not need a second copy.
     */
    var afterFirstDelta: (suspend () -> Unit)? = null

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        calls += request
        failWith?.let { throw it }

        val body = bodyByPromptSubstring.entries
            .firstOrNull { request.userPrompt.contains(it.key) }
            ?.value
            ?: nextBody

        body.chunked(16).forEachIndexed { index, part ->
            emit(LlmChunk.Delta(part))
            if (index == 0) afterFirstDelta?.invoke()
        }

        emit(
            LlmChunk.Done(
                usage = LlmUsage(
                    inputTokens = request.userPrompt.length / 4L + 1,
                    outputTokens = body.length / 4L + 1,
                ),
                stopReason = nextStopReason,
            )
        )
    }

    override suspend fun structured(request: StructuredRequest): StructuredResult {
        structuredCalls += request
        structuredFailWith?.let { throw it }
        val json = structuredJsonByPromptSubstring.entries
            .firstOrNull { request.userPrompt.contains(it.key) }
            ?.value
            ?: nextStructuredJson
        return StructuredResult(json, structuredUsage)
    }
}
