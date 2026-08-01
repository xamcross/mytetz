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

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        calls += request
        failWith?.let { throw it }

        val body = bodyByPromptSubstring.entries
            .firstOrNull { request.userPrompt.contains(it.key) }
            ?.value
            ?: nextBody

        body.chunked(16).forEach { emit(LlmChunk.Delta(it)) }

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
}
