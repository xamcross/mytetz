package com.mytetz.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Kotlin uses the Anthropic Java SDK, which is blocking/OkHttp. The blocking stream is
 * consumed via an iterator inside a Flow builder and moved to Dispatchers.IO — `emit`
 * is suspending and cannot be called from the SDK's Java `forEach` or `ifPresent` lambdas,
 * so every value is pulled out of its Optional first and emitted from the loop body.
 *
 * Adaptive thinking is deliberately left ON (the Claude Opus 5 default), which on this
 * model means simply not setting `thinking` at all. Disabling it makes the model
 * occasionally write a tool call into visible prose — the call silently never runs —
 * and leak <thinking> tags into output. Cost is controlled with effort instead.
 *
 * maxTokens caps thinking *plus* response text together, so it is sized with headroom
 * (4000) rather than around the prose alone; length is enforced by a validator downstream.
 * We are billed for actual output, so unused headroom is free.
 */
class AnthropicLlmClient(
    private val client: AnthropicClient = AnthropicOkHttpClient.fromEnv(),
    override val modelId: String = System.getenv("MYTETZ_MODEL_ID") ?: "claude-opus-5",
    override val modelFamily: String = System.getenv("MYTETZ_MODEL_FAMILY") ?: "claude-opus-5",
) : LlmClient {

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        val params = MessageCreateParams.builder()
            .model(modelId)
            .maxTokens(request.maxTokens)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(request.system)
                        // Claude Opus 5's minimum cacheable prefix is 512 tokens; shorter
                        // system prompts simply do not cache. Nothing is padded to reach it.
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()
                )
            )
            .outputConfig(OutputConfig.builder().effort(effortOf(request.effort)).build())
            .addUserMessage(request.userPrompt)
            .build()

        var usage = LlmUsage()
        var stopReason: String? = null

        client.messages().createStreaming(params).use { response ->
            val events = response.stream().iterator()
            while (events.hasNext()) {
                val event = events.next()

                val deltaText: String? = event.contentBlockDelta()
                    .flatMap { it.delta().text() }
                    .map { it.text() }
                    .orElse(null)
                if (deltaText != null) emit(LlmChunk.Delta(deltaText))

                val start = event.messageStart().orElse(null)
                if (start != null) {
                    val u = start.message().usage()
                    usage = usage.copy(
                        inputTokens = u.inputTokens(),
                        cacheReadInputTokens = u.cacheReadInputTokens().orElse(0L),
                        cacheCreationInputTokens = u.cacheCreationInputTokens().orElse(0L),
                    )
                }

                val messageDelta = event.messageDelta().orElse(null)
                if (messageDelta != null) {
                    usage = usage.copy(outputTokens = messageDelta.usage().outputTokens())
                    stopReason = messageDelta.delta().stopReason()
                        .map { it.toString() }
                        .orElse(stopReason)
                }
            }
        }

        emit(LlmChunk.Done(usage, stopReason))
    }.flowOn(Dispatchers.IO)

    private fun effortOf(effort: LlmEffort): OutputConfig.Effort = when (effort) {
        LlmEffort.LOW -> OutputConfig.Effort.LOW
        LlmEffort.MEDIUM -> OutputConfig.Effort.MEDIUM
        LlmEffort.HIGH -> OutputConfig.Effort.HIGH
    }
}
