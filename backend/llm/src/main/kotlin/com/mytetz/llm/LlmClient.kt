package com.mytetz.llm

import kotlinx.coroutines.flow.Flow

enum class LlmEffort { LOW, MEDIUM, HIGH }

data class LlmRequest(
    val system: String,
    val userPrompt: String,
    val maxTokens: Long = 4000,
    val effort: LlmEffort = LlmEffort.LOW,
)

data class LlmUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
    val cacheCreationInputTokens: Long = 0,
)

sealed interface LlmChunk {
    data class Delta(val text: String) : LlmChunk
    data class Done(val usage: LlmUsage, val stopReason: String?) : LlmChunk
}

/** Vendor-agnostic port. One adapter behind it; swapping providers touches one file. */
interface LlmClient {
    val modelId: String
    val modelFamily: String
    fun stream(request: LlmRequest): Flow<LlmChunk>
}
