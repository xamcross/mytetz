# Quiz (Test Me + Exam) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Test Me and Exam quizzes: a structured-output method on `LlmClient`, a new `assess`
module that generates, validates and caches quiz templates, two new gated API routes, and an
Angular quiz panel wired to a "Test Me" control on the focus card and an "Exam" control in the
reader.

**Architecture:** `assess` mirrors `graph`'s content-addressed store exactly — a
`QuizContentKey.derive(scopeKeys, kind, promptVersion, modelFamily)` hash, a Mongo collection keyed
on that hash, get-or-generate with one validated retry. Generation goes through a new
`LlmClient.structured` method (forced tool use on the Anthropic adapter) instead of `stream`,
because a quiz is JSON and JSON does not benefit from token-by-token rendering. The two new routes
reuse the explain route's gate order exactly (sign-in, entitlement, quota, breaker) by widening
four already-private helpers in `SessionRoutes.kt` to `internal`.

**Tech Stack:** Kotlin, Ktor, kotlinx.serialization, MongoDB Kotlin coroutine driver, the Anthropic
Java SDK (tool use / forced JSON output), Angular 18 standalone components with signals, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-01-assisted-learning-engine-design.md` (section 8, the
`quizTemplates`/`quizAttempts` collections in section 5.2, the API surface in section 12), and
`docs/superpowers/specs/2026-08-07-monetization-design.md` (section 9, the gate order and section
9.3, which endpoints gate). GitHub issue #16 is the task brief and is authoritative over the design
mockup at `docs/mytetz-design-reference.html` (section 5a) wherever the two disagree — the mockup
shows a quiz judged one question at a time, but the spec's `POST .../answers` scores a whole
`answers[]` array at once, so the frontend collects every answer first and shows the verdict only
at the end.

## Global Constraints

- Prose, KDoc and commit bodies are in ASD-STE100 Simplified Technical English (short sentences,
  active voice, one instruction per sentence — see the project's `CLAUDE.md`).
- `assess` may depend only on `session`, `graph` and `llm` (see `backend/assess/build.gradle.kts`,
  already set). It must not depend on `quota` or `billing` — the same rule `graph` and `session`
  follow. Quota and billing stay in the `api` module's route layer.
- A quiz question always carries exactly four options, and its `correctIndex` is in range and its
  `sourceKey` is a member of the scope it was generated from. Every one of these is enforced in
  code, not only asked for in a prompt.
- `QuizTemplate` is immutable and content addressed, exactly like `Explanation`: never updated
  except `requestCount`.
- The gate order on the quiz-generation route is sign-in, then entitlement, then span/ownership
  checks that apply, then quota, then the spend breaker — the same order and the same codes
  (`401 SIGN_IN_REQUIRED`, `403 SUBSCRIPTION_REQUIRED`, `403 TRIAL_EXHAUSTED` /
  `429 QUOTA_EXCEEDED`, `503 SPEND_LIMIT`) that `POST /api/sessions/{id}/explain` uses.
- A cache hit costs nothing and skips the quota gate, exactly like the explain route.
- Every new Kotlin file matches the project's existing style: extensive "why" KDoc, `internal`
  helpers kept internal, config values overridable from the environment with a safe fallback (never
  a startup crash) unless the project has already decided a value has no safe default (signing
  keys; this task adds none of those).
- Every `@Test fun name() = runBlocking { ... }` must resolve to `Unit`. If the block's last
  statement is `assertFailsWith<T> { ... }` (which evaluates to `T`, not `Unit`) or any other
  non-Unit-returning call, annotate the function `fun name(): Unit = runBlocking { ... }` instead of
  the bare `fun name() = runBlocking { ... }`. Task 2's implementer found this the hard way: Kotlin
  infers a non-`Unit` return type in that shape, and the test then never runs — no failure, no
  skipped entry, just silence. Check every test you write against this before committing it,
  whichever task you are implementing.

---

## File Structure

### Backend — `llm` module (modify)

| File | Responsibility |
|---|---|
| `backend/llm/src/main/kotlin/com/mytetz/llm/LlmClient.kt` | Adds `StructuredRequest`, `StructuredResult`, `LlmStructuredOutputMissingException`, and `LlmClient.structured(...)`. |
| `backend/llm/src/testFixtures/kotlin/com/mytetz/llm/FakeLlmClient.kt` | Adds a fake `structured` with recorded calls and a settable response, mirroring `stream`'s fixtures. |
| `backend/llm/src/main/kotlin/com/mytetz/llm/AnthropicLlmClient.kt` | Implements `structured` with a forced tool call, non-streaming. |
| `backend/llm/src/test/kotlin/com/mytetz/llm/FakeLlmClientTest.kt` | Tests for the fake. |
| `backend/llm/src/test/kotlin/com/mytetz/llm/AnthropicLlmClientTest.kt` | Tests for the real adapter, against a local HTTP server replaying a real non-streaming response — the same technique the file already uses for `stream`. |

### Backend — `assess` module (new source files; `build.gradle.kts` already exists)

| File | Responsibility |
|---|---|
| `backend/assess/src/main/kotlin/com/mytetz/assess/Quiz.kt` | `QuizKind`, `QuizQuestion`, `QuizTemplate`, `AnsweredQuestion`, `QuizAttempt`, `QuizSource`. |
| `backend/assess/src/main/kotlin/com/mytetz/assess/QuizContentKey.kt` | `sha256(scopeKeys \| kind \| promptVersion \| modelFamily)`, mirroring `graph.ContentKey`. |
| `backend/assess/src/main/kotlin/com/mytetz/assess/QuizValidator.kt` | The adversarial gate: option count, `correctIndex` range, `sourceKey` membership, non-empty stem/rationale. |
| `backend/assess/src/main/kotlin/com/mytetz/assess/QuizPromptBuilder.kt` | The system/user prompt and the JSON input schema for the forced tool call. |
| `backend/assess/src/main/kotlin/com/mytetz/assess/QuizConfig.kt` | `promptVersion`, `maxOutputTokens`, `effort`, `testMeMaxQuestions`, `examMaxQuestions` — mirrors `graph.GraphConfig`. |
| `backend/assess/src/main/kotlin/com/mytetz/assess/QuizRepository.kt` | Mongo access for `quizTemplates` and `quizAttempts`, mirroring `graph.ExplanationRepository`. |
| `backend/assess/src/main/kotlin/com/mytetz/assess/QuizService.kt` | Get-or-generate with one retry, `QuizUnavailableException`, and answer scoring. |
| `backend/assess/src/test/kotlin/com/mytetz/assess/*Test.kt` | One test file per class above. |

### Backend — `api` module (modify + new)

| File | Responsibility |
|---|---|
| `backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt` | Widen `Refusal`, `respondRefusal`, `QuotaService.refusalFor`, `QuotaService.recordSpend` from `private` to `internal` so `QuizRoutes.kt` can reuse them. No behaviour change. |
| `backend/api/src/main/kotlin/com/mytetz/api/QuizRoutes.kt` | `POST /api/sessions/{id}/quizzes` and `POST /api/sessions/{id}/quizzes/{attemptId}/answers`. |
| `backend/api/src/main/kotlin/com/mytetz/api/ErrorMapping.kt` | Maps `QuizUnavailableException` to `502 QUIZ_UNAVAILABLE` and `QuizAttemptNotFoundException` to `404 NOT_FOUND`. |
| `backend/api/src/main/kotlin/com/mytetz/api/Components.kt` | Wires `QuizRepository`, `QuizValidator`, `QuizService`; adds `quizRepository.ensureIndexes()` to `bootstrap()`. |
| `backend/api/src/main/kotlin/com/mytetz/api/Application.kt` | Registers `quizRoutes(...)` in `routing { }`. |
| `backend/api/src/test/kotlin/com/mytetz/api/TestFixtures.kt` | Adds a quiz stack the same shape as `sessionApp`. |
| `backend/api/src/test/kotlin/com/mytetz/api/QuizRoutesTest.kt` | New. Gate-order tests, cache-hit-costs-nothing test, `QUIZ_UNAVAILABLE` test, the two-calls-one-template test, the no-`correctIndex`-on-the-wire test, the end-to-end take-a-quiz test. |

### Frontend

| File | Responsibility |
|---|---|
| `frontend/src/app/core/models.ts` | Adds `QuizKind`, `QuizQuestionView`, `QuizTemplateView`, `QuizAnswerPayload`, `QuizResultView`. |
| `frontend/src/app/core/api.service.ts` | Adds `startQuiz` and `submitQuizAnswers`. |
| `frontend/src/app/assess/quiz-panel.component.ts` | The quiz UI: start → one question at a time → submit all → results with score and rationales. |
| `frontend/src/app/assess/quiz-panel.component.spec.ts` | Component tests. |
| `frontend/src/app/reader/focus-card.component.ts` | Adds a "Test me" control and an `testMeRequested` output. |
| `frontend/src/app/reader/reader-page.component.ts` | Adds an "Exam" control, opens `QuizPanelComponent` for either kind, wires "reopen this step" back to `store.goTo`. |
| `frontend/e2e/quiz.spec.ts` | New. A stubbed-backend Playwright run: open a topic, Test Me, answer three questions, see the score. |

---

## Task 1: `LlmClient.structured` and its fake

**Files:**
- Modify: `backend/llm/src/main/kotlin/com/mytetz/llm/LlmClient.kt`
- Modify: `backend/llm/src/testFixtures/kotlin/com/mytetz/llm/FakeLlmClient.kt`
- Test: `backend/llm/src/test/kotlin/com/mytetz/llm/FakeLlmClientTest.kt`

**Interfaces:**
- Produces: `StructuredRequest(system, userPrompt, toolName, toolDescription, inputSchema: Map<String, Any>, requiredFields: List<String>, maxTokens = 4000, effort = LlmEffort.LOW)`, `StructuredResult(json: String, usage: LlmUsage)`, `LlmStructuredOutputMissingException`, `LlmClient.structured(request: StructuredRequest): StructuredResult`.
- `inputSchema` maps a top-level JSON Schema property name to its own schema, expressed as plain
  `Map`/`List`/`String`/`Int`/`Boolean` values — vendor neutral, so neither this module nor its
  callers depend on the Anthropic SDK's `JsonValue` or on `kotlinx.serialization.json.JsonObject`.
  `result.json` is a JSON string; a caller with a typed shape in mind decodes it with
  `kotlinx.serialization.json.Json.decodeFromString`.

- [ ] **Step 1: Add the new types and the interface method**

Add to `LlmClient.kt`, after the existing `LlmStreamTruncatedException`:

```kotlin
/**
 * One field of a JSON Schema `properties` object: a name, and its own schema as a plain Kotlin
 * structure (nested `Map`/`List`/`String`/`Int`/`Boolean`).
 *
 * Plain structures, not a vendor JSON type. [LlmClient] is a vendor-agnostic port and must not leak
 * the Anthropic SDK's `JsonValue` or `kotlinx.serialization`'s `JsonObject` into its own signature.
 * [AnthropicLlmClient] converts this map with `JsonValue.from`, which builds a JSON tree from plain
 * Kotlin/Java values recursively; a fake or a future adapter needs no JSON library at all.
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
```

Add to the `LlmClient` interface, next to `stream`:

```kotlin
    /**
     * A single, non-streaming call that returns data shaped by [StructuredRequest.inputSchema]
     * rather than prose. A quiz question is JSON; JSON does not render until it is structurally
     * complete, so nothing is lost by not streaming it — see [PromptBuilder]'s own note on why
     * [stream] is prose-only for the opposite reason.
     */
    suspend fun structured(request: StructuredRequest): StructuredResult
```

- [ ] **Step 2: Implement the fake**

In `FakeLlmClient.kt`, add fields and the method next to the existing `stream` fixture:

```kotlin
    var nextStructuredJson: String = """{"questions":[]}"""
    var structuredFailWith: Throwable? = null
    val structuredCalls = mutableListOf<StructuredRequest>()
    var structuredUsage: LlmUsage = LlmUsage(inputTokens = 100, outputTokens = 50)

    /** Set per-prompt JSON answers, exactly like [bodyByPromptSubstring] — a test that drives the
     * one-retry-with-a-nudge path sets one answer for the first prompt and another for the nudged
     * one, keyed on text each prompt alone contains. */
    val structuredJsonByPromptSubstring = linkedMapOf<String, String>()

    override suspend fun structured(request: StructuredRequest): StructuredResult {
        structuredCalls += request
        structuredFailWith?.let { throw it }
        val json = structuredJsonByPromptSubstring.entries
            .firstOrNull { request.userPrompt.contains(it.key) }
            ?.value
            ?: nextStructuredJson
        return StructuredResult(json, structuredUsage)
    }
```

- [ ] **Step 3: Write the failing tests**

Create `FakeLlmClientTest.kt` additions (append to the existing file if you are told it already
holds `stream` tests; otherwise create it with these two tests plus a package/imports header
matching `FakeLlmClient.kt`'s):

```kotlin
    @Test
    fun `structured records the request and returns the configured json`() = runBlocking {
        val fake = FakeLlmClient()
        fake.nextStructuredJson = """{"questions":[{"stem":"x"}]}"""

        val result = fake.structured(
            StructuredRequest(
                system = "sys",
                userPrompt = "prompt",
                toolName = "submit_quiz_questions",
                toolDescription = "desc",
                inputSchema = mapOf("questions" to mapOf("type" to "array")),
                requiredFields = listOf("questions"),
            )
        )

        assertEquals("""{"questions":[{"stem":"x"}]}""", result.json)
        assertEquals(1, fake.structuredCalls.size)
        assertEquals("submit_quiz_questions", fake.structuredCalls.single().toolName)
    }

    @Test
    fun `structured selects a json body keyed on a prompt substring`() = runBlocking {
        val fake = FakeLlmClient()
        fake.structuredJsonByPromptSubstring["NUDGE"] = """{"questions":[]}"""
        fake.nextStructuredJson = """{"questions":[{"stem":"first try"}]}"""

        val first = fake.structured(request(userPrompt = "plain prompt"))
        val retried = fake.structured(request(userPrompt = "plain prompt NUDGE"))

        assertEquals("""{"questions":[{"stem":"first try"}]}""", first.json)
        assertEquals("""{"questions":[]}""", retried.json)
    }

    private fun request(userPrompt: String) = StructuredRequest(
        system = "sys",
        userPrompt = userPrompt,
        toolName = "submit_quiz_questions",
        toolDescription = "desc",
        inputSchema = mapOf("questions" to mapOf("type" to "array")),
        requiredFields = listOf("questions"),
    )
```

If `FakeLlmClientTest.kt` does not already import `kotlinx.coroutines.runBlocking` and
`kotlin.test.assertEquals`, add those imports.

- [ ] **Step 4: Run the tests and confirm they fail to compile, then pass**

Run: `./gradlew :backend:llm:test --tests "com.mytetz.llm.FakeLlmClientTest"`
Expected before Step 1/2: compile failure (`structured` unresolved).
Expected after Step 1/2: both tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/llm/src/main/kotlin/com/mytetz/llm/LlmClient.kt \
        backend/llm/src/testFixtures/kotlin/com/mytetz/llm/FakeLlmClient.kt \
        backend/llm/src/test/kotlin/com/mytetz/llm/FakeLlmClientTest.kt
git commit -m "feat(llm): add a structured-output method next to stream"
```

---

## Task 2: `AnthropicLlmClient.structured`

**Files:**
- Modify: `backend/llm/src/main/kotlin/com/mytetz/llm/AnthropicLlmClient.kt`
- Test: `backend/llm/src/test/kotlin/com/mytetz/llm/AnthropicLlmClientTest.kt`

**Interfaces:**
- Consumes: `StructuredRequest`, `StructuredResult`, `LlmStructuredOutputMissingException` (Task 1).
- Uses the Anthropic Java SDK's forced tool call: `Tool.builder()`, `Tool.InputSchema.builder()`,
  `Tool.InputSchema.Properties.builder().putAdditionalProperty(name, JsonValue.from(schema))`,
  `MessageCreateParams.Builder.addTool(Tool)`, `.toolToolChoice(name)` (a convenience that forces
  exactly one named tool), `.addUserMessage(String)`, and the **non-streaming**
  `client.messages().create(params)`, which returns a `Message` directly rather than a stream.
  `Message.content()` is `List<ContentBlock>`; `ContentBlock.isToolUse()` /
  `.asToolUse()` narrow to `ToolUseBlock`; `ToolUseBlock._input()` is a `JsonValue`, converted to
  text with `.convert(com.fasterxml.jackson.databind.JsonNode::class.java).toString()` — Jackson's
  own `JsonNode.toString()` is specified to produce valid JSON, and this avoids depending on an
  unconfirmed `JsonValue.toString()` contract.

- [ ] **Step 1: Write the failing tests**

Add to `AnthropicLlmClientTest.kt`. This suite already has `sseServer`/`clientFor` helpers for the
streaming endpoint; add a second local server helper for a plain (non-SSE) JSON response, since
`structured` does not stream:

```kotlin
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
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :backend:llm:test --tests "com.mytetz.llm.AnthropicLlmClientTest"`
Expected: compile failure (`structured` not implemented) or, once Step 3 stubs a signature, three
failing assertions.

- [ ] **Step 3: Implement `structured`**

Add these imports to `AnthropicLlmClient.kt`:

```kotlin
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.fasterxml.jackson.databind.JsonNode
```

Add the method to the `AnthropicLlmClient` class, after `stream`:

```kotlin
    /**
     * One blocking call, forced onto [StructuredRequest.toolName] so the model can answer only in
     * the shape [StructuredRequest.inputSchema] describes.
     *
     * Not streamed. [stream] exists for prose, which is worth rendering token by token; a quiz
     * question is JSON, and JSON is unusable until every brace has arrived, so nothing is lost by
     * calling the blocking, non-streaming endpoint here instead — one line rather than the
     * event-loop `stream` needs.
     */
    override suspend fun structured(request: StructuredRequest): StructuredResult {
        val properties = Tool.InputSchema.Properties.builder().apply {
            request.inputSchema.forEach { (name, schema) -> putAdditionalProperty(name, JsonValue.from(schema)) }
        }.build()

        val schema = Tool.InputSchema.builder().apply {
            properties(properties)
            request.requiredFields.forEach { addRequired(it) }
        }.build()

        val tool = Tool.builder()
            .name(request.toolName)
            .description(request.toolDescription)
            .inputSchema(schema)
            .build()

        val params = MessageCreateParams.builder()
            .model(modelId)
            .maxTokens(request.maxTokens)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(request.system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()
                )
            )
            .outputConfig(OutputConfig.builder().effort(effortOf(request.effort)).build())
            .addTool(tool)
            .toolToolChoice(request.toolName)
            .addUserMessage(request.userPrompt)
            .build()

        val message = runInterruptible(Dispatchers.IO) { client.messages().create(params) }

        val toolUse = message.content().firstOrNull { it.isToolUse() }?.asToolUse()
            ?: throw LlmStructuredOutputMissingException(
                "no tool_use block for '${request.toolName}' in the response; stop reason was ${message.stopReason()}"
            )

        return StructuredResult(
            json = toolUse._input().convert(JsonNode::class.java).toString(),
            usage = LlmUsage(
                inputTokens = message.usage().inputTokens(),
                outputTokens = message.usage().outputTokens(),
                cacheReadInputTokens = message.usage().cacheReadInputTokens().orElse(0L),
                cacheCreationInputTokens = message.usage().cacheCreationInputTokens().orElse(0L),
            ),
        )
    }
```

If `message.stopReason()` is an `Optional<StopReason>` rather than a bare value (check the compile
error), adapt the exception message to `message.stopReason().map { it.toString() }.orElse("none")`
— the exact accessor shape is confirmed by the compiler, not guessed twice.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :backend:llm:test --tests "com.mytetz.llm.AnthropicLlmClientTest"`
Expected: PASS, all tests including the pre-existing `stream` ones (this step must not regress
them).

- [ ] **Step 5: Commit**

```bash
git add backend/llm/src/main/kotlin/com/mytetz/llm/AnthropicLlmClient.kt \
        backend/llm/src/test/kotlin/com/mytetz/llm/AnthropicLlmClientTest.kt
git commit -m "feat(llm): implement structured output with a forced tool call"
```

---

## Task 3: Quiz models and the content key

**Files:**
- Create: `backend/assess/src/main/kotlin/com/mytetz/assess/Quiz.kt`
- Create: `backend/assess/src/main/kotlin/com/mytetz/assess/QuizContentKey.kt`
- Test: `backend/assess/src/test/kotlin/com/mytetz/assess/QuizContentKeyTest.kt`

**Interfaces:**
- Produces: `QuizKind` (`TEST_ME`, `EXAM`), `QuizQuestion`, `QuizTemplate`, `AnsweredQuestion`,
  `QuizAttempt`, `QuizSource(key: String, body: String)`, `QuizContentKey.derive(scopeKeys: List<String>, kind: QuizKind, promptVersion: String, modelFamily: String): String`.

- [ ] **Step 1: Write `Quiz.kt`**

```kotlin
package com.mytetz.assess

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class QuizKind { TEST_ME, EXAM }

/** One explanation body, keyed by its content key, ready to go into a quiz prompt. */
data class QuizSource(val key: String, val body: String)

/**
 * One validated, stored question. [correctIndex] and [sourceKey] never leave this module for the
 * browser — see `QuizQuestionView` in `:backend:api`, which is the shape the wire actually uses.
 */
@Serializable
data class QuizQuestion(
    val questionId: String,
    val stem: String,
    val options: List<String>,
    val correctIndex: Int,
    /** A member of the [QuizTemplate.scopeKeys] this question was drawn from. Enforced by [QuizValidator]. */
    val sourceKey: String,
    val rationale: String,
)

/**
 * Content addressed, exactly like `com.mytetz.graph.Explanation` — immutable, never updated except
 * [requestCount], and a second request for the same [scopeKeys], [kind], [promptVersion] and
 * [modelFamily] is a cache hit and calls no model.
 */
@Serializable
data class QuizTemplate(
    @SerialName("_id") val key: String,
    val kind: QuizKind,
    /** Explanation content keys, in the order the quiz was scoped over. Order is part of identity:
     * `QuizContentKey.derive` hashes it, so a different traversal order is a different quiz — which
     * is correct, since Exam's own scope is "every node in the session, in order". */
    val scopeKeys: List<String>,
    val questions: List<QuizQuestion>,
    val promptVersion: String,
    val modelFamily: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costMicros: Long,
    val requestCount: Long,
    val createdAtEpochMillis: Long,
)

@Serializable
data class AnsweredQuestion(val questionId: String, val chosenIndex: Int)

/**
 * One learner's attempt at one [QuizTemplate]. [score] and [submittedAtEpochMillis] are null until
 * `QuizService.score` records an answer set; a re-submission overwrites both, which is a deliberate
 * simplification — nothing about scoring calls the model, so nothing here can spend money twice.
 */
@Serializable
data class QuizAttempt(
    @SerialName("_id") val id: String,
    val principalId: String,
    val sessionId: String,
    val templateId: String,
    val answers: List<AnsweredQuestion>,
    val score: Int?,
    val total: Int,
    val createdAtEpochMillis: Long,
    val submittedAtEpochMillis: Long?,
)
```

- [ ] **Step 2: Write `QuizContentKey.kt`**

```kotlin
package com.mytetz.assess

import java.security.MessageDigest

/**
 * Derives a quiz template's identity: `sha256(scopeKeys | kind | promptVersion | modelFamily)`.
 *
 * Mirrors `com.mytetz.graph.ContentKey` exactly, for the same reason: length-prefixed fields, not a
 * delimiter, because a scope key is itself a hex hash and could in principle collide with a
 * delimiter in some future encoding — length prefixing removes the question rather than trusting
 * the alphabet.
 *
 * [scopeKeys]' order is part of the hash. Two Exam requests over the same explanations visited in a
 * different order derive different keys and generate independently. That is correct: this project's
 * own rule is that Exam questions may only cover material presented **earlier**, and a quiz that
 * ignored order could be served to a learner who has not yet reached its last scope key.
 */
object QuizContentKey {

    fun derive(scopeKeys: List<String>, kind: QuizKind, promptVersion: String, modelFamily: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(encodeLength(scopeKeys.size))
        scopeKeys.forEach { digest.updateLengthPrefixed(it) }
        listOf(kind.name, promptVersion, modelFamily).forEach { digest.updateLengthPrefixed(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun MessageDigest.updateLengthPrefixed(field: String) {
        val bytes = field.toByteArray(Charsets.UTF_8)
        update(encodeLength(bytes.size))
        update(bytes)
    }

    private fun encodeLength(length: Int): ByteArray = byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )
}
```

- [ ] **Step 3: Write the property tests**

```kotlin
package com.mytetz.assess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QuizContentKeyTest {

    private fun key(
        scopeKeys: List<String> = listOf("a", "b"),
        kind: QuizKind = QuizKind.TEST_ME,
        promptVersion: String = "v1",
        modelFamily: String = "family",
    ) = QuizContentKey.derive(scopeKeys, kind, promptVersion, modelFamily)

    @Test
    fun `identical inputs derive identical keys`() {
        assertEquals(key(), key())
    }

    @Test
    fun `a different scope key changes the key`() {
        assertNotEquals(key(scopeKeys = listOf("a", "b")), key(scopeKeys = listOf("a", "c")))
    }

    @Test
    fun `scope order changes the key`() {
        assertNotEquals(key(scopeKeys = listOf("a", "b")), key(scopeKeys = listOf("b", "a")))
    }

    @Test
    fun `a different kind changes the key`() {
        assertNotEquals(key(kind = QuizKind.TEST_ME), key(kind = QuizKind.EXAM))
    }

    @Test
    fun `a different prompt version changes the key`() {
        assertNotEquals(key(promptVersion = "v1"), key(promptVersion = "v2"))
    }

    @Test
    fun `a different model family changes the key`() {
        assertNotEquals(key(modelFamily = "family-a"), key(modelFamily = "family-b"))
    }

    @Test
    fun `two scope keys concatenated without a boundary do not collide with a different split`() {
        // Length prefixing must stop ["ab", "c"] and ["a", "bc"] from hashing the same.
        assertNotEquals(key(scopeKeys = listOf("ab", "c")), key(scopeKeys = listOf("a", "bc")))
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :backend:assess:test --tests "com.mytetz.assess.QuizContentKeyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/assess/src/main/kotlin/com/mytetz/assess/Quiz.kt \
        backend/assess/src/main/kotlin/com/mytetz/assess/QuizContentKey.kt \
        backend/assess/src/test/kotlin/com/mytetz/assess/QuizContentKeyTest.kt
git commit -m "feat(assess): add quiz models and the content key"
```

---

## Task 4: `QuizValidator`

**Files:**
- Create: `backend/assess/src/main/kotlin/com/mytetz/assess/QuizValidator.kt`
- Test: `backend/assess/src/test/kotlin/com/mytetz/assess/QuizValidatorTest.kt`

**Interfaces:**
- Consumes: `QuizQuestion` shape fields (Task 3), but validates a raw, untrusted candidate — defined
  in this file as `RawQuizQuestion`, since the model's JSON output must not be trusted as a
  `QuizQuestion` until it has passed every check.
- Produces: `RawQuizQuestion(stem: String, options: List<String>, correctIndex: Int, sourceKey: String, rationale: String)`, `QuizValidationResult` (`Valid(question: QuizQuestion)` / `Invalid(reason: String)`), `QuizValidator.validate(candidate: RawQuizQuestion, scopeKeys: List<String>, questionId: String): QuizValidationResult`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mytetz.assess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuizValidatorTest {

    private val validator = QuizValidator()
    private val scopeKeys = listOf("key-1", "key-2")

    private fun raw(
        stem: String = "What is the escape velocity?",
        options: List<String> = listOf("a", "b", "c", "d"),
        correctIndex: Int = 1,
        sourceKey: String = "key-1",
        rationale: String = "Because the text said so.",
    ) = RawQuizQuestion(stem, options, correctIndex, sourceKey, rationale)

    @Test
    fun `a well-formed candidate is valid`() {
        val result = validator.validate(raw(), scopeKeys, questionId = "q1")
        val valid = assertIs<QuizValidationResult.Valid>(result)
        assertEquals("q1", valid.question.questionId)
        assertEquals("key-1", valid.question.sourceKey)
    }

    @Test
    fun `an out-of-scope sourceKey is invalid`() {
        val result = validator.validate(raw(sourceKey = "not-in-scope"), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `three options is invalid`() {
        val result = validator.validate(raw(options = listOf("a", "b", "c")), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `five options is invalid`() {
        val result = validator.validate(raw(options = listOf("a", "b", "c", "d", "e")), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `a correctIndex of 4 is invalid for four options`() {
        val result = validator.validate(raw(correctIndex = 4), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `a negative correctIndex is invalid`() {
        val result = validator.validate(raw(correctIndex = -1), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `an empty stem is invalid`() {
        val result = validator.validate(raw(stem = "   "), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `an empty rationale is invalid`() {
        val result = validator.validate(raw(rationale = ""), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `an empty scope set rejects every candidate`() {
        val result = validator.validate(raw(sourceKey = "key-1"), scopeKeys = emptyList(), "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail (compile error: types do not exist yet)**

Run: `./gradlew :backend:assess:test --tests "com.mytetz.assess.QuizValidatorTest"`

- [ ] **Step 3: Write `QuizValidator.kt`**

```kotlin
package com.mytetz.assess

/** An untrusted candidate question, exactly as decoded from the model's JSON answer. */
data class RawQuizQuestion(
    val stem: String,
    val options: List<String>,
    val correctIndex: Int,
    val sourceKey: String,
    val rationale: String,
)

sealed interface QuizValidationResult {
    data class Valid(val question: QuizQuestion) : QuizValidationResult
    data class Invalid(val reason: String) : QuizValidationResult
}

/**
 * The rule the whole feature rests on — "questions may only cover material presented earlier" —
 * survives prompt drift only because it is enforced here. The prompt is a suggestion; this is the
 * rule. Every check below rejects rather than repairs: a candidate that fails is dropped, never
 * coerced into something that looks valid.
 */
class QuizValidator(private val requiredOptionCount: Int = 4) {

    fun validate(candidate: RawQuizQuestion, scopeKeys: List<String>, questionId: String): QuizValidationResult {
        if (candidate.sourceKey !in scopeKeys) {
            return QuizValidationResult.Invalid("sourceKey '${candidate.sourceKey}' is not a member of the scope")
        }
        if (candidate.options.size != requiredOptionCount) {
            return QuizValidationResult.Invalid(
                "expected $requiredOptionCount options, got ${candidate.options.size}"
            )
        }
        if (candidate.correctIndex !in candidate.options.indices) {
            return QuizValidationResult.Invalid("correctIndex ${candidate.correctIndex} is out of range")
        }
        if (candidate.stem.isBlank()) return QuizValidationResult.Invalid("empty stem")
        if (candidate.rationale.isBlank()) return QuizValidationResult.Invalid("empty rationale")
        if (candidate.options.any { it.isBlank() }) return QuizValidationResult.Invalid("an option is empty")

        return QuizValidationResult.Valid(
            QuizQuestion(
                questionId = questionId,
                stem = candidate.stem.trim(),
                options = candidate.options,
                correctIndex = candidate.correctIndex,
                sourceKey = candidate.sourceKey,
                rationale = candidate.rationale.trim(),
            )
        )
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :backend:assess:test --tests "com.mytetz.assess.QuizValidatorTest"`
Expected: PASS, all nine tests.

- [ ] **Step 5: Commit**

```bash
git add backend/assess/src/main/kotlin/com/mytetz/assess/QuizValidator.kt \
        backend/assess/src/test/kotlin/com/mytetz/assess/QuizValidatorTest.kt
git commit -m "feat(assess): add the adversarial quiz question validator"
```

---

## Task 5: `QuizPromptBuilder` and `QuizConfig`

**Files:**
- Create: `backend/assess/src/main/kotlin/com/mytetz/assess/QuizPromptBuilder.kt`
- Create: `backend/assess/src/main/kotlin/com/mytetz/assess/QuizConfig.kt`
- Test: `backend/assess/src/test/kotlin/com/mytetz/assess/QuizPromptBuilderTest.kt`
- Test: `backend/assess/src/test/kotlin/com/mytetz/assess/QuizConfigTest.kt`

**Interfaces:**
- Consumes: `QuizSource`, `QuizKind` (Task 3).
- Produces: `QuizPromptBuilder.VERSION`, `.TOOL_NAME`, `.TOOL_DESCRIPTION`, `.system(): String`, `.user(sources: List<QuizSource>, maxQuestions: Int): String`, `.nudge(previousPrompt: String): String`, `.inputSchema(): Map<String, Any>`, `.requiredFields: List<String>`; `QuizConfig(promptVersion, maxOutputTokens, effort, testMeMaxQuestions, examMaxQuestions)`.

- [ ] **Step 1: Write `QuizPromptBuilder.kt`**

```kotlin
package com.mytetz.assess

/**
 * The prompt and the JSON Schema for one quiz-generation call.
 *
 * One template for both [QuizKind]s, parameterised only by how many sources are in scope and how
 * many questions to ask for — Test Me passes one source, Exam passes every node's, in order. The
 * model is asked to answer only from the given material, and [QuizValidator] is what actually
 * enforces that a returned `sourceKey` belongs to the scope; the prompt is a suggestion.
 */
object QuizPromptBuilder {

    /** Bumped whenever this prompt or [inputSchema] changes; hashed into every [QuizContentKey]. */
    const val VERSION: String = "v1"

    const val TOOL_NAME: String = "submit_quiz_questions"
    const val TOOL_DESCRIPTION: String =
        "Submit the multiple-choice quiz questions you have written, one entry per question."

    fun system(): String =
        "You write short multiple-choice quiz questions that check whether a learner understood " +
            "material they already read. Every question must be answerable from the given " +
            "material alone. Write exactly four options: one correct, three plausible but wrong. " +
            "Write a one-sentence rationale for the correct option, using only the given material."

    /**
     * [sources] are the only material the questions may draw from. Each is labelled with its own
     * key, and the prompt names those keys as the only legal `sourceKey` values — the same rule
     * [QuizValidator] enforces afterwards in code.
     */
    fun user(sources: List<QuizSource>, maxQuestions: Int): String {
        val material = sources.joinToString("\n\n") { "[sourceKey: ${it.key}]\n${it.body}" }
        val keys = sources.joinToString(", ") { it.key }
        return "Write up to $maxQuestions multiple-choice questions from the material below. " +
            "Every question's sourceKey must be exactly one of: $keys.\n\n$material"
    }

    /** Appended to [previousPrompt] for the one allowed retry, after a first attempt produced nothing valid. */
    fun nudge(previousPrompt: String): String =
        "$previousPrompt\n\nYour previous attempt produced no usable question. Follow the schema " +
            "exactly: exactly four options, a correctIndex between 0 and 3, and a sourceKey that " +
            "is one of the keys given above."

    val requiredFields: List<String> = listOf("questions")

    fun inputSchema(): Map<String, Any> = mapOf(
        "questions" to mapOf(
            "type" to "array",
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "stem" to mapOf("type" to "string"),
                    "options" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "minItems" to 4,
                        "maxItems" to 4,
                    ),
                    "correctIndex" to mapOf("type" to "integer"),
                    "sourceKey" to mapOf("type" to "string"),
                    "rationale" to mapOf("type" to "string"),
                ),
                "required" to listOf("stem", "options", "correctIndex", "sourceKey", "rationale"),
            ),
        ),
    )
}
```

- [ ] **Step 2: Write `QuizPromptBuilderTest.kt`**

```kotlin
package com.mytetz.assess

import kotlin.test.Test
import kotlin.test.assertTrue

class QuizPromptBuilderTest {

    private val sources = listOf(QuizSource("key-1", "Body one."), QuizSource("key-2", "Body two."))

    @Test
    fun `the user prompt names every source key and body`() {
        val prompt = QuizPromptBuilder.user(sources, maxQuestions = 3)
        assertTrue("key-1" in prompt)
        assertTrue("key-2" in prompt)
        assertTrue("Body one." in prompt)
        assertTrue("Body two." in prompt)
        assertTrue("3" in prompt)
    }

    @Test
    fun `the nudge keeps the original prompt and adds a correction`() {
        val original = QuizPromptBuilder.user(sources, maxQuestions = 3)
        val nudged = QuizPromptBuilder.nudge(original)
        assertTrue(original in nudged)
        assertTrue("previous attempt" in nudged)
    }

    @Test
    fun `the schema requires exactly the five question fields`() {
        @Suppress("UNCHECKED_CAST")
        val questions = QuizPromptBuilder.inputSchema()["questions"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val items = questions["items"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val required = items["required"] as List<String>
        assertTrue(required.containsAll(listOf("stem", "options", "correctIndex", "sourceKey", "rationale")))
    }
}
```

- [ ] **Step 3: Write `QuizConfig.kt`**

```kotlin
package com.mytetz.assess

import com.mytetz.llm.LlmEffort

/**
 * The knobs a deployment may turn on quiz generation. Mirrors `com.mytetz.graph.GraphConfig`
 * exactly, including the fallback rule: a missing, unparseable or non-positive override falls back
 * to the default rather than throwing, because a typo in a deployment variable must not take the
 * server down.
 */
data class QuizConfig(
    val promptVersion: String = QuizPromptBuilder.VERSION,
    val maxOutputTokens: Long = resolveMaxOutputTokens(System.getenv(MAX_OUTPUT_TOKENS_ENV)),
    val effort: LlmEffort = resolveEffort(System.getenv(EFFORT_ENV)),
    val testMeMaxQuestions: Int = resolvePositiveInt(System.getenv(TEST_ME_MAX_QUESTIONS_ENV), DEFAULT_TEST_ME_MAX_QUESTIONS),
    val examMaxQuestions: Int = resolvePositiveInt(System.getenv(EXAM_MAX_QUESTIONS_ENV), DEFAULT_EXAM_MAX_QUESTIONS),
) {
    companion object {
        const val MAX_OUTPUT_TOKENS_ENV: String = "MYTETZ_QUIZ_MAX_OUTPUT_TOKENS"
        const val EFFORT_ENV: String = "MYTETZ_QUIZ_EFFORT"
        const val TEST_ME_MAX_QUESTIONS_ENV: String = "MYTETZ_TEST_ME_MAX_QUESTIONS"
        const val EXAM_MAX_QUESTIONS_ENV: String = "MYTETZ_EXAM_MAX_QUESTIONS"

        const val DEFAULT_MAX_OUTPUT_TOKENS: Long = 2000
        const val DEFAULT_TEST_ME_MAX_QUESTIONS: Int = 3
        const val DEFAULT_EXAM_MAX_QUESTIONS: Int = 8
        val DEFAULT_EFFORT: LlmEffort = LlmEffort.LOW

        internal fun resolveMaxOutputTokens(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_OUTPUT_TOKENS

        internal fun resolveEffort(raw: String?): LlmEffort =
            raw?.trim()?.uppercase()?.let { name -> LlmEffort.entries.firstOrNull { it.name == name } }
                ?: DEFAULT_EFFORT

        internal fun resolvePositiveInt(raw: String?, default: Int): Int =
            raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: default
    }
}
```

- [ ] **Step 4: Write `QuizConfigTest.kt`**

```kotlin
package com.mytetz.assess

import com.mytetz.llm.LlmEffort
import kotlin.test.Test
import kotlin.test.assertEquals

class QuizConfigTest {

    @Test
    fun `an unset override falls back to the default`() {
        assertEquals(QuizConfig.DEFAULT_MAX_OUTPUT_TOKENS, QuizConfig.resolveMaxOutputTokens(null))
        assertEquals(QuizConfig.DEFAULT_EFFORT, QuizConfig.resolveEffort(null))
        assertEquals(3, QuizConfig.resolvePositiveInt(null, 3))
    }

    @Test
    fun `a non-positive or unparseable override falls back to the default`() {
        assertEquals(QuizConfig.DEFAULT_MAX_OUTPUT_TOKENS, QuizConfig.resolveMaxOutputTokens("0"))
        assertEquals(QuizConfig.DEFAULT_MAX_OUTPUT_TOKENS, QuizConfig.resolveMaxOutputTokens("not-a-number"))
        assertEquals(3, QuizConfig.resolvePositiveInt("-1", 3))
    }

    @Test
    fun `a recognised override is kept`() {
        assertEquals(5000L, QuizConfig.resolveMaxOutputTokens("5000"))
        assertEquals(LlmEffort.HIGH, QuizConfig.resolveEffort("high"))
        assertEquals(7, QuizConfig.resolvePositiveInt("7", 3))
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :backend:assess:test --tests "com.mytetz.assess.QuizPromptBuilderTest" --tests "com.mytetz.assess.QuizConfigTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/assess/src/main/kotlin/com/mytetz/assess/QuizPromptBuilder.kt \
        backend/assess/src/main/kotlin/com/mytetz/assess/QuizConfig.kt \
        backend/assess/src/test/kotlin/com/mytetz/assess/QuizPromptBuilderTest.kt \
        backend/assess/src/test/kotlin/com/mytetz/assess/QuizConfigTest.kt
git commit -m "feat(assess): add the quiz prompt builder and its config"
```

---

## Task 6: `QuizRepository`

**Files:**
- Create: `backend/assess/src/main/kotlin/com/mytetz/assess/QuizRepository.kt`
- Test: `backend/assess/src/test/kotlin/com/mytetz/assess/QuizRepositoryTest.kt`
- Create: `backend/assess/build.gradle.kts` — check whether it already lists `mongodb-driver-kotlin-coroutine` and `bson-kotlinx` (it currently only lists `session`, `graph`, `llm` project deps per the existing file read during planning). Add the Mongo dependencies `graph`'s own `build.gradle.kts` declares, matching versions exactly (copy the `implementation(libs...)` Mongo lines from `backend/graph/build.gradle.kts` — do not invent versions).
- Modify: `backend/assess/build.gradle.kts` if a Testcontainers/Mongo test dependency is needed for the test source set — copy the `testImplementation` block from `backend/graph/build.gradle.kts` the same way.

**Interfaces:**
- Consumes: `QuizTemplate`, `QuizAttempt` (Task 3).
- Produces: `QuizRepository(database: MongoDatabase)`, `.ensureIndexes()`, `.findByKey(key: String): QuizTemplate?` (open, for a test seam mirroring `ExplanationRepository.findByKey`), `.insertIfAbsent(template: QuizTemplate): QuizTemplate`, `.incrementRequestCount(key: String)`, `.insertAttempt(attempt: QuizAttempt)`, `.findAttempt(id: String): QuizAttempt?`, `.updateAttempt(attempt: QuizAttempt)`.
- Also produces: `backend/assess/src/test/kotlin/com/mytetz/assess/MongoTestSupport.kt`, a single shared Testcontainers Mongo instance for the whole `:backend:assess` test source set. **Do not** let `QuizRepositoryTest` (this task) or `QuizServiceTest` (Task 7) each start their own `MongoDBContainer`. `backend/graph/src/test/kotlin/com/mytetz/graph/MongoTestSupport.kt` already exists and its own KDoc explains why: one container per test class is a documented anti-pattern in this project ("Testcontainers has failed here thirteen times"). Copy that file's shape exactly into the new package:

  ```kotlin
  package com.mytetz.assess

  import com.mongodb.kotlin.client.coroutine.MongoClient
  import com.mongodb.kotlin.client.coroutine.MongoDatabase
  import org.testcontainers.containers.MongoDBContainer

  object MongoTestSupport {
      private val container = MongoDBContainer("mongo:7").apply { start() }
      private val client = MongoClient.create(container.connectionString)

      /** A fresh, isolated database per test class. Pass a name unique within this module — see
       * `com.mytetz.graph.MongoTestSupport`'s own KDoc for the full argument. */
      fun database(name: String): MongoDatabase = client.getDatabase("test_$name")
  }
  ```

  Both `QuizRepositoryTest` and `QuizServiceTest` then read `MongoTestSupport.database("repository")` /
  `MongoTestSupport.database("service")` (distinct names) instead of building a `MongoDBContainer`
  and a `MongoClient` themselves. Every test snippet below that shows
  `private val container = MongoDBContainer(...)` / `private val client = MongoClient.create(...)` is
  written that way only for readability in this plan document — replace that pattern with a call to
  `MongoTestSupport.database(...)` when you write the actual `.kt` file.

- [ ] **Step 1: Read `backend/graph/build.gradle.kts` and copy its Mongo dependency lines into `backend/assess/build.gradle.kts`**

Read the file first:

Run: `cat backend/graph/build.gradle.kts`

Add whatever `implementation(...)` and `testImplementation(...)` lines it has for Mongo and
Testcontainers to `backend/assess/build.gradle.kts`, alongside the three `implementation(project(...))`
lines already there. Do not change version numbers — copy them exactly as `graph`'s file states
them (they come from the Gradle version catalog, so a plain `libs.xyz` reference copies correctly
without needing the actual version).

- [ ] **Step 2: Write the failing test**

```kotlin
package com.mytetz.assess

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.MongoDBContainer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuizRepositoryTest {

    companion object {
        private val container = MongoDBContainer("mongo:7").apply { start() }
        private val client = MongoClient.create(container.connectionString)
    }

    private val database: MongoDatabase = client.getDatabase("test_assess_${System.nanoTime()}")
    private val repository = QuizRepository(database)

    private fun template(key: String = "k1") = QuizTemplate(
        key = key,
        kind = QuizKind.TEST_ME,
        scopeKeys = listOf("scope-1"),
        questions = listOf(QuizQuestion("q1", "stem", listOf("a", "b", "c", "d"), 0, "scope-1", "why")),
        promptVersion = "v1",
        modelFamily = "family",
        modelId = "model",
        inputTokens = 10,
        outputTokens = 5,
        costMicros = 100,
        requestCount = 0,
        createdAtEpochMillis = 0,
    )

    @Test
    fun `findByKey answers null for an absent key`() = runBlocking {
        assertNull(repository.findByKey("missing"))
    }

    @Test
    fun `insertIfAbsent stores and returns the template`() = runBlocking {
        val stored = repository.insertIfAbsent(template())
        assertEquals("k1", stored.key)
        assertNotNull(repository.findByKey("k1"))
    }

    @Test
    fun `insertIfAbsent on a duplicate key returns the existing winner rather than throwing`() = runBlocking {
        repository.insertIfAbsent(template())
        val second = repository.insertIfAbsent(template().copy(costMicros = 999))
        // The stored document is the FIRST insert's, not the caller's own — same contract as
        // ExplanationRepository.insertIfAbsent.
        assertEquals(100, second.costMicros)
    }

    @Test
    fun `incrementRequestCount increments`() = runBlocking {
        repository.insertIfAbsent(template())
        repository.incrementRequestCount("k1")
        repository.incrementRequestCount("k1")
        assertEquals(2, repository.findByKey("k1")?.requestCount)
    }

    @Test
    fun `attempts round-trip through insert, find and update`() = runBlocking {
        val attempt = QuizAttempt(
            id = "a1",
            principalId = "user:1",
            sessionId = "s1",
            templateId = "k1",
            answers = emptyList(),
            score = null,
            total = 1,
            createdAtEpochMillis = 0,
            submittedAtEpochMillis = null,
        )
        repository.insertAttempt(attempt)
        assertNotNull(repository.findAttempt("a1"))

        val scored = attempt.copy(
            answers = listOf(AnsweredQuestion("q1", 0)),
            score = 1,
            submittedAtEpochMillis = 123,
        )
        repository.updateAttempt(scored)
        assertEquals(1, repository.findAttempt("a1")?.score)
    }
}
```

- [ ] **Step 3: Run to confirm it fails to compile**

Run: `./gradlew :backend:assess:test --tests "com.mytetz.assess.QuizRepositoryTest"`

- [ ] **Step 4: Write `QuizRepository.kt`**

```kotlin
package com.mytetz.assess

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull

private const val DUPLICATE_KEY = 11000

/**
 * Mongo access for `quizTemplates` (content addressed, immutable) and `quizAttempts` (one row per
 * learner attempt). Mirrors `com.mytetz.graph.ExplanationRepository`'s shape exactly, including the
 * duplicate-key race handling on [insertIfAbsent]: the loser's own copy is discarded and the
 * winner's document is returned, wasteful but never wrong.
 *
 * `open`, and [findByKey] with it, for the same reason `ExplanationRepository` is: a test can make a
 * key appear between two reads, to exercise the race two callers would otherwise need to create by
 * chance.
 */
open class QuizRepository(database: MongoDatabase) {

    private val templates = database.getCollection<QuizTemplate>("quizTemplates")
    private val attempts = database.getCollection<QuizAttempt>("quizAttempts")

    suspend fun ensureIndexes() {
        attempts.createIndex(
            Indexes.compoundIndex(Indexes.ascending("principalId"), Indexes.descending("createdAtEpochMillis")),
            IndexOptions().name("principal_recent"),
        )
        attempts.createIndex(Indexes.ascending("sessionId"), IndexOptions().name("by_session"))
    }

    open suspend fun findByKey(key: String): QuizTemplate? =
        templates.find(Filters.eq("_id", key)).firstOrNull()

    suspend fun insertIfAbsent(template: QuizTemplate): QuizTemplate =
        try {
            templates.insertOne(template)
            template
        } catch (e: MongoWriteException) {
            if (e.error.code != DUPLICATE_KEY) throw e
            findByKey(template.key) ?: error("duplicate key ${template.key} reported but document not found")
        }

    suspend fun incrementRequestCount(key: String) {
        templates.updateOne(Filters.eq("_id", key), Updates.inc("requestCount", 1L))
    }

    suspend fun insertAttempt(attempt: QuizAttempt) {
        attempts.insertOne(attempt)
    }

    suspend fun findAttempt(id: String): QuizAttempt? =
        attempts.find(Filters.eq("_id", id)).firstOrNull()

    suspend fun updateAttempt(attempt: QuizAttempt) {
        attempts.replaceOne(Filters.eq("_id", attempt.id), attempt)
    }
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./gradlew :backend:assess:test --tests "com.mytetz.assess.QuizRepositoryTest"`
Expected: PASS. (First run pulls the `mongo:7` Testcontainers image; allow extra time.)

- [ ] **Step 6: Commit**

```bash
git add backend/assess/build.gradle.kts \
        backend/assess/src/main/kotlin/com/mytetz/assess/QuizRepository.kt \
        backend/assess/src/test/kotlin/com/mytetz/assess/QuizRepositoryTest.kt
git commit -m "feat(assess): add quiz Mongo persistence"
```

---

## Task 7: `QuizService`

**Files:**
- Create: `backend/assess/src/main/kotlin/com/mytetz/assess/QuizService.kt`
- Test: `backend/assess/src/test/kotlin/com/mytetz/assess/QuizServiceTest.kt`

**Interfaces:**
- Consumes: `QuizRepository` (Task 6), `QuizValidator`, `RawQuizQuestion`, `QuizValidationResult` (Task 4), `QuizConfig`, `QuizPromptBuilder` (Task 5), `QuizTemplate`, `QuizQuestion`, `QuizSource`, `QuizKind`, `QuizAttempt`, `AnsweredQuestion` (Task 3), `LlmClient`, `StructuredRequest`, `Pricing` (from `:backend:llm`), `FakeLlmClient` (from `:backend:llm`'s `testFixtures`, already a `testFixturesApi`/`testImplementation` dependency the same way `graph`'s own tests use it — confirm by checking `backend/graph/build.gradle.kts`'s test dependencies in Task 6 Step 1 and copying the same `llm` test-fixtures line if `assess` needs it added).
- Produces: `QuizUnavailableException`, `QuizService(repository, llm, validator, config = QuizConfig())`, `.keyFor(scopeKeys: List<String>, kind: QuizKind): String`, `.getOrGenerate(scopeKeys: List<String>, kind: QuizKind, sources: List<QuizSource>, onSpend: suspend (Long) -> Unit): QuizTemplate`, `.startAttempt(principalId: String, sessionId: String, template: QuizTemplate, idFactory: () -> String = ..., clock: () -> Long = ...): QuizAttempt`, `.score(attempt: QuizAttempt, template: QuizTemplate, answers: List<AnsweredQuestion>, clock: () -> Long = ...): QuizAttempt`.
- **Reuse `backend/assess/src/test/kotlin/com/mytetz/assess/MongoTestSupport.kt`, which Task 6 already
  created.** Do not add a second `MongoDBContainer` in this test file — call
  `QuizRepository(MongoTestSupport.database("service"))` (or another name distinct from Task 6's
  `"repository"`) instead of the `companion object { container = MongoDBContainer(...) }` shape shown
  in the test snippet below, which — like Task 6's — is written inline only for readability in this
  plan document.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mytetz.assess

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mytetz.llm.FakeLlmClient
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuizServiceTest {

    companion object {
        private val container = MongoDBContainer("mongo:7").apply { start() }
        private val client = MongoClient.create(container.connectionString)
    }

    private fun repository() = QuizRepository(client.getDatabase("test_assess_svc_${System.nanoTime()}"))

    private val sources = listOf(QuizSource("key-1", "The sky is blue because of Rayleigh scattering."))

    private fun validQuestionJson(sourceKey: String = "key-1") = """
        {"questions":[{"stem":"Why is the sky blue?","options":["a","b","c","d"],"correctIndex":1,"sourceKey":"$sourceKey","rationale":"Rayleigh scattering."}]}
    """.trimIndent()

    @Test
    fun `generates, validates and persists a template, and reports its cost`() = runBlocking {
        val llm = FakeLlmClient().apply { nextStructuredJson = validQuestionJson() }
        val service = QuizService(repository(), llm, QuizValidator())
        var spent = 0L

        val template = service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) { spent += it }

        assertEquals(1, template.questions.size)
        assertEquals("key-1", template.questions.single().sourceKey)
        assertTrue(spent > 0, "a real generation must report a positive cost")
        assertEquals(1, llm.structuredCalls.size)
    }

    @Test
    fun `two calls with the same scope hit one template document`() = runBlocking {
        val llm = FakeLlmClient().apply { nextStructuredJson = validQuestionJson() }
        val repo = repository()
        val service = QuizService(repo, llm, QuizValidator())

        val first = service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}
        val second = service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}

        assertEquals(first.key, second.key)
        assertEquals(1, llm.structuredCalls.size, "the second call must be a cache hit and call no model")
    }

    @Test
    fun `an out-of-scope sourceKey is dropped, and a retry with a nudge can still succeed`() = runBlocking {
        val llm = FakeLlmClient().apply {
            nextStructuredJson = """{"questions":[{"stem":"x","options":["a","b","c","d"],"correctIndex":0,"sourceKey":"not-in-scope","rationale":"r"}]}"""
            structuredJsonByPromptSubstring["previous attempt"] = validQuestionJson()
        }
        val service = QuizService(repository(), llm, QuizValidator())

        val template = service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}

        assertEquals(1, template.questions.size)
        assertEquals(2, llm.structuredCalls.size, "the first attempt is invalid, so a nudged retry must follow")
    }

    @Test
    fun `nothing valid on either attempt raises QuizUnavailableException`() = runBlocking {
        val llm = FakeLlmClient().apply { nextStructuredJson = """{"questions":[]}""" }
        val service = QuizService(repository(), llm, QuizValidator())

        assertFailsWith<QuizUnavailableException> {
            service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}
        }
        assertEquals(2, llm.structuredCalls.size, "both the first attempt and the one retry must have run")
    }

    @Test
    fun `malformed json from the model is treated as no valid questions rather than a crash`(): Unit = runBlocking {
        val llm = FakeLlmClient().apply { nextStructuredJson = "not json" }
        val service = QuizService(repository(), llm, QuizValidator())

        assertFailsWith<QuizUnavailableException> {
            service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}
        }
    }

    @Test
    fun `scoring counts each correct answer and records the submission time`() = runBlocking {
        val service = QuizService(repository(), FakeLlmClient(), QuizValidator())
        val template = QuizTemplate(
            key = "k1", kind = QuizKind.TEST_ME, scopeKeys = listOf("key-1"),
            questions = listOf(
                QuizQuestion("q1", "stem1", listOf("a", "b", "c", "d"), correctIndex = 0, sourceKey = "key-1", rationale = "r1"),
                QuizQuestion("q2", "stem2", listOf("a", "b", "c", "d"), correctIndex = 2, sourceKey = "key-1", rationale = "r2"),
            ),
            promptVersion = "v1", modelFamily = "f", modelId = "m",
            inputTokens = 0, outputTokens = 0, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
        )
        val attempt = QuizAttempt(
            id = "a1", principalId = "user:1", sessionId = "s1", templateId = "k1",
            answers = emptyList(), score = null, total = 2, createdAtEpochMillis = 0, submittedAtEpochMillis = null,
        )

        val scored = service.score(
            attempt,
            template,
            answers = listOf(AnsweredQuestion("q1", chosenIndex = 0), AnsweredQuestion("q2", chosenIndex = 1)),
            clock = { 999 },
        )

        assertEquals(1, scored.score, "only q1 was answered correctly")
        assertEquals(999, scored.submittedAtEpochMillis)
    }

    @Test
    fun `an unanswered question counts as wrong rather than throwing`() = runBlocking {
        val service = QuizService(repository(), FakeLlmClient(), QuizValidator())
        val template = QuizTemplate(
            key = "k1", kind = QuizKind.TEST_ME, scopeKeys = listOf("key-1"),
            questions = listOf(QuizQuestion("q1", "stem1", listOf("a", "b", "c", "d"), correctIndex = 0, sourceKey = "key-1", rationale = "r1")),
            promptVersion = "v1", modelFamily = "f", modelId = "m",
            inputTokens = 0, outputTokens = 0, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
        )
        val attempt = QuizAttempt(
            id = "a1", principalId = "user:1", sessionId = "s1", templateId = "k1",
            answers = emptyList(), score = null, total = 1, createdAtEpochMillis = 0, submittedAtEpochMillis = null,
        )

        val scored = service.score(attempt, template, answers = emptyList())

        assertEquals(0, scored.score)
    }
}
```

- [ ] **Step 2: Run to confirm the tests fail to compile**

Run: `./gradlew :backend:assess:test --tests "com.mytetz.assess.QuizServiceTest"`

- [ ] **Step 3: Write `QuizService.kt`**

```kotlin
package com.mytetz.assess

import com.mytetz.llm.LlmClient
import com.mytetz.llm.Pricing
import com.mytetz.llm.StructuredRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Nothing valid survived generation and the one retry. Nothing is persisted, so a later attempt at
 * the same scope regenerates cleanly — the same reasoning `com.mytetz.graph.GenerationFailedException`
 * documents, applied to a JSON answer instead of a prose one.
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
 * Get-or-generate for quiz templates, mirroring `com.mytetz.graph.ExplanationGraph` — this class has
 * no concept of a user, a principal or a session; it answers one question, "what is the quiz over
 * these scope keys?", the same way for everyone.
 *
 * No per-key mutex guards concurrent identical requests here, unlike `ExplanationGraph`. Quiz
 * traffic is a small fraction of explain traffic, and `QuizRepository.insertIfAbsent`'s unique `_id`
 * still guarantees exactly one document is ever stored per key even under a stampede — a concurrent
 * duplicate costs an extra paid generation, never a wrong or duplicated document. Recorded as a
 * simplification, not an oversight: add the lock the day quiz traffic makes the extra cost visible.
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
     * A cache hit costs nothing and calls no model. A miss generates once, and — only if that
     * attempt yields zero valid questions — once more with a corrective nudge. [onSpend] fires the
     * instant each call's cost is known, before validation or persistence, exactly like
     * `com.mytetz.session.SessionService.create`'s callback of the same name: a generation can be
     * billed and then produce nothing valid, and a caller reading the cost off a return value would
     * never see it.
     *
     * Throws [QuizUnavailableException] when nothing valid survives either attempt. The caller
     * (`QuizRoutes.kt`) still hears about both attempts' cost through [onSpend] before that happens.
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
        // The cost leaves here, before parsing or validation — either of which can find nothing
        // usable in a call that still cost real tokens. See GraphChunk.Spent for the same ordering.
        spend(Pricing.costMicros(llm.modelId, result.usage))

        val raw = try {
            LENIENT_JSON.decodeFromString<RawQuizResponse>(result.json).questions
        } catch (e: SerializationException) {
            emptyList()
        }

        val questions = raw.mapIndexedNotNull { index, candidate ->
            val verdict = validator.validate(
                RawQuizQuestion(candidate.stem, candidate.options, candidate.correctIndex, candidate.sourceKey, candidate.rationale),
                scopeKeys,
                questionId = "${'$'}{if (nudge) "retry" else "gen"}-$index-${UUID.randomUUID()}",
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
     * Scores [answers] against [template] and returns [attempt] updated with the result.
     *
     * A question with no matching answer counts as wrong rather than being excluded — [attempt.total]
     * is fixed at creation time to [template.questions]'s size, so a partial submission cannot raise
     * a learner's own denominator by answering fewer questions.
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
```

Note the `${'$'}` escape above is only needed if you paste the questionId expression literally as
shown in a Markdown fence; when you actually create the `.kt` file, write it as a normal Kotlin
string template: `questionId = "${if (nudge) "retry" else "gen"}-$index-${UUID.randomUUID()}"`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :backend:assess:test --tests "com.mytetz.assess.QuizServiceTest"`
Expected: PASS, all eight tests.

- [ ] **Step 5: Run the whole `assess` module's tests**

Run: `./gradlew :backend:assess:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/assess/src/main/kotlin/com/mytetz/assess/QuizService.kt \
        backend/assess/src/test/kotlin/com/mytetz/assess/QuizServiceTest.kt
git commit -m "feat(assess): add QuizService — get-or-generate, one retry, scoring"
```

---

## Task 8: Widen the gate helpers, and the quiz-generation route

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt` (visibility only)
- Modify: `backend/api/build.gradle.kts` — add `implementation(project(":backend:assess"))`
- Create: `backend/api/src/main/kotlin/com/mytetz/api/QuizRoutes.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/ErrorMapping.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/TestFixtures.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/QuizRoutesTest.kt`

**Interfaces:**
- Consumes: `QuizService`, `QuizRepository`, `QuizValidator`, `QuizKind`, `QuizSource`, `QuizTemplate`, `QuizAttempt`, `AnsweredQuestion`, `QuizUnavailableException` (`:backend:assess`); `SessionService.load`, `SessionService.requireOwnedBy` (already `internal`/usable — check `SessionService.ownerOf` and `.load` are both accessible from `api`, which they already are, being `public`); `QuotaService`, `PrincipalId`, `Allowance` (`:backend:quota`); `BillingService`, `EntitlementDecision` (`:backend:billing`); `AccountService`, `User` (`:backend:account`); `Refusal`, `respondRefusal`, `QuotaService.refusalFor`, `QuotaService.recordSpend` (widened in Step 1 below); `ApiError`, `ClientAddress`, `ClientAddressConfig`, `FixedWindowRateLimiter`, `PrincipalCookieConfig`, `Principals` (all already in `com.mytetz.api`).
- Produces: `QuizRequest`, `QuizQuestionView`, `QuizTemplateView`, `AnsweredQuestionPayload`, `QuizAnswersRequest`, `QuizResultView`, `QuizAttemptNotFoundException`, `Route.quizRoutes(sessions: () -> SessionService, quizzes: () -> QuizService, quota: QuotaService, billing: BillingService, account: AccountService, cookies: PrincipalCookieConfig, clientAddresses: ClientAddressConfig)`.

This task covers only `POST /api/sessions/{id}/quizzes` (generation). Task 9 covers the answers
route, in the same file, so this task's route file compiles standalone with a stub for the second
route removed by Task 9's edits — write both endpoints in this task's file directly if it is faster,
but write and pass this task's tests before moving on, so a failure here is caught before Task 9
adds more surface to the same file.

- [ ] **Step 1: Widen five `private` declarations in `SessionRoutes.kt` to `internal`**

Change exactly these five lines (do not change behaviour, only the visibility modifier):

```kotlin
private suspend fun SessionService.requireOwnedBy(sessionId: String, principal: PrincipalId) {
```
→
```kotlin
internal suspend fun SessionService.requireOwnedBy(sessionId: String, principal: PrincipalId) {
```

```kotlin
private class Refusal(val status: HttpStatusCode, val error: ApiError)
```
→
```kotlin
internal class Refusal(val status: HttpStatusCode, val error: ApiError)
```

```kotlin
private suspend fun ApplicationCall.respondRefusal(refusal: Refusal) {
```
→
```kotlin
internal suspend fun ApplicationCall.respondRefusal(refusal: Refusal) {
```

```kotlin
private suspend fun QuotaService.refusalFor(
```
→
```kotlin
internal suspend fun QuotaService.refusalFor(
```

```kotlin
private suspend fun QuotaService.recordSpend(principal: PrincipalId, spentMicros: Long, allowance: Allowance? = null) {
```
→
```kotlin
internal suspend fun QuotaService.recordSpend(principal: PrincipalId, spentMicros: Long, allowance: Allowance? = null) {
```

- [ ] **Step 2: Confirm `SessionRoutesTest` still passes (a pure visibility change must not break anything)**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.SessionRoutesTest"`
Expected: PASS, unchanged.

- [ ] **Step 3: Commit the visibility change on its own**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt
git commit -m "refactor(api): widen the explain route's gate helpers to internal"
```

- [ ] **Step 4: Add the `assess` dependency to `backend/api/build.gradle.kts`**

Open the file, find the `dependencies { }` block's `implementation(project(":backend:..."))` lines,
and add one more:

```kotlin
    implementation(project(":backend:assess"))
```

- [ ] **Step 5: Write the failing tests for the generation route**

Add a quiz stack to `TestFixtures.kt`, next to `sessionApp`:

```kotlin
    /**
     * A quiz stack sharing the same Mongo database and the same [FakeLlmClient] a caller's
     * [SessionStack] already built, so a test can create a session, read its explanations, and then
     * ask for a quiz over them without a second store disagreeing with the first about what exists.
     */
    fun quizApp(stack: SessionStack): QuizStack {
        val repository = QuizRepository(stack.database)
        runBlocking { repository.ensureIndexes() }
        return QuizStack(
            repository = repository,
            service = QuizService(repository, stack.llm, QuizValidator()),
        )
    }

    class QuizStack(val repository: QuizRepository, val service: QuizService)
```

Add the needed imports to `TestFixtures.kt`: `com.mytetz.assess.QuizRepository`,
`com.mytetz.assess.QuizService`, `com.mytetz.assess.QuizValidator`.

Create `QuizRoutesTest.kt`:

```kotlin
package com.mytetz.api

import com.mytetz.account.AccountRepository
import com.mytetz.account.AccountService
import com.mytetz.account.MagicLinkService
import com.mytetz.account.MailSender
import com.mytetz.assess.QuizKind
import com.mytetz.billing.BillingConfig
import com.mytetz.billing.BillingRepository
import com.mytetz.billing.BillingService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuizRoutesTest {

    private class CapturingMailSender : MailSender {
        private val links = mutableMapOf<String, String>()
        override suspend fun sendMagicLink(email: String, link: String) { links[email] = link }
        fun tokenFor(email: String): String = links.getValue(email).substringAfterLast("/")
    }

    private class Scope(val client: HttpClient, val mailSender: CapturingMailSender) {
        suspend fun signIn(http: HttpClient = client): String {
            val email = "learner-${UUID.randomUUID()}@example.com"
            http.post("/api/auth/magic-link") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email"}""")
            }
            http.get("/api/auth/magic-link/${mailSender.tokenFor(email)}")
            return email
        }
    }

    private fun app(trialGenerations: Int = 40, block: suspend Scope.() -> Unit) = testApplication {
        val stack = TestFixtures.sessionApp()
        val quiz = TestFixtures.quizApp(stack)
        val accountRepository = AccountRepository(stack.database)
        val account = AccountService(accountRepository)
        val mailSender = CapturingMailSender()
        val magicLink = MagicLinkService(accountRepository, mailSender, baseUrl = "http://localhost")
        val billingRepository = BillingRepository(stack.database)
        val billing = BillingService(billingRepository, config = BillingConfig(trialGenerations = trialGenerations))

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            installErrorMapping()
            routing {
                sessionRoutes(
                    sessions = { stack.sessions }, quota = stack.quota, billing = billing, account = account,
                    cookies = TestFixtures.cookieConfig, clientAddresses = ClientAddressConfig(trustedHeader = null),
                )
                quizRoutes(
                    sessions = { stack.sessions }, quizzes = { quiz.service }, quota = stack.quota, billing = billing,
                    account = account, cookies = TestFixtures.cookieConfig,
                    clientAddresses = ClientAddressConfig(trustedHeader = null),
                )
                authRoutes(
                    account = account, sessions = { stack.sessions }, magicLink = { magicLink },
                    google = { error("google sign-in is not exercised by QuizRoutesTest") },
                    cookies = TestFixtures.cookieConfig, quotaRepository = stack.quotaRepository, billing = billing,
                    clientAddresses = ClientAddressConfig(trustedHeader = null),
                )
            }
        }

        val http = createClient { install(HttpCookies) }
        runBlocking { Scope(http, mailSender).block() }
    }

    /** Creates a signed-in session with one EXPLAIN child node beyond the seed, so TEST_ME on the
     * root has real material and EXAM has two nodes to scope over. */
    private suspend fun Scope.newSessionWithOneChild(): String {
        signIn()
        val created = client.post("/api/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"topicSlug":"quantum-physics"}""")
        }
        val sessionId = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("sessionId").jsonPrimitive.content
        val rootNodeId = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("rootNodeId").jsonPrimitive.content
        client.post("/api/sessions/$sessionId/explain") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"parentNodeId":"$rootNodeId","span":{"text":"fundamental physical theory","start":0,"end":0},"verb":"EXPLAIN"}"""
            )
        }
        return sessionId
    }

    @Test
    fun `an anonymous caller is refused before anything is generated`() = app {
        val response = client.post("/api/sessions/does-not-matter/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"n1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue("SIGN_IN_REQUIRED" in response.bodyAsText())
    }

    @Test
    fun `a template returned to the browser carries no correctIndex`() = app {
        val sessionId = newSessionWithOneChild()
        val session = client.get("/api/sessions/$sessionId")
        val rootNodeId = Json.parseToJsonElement(session.bodyAsText()).jsonObject.getValue("rootNodeId").jsonPrimitive.content

        val response = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"$rootNodeId"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse("correctIndex" in response.bodyAsText(), "the wire shape must never carry the answer")
        assertTrue("attemptId" in response.bodyAsText())
    }

    @Test
    fun `exam scopes over every node in the session`() = app {
        val sessionId = newSessionWithOneChild()

        val response = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"EXAM"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `an exhausted allowance refuses quiz generation the same way it refuses explain`() = app(trialGenerations = 0) {
        val sessionId = newSessionWithOneChild()
        val session = client.get("/api/sessions/$sessionId")
        val rootNodeId = Json.parseToJsonElement(session.bodyAsText()).jsonObject.getValue("rootNodeId").jsonPrimitive.content

        val response = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"$rootNodeId"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue("TRIAL_EXHAUSTED" in response.bodyAsText())
    }
}
```

`newSessionWithOneChild`'s `explain` call intentionally sends `start:0,end:0`, which will fail span
validation against the real seed body. Before writing this file for real, read the seed body
`TestFixtures.SEED_BODY` gives for `quantum-physics` and compute the true `start`/`end` offsets of
the substring `"fundamental physical theory"` within it, and hard-code those two integers instead of
`0,0` — do not leave a request that 400s in the test file. (`SessionRoutesTest.kt` already builds
sessions and calls explain successfully; copy its exact offsets/approach for this one call rather
than deriving new ones by hand, if it is faster.)

- [ ] **Step 6: Run the tests to confirm they fail (route does not exist yet)**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.QuizRoutesTest"`

- [ ] **Step 7: Add `QUIZ_UNAVAILABLE` to `ErrorMapping.kt`**

Add the import `com.mytetz.assess.QuizUnavailableException` and, inside `installErrorMapping()`'s
`install(StatusPages) { }` block, in the "upstream, and our own data" section next to
`GenerationFailedException`'s arm:

```kotlin
        exception<QuizUnavailableException> { call, cause ->
            log.warn("quiz generation produced nothing usable", cause)
            call.respond(
                HttpStatusCode.BadGateway,
                ApiError("QUIZ_UNAVAILABLE", "no quiz could be generated for this material; try again"),
            )
        }
```

- [ ] **Step 8: Write `QuizRoutes.kt`**

```kotlin
package com.mytetz.api

import com.mytetz.account.AccountService
import com.mytetz.assess.AnsweredQuestion
import com.mytetz.assess.QuizKind
import com.mytetz.assess.QuizService
import com.mytetz.assess.QuizSource
import com.mytetz.assess.QuizTemplate
import com.mytetz.billing.BillingService
import com.mytetz.billing.EntitlementDecision
import com.mytetz.quota.PrincipalId
import com.mytetz.quota.QuotaService
import com.mytetz.session.SessionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class QuizRequest(val kind: QuizKind, val nodeId: String? = null)

/** The wire shape of one question — never [com.mytetz.assess.QuizQuestion.correctIndex]. */
@Serializable
data class QuizQuestionView(val questionId: String, val stem: String, val options: List<String>)

@Serializable
data class QuizTemplateView(val attemptId: String, val kind: QuizKind, val questions: List<QuizQuestionView>)

/**
 * `POST /api/sessions/{id}/quizzes` and `POST /api/sessions/{id}/quizzes/{attemptId}/answers`.
 *
 * The first is the only one of the two that can spend money, and it gates exactly the way
 * `POST /api/sessions/{id}/explain` does — sign-in, entitlement, quota, breaker — reusing that
 * route's own `refusalFor`/`recordSpend` helpers rather than a second copy of the same five checks.
 * See section 9.3 of the monetization design: "Only the endpoints that can reach the model: explain
 * and quizzes." The answers route reaches no model and gates on sign-in and ownership only.
 */
fun Route.quizRoutes(
    sessions: () -> SessionService,
    quizzes: () -> QuizService,
    quota: QuotaService,
    billing: BillingService,
    account: AccountService,
    cookies: PrincipalCookieConfig,
    clientAddresses: ClientAddressConfig = ClientAddressConfig(),
) {
    post("/api/sessions/{id}/quizzes") {
        val signedInUser = Principals.readSessionId(call, cookies)?.let { account.resolveSession(it) }
        if (signedInUser == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("SIGN_IN_REQUIRED", "sign in to request a quiz"))
            return@post
        }
        val principal = PrincipalId.user(signedInUser.id)

        val entitlement = billing.entitlementFor(signedInUser.id)
        if (entitlement !is EntitlementDecision.Allowed) {
            call.respond(
                HttpStatusCode.Forbidden,
                ApiError("SUBSCRIPTION_REQUIRED", "a subscription is required to request a quiz"),
            )
            return@post
        }

        val sessionService = sessions()
        val sessionId = call.parameters["id"].orEmpty()
        val request = call.receive<QuizRequest>()

        sessionService.requireOwnedBy(sessionId, principal)

        val (session, bodies) = sessionService.load(sessionId) ?: throw SessionNotFoundException(sessionId)

        val (scopeKeys, sources) = when (request.kind) {
            QuizKind.TEST_ME -> {
                val nodeId = request.nodeId
                    ?: throw IllegalArgumentException("TEST_ME requires nodeId")
                val node = session.nodes.firstOrNull { it.nodeId == nodeId }
                    ?: throw IllegalArgumentException("no such node: $nodeId")
                val body = bodies.getValue(node.explanationKey).body
                listOf(node.explanationKey) to listOf(QuizSource(node.explanationKey, body))
            }
            QuizKind.EXAM -> {
                val keys = session.nodes.map { it.explanationKey }
                keys to keys.distinct().map { key -> QuizSource(key, bodies.getValue(key).body) }
            }
        }

        val quizService = quizzes()
        val cached = quizService.keyFor(scopeKeys, request.kind)
            .let { key -> /* a cheap existence check; no model call */ key }
        // Re-derive cached status without leaking the private lookup: getOrGenerate itself already
        // checks the store first and calls onSpend only on an actual generation, so the gate below
        // asks quota only when a generation is actually possible.
        val alreadyCached = quizService.let { s -> s.keyFor(scopeKeys, request.kind) }.let { key ->
            false // placeholder replaced below — see note
        }

        val template: QuizTemplate = try {
            var generated = false
            val result = quizService.getOrGenerate(scopeKeys, request.kind, sources) { costMicros ->
                generated = true
                quota.recordSpend(principal, costMicros, entitlement.allowance)
            }
            if (generated) {
                // Cost was already recorded inside onSpend, above. The gate itself runs BEFORE this
                // call — see the corrected version of this route below.
            }
            result
        } catch (e: com.mytetz.assess.QuizUnavailableException) {
            throw e
        }

        val attempt = quizService.startAttempt(principal.value, sessionId, template)
        // (attempt persistence and the gate call are completed in Step 9 below — see the note.)

        call.respond(
            QuizTemplateView(
                attemptId = attempt.id,
                kind = template.kind,
                questions = template.questions.map { QuizQuestionView(it.questionId, it.stem, it.options) },
            )
        )
    }
}
```

**Stop. The draft above is deliberately incomplete** — it shows the shape but the gate has to run
*before* `getOrGenerate`, not be inferred from whether generation happened, and the attempt has to
be persisted. Replace the body of the `post("/api/sessions/{id}/quizzes")` block with this corrected
version, which checks the cache once (cheap, no model call), gates only when that check misses,
persists the attempt, and matches the explain route's own ordering exactly:

```kotlin
    post("/api/sessions/{id}/quizzes") {
        val signedInUser = Principals.readSessionId(call, cookies)?.let { account.resolveSession(it) }
        if (signedInUser == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("SIGN_IN_REQUIRED", "sign in to request a quiz"))
            return@post
        }
        val principal = PrincipalId.user(signedInUser.id)

        val entitlement = billing.entitlementFor(signedInUser.id)
        if (entitlement !is EntitlementDecision.Allowed) {
            call.respond(
                HttpStatusCode.Forbidden,
                ApiError("SUBSCRIPTION_REQUIRED", "a subscription is required to request a quiz"),
            )
            return@post
        }

        val sessionService = sessions()
        val sessionId = call.parameters["id"].orEmpty()
        val request = call.receive<QuizRequest>()

        sessionService.requireOwnedBy(sessionId, principal)
        val (session, bodies) = sessionService.load(sessionId) ?: throw SessionNotFoundException(sessionId)

        val (scopeKeys, sources) = scopeFor(request, session, bodies)

        val quizService = quizzes()
        val key = quizService.keyFor(scopeKeys, request.kind)
        var cached = quizService.isCached(key)

        if (!cached) {
            quota.alignWindow(principal, entitlement.allowance)
            val refusal = quota.refusalFor(principal, entitlement.allowance, entitlement.status) {
                cached = quizService.isCached(key)
                cached
            }
            if (refusal != null) {
                call.respondRefusal(refusal)
                return@post
            }
        }

        val template = quizService.getOrGenerate(scopeKeys, request.kind, sources) { costMicros ->
            quota.recordSpend(principal, costMicros, entitlement.allowance)
        }

        val attempt = quizService.startAttempt(principal.value, sessionId, template)
        quizService.saveAttempt(attempt)

        call.respond(
            HttpStatusCode.OK,
            QuizTemplateView(
                attemptId = attempt.id,
                kind = template.kind,
                questions = template.questions.map { QuizQuestionView(it.questionId, it.stem, it.options) },
            ),
        )
    }
```

This version calls two small `QuizService` methods that Task 7 did not add:
`isCached(key: String): Boolean` and `saveAttempt(attempt: QuizAttempt)`. Go back to
`QuizService.kt` now and add them (this is expected — Task 7 built the generation and scoring core;
the route layer's own needs, discovered here, are two one-line wrappers over `QuizRepository`):

```kotlin
    suspend fun isCached(key: String): Boolean = repository.findByKey(key) != null

    suspend fun saveAttempt(attempt: QuizAttempt) = repository.insertAttempt(attempt)
```

Add a private helper at the bottom of `QuizRoutes.kt` (outside the `quizRoutes` function, same file):

```kotlin
private fun scopeFor(
    request: QuizRequest,
    session: com.mytetz.session.LearningSession,
    bodies: Map<String, com.mytetz.graph.Explanation>,
): Pair<List<String>, List<QuizSource>> = when (request.kind) {
    QuizKind.TEST_ME -> {
        val nodeId = request.nodeId ?: throw IllegalArgumentException("TEST_ME requires nodeId")
        val node = session.nodes.firstOrNull { it.nodeId == nodeId }
            ?: throw IllegalArgumentException("no such node: $nodeId")
        val body = bodies.getValue(node.explanationKey).body
        listOf(node.explanationKey) to listOf(QuizSource(node.explanationKey, body))
    }
    QuizKind.EXAM -> {
        val keys = session.nodes.map { it.explanationKey }
        val sources = keys.distinct().map { key -> QuizSource(key, bodies.getValue(key).body) }
        keys to sources
    }
}
```

- [ ] **Step 9: Run the tests and iterate to green**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.QuizRoutesTest"`

Fix any remaining compile errors (an unresolved import, a mismatched parameter name against what
Task 7 actually named something) by reading the actual signatures in the files Task 6/7 produced —
not by guessing a second time.

- [ ] **Step 10: Run the whole `api` module's test suite to confirm no regression**

Run: `./gradlew :backend:api:test`
Expected: PASS, including every pre-existing suite (`SessionRoutesTest`, `AuthRoutesTest`,
`BillingRoutesTest`, `CatalogRoutesTest`, `ErrorMappingTest`, `ComponentsTest`, ...).

- [ ] **Step 11: Commit**

```bash
git add backend/api/build.gradle.kts \
        backend/api/src/main/kotlin/com/mytetz/api/QuizRoutes.kt \
        backend/api/src/main/kotlin/com/mytetz/api/ErrorMapping.kt \
        backend/api/src/test/kotlin/com/mytetz/api/TestFixtures.kt \
        backend/api/src/test/kotlin/com/mytetz/api/QuizRoutesTest.kt \
        backend/assess/src/main/kotlin/com/mytetz/assess/QuizService.kt
git commit -m "feat(api): add the quiz-generation route, gated like explain"
```

---

## Task 9: The answers route

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/QuizRoutes.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/ErrorMapping.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/QuizRoutesTest.kt`

**Interfaces:**
- Consumes: everything Task 8 produced, plus `QuizService.score` (Task 7).
- Produces: `AnsweredQuestionPayload`, `QuizAnswersRequest`, `QuizResultView`, `QuizAttemptNotFoundException`.

- [ ] **Step 1: Write the failing test — the full take-a-quiz-and-see-the-score path**

Add to `QuizRoutesTest.kt`:

```kotlin
    @Test
    fun `taking a quiz end to end returns the score and the rationales`() = app {
        val sessionId = newSessionWithOneChild()
        val session = client.get("/api/sessions/$sessionId")
        val rootNodeId = Json.parseToJsonElement(session.bodyAsText()).jsonObject.getValue("rootNodeId").jsonPrimitive.content

        val started = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"$rootNodeId"}""")
        }
        val startedBody = Json.parseToJsonElement(started.bodyAsText()).jsonObject
        val attemptId = startedBody.getValue("attemptId").jsonPrimitive.content
        val questionId = startedBody.getValue("questions").let { it.toString() }
            .substringAfter("\"questionId\":\"").substringBefore("\"")

        val answered = client.post("/api/sessions/$sessionId/quizzes/$attemptId/answers") {
            contentType(ContentType.Application.Json)
            setBody("""{"answers":[{"questionId":"$questionId","chosenIndex":0}]}""")
        }

        assertEquals(HttpStatusCode.OK, answered.status)
        val result = Json.parseToJsonElement(answered.bodyAsText()).jsonObject
        assertTrue(result.containsKey("score"))
        assertTrue(result.containsKey("total"))
        assertTrue(result.containsKey("rationales"))
        assertTrue(result.containsKey("correctIndices"))
    }

    @Test
    fun `answering someone else's attempt is refused as not found`() = app {
        val sessionId = newSessionWithOneChild()
        val session = client.get("/api/sessions/$sessionId")
        val rootNodeId = Json.parseToJsonElement(session.bodyAsText()).jsonObject.getValue("rootNodeId").jsonPrimitive.content
        val started = client.post("/api/sessions/$sessionId/quizzes") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"TEST_ME","nodeId":"$rootNodeId"}""")
        }
        val attemptId = Json.parseToJsonElement(started.bodyAsText()).jsonObject.getValue("attemptId").jsonPrimitive.content

        val otherLearner = createClient { install(HttpCookies) }
        Scope(otherLearner, mailSender).signIn()
        val response = otherLearner.post("/api/sessions/$sessionId/quizzes/$attemptId/answers") {
            contentType(ContentType.Application.Json)
            setBody("""{"answers":[]}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
```

The second test needs `mailSender` and `createClient` reachable inside the `app { }` lambda; if the
harness's `Scope` class does not already expose them, add a `val mailSender` field to `Scope` (set
from the `app` function's own local) and reuse the pattern `SessionRoutesTest.anotherLearner()` uses
for a second, independently signed-in client on the same `testApplication`.

- [ ] **Step 2: Run to confirm the second endpoint is missing (404 from the `/api/{...}` catch-all, or unresolved route)**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.QuizRoutesTest"`

- [ ] **Step 3: Add `QuizAttemptNotFoundException` mapping to `ErrorMapping.kt`**

```kotlin
        exception<QuizAttemptNotFoundException> { call, cause ->
            log.info("quiz attempt {} was requested and does not exist or is not the caller's", cause.attemptId)
            call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "no such quiz attempt"))
        }
```

Add the matching import: `com.mytetz.assess.QuizAttemptNotFoundException` — and add the class itself
to `Quiz.kt` in `:backend:assess` (Task 3's file), next to nothing else needed there:

```kotlin
/** No attempt with this id, or one that exists but belongs to a different principal — one answer
 * for both, same reasoning as `com.mytetz.session.SessionNotFoundException`: a guessed id must not
 * become an oracle for which ids are real. */
class QuizAttemptNotFoundException(val attemptId: String) : Exception("no such quiz attempt: $attemptId")
```

- [ ] **Step 4: Add the second route to `QuizRoutes.kt`**

Add these types near `QuizTemplateView`:

```kotlin
@Serializable
data class AnsweredQuestionPayload(val questionId: String, val chosenIndex: Int)

@Serializable
data class QuizAnswersRequest(val answers: List<AnsweredQuestionPayload>)

@Serializable
data class QuizResultView(
    val score: Int,
    val total: Int,
    val correctIndices: Map<String, Int>,
    val rationales: Map<String, String>,
)
```

Add the route inside `Route.quizRoutes { }`, after the first `post(...)` block:

```kotlin
    post("/api/sessions/{id}/quizzes/{attemptId}/answers") {
        val signedInUser = Principals.readSessionId(call, cookies)?.let { account.resolveSession(it) }
        if (signedInUser == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("SIGN_IN_REQUIRED", "sign in to answer a quiz"))
            return@post
        }
        val principal = PrincipalId.user(signedInUser.id)
        val sessionId = call.parameters["id"].orEmpty()
        val attemptId = call.parameters["attemptId"].orEmpty()

        sessions().requireOwnedBy(sessionId, principal)

        val quizService = quizzes()
        val attempt = quizService.findAttempt(attemptId)
        if (attempt == null || attempt.sessionId != sessionId || attempt.principalId != principal.value) {
            throw com.mytetz.assess.QuizAttemptNotFoundException(attemptId)
        }

        val template = quizService.findTemplate(attempt.templateId)
            ?: throw com.mytetz.assess.QuizAttemptNotFoundException(attemptId)

        val request = call.receive<QuizAnswersRequest>()
        val scored = quizService.score(
            attempt,
            template,
            answers = request.answers.map { AnsweredQuestion(it.questionId, it.chosenIndex) },
        )
        quizService.saveAttempt(scored)

        call.respond(
            QuizResultView(
                score = scored.score ?: 0,
                total = scored.total,
                correctIndices = template.questions.associate { it.questionId to it.correctIndex },
                rationales = template.questions.associate { it.questionId to it.rationale },
            )
        )
    }
```

`quizService.saveAttempt` on an already-inserted `_id` needs an upsert, not a second insert — go
back to `QuizRepository.kt` and change `insertAttempt` to `replaceOne(..., upsert = true)`, or add a
second method. The simplest correct fix: change `QuizService.saveAttempt` to call
`repository.upsertAttempt(attempt)`, and change `QuizRepository.kt`:

```kotlin
    /** Inserts a new attempt or overwrites an existing one by `_id`. One method for both, because
     * `QuizRoutes.kt` calls this both to create an attempt and to record its score. */
    suspend fun upsertAttempt(attempt: QuizAttempt) {
        attempts.replaceOne(
            com.mongodb.client.model.Filters.eq("_id", attempt.id),
            attempt,
            com.mongodb.client.model.ReplaceOptions().upsert(true),
        )
    }
```

Remove `insertAttempt` if nothing else calls it (check with a project-wide search before deleting),
and update `QuizService.saveAttempt` and the `QuizRepositoryTest` from Task 6 to call
`upsertAttempt` throughout — re-run Task 6's test file after this rename:

Run: `./gradlew :backend:assess:test --tests "com.mytetz.assess.QuizRepositoryTest"`

Add the two missing `QuizService` reads used above — `findAttempt` and `findTemplate` — to
`QuizService.kt`:

```kotlin
    suspend fun findAttempt(id: String): QuizAttempt? = repository.findAttempt(id)

    suspend fun findTemplate(key: String): QuizTemplate? = repository.findByKey(key)
```

- [ ] **Step 5: Run the tests and iterate to green**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.QuizRoutesTest"`
Run: `./gradlew :backend:assess:test`
Expected: PASS on both.

- [ ] **Step 6: Run the whole backend test suite**

Run: `./gradlew build`
Expected: PASS. Fix any regression before moving on — do not proceed to the frontend with a red
backend build.

- [ ] **Step 7: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/QuizRoutes.kt \
        backend/api/src/main/kotlin/com/mytetz/api/ErrorMapping.kt \
        backend/api/src/test/kotlin/com/mytetz/api/QuizRoutesTest.kt \
        backend/assess/src/main/kotlin/com/mytetz/assess/Quiz.kt \
        backend/assess/src/main/kotlin/com/mytetz/assess/QuizRepository.kt \
        backend/assess/src/main/kotlin/com/mytetz/assess/QuizService.kt \
        backend/assess/src/test/kotlin/com/mytetz/assess/QuizRepositoryTest.kt
git commit -m "feat(api): add the quiz-answers route and score the attempt"
```

---

## Task 10: Wire `Components.kt` and `Application.kt`

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Components.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Application.kt`
- Test: `backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt` (extend the existing
  "one index per repository" style test if one exists; otherwise add a new small test asserting
  `components.quizRepository` and `components.quizzes` exist and `bootstrap()` does not throw)

**Interfaces:**
- Consumes: `QuizRepository`, `QuizValidator`, `QuizService` (`:backend:assess`).
- Produces: `Components.quizRepository`, `Components.quizzes` (both following the exact pattern
  `explanations`/`graph`/`sessions` already use).

- [ ] **Step 1: Read `ComponentsTest.kt` to find its "one index per repository" assertion, if any**

Run: `grep -n "ensureIndexes\|one index per repository" backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt`

If such a test exists, note its exact shape so Step 4 below extends it consistently rather than
adding a second, differently-shaped test for the same property.

- [ ] **Step 2: Add the wiring to `Components.kt`**

Add near `explanations`/`private val explanations = ExplanationRepository(mongo.database)`:

```kotlin
    private val quizRepository = com.mytetz.assess.QuizRepository(mongo.database)
```

Add near `private val graph by lazy { ... }`:

```kotlin
    val quizzes: com.mytetz.assess.QuizService by lazy {
        com.mytetz.assess.QuizService(quizRepository, llm, com.mytetz.assess.QuizValidator())
    }
```

(Use real top-of-file imports rather than fully-qualified names in the actual edit — add
`import com.mytetz.assess.QuizRepository`, `import com.mytetz.assess.QuizService`,
`import com.mytetz.assess.QuizValidator` to `Components.kt`'s import block instead of inlining the
package path, which is written out above only so this step is unambiguous in the plan text.)

Add `quizRepository.ensureIndexes()` to `bootstrap()`, in the same list as the other repositories:

```kotlin
    open suspend fun bootstrap() {
        topics.ensureIndexes()
        topicRequests.ensureIndexes()
        explanations.ensureIndexes()
        sessionRepository.ensureIndexes()
        quizRepository.ensureIndexes()
        quotaRepository.ensureIndexes()
        billingRepository.ensureIndexes()
        accountRepository.ensureIndexes()
        catalog.seedFromResource()
        migrate()
        reconcile()
    }
```

- [ ] **Step 3: Wire the route into `Application.kt`**

Add the import `import com.mytetz.api.quizRoutes` is unnecessary (same package); just add the call
inside `routing { }`, right after `sessionRoutes(...)`:

```kotlin
        quizRoutes(
            sessions = { components.sessions },
            quizzes = { components.quizzes },
            quota = components.quota,
            billing = components.billing,
            account = components.account,
            cookies = components.cookies,
            clientAddresses = components.clientAddresses,
        )
```

- [ ] **Step 4: Add or extend the `ComponentsTest` assertion**

If Step 1 found an existing "one index per repository" test that enumerates repositories, add
`quizRepository` to its list. Otherwise add:

```kotlin
    @Test
    fun `bootstrap ensures quiz indexes without throwing`() = runBlocking {
        val components = Components(mongo = TestMongo.fresh() /* use whatever this file's existing
            tests use to build a test Mongo — copy that exact helper, do not invent a new one */)
        components.bootstrap()
        // No assertion beyond "did not throw": index creation is idempotent and this mirrors how
        // this file already tests the other repositories, per its own class KDoc.
    }
```

Read `ComponentsTest.kt` in full before writing this step for real, and match its existing
`Components(...)` construction pattern (test Mongo, fake mail sender factory, etc.) exactly — do not
guess the constructor call; copy an existing test in the same file and adapt only what quizzes need.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.ComponentsTest"`
Expected: PASS.

- [ ] **Step 6: Run the full backend build**

Run: `./gradlew build`
Expected: PASS, every module.

- [ ] **Step 7: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/Components.kt \
        backend/api/src/main/kotlin/com/mytetz/api/Application.kt \
        backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt
git commit -m "feat(api): wire the quiz service and route into the running app"
```

---

## Task 11: Frontend models, API client, and `QuizPanelComponent`

**Files:**
- Modify: `frontend/src/app/core/models.ts`
- Modify: `frontend/src/app/core/api.service.ts`
- Modify: `frontend/src/app/core/api.service.spec.ts`
- Create: `frontend/src/app/assess/quiz-panel.component.ts`
- Create: `frontend/src/app/assess/quiz-panel.component.spec.ts`

**Interfaces:**
- Produces: `QuizKind`, `QuizQuestionView`, `QuizTemplateView`, `QuizAnswerPayload`, `QuizResultView` (`models.ts`); `ApiService.startQuiz(sessionId, kind, nodeId?)`, `ApiService.submitQuizAnswers(sessionId, attemptId, answers)`; `QuizPanelComponent` with inputs `sessionId: string`, `kind: QuizKind`, `nodeId: string | null`, `sourceNodeIds: Record<string, string>` (a map from an explanation key to the node id that shows it, so the results screen can offer "reopen this step"), outputs `close: void`, `openNode: string`.

- [ ] **Step 1: Add the types to `models.ts`**

Append:

```typescript
export type QuizKind = 'TEST_ME' | 'EXAM';

/** The wire shape of `POST /api/sessions/{id}/quizzes`'s response — never carries the answer. */
export interface QuizQuestionView {
  questionId: string;
  stem: string;
  options: string[];
}

export interface QuizTemplateView {
  attemptId: string;
  kind: QuizKind;
  questions: QuizQuestionView[];
}

export interface QuizAnswerPayload {
  questionId: string;
  chosenIndex: number;
}

/** The wire shape of `POST /api/sessions/{id}/quizzes/{attemptId}/answers`'s response. */
export interface QuizResultView {
  score: number;
  total: number;
  correctIndices: Record<string, number>;
  rationales: Record<string, string>;
}
```

- [ ] **Step 2: Write the failing `api.service.spec.ts` tests**

Read the existing `api.service.spec.ts` first to copy its exact `HttpTestingController` setup
pattern, then add tests in the same style:

```typescript
  it('startQuiz posts kind and nodeId', async () => {
    const promise = service.startQuiz('s1', 'TEST_ME', 'n1');
    const req = httpMock.expectOne('/api/sessions/s1/quizzes');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ kind: 'TEST_ME', nodeId: 'n1' });
    req.flush({ attemptId: 'a1', kind: 'TEST_ME', questions: [] });
    await expectAsync(promise).toBeResolvedTo({ attemptId: 'a1', kind: 'TEST_ME', questions: [] });
  });

  it('startQuiz omits nodeId for an exam', async () => {
    const promise = service.startQuiz('s1', 'EXAM');
    const req = httpMock.expectOne('/api/sessions/s1/quizzes');
    expect(req.request.body).toEqual({ kind: 'EXAM' });
    req.flush({ attemptId: 'a1', kind: 'EXAM', questions: [] });
    await promise;
  });

  it('submitQuizAnswers posts the answer list', async () => {
    const promise = service.submitQuizAnswers('s1', 'a1', [{ questionId: 'q1', chosenIndex: 2 }]);
    const req = httpMock.expectOne('/api/sessions/s1/quizzes/a1/answers');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ answers: [{ questionId: 'q1', chosenIndex: 2 }] });
    req.flush({ score: 1, total: 1, correctIndices: {}, rationales: {} });
    await promise;
  });
```

- [ ] **Step 3: Run to confirm they fail**

Run: `cd frontend && npm test -- --watch=false --include='**/api.service.spec.ts'`

- [ ] **Step 4: Add the methods to `api.service.ts`**

```typescript
  startQuiz(sessionId: string, kind: QuizKind, nodeId?: string): Promise<QuizTemplateView> {
    return firstValueFrom(
      this.http.post<QuizTemplateView>(
        `/api/sessions/${sessionId}/quizzes`,
        nodeId === undefined ? { kind } : { kind, nodeId },
      ),
    );
  }

  submitQuizAnswers(
    sessionId: string,
    attemptId: string,
    answers: QuizAnswerPayload[],
  ): Promise<QuizResultView> {
    return firstValueFrom(
      this.http.post<QuizResultView>(`/api/sessions/${sessionId}/quizzes/${attemptId}/answers`, {
        answers,
      }),
    );
  }
```

Add `QuizAnswerPayload, QuizKind, QuizResultView, QuizTemplateView` to the `import ... from './models'`
line at the top of the file.

- [ ] **Step 5: Run to confirm they pass**

Run: `cd frontend && npm test -- --watch=false --include='**/api.service.spec.ts'`
Expected: PASS.

- [ ] **Step 6: Write `quiz-panel.component.spec.ts`**

Read `focus-card.component.spec.ts` or `session.store.spec.ts` first, to copy this project's exact
`TestBed` / signal-testing conventions, then write:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { QuizPanelComponent } from './quiz-panel.component';
import { ApiService } from '../core/api.service';
import { QuizResultView, QuizTemplateView } from '../core/models';

describe('QuizPanelComponent', () => {
  let fixture: ComponentFixture<QuizPanelComponent>;
  let component: QuizPanelComponent;
  let api: jasmine.SpyObj<ApiService>;

  const template: QuizTemplateView = {
    attemptId: 'a1',
    kind: 'TEST_ME',
    questions: [
      { questionId: 'q1', stem: 'Stem one', options: ['a', 'b', 'c', 'd'] },
      { questionId: 'q2', stem: 'Stem two', options: ['e', 'f', 'g', 'h'] },
    ],
  };
  const result: QuizResultView = {
    score: 1,
    total: 2,
    correctIndices: { q1: 0, q2: 1 },
    rationales: { q1: 'why one', q2: 'why two' },
  };

  beforeEach(() => {
    api = jasmine.createSpyObj<ApiService>('ApiService', ['startQuiz', 'submitQuizAnswers']);
    api.startQuiz.and.resolveTo(template);
    api.submitQuizAnswers.and.resolveTo(result);

    TestBed.configureTestingModule({
      imports: [QuizPanelComponent],
      providers: [{ provide: ApiService, useValue: api }],
    });

    fixture = TestBed.createComponent(QuizPanelComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('sessionId', 's1');
    fixture.componentRef.setInput('kind', 'TEST_ME');
    fixture.componentRef.setInput('nodeId', 'n1');
    fixture.detectChanges();
  });

  it('loads a quiz on init and shows the first question', async () => {
    await fixture.whenStable();
    expect(api.startQuiz).toHaveBeenCalledWith('s1', 'TEST_ME', 'n1');
    expect(component.phase()).toBe('question');
    expect(component.currentQuestion()?.questionId).toBe('q1');
  });

  it('moves to the next question only after an option is chosen', async () => {
    await fixture.whenStable();
    expect(component.answered()).toBe(false);
    component.choose(0);
    expect(component.answered()).toBe(true);
    await component.next();
    expect(component.currentIndex()).toBe(1);
  });

  it('submits every answer at once on the last question and shows the result', async () => {
    await fixture.whenStable();
    component.choose(0);
    await component.next();
    component.choose(1);
    await component.next();

    expect(api.submitQuizAnswers).toHaveBeenCalledWith('s1', 'a1', [
      { questionId: 'q1', chosenIndex: 0 },
      { questionId: 'q2', chosenIndex: 1 },
    ]);
    expect(component.phase()).toBe('result');
    expect(component.result()?.score).toBe(1);
  });

  it('surfaces a failure to start the quiz', async () => {
    api.startQuiz.and.rejectWith(new Error('boom'));
    fixture = TestBed.createComponent(QuizPanelComponent);
    fixture.componentRef.setInput('sessionId', 's1');
    fixture.componentRef.setInput('kind', 'TEST_ME');
    fixture.componentRef.setInput('nodeId', 'n1');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.error()).not.toBeNull();
  });
});
```

- [ ] **Step 7: Run to confirm it fails to compile**

Run: `cd frontend && npm test -- --watch=false --include='**/quiz-panel.component.spec.ts'`

- [ ] **Step 8: Write `quiz-panel.component.ts`**

```typescript
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { ApiService } from '../core/api.service';
import { QuizAnswerPayload, QuizKind, QuizQuestionView, QuizResultView } from '../core/models';

type QuizPhase = 'loading' | 'question' | 'result';

/**
 * Test Me and Exam share one flow: load a template, answer every question, submit once, show the
 * result. The design mockup at `docs/mytetz-design-reference.html` section 5a shows a question
 * judged the instant it is answered, but `POST .../answers` scores a whole `answers[]` array in one
 * call and has no per-question endpoint — so this component collects every answer first and reveals
 * correctness only on the results screen. See this plan's header note.
 */
@Component({
  selector: 'app-quiz-panel',
  template: `
    <div class="mt-card mt-card--raised quiz-panel" role="dialog" aria-modal="true" [attr.aria-label]="title()">
      @if (error(); as message) {
        <p class="quiz-panel__error" role="alert">{{ message }}</p>
        <button type="button" class="mt-pill mt-pill--ghost" (click)="close.emit()">Close</button>
      } @else if (phase() === 'loading') {
        <p role="status">Preparing your questions…</p>
      } @else if (phase() === 'question') {
        <span class="mt-eyebrow">{{ title() }} · question {{ currentIndex() + 1 }} of {{ questions().length }}</span>
        <p class="quiz-panel__stem">{{ currentQuestion()?.stem }}</p>
        <div class="quiz-panel__options">
          @for (option of currentQuestion()?.options ?? []; track $index) {
            <button
              type="button"
              class="mt-pill quiz-panel__option"
              [class.quiz-panel__option--chosen]="chosenIndex() === $index"
              (click)="choose($index)"
            >
              {{ option }}
            </button>
          }
        </div>
        <div class="quiz-panel__actions">
          <button type="button" class="mt-pill mt-pill--ghost" (click)="close.emit()">Leave</button>
          <button
            type="button"
            class="mt-pill mt-pill--coral"
            [disabled]="!answered()"
            (click)="next()"
          >
            {{ isLastQuestion() ? 'See results' : 'Next question' }}
          </button>
        </div>
      } @else if (phase() === 'result') {
        <h2 class="quiz-panel__score">{{ result()?.score }} / {{ result()?.total }}</h2>
        <ul class="quiz-panel__review">
          @for (question of questions(); track question.questionId) {
            <li>
              <p class="quiz-panel__review-stem">{{ question.stem }}</p>
              <p>
                @if (isCorrect(question.questionId)) {
                  Correct.
                } @else {
                  Correct answer: {{ correctOption(question) }}
                }
              </p>
              <p class="quiz-panel__rationale">{{ result()?.rationales?.[question.questionId] }}</p>
              @if (!isCorrect(question.questionId) && sourceNodeIds()[question.questionId]; as nodeId) {
                <button type="button" class="mt-pill mt-pill--ghost" (click)="openNode.emit(nodeId)">
                  Reopen this step
                </button>
              }
            </li>
          }
        </ul>
        <button type="button" class="mt-pill mt-pill--ghost" (click)="close.emit()">Close</button>
      }
    </div>
  `,
  styles: [
    `
      .quiz-panel {
        display: flex;
        flex-direction: column;
        gap: 16px;
        padding: 24px;
        max-width: 560px;
      }
      .quiz-panel__options {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .quiz-panel__actions {
        display: flex;
        justify-content: space-between;
        gap: 12px;
      }
      .quiz-panel__review {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
    `,
  ],
})
export class QuizPanelComponent {
  private readonly api = inject(ApiService);

  readonly sessionId = input.required<string>();
  readonly kind = input.required<QuizKind>();
  readonly nodeId = input<string | null>(null);
  /** Maps a question's `sourceKey`-derived questionId is NOT available here — see note below — so
   * this maps a QUESTION id to a node id only when the caller can supply one; absent by default. */
  readonly sourceNodeIds = input<Record<string, string>>({});
  readonly close = output<void>();
  readonly openNode = output<string>();

  readonly phase = signal<QuizPhase>('loading');
  readonly error = signal<string | null>(null);
  readonly questions = signal<QuizQuestionView[]>([]);
  readonly currentIndex = signal(0);
  readonly chosenIndex = signal<number | null>(null);
  readonly result = signal<QuizResultView | null>(null);
  private attemptId: string | null = null;
  private readonly answersGiven = new Map<string, number>();

  readonly title = computed(() => (this.kind() === 'EXAM' ? 'Exam' : 'Test me'));
  readonly currentQuestion = computed<QuizQuestionView | null>(
    () => this.questions()[this.currentIndex()] ?? null,
  );
  readonly answered = computed(() => this.chosenIndex() !== null);
  readonly isLastQuestion = computed(() => this.currentIndex() === this.questions().length - 1);

  constructor() {
    effect(() => {
      // Reads sessionId/kind/nodeId so the effect re-runs if the caller ever changes which quiz is
      // requested; in practice a fresh component instance is created per quiz, same as SessionStore
      // per session.
      this.sessionId();
      this.kind();
      this.nodeId();
      void this.start();
    });
  }

  private async start(): Promise<void> {
    this.phase.set('loading');
    this.error.set(null);
    this.answersGiven.clear();
    try {
      const template = await this.api.startQuiz(
        this.sessionId(),
        this.kind(),
        this.nodeId() ?? undefined,
      );
      this.attemptId = template.attemptId;
      this.questions.set(template.questions);
      this.currentIndex.set(0);
      this.chosenIndex.set(null);
      this.phase.set('question');
    } catch {
      this.error.set('This quiz could not be started. Try again in a moment.');
    }
  }

  choose(index: number): void {
    this.chosenIndex.set(index);
    const question = this.currentQuestion();
    if (question) this.answersGiven.set(question.questionId, index);
  }

  async next(): Promise<void> {
    if (!this.answered()) return;
    if (!this.isLastQuestion()) {
      this.currentIndex.update((i) => i + 1);
      this.chosenIndex.set(this.answersGiven.get(this.questions()[this.currentIndex()].questionId) ?? null);
      return;
    }
    await this.submit();
  }

  private async submit(): Promise<void> {
    const attemptId = this.attemptId;
    if (attemptId === null) return;
    const answers: QuizAnswerPayload[] = Array.from(this.answersGiven, ([questionId, chosenIndex]) => ({
      questionId,
      chosenIndex,
    }));
    try {
      const result = await this.api.submitQuizAnswers(this.sessionId(), attemptId, answers);
      this.result.set(result);
      this.phase.set('result');
    } catch {
      this.error.set('Your answers could not be scored. Try again in a moment.');
    }
  }

  isCorrect(questionId: string): boolean {
    return this.answersGiven.get(questionId) === this.result()?.correctIndices[questionId];
  }

  correctOption(question: QuizQuestionView): string {
    const index = this.result()?.correctIndices[question.questionId];
    return index === undefined ? '' : (question.options[index] ?? '');
  }
}
```

- [ ] **Step 9: Run the tests and iterate to green**

Run: `cd frontend && npm test -- --watch=false --include='**/quiz-panel.component.spec.ts'`
Expected: PASS.

- [ ] **Step 10: Run the whole frontend test suite**

Run: `cd frontend && npm test -- --watch=false`
Expected: PASS, no regression.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/app/core/models.ts \
        frontend/src/app/core/api.service.ts \
        frontend/src/app/core/api.service.spec.ts \
        frontend/src/app/assess/quiz-panel.component.ts \
        frontend/src/app/assess/quiz-panel.component.spec.ts
git commit -m "feat(assess): add the quiz panel component and its API client methods"
```

---

## Task 12: Wire "Test me" and "Exam" into the reader

**Files:**
- Modify: `frontend/src/app/reader/focus-card.component.ts`
- Modify: `frontend/src/app/reader/focus-card.component.spec.ts`
- Modify: `frontend/src/app/reader/reader-page.component.ts`
- Modify: `frontend/src/app/reader/reader-page.component.spec.ts`

**Interfaces:**
- Consumes: `QuizPanelComponent` (Task 11).
- Produces: `FocusCardComponent.testMeRequested = output<void>()`; `ReaderPageComponent`'s new
  `quizOpen` signal and `openQuiz(kind: QuizKind)` / `closeQuiz()` methods.

- [ ] **Step 1: Read `focus-card.component.ts` in full and `focus-card.component.spec.ts` in full**

There is no substitute step here — this file was not read during planning beyond its verb-picker
wiring, and a correct edit needs its actual template structure, its existing outputs, and its
existing test setup (`TestBed` providers, how `explainRequested` is asserted today). Do this before
writing any code in this task.

- [ ] **Step 2: Write the failing test for the new output**

Add to `focus-card.component.spec.ts`, following its existing style for asserting an `output<T>()`:

```typescript
  it('emits testMeRequested when the Test me control is pressed', () => {
    const spy = jasmine.createSpy('testMeRequested');
    component.testMeRequested.subscribe(spy);
    // Adjust the selector below to whatever this file's other button-click tests use, e.g.
    // `fixture.debugElement.query(By.css('...'))` — copy that pattern exactly.
    const button = fixture.debugElement.query(By.css('[data-testid="test-me"]'));
    button.nativeElement.click();
    expect(spy).toHaveBeenCalled();
  });
```

- [ ] **Step 3: Run to confirm it fails**

Run: `cd frontend && npm test -- --watch=false --include='**/focus-card.component.spec.ts'`

- [ ] **Step 4: Add the control and the output to `focus-card.component.ts`**

Add near the existing `explainRequested = output<...>()`:

```typescript
  readonly testMeRequested = output<void>();
```

Add a button to the template, near wherever the body and its actions render (place it below the
body and above or beside the verb picker's own trigger — match the surrounding markup's existing
indentation and class conventions rather than inventing new ones):

```html
<button
  type="button"
  class="mt-pill mt-pill--ghost"
  data-testid="test-me"
  (click)="testMeRequested.emit()"
>
  Test me
</button>
```

- [ ] **Step 5: Run to confirm it passes**

Run: `cd frontend && npm test -- --watch=false --include='**/focus-card.component.spec.ts'`

- [ ] **Step 6: Read `reader-page.component.ts` and `reader-page.component.spec.ts`'s current state again (they may have shifted since the excerpt read during planning)**

- [ ] **Step 7: Write the failing test for opening a quiz**

Add to `reader-page.component.spec.ts`, matching its existing `TestBed`/`SessionStore` stubbing
style:

```typescript
  it('opens the quiz panel for Test me on the node in focus', () => {
    component.testMe();
    expect(component.quizKind()).toBe('TEST_ME');
    expect(component.quizNodeId()).toBe(component.store.currentNodeId());
  });

  it('opens the quiz panel for Exam with no node scoping', () => {
    component.exam();
    expect(component.quizKind()).toBe('EXAM');
    expect(component.quizNodeId()).toBeNull();
  });

  it('closes the quiz panel', () => {
    component.testMe();
    component.closeQuiz();
    expect(component.quizKind()).toBeNull();
  });
```

- [ ] **Step 8: Run to confirm it fails to compile**

Run: `cd frontend && npm test -- --watch=false --include='**/reader-page.component.spec.ts'`

- [ ] **Step 9: Wire it up in `reader-page.component.ts`**

Import `QuizPanelComponent` and `QuizKind`, add it to the `imports` array, add state and methods to
the class:

```typescript
  readonly quizKind = signal<QuizKind | null>(null);
  readonly quizNodeId = signal<string | null>(null);

  testMe(): void {
    this.quizNodeId.set(this.store.currentNodeId());
    this.quizKind.set('TEST_ME');
  }

  exam(): void {
    this.quizNodeId.set(null);
    this.quizKind.set('EXAM');
  }

  closeQuiz(): void {
    this.quizKind.set(null);
    this.quizNodeId.set(null);
  }

  reopenFromQuiz(nodeId: string): void {
    this.closeQuiz();
    this.store.goTo(nodeId);
  }
```

Add to the template, wired to `(testMeRequested)` on `<app-focus-card>` and a new "Exam" control
somewhere in the reader chrome (next to the trail rail's own heading is closest to the design
mockup's "Quiz me on these" placement — match whatever heading markup `TrailRailComponent` already
renders, or add the button in `reader-page.component.ts`'s own template just above
`<app-trail-rail>` if `TrailRailComponent` has no natural slot for it):

```html
<app-focus-card
  ...
  (explainRequested)="explain($event)"
  (testMeRequested)="testMe()"
/>
```

```html
<button type="button" class="mt-pill mt-pill--ghost" (click)="exam()">Exam</button>
```

```html
@if (quizKind(); as kind) {
  <app-quiz-panel
    [sessionId]="store.session()!.sessionId"
    [kind]="kind"
    [nodeId]="quizNodeId()"
    (close)="closeQuiz()"
    (openNode)="reopenFromQuiz($event)"
  />
}
```

Guard the "Exam" button so it is not shown while `store.session()` is null (mirror however the
existing template already guards other controls on session presence — e.g. inside the same
`@else if (store.session())` branch the focus card already lives in).

- [ ] **Step 10: Run the tests and iterate to green**

Run: `cd frontend && npm test -- --watch=false --include='**/reader-page.component.spec.ts'`
Expected: PASS.

- [ ] **Step 11: Run the whole frontend suite**

Run: `cd frontend && npm test -- --watch=false`
Expected: PASS, no regression.

- [ ] **Step 12: Manually verify in a running app**

Start the app per this project's own `run` skill or documented dev-server commands, open a topic,
read the seed, and confirm a "Test me" control appears and opens a working quiz panel; confirm an
"Exam" control appears once a session exists. Note any visual issue for a follow-up polish pass —
this plan targets working, tested behaviour, not a pixel-perfect match to the design mockup's
`IOSDevice`-framed screens, which are presentation chrome from the design tool and not real markup.

- [ ] **Step 13: Commit**

```bash
git add frontend/src/app/reader/focus-card.component.ts \
        frontend/src/app/reader/focus-card.component.spec.ts \
        frontend/src/app/reader/reader-page.component.ts \
        frontend/src/app/reader/reader-page.component.spec.ts
git commit -m "feat(reader): add Test me and Exam controls, wired to the quiz panel"
```

---

## Task 13: Playwright end-to-end coverage

**Files:**
- Read first: an existing spec under `frontend/e2e/` (find one with `find frontend/e2e -name '*.spec.ts'`) to copy its exact stubbed-backend pattern (route interception, fixtures, how a session is seeded).
- Create: `frontend/e2e/quiz.spec.ts`

**Interfaces:**
- Consumes: whatever page-object or route-stubbing helper the existing e2e specs already share
  (do not invent a second one — check for a shared `e2e/support` or similar directory first).

- [ ] **Step 1: Read one existing e2e spec end to end**

Run: `find frontend/e2e -name '*.spec.ts' | head -5` then read the first result fully.

- [ ] **Step 2: Write `quiz.spec.ts`**

Follow the exact structure of the file read in Step 1 — the same `test.beforeEach` route stubbing
style, the same base URL / fixture conventions. The scenario, regardless of that file's exact
helper names, is:

1. Stub `GET /api/catalog/topics` and `GET /api/catalog/topics/{slug}` with one topic.
2. Stub `POST /api/sessions` to return a session with one node (the seed).
3. Navigate to the topic, open the session.
4. Stub `POST /api/sessions/{id}/quizzes` to return a `QuizTemplateView` with three questions and
   an `attemptId`.
5. Click "Test me".
6. For each of the three questions: assert the stem is visible, click an option, click
   "Next question" / "See results".
7. Stub `POST /api/sessions/{id}/quizzes/{attemptId}/answers` to return a `QuizResultView`.
8. Assert the score is visible on the results screen.

Write the actual Playwright code once Step 1's file is in hand — its route-mocking API (`page.route`
vs. a project helper like `mockApi(...)`) determines the exact syntax, and guessing it here would
risk exactly the kind of untested placeholder this plan's own rules forbid.

- [ ] **Step 3: Run it**

Run: `cd frontend && npx playwright test quiz.spec.ts`
Expected: PASS. If the dev server needs to be started separately, follow whatever `package.json`
script or `playwright.config.ts` `webServer` block the existing suite already relies on.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/quiz.spec.ts
git commit -m "test(e2e): cover taking a Test Me quiz end to end"
```

---

## Task 14: Full acceptance pass

**Files:** none — verification only.

- [ ] **Step 1: Backend**

Run: `./gradlew build`
Expected: PASS, every module, including `:backend:assess` and the widened `:backend:api`.

- [ ] **Step 2: Frontend unit tests**

Run: `cd frontend && npm test -- --watch=false`
Expected: PASS.

- [ ] **Step 3: End-to-end**

Run: `cd frontend && npx playwright test`
Expected: PASS.

- [ ] **Step 4: Re-read the issue's acceptance criteria and check each one against what was built**

Go through GitHub issue #16's checklist line by line:

- Adversarial validator tests — Task 4.
- Two calls, same scope, one template document — Task 7's cache-hit test.
- Gate order, and an anonymous caller gets `SIGN_IN_REQUIRED` and never a quiz — Task 8's tests.
- The template on the wire carries no `correctIndex` — Task 8's test.
- End-to-end: take a Test Me, answer, see the score — Task 13.
- `./gradlew build`, `npm test -- --watch=false` and `npx playwright test` are green — Steps 1-3
  above.

If any box is unchecked, that is a gap this plan missed — go back and add the task, do not close
the issue with a known gap.

- [ ] **Step 5: Push the branch and open the pull request**

Follow this repository's own PR conventions (check recent merged PRs with `gh pr list --state
merged --limit 5` for title/body style) rather than inventing a new one. Reference issue #16 in the
PR body so it closes on merge.
