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

/**
 * A provider stream ended without the terminal metadata that proves the generation completed,
 * so its output token count — and therefore its cost — is unknown. Callers must treat this as a
 * failed generation and persist nothing: a silent zero-cost result would under-report spend.
 */
class LlmStreamTruncatedException(message: String) : RuntimeException(message)

/**
 * One field of a JSON Schema `properties` object: a name, and its own schema as a plain Kotlin
 * structure (nested `Map`/`List`/`String`/`Int`/`Boolean`).
 *
 * Plain structures, not a vendor JSON type. [LlmClient] is a vendor-agnostic port and must not leak
 * the Anthropic SDK's `JsonValue` or `kotlinx.serialization`'s `JsonObject` into its own signature.
 * [AnthropicLlmClient] converts this map with `JsonValue.from`. This builds a JSON tree from plain
 * Kotlin and Java values, recursively. A fake or a future adapter needs no JSON library.
 */
data class StructuredRequest(
    val system: String,
    val userPrompt: String,
    /** How the model names this call in a `tool_use` block. Never shown to a learner. */
    val toolName: String,
    val toolDescription: String,
    /** A JSON Schema `properties` object, one entry per top-level field the caller wants back. */
    val inputSchema: Map<String, Any>,
    /** Field names from [inputSchema] that must be present in the answer. */
    val requiredFields: List<String>,
    val maxTokens: Long = 4000,
    val effort: LlmEffort = LlmEffort.LOW,
)

/** [json] is [inputSchema]'s shape, serialised as text. A caller decodes it into its own type. */
data class StructuredResult(val json: String, val usage: LlmUsage)

/**
 * The model answered with no `tool_use` block at all — a refusal, or a plain-text answer that
 * ignored [StructuredRequest.toolName]. Nothing is trustworthy about a shape that never arrived, so
 * this is a failure and never an empty [StructuredResult].
 */
class LlmStructuredOutputMissingException(message: String) : RuntimeException(message)

/** Vendor-agnostic port. One adapter behind it; swapping providers touches one file. */
interface LlmClient {
    val modelId: String
    val modelFamily: String
    fun stream(request: LlmRequest): Flow<LlmChunk>

    /**
     * A single, non-streaming call that returns data shaped by [StructuredRequest.inputSchema]
     * rather than prose. A quiz question is JSON. JSON does not render until it is structurally
     * complete. Nothing is lost by not streaming it.
     */
    suspend fun structured(request: StructuredRequest): StructuredResult
}
