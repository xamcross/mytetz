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
import java.time.Duration

/**
 * Kotlin uses the Anthropic Java SDK, which is blocking/OkHttp. The blocking stream is consumed
 * via an iterator inside a Flow builder and moved to Dispatchers.IO — `emit` is suspending and
 * cannot be called from the SDK's Java `forEach` or `ifPresent` lambdas, so every value is pulled
 * out of its Optional first and emitted from the loop body.
 *
 * Adaptive thinking is deliberately left ON (the Claude Sonnet 5 default), which on this model means
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
 * `StreamResponse` nor `AsyncStreamResponse` exposes an abort.
 *
 * A stalled read therefore holds its IO thread until the request timeout fires. The default client
 * built here caps that at [DEFAULT_TIMEOUT_SECONDS] seconds (override with
 * `MYTETZ_LLM_TIMEOUT_SECONDS`) instead of leaving the SDK's 10-minute default in place: an
 * explanation is one to three sentences at effort LOW, so a healthy response completes in seconds
 * and 120s is roughly twenty times that — generous enough that adaptive thinking on a hard span
 * never trips it. A client passed in by a caller governs its own timeout.
 */
class AnthropicLlmClient(
    private val client: AnthropicClient = defaultClient(),
    override val modelId: String = resolveModel(System.getenv(MODEL_ID_ENV)),
    override val modelFamily: String = resolveModel(System.getenv(MODEL_FAMILY_ENV)),
) : LlmClient {

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
        val params = MessageCreateParams.builder()
            .model(modelId)
            .maxTokens(request.maxTokens)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(request.system)
                        // Claude Sonnet 5's minimum cacheable prefix is 1024 tokens. Only a
                        // prompt at or above that length caches. Nothing is padded to reach it.
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

    companion object {

        /** Ceiling on a single streamed request, and so on how long a stalled read holds a thread. */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        const val MODEL_ID_ENV: String = "MYTETZ_MODEL_ID"
        const val MODEL_FAMILY_ENV: String = "MYTETZ_MODEL_FAMILY"

        /**
         * Sonnet 5, and not Opus 5. The reason is arithmetic and not preference.
         *
         * One explanation is about 1 000 input tokens and 500 output tokens. Opus 5 costs $5 and
         * $25 for each 1M tokens. One explanation on Opus 5 therefore costs $0.0175. Sonnet 5 costs
         * $3 and $15. One explanation on Sonnet 5 therefore costs $0.0105.
         *
         * The subscription is €10 each month. The Freemius fee leaves €9.53. That is about $10.29.
         * An allowance of 25 each day therefore costs $7.88 on Sonnet 5 and $13.13 on Opus 5. Only
         * the first number leaves a margin.
         *
         * See section 3 of `docs/superpowers/specs/2026-08-07-monetization-design.md`.
         *
         * **`modelFamily` hashes this value into every content key.** A change here orphans the
         * whole explanation store. The design intends that behaviour. Read section 13 of the same
         * document before you change this value.
         */
        const val DEFAULT_MODEL: String = "claude-sonnet-5"

        /**
         * A missing, empty or blank override falls back to the default. It does not throw.
         *
         * The process reads this value while it starts. A typo in a deployment variable must not
         * stop the server. `GraphConfig.resolveMaxOutputTokens` and
         * `QuotaConfig.resolveDailyExplains` hold the same rule and the same shape.
         *
         * This function trims the value. `fly secrets set` leaves a trailing newline. A hand-edited
         * `.env` does the same. A model id with a newline gives a 404 from the API.
         */
        internal fun resolveModel(raw: String?): String =
            raw?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL

        /**
         * The safe value is the default, so it applies unless a caller deliberately supplies their
         * own client — correctness must not depend on every future construction site remembering to
         * configure it. A missing, unparseable or non-positive override falls back to the default
         * rather than silently disabling the bound.
         */
        internal fun resolveTimeoutSeconds(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS

        private fun defaultClient(): AnthropicClient = AnthropicOkHttpClient.builder()
            .fromEnv()
            .timeout(Duration.ofSeconds(resolveTimeoutSeconds(System.getenv("MYTETZ_LLM_TIMEOUT_SECONDS"))))
            .build()
    }
}
