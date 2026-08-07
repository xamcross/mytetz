package com.mytetz.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the real Anthropic SDK against a local HTTP server that replays the documented SSE wire
 * format. The SDK does all the parsing, so this pins the adapter's accessor chain, its usage
 * accumulation, and its failure behaviour. Nothing here touches the network beyond 127.0.0.1 and
 * no API key is required, so it is safe in CI.
 */
class AnthropicLlmClientTest {

    private val messageStart = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1234,"output_tokens":1,"cache_read_input_tokens":512,"cache_creation_input_tokens":64}}}
    """.trimIndent()

    private val contentBlockStart = """
        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
    """.trimIndent()

    private fun textDelta(text: String) = """
        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}
    """.trimIndent()

    private val contentBlockStop = """
        event: content_block_stop
        data: {"type":"content_block_stop","index":0}
    """.trimIndent()

    private val messageDelta = """
        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":57}}
    """.trimIndent()

    private val messageStop = """
        event: message_stop
        data: {"type":"message_stop"}
    """.trimIndent()

    private fun sse(vararg blocks: String) = blocks.joinToString("\n\n", postfix = "\n\n")

    /**
     * Starts an SSE endpoint on a free loopback port; [respond] writes the body. [onRequest]
     * receives the outbound request body, which is how a test can assert on what was *sent*
     * rather than only on what came back.
     */
    private fun sseServer(
        onRequest: (String) -> Unit = {},
        respond: (OutputStream) -> Unit,
    ): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/messages") { exchange ->
                onRequest(exchange.requestBody.use { it.readBytes() }.decodeToString())
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                try {
                    respond(exchange.responseBody)
                } finally {
                    runCatching { exchange.responseBody.close() }
                }
            }
            start()
        }

    private fun clientFor(server: HttpServer): AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey("test-key-never-sent-anywhere-real")
        .baseUrl("http://127.0.0.1:${server.address.port}")
        .build()

    @Test
    fun `a complete stream accumulates usage from both message start and message delta`() = runBlocking {
        val body = sse(messageStart, contentBlockStart, textDelta("A hash function maps "), textDelta("data to a fixed size."), contentBlockStop, messageDelta, messageStop)
        val server = sseServer { out -> out.write(body.toByteArray()) }

        try {
            val chunks = withTimeout(30_000) {
                AnthropicLlmClient(clientFor(server)).stream(LlmRequest("system", "prompt")).toList()
            }

            val text = chunks.filterIsInstance<LlmChunk.Delta>().joinToString("") { it.text }
            assertEquals("A hash function maps data to a fixed size.", text)

            val done = chunks.filterIsInstance<LlmChunk.Done>().single()
            // input and cache figures arrive on message_start, output only on message_delta:
            // proving both are present is what proves the accumulation across events works.
            assertEquals(1234, done.usage.inputTokens)
            assertEquals(57, done.usage.outputTokens)
            assertEquals(512, done.usage.cacheReadInputTokens)
            assertEquals(64, done.usage.cacheCreationInputTokens)
            assertEquals("end_turn", done.stopReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a stream that ends without a stop reason fails instead of reporting zero cost`() = runBlocking {
        // Everything except the message_delta event — exactly what a truncated generation looks like.
        val body = sse(messageStart, contentBlockStart, textDelta("A hash function maps "), contentBlockStop, messageStop)
        val server = sseServer { out -> out.write(body.toByteArray()) }

        try {
            // This test pins modelId. It does not use the code default. The assertion below names an
            // exact model. It must hold whatever the code default becomes.
            val failure = assertFailsWith<LlmStreamTruncatedException> {
                withTimeout(30_000) {
                    AnthropicLlmClient(clientFor(server), modelId = "claude-opus-5")
                        .stream(LlmRequest("system", "prompt")).toList()
                }
            }

            val message = failure.message.orEmpty()
            assertTrue("stop reason" in message, "message should name what was missing, was: $message")
            assertTrue("claude-opus-5" in message, "message should name the model, was: $message")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the outbound request sets no stop sequences`() = runBlocking {
        // ExplanationValidator accepts ONLY "end_turn" and rejects "stop_sequence" as an
        // unrecognised stop reason. That exclusion is correct precisely because this adapter never
        // asks for a stop sequence, so the API can never end a completion that way -- but nothing
        // enforced it. Add one here, even conditionally, and every completion ending on it is
        // silently discarded as unrecognised: the same permanent-loss failure this gate exists to
        // prevent, wearing a different value. This test breaks the moment that stops being true.
        val captured = CompletableDeferred<String>()
        val server = sseServer(onRequest = { captured.complete(it) }) { out ->
            out.write(sse(messageStart, contentBlockStart, textDelta("x"), contentBlockStop, messageDelta, messageStop).toByteArray())
        }

        try {
            withTimeout(30_000) {
                AnthropicLlmClient(clientFor(server))
                    .stream(LlmRequest("SYSTEM-SENTINEL", "USERPROMPT-SENTINEL"))
                    .toList()
            }
            val request = withTimeout(5_000) { captured.await() }

            // Prove the capture is real first. Without this, an empty or unread body would make
            // the assertion below pass for the wrong reason -- the check would be free.
            assertTrue("SYSTEM-SENTINEL" in request, "captured no real request body: $request")
            assertTrue("USERPROMPT-SENTINEL" in request, "captured no real request body: $request")

            // Substring, not an exact field name: this catches "stop_sequences" and "stopSequences"
            // and any singular spelling the SDK might serialise.
            assertFalse(
                request.contains("stop_sequence", ignoreCase = true) ||
                    request.contains("stopSequence", ignoreCase = true),
                "the adapter now sets a stop sequence, so ExplanationValidator must stop treating " +
                    "'stop_sequence' as an unrecognised stop reason. Request was: $request",
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `every stop reason crosses the SDK boundary as its bare wire string`() {
        // ExplanationValidator -- the last gate before an explanation is persisted immutably --
        // decides on these exact strings, and until this test nothing in the project had ever
        // round-tripped anything but "end_turn". The hazard is concrete: an earlier draft of this
        // adapter unwrapped the Optional with `stopReason()?.toString()`, which yields
        // "Optional[refusal]". That compiles, streams, and matches no branch downstream -- so a
        // model refusal would have been cached as a legitimate explanation and later published.
        //
        // `StopReason` is an SDK "open enum", so `toString()` is the wrapped wire string and an
        // unrecognised value does not break deserialization. Note that SDK 2.34.0 ships no
        // constant for `model_context_window_exceeded`: it round-trips fine here, but
        // `StopReason.known()` would throw on it, which is why the adapter must keep using
        // `toString()` rather than switching on `known()`.
        listOf(
            "end_turn", "max_tokens", "refusal",
            "stop_sequence", "tool_use", "pause_turn", "model_context_window_exceeded",
        ).forEach { wire ->
            val delta = """
                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"$wire","stop_sequence":null},"usage":{"output_tokens":57}}
            """.trimIndent()
            val server = sseServer { out ->
                out.write(sse(messageStart, contentBlockStart, textDelta("partial"), contentBlockStop, delta, messageStop).toByteArray())
            }

            try {
                val chunks = runBlocking {
                    withTimeout(30_000) {
                        AnthropicLlmClient(clientFor(server)).stream(LlmRequest("system", "prompt")).toList()
                    }
                }
                assertEquals(wire, chunks.filterIsInstance<LlmChunk.Done>().single().stopReason)
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `the request timeout defaults to 120s and refuses unusable overrides`() {
        // Bounds how long a stalled read can hold an IO thread. Asserting the resolved value is
        // cheap; actually waiting one out would cost two minutes of runtime, so this does not.
        assertEquals(120, AnthropicLlmClient.DEFAULT_TIMEOUT_SECONDS)
        assertEquals(120, AnthropicLlmClient.resolveTimeoutSeconds(null))
        assertEquals(300, AnthropicLlmClient.resolveTimeoutSeconds("300"))
        assertEquals(300, AnthropicLlmClient.resolveTimeoutSeconds("  300  "))
        // A typo or a nonsense value must not silently remove the bound.
        assertEquals(120, AnthropicLlmClient.resolveTimeoutSeconds("not-a-number"))
        assertEquals(120, AnthropicLlmClient.resolveTimeoutSeconds(""))
        assertEquals(120, AnthropicLlmClient.resolveTimeoutSeconds("0"))
        assertEquals(120, AnthropicLlmClient.resolveTimeoutSeconds("-5"))
    }

    @Test
    fun `a cancelling collector stops a stream of events that emit nothing`() = runBlocking {
        // After one text delta the server sends an endless run of content_block_stop events. The
        // adapter emits nothing for those, so `emit` — the loop's other suspension point — is never
        // reached: the per-event runInterruptible is the ONLY place cancellation can be observed.
        // Remove it and this loop spins forever on a live socket and cancelAndJoin never returns.
        val release = CountDownLatch(1)
        val server = sseServer { out ->
            out.write(sse(messageStart, contentBlockStart, textDelta("A hash function maps ")).toByteArray())
            out.flush()
            while (release.count > 0) {
                out.write(sse(contentBlockStop).toByteArray())
                out.flush()
                Thread.sleep(5)
            }
        }

        try {
            val firstDelta = CompletableDeferred<String>()
            val collector = launch(Dispatchers.Default) {
                AnthropicLlmClient(clientFor(server)).stream(LlmRequest("system", "prompt")).collect { chunk ->
                    if (chunk is LlmChunk.Delta) firstDelta.complete(chunk.text)
                }
            }

            withTimeout(30_000) { firstDelta.await() }

            // join() returns only once the upstream coroutine has actually finished, so this times
            // out if the event loop never observes cancellation.
            withTimeout(15_000) { collector.cancelAndJoin() }
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    // ------------------------------------------------------------------ the model default

    @Test
    fun `an unset model falls back to the default`() {
        assertEquals("claude-sonnet-5", AnthropicLlmClient.resolveModel(null))
    }

    @Test
    fun `a blank model falls back to the default`() {
        assertEquals("claude-sonnet-5", AnthropicLlmClient.resolveModel(""))
        assertEquals("claude-sonnet-5", AnthropicLlmClient.resolveModel("   "))
    }

    @Test
    fun `an override is trimmed and kept`() {
        assertEquals("claude-opus-5", AnthropicLlmClient.resolveModel("  claude-opus-5\n"))
    }

    @Test
    fun `the default model is one Pricing knows`() {
        // Pricing falls back to the dearest known rate for an unknown model. A typo in the
        // default would therefore over-report every cost silently rather than fail. This is the
        // check that makes the fallback safe to keep.
        val oneMillionOut = LlmUsage(inputTokens = 0, outputTokens = 1_000_000)
        assertEquals(
            15_000_000L,
            Pricing.costMicros(AnthropicLlmClient.DEFAULT_MODEL, oneMillionOut),
            "the default model must bill at Sonnet 5's published output rate of \$15 for each 1M tokens",
        )
    }
}
