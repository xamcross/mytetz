package com.mytetz.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
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
    fun `the early usage from message_start arrives before the first delta`() = runBlocking {
        val body = sse(messageStart, contentBlockStart, textDelta("A hash function maps "), textDelta("data to a fixed size."), contentBlockStop, messageDelta, messageStop)
        val server = sseServer { out -> out.write(body.toByteArray()) }

        try {
            val chunks = withTimeout(30_000) {
                AnthropicLlmClient(clientFor(server)).stream(LlmRequest("system", "prompt")).toList()
            }

            val earlyUsageIndex = chunks.indexOfFirst { it is LlmChunk.EarlyUsage }
            val firstDeltaIndex = chunks.indexOfFirst { it is LlmChunk.Delta }

            assertTrue(earlyUsageIndex >= 0, "no EarlyUsage chunk was emitted at all")
            assertTrue(
                earlyUsageIndex < firstDeltaIndex,
                "the early usage must arrive before the first delta, but arrived at " +
                    "$earlyUsageIndex, and the first delta at $firstDeltaIndex",
            )

            // message_start reports input and cache figures only; output is not known yet.
            val early = (chunks[earlyUsageIndex] as LlmChunk.EarlyUsage).usage
            assertEquals(1234, early.inputTokens)
            assertEquals(512, early.cacheReadInputTokens)
            assertEquals(64, early.cacheCreationInputTokens)
            assertEquals(0, early.outputTokens)
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

    // ------------------------------------------------------------------ readInterruptible
    //
    // Issue #131. On CI, a cancelling collector sometimes saw `com.anthropic.errors.
    // AnthropicIoException` instead of a `CancellationException`: the interrupt from
    // `runInterruptible` landed inside the SDK's own blocking read, and the SDK wrapped the
    // resulting `InterruptedIOException` in its own exception class before `runInterruptible`
    // could recognise it. See [AnthropicLlmClient]'s KDoc, section "Cancellation", and
    // [readInterruptible]'s own KDoc, for the confirmed order of events and the fix.
    //
    // These four tests drive [readInterruptible] directly, and not through a real socket: the real
    // failure needs the interrupt to land at one exact instant during a live read, so a test built
    // on real timing can only show the defect sometimes (see the test above, and the reproduction
    // count in the pull request for this issue). Cancelling this coroutine from inside the fake
    // read reproduces the same interleaving every time, with no dependency on timing at all.

    /**
     * The exact shape the vendor SDK produces when an interrupt lands inside its own blocking
     * read: not a bare [InterruptedException], so [kotlinx.coroutines.runInterruptible] does not
     * convert it on its own. Confirmed by reading `StreamHandler.kt` and `OkHttpClient.kt` in the
     * SDK's own source (both wrap `java.io.IOException` in `AnthropicIoException`) and Okio's
     * `Timeout.throwIfReached`, which throws `InterruptedIOException` once it sees the thread's
     * interrupt flag set.
     */
    private fun wrappedInterrupt(): AnthropicIoException =
        AnthropicIoException("Stream failed", InterruptedIOException("interrupted"))

    @Test
    fun `readInterruptible turns a read failure into a cancellation once this coroutine is cancelled`() = runBlocking {
        var observed: Throwable? = null

        val job = launch {
            val ownJob = coroutineContext[Job]!!
            try {
                readInterruptible {
                    // Cancels this coroutine first, and only then fails: the read failing because
                    // the coroutine is already cancelled is exactly the case this function exists
                    // to correct.
                    ownJob.cancel()
                    throw wrappedInterrupt()
                }
            } catch (e: Throwable) {
                observed = e
            }
        }
        job.join()

        assertTrue(
            observed is CancellationException,
            "a read that fails after this coroutine is cancelled must surface as a cancellation, " +
                "not as ${observed?.let { it::class.simpleName }}",
        )
    }

    // Not `assertSame` on the thrown object below. `readInterruptible` crosses a real dispatch
    // (its own `runInterruptible(Dispatchers.IO, ...)`), and kotlinx.coroutines' own stack-trace
    // recovery copies some exception classes across a dispatch boundary -- confirmed by reading
    // `ExceptionsConstructor.kt` in kotlinx.coroutines. The copy keeps the class and the message
    // and chains the original as its cause, so the checks below use those instead of identity.
    // This copying is a normal side effect of crossing `withContext`, present with or without
    // this function's own correction, and it is not what these tests exist to check.

    @Test
    fun `readInterruptible leaves a real read failure unchanged while this coroutine is still active`() = runBlocking {
        val boom = wrappedInterrupt()

        val failure = assertFailsWith<AnthropicIoException> {
            readInterruptible { throw boom }
        }

        assertEquals("Stream failed", failure.message)
        assertTrue(
            generateSequence(failure as Throwable) { it.cause }.any { it is InterruptedIOException },
            "a real read fault, with no cancellation behind it, must reach the caller unchanged, so " +
                "a genuine network fault never looks like a cancellation: $failure",
        )
    }

    @Test
    fun `a bare InterruptedIOException stays an error while this coroutine is still active`() = runBlocking {
        // The correction reads the state of the coroutine, and not the class of the exception. An
        // InterruptedIOException on its own must not become a cancellation only because its name
        // suggests one.
        val failure = assertFailsWith<InterruptedIOException> {
            readInterruptible { throw InterruptedIOException("interrupted") }
        }

        assertEquals("interrupted", failure.message)
    }

    @Test
    fun `a cancellation the read raises directly passes through readInterruptible unchanged`() = runBlocking {
        val failure = assertFailsWith<CancellationException> {
            readInterruptible { throw CancellationException("stopped") }
        }

        assertEquals("stopped", failure.message, "a CancellationException from the read must never be replaced")
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

    // ------------------------------------------------------------------ structured

    /**
     * Starts a plain JSON endpoint on a free loopback port. [structured] does not stream.
     * This helper writes one full response body instead of the SSE frames [sseServer] writes.
     */
    private fun jsonServer(
        onRequest: (String) -> Unit = {},
        body: String,
    ): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/messages") { exchange ->
                onRequest(exchange.requestBody.use { it.readBytes() }.decodeToString())
                exchange.responseHeaders.add("Content-Type", "application/json")
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun toolUseMessage(inputJson: String, inputTokens: Int = 500, outputTokens: Int = 40): String = """
        {
          "id": "msg_01", "type": "message", "role": "assistant", "model": "claude-sonnet-5",
          "stop_reason": "tool_use", "stop_sequence": null,
          "usage": {"input_tokens": $inputTokens, "output_tokens": $outputTokens},
          "content": [
            {"type": "tool_use", "id": "toolu_01", "name": "submit_quiz_questions", "input": $inputJson}
          ]
        }
    """.trimIndent()

    private fun structuredRequest(userPrompt: String = "prompt") = StructuredRequest(
        system = "system",
        userPrompt = userPrompt,
        toolName = "submit_quiz_questions",
        toolDescription = "Submit the generated quiz questions.",
        inputSchema = mapOf(
            "questions" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "object"),
            )
        ),
        requiredFields = listOf("questions"),
    )

    @Test
    fun `structured returns the tool call's input and the message's usage`() = runBlocking {
        val body = toolUseMessage("""{"questions":[{"stem":"x","options":["a","b","c","d"],"correctIndex":0,"sourceKey":"k","rationale":"r"}]}""")
        val server = jsonServer(body = body)

        try {
            val result = withTimeout(30_000) {
                AnthropicLlmClient(clientFor(server)).structured(structuredRequest())
            }

            assertTrue("\"stem\":\"x\"" in result.json, "expected the tool input's JSON, got: ${result.json}")
            assertEquals(500, result.usage.inputTokens)
            assertEquals(40, result.usage.outputTokens)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `structured forces the named tool and sends no stop sequences`() = runBlocking {
        val captured = CompletableDeferred<String>()
        val server = jsonServer(onRequest = { captured.complete(it) }, body = toolUseMessage("""{"questions":[]}"""))

        try {
            withTimeout(30_000) {
                AnthropicLlmClient(clientFor(server)).structured(structuredRequest(userPrompt = "USERPROMPT-SENTINEL"))
            }
            val request = withTimeout(5_000) { captured.await() }

            assertTrue("USERPROMPT-SENTINEL" in request)
            assertTrue("\"name\":\"submit_quiz_questions\"" in request, "tool choice should force the named tool: $request")
            assertFalse(request.contains("stop_sequence", ignoreCase = true))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `structured raises when the tool call was truncated at max_tokens`(): Unit = runBlocking {
        // A tool_use block is present, unlike the test below. It is what makes this case worth
        // its own test: the block existing is not enough on its own to trust its JSON.
        val body = """
            {
              "id": "msg_01", "type": "message", "role": "assistant", "model": "claude-sonnet-5",
              "stop_reason": "max_tokens", "stop_sequence": null,
              "usage": {"input_tokens": 500, "output_tokens": 2000},
              "content": [
                {"type": "tool_use", "id": "toolu_01", "name": "submit_quiz_questions", "input": {"questions": []}}
              ]
            }
        """.trimIndent()
        val server = jsonServer(body = body)

        try {
            val error = assertFailsWith<LlmStructuredOutputMissingException> {
                withTimeout(30_000) { AnthropicLlmClient(clientFor(server)).structured(structuredRequest()) }
            }
            assertTrue("max_tokens" in error.message.orEmpty(), "the message should name the real cause")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `structured fails when the response carries no tool_use block`(): Unit = runBlocking {
        val body = """
            {
              "id": "msg_01", "type": "message", "role": "assistant", "model": "claude-sonnet-5",
              "stop_reason": "end_turn", "stop_sequence": null,
              "usage": {"input_tokens": 10, "output_tokens": 5},
              "content": [{"type": "text", "text": "I would rather not."}]
            }
        """.trimIndent()
        val server = jsonServer(body = body)

        try {
            assertFailsWith<LlmStructuredOutputMissingException> {
                withTimeout(30_000) { AnthropicLlmClient(clientFor(server)).structured(structuredRequest()) }
            }
        } finally {
            server.stop(0)
        }
    }
}
