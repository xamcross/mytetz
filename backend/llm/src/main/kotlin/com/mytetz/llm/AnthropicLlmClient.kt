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
import kotlinx.coroutines.runInterruptible

/**
 * Kotlin uses the Anthropic Java SDK, which is blocking/OkHttp. The blocking stream is consumed
 * via an iterator inside a Flow builder and moved to Dispatchers.IO — `emit` is suspending and
 * cannot be called from the SDK's Java `forEach` or `ifPresent` lambdas, so every value is pulled
 * out of its Optional first and emitted from the loop body.
 *
 * Adaptive thinking is deliberately left ON (the Claude Opus 5 default), which on this model means
 * simply not setting `thinking` at all. Disabling it makes the model occasionally write a tool call
 * into visible prose — the call silently never runs — and leak <thinking> tags into output. Cost is
 * controlled with effort instead.
 *
 * maxTokens caps thinking *plus* response text together, so it is sized with headroom (4000) rather
 * than around the prose alone; length is enforced by a validator downstream. We are billed for
 * actual output, so unused headroom is free.
 *
 * ## Cancellation
 *
 * Each event is read inside [runInterruptible], which gives the loop a suspension point per event,
 * so a collector that cancels while events are arriving stops the stream promptly.
 *
 * A collector that cancels while a read is blocked on a *stalled* connection is a different case,
 * and this SDK cannot abort one: `Thread.interrupt()` does not abort a classic blocking socket
 * read, and `StreamResponse.close()` cannot be used to break it either — close() contends with the
 * in-progress read and blocks until that read returns on its own (measured: a close() issued
 * against a stalled stream returned only when the peer finally sent data, 15s later). Neither
 * `StreamResponse` nor `AsyncStreamResponse` exposes an abort. A stalled read therefore holds its
 * IO thread until the SDK's request timeout fires — for maxTokens=4000 that bound is 10 minutes.
 * Tightening that bound is a client-construction concern (`AnthropicOkHttpClient.builder().timeout`),
 * not something this adapter can do to an injected client.
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
        var deltaCount = 0

        runInterruptible(Dispatchers.IO) { client.messages().createStreaming(params) }.use { response ->
            val events = response.stream().iterator()
            while (true) {
                // One event per iteration inside runInterruptible: its block is not suspending, so
                // `emit` cannot live in there. This is also the loop's cancellation check.
                val event = runInterruptible(Dispatchers.IO) {
                    if (events.hasNext()) events.next() else null
                } ?: break

                val deltaText: String? = event.contentBlockDelta()
                    .flatMap { it.delta().text() }
                    .map { it.text() }
                    .orElse(null)
                if (deltaText != null) {
                    deltaCount++
                    emit(LlmChunk.Delta(deltaText))
                }

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

        // A stream that ends without a stop reason never delivered its message_delta, so
        // outputTokens is still 0. Emitting Done here would report a completed generation at zero
        // cost — and cost accounting feeds the daily spend breaker, so a systematic zero would
        // silently disable the protection against a runaway bill. Fail loudly instead:
        // ExplanationGraph maps a thrown failure to GenerationFailedException, which leaves the
        // truncated explanation unpersisted rather than cached forever under its content key.
        val completedStopReason = stopReason ?: throw LlmStreamTruncatedException(
            "Stream for model $modelId ended without a message_delta stop reason after " +
                "$deltaCount text delta(s); the response is incomplete and its output token " +
                "count is unknown, so its cost cannot be accounted for.",
        )

        emit(LlmChunk.Done(usage, completedStopReason))
    }.flowOn(Dispatchers.IO)

    private fun effortOf(effort: LlmEffort): OutputConfig.Effort = when (effort) {
        LlmEffort.LOW -> OutputConfig.Effort.LOW
        LlmEffort.MEDIUM -> OutputConfig.Effort.MEDIUM
        LlmEffort.HIGH -> OutputConfig.Effort.HIGH
    }
}
