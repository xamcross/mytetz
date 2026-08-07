# Monetization Slice B0 — Cost Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Halve the cost of one explanation and give `quota` a per-caller allowance, with no change that a learner can see.

**Architecture:** Four independent changes. `quota` gains an `Allowance` value type that both of its methods accept, with a default that preserves today's behaviour exactly. The default model becomes `claude-sonnet-5`, which changes `modelFamily`, which changes every content key and orphans the whole explanation store. A repository method deletes the orphans and a session method generates a missing seed. One boot-time flag runs those two migration steps one time.

**Tech Stack:** Kotlin 2.x, Ktor, MongoDB Kotlin coroutine driver, kotlinx.serialization, JUnit 5 with `kotlin.test`, Testcontainers (`mongo:7`), Gradle multi-module.

## Global Constraints

- Read the specification first: `docs/superpowers/specs/2026-08-07-monetization-design.md`. Sections 3, 4.4, 8.3 and 13 cover this slice.
- **No behaviour a learner can see changes in B0.** No existing **assertion** may change, and no existing test may be deleted, renamed or disabled. An assertion that has to change to pass is a signal that the change is not behaviour-neutral — stop and report it.
- **Widening a test fixture is allowed and expected.** Tasks 3 and 5 each add a parameter to an existing fixture builder, with the current literal as its default, so every existing call site keeps its exact meaning. That is not an edit to a test; it is an addition to a helper. Adding a near-duplicate builder instead is the worse outcome.
- Write all prose, KDoc and commit message bodies in ASD-STE100 Simplified Technical English. Keep the conventional-commit subject line format. **This rule binds the KDoc in this plan too.** A code block here is a specification of behaviour and not a licence to copy prose that breaks the rule. When a KDoc block in a task looks non-compliant, rewrite it and say so in your report.
- **The concrete test for "one statement in one sentence":** a sentence must not hold two subjects that each carry their own verb. `"The migration deletes documents, so it must never start by accident"` has two — `the migration` and `it`. Split it: `"The migration deletes documents. It must never start by accident."` A compound predicate on one subject is allowed: `"This function trims the value and returns it"` has one subject. The reviewer applies this test. Apply it yourself before you commit. **It binds a `//` comment, an assertion message and prose in `.env.example` as much as it binds a KDoc block.**
- **Do not add a unique index to `principals` or `costLedger`.** `QuotaRepository.ensureIndexes` explains why: the upsert race resolution depends on it.
- Every new TTL field is written through `EpochMillisAsBsonDateTime`. A `Long` makes a TTL index do nothing. This slice adds no TTL field, so the rule only matters if you add one.
- The backend test command is `./gradlew build` from the repository root. It runs 325 tests today.
- Testcontainers needs Docker. The local Docker VM has about 2 GB and the first container start of a session fails sometimes. Retry one time before you report a failure.
- Work on the branch `spec-b-monetization`, which already holds the specification commit.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `backend/quota/src/main/kotlin/com/mytetz/quota/Allowance.kt` | **New.** The value type that carries a count and a window. Nothing else. | 1 |
| `backend/quota/src/main/kotlin/com/mytetz/quota/QuotaConfig.kt` | Gains `defaultAllowance`, built from the two fields it already holds | 1 |
| `backend/quota/src/main/kotlin/com/mytetz/quota/QuotaService.kt` | Both methods accept an `Allowance` and stop reading `config` for the count and the window | 1 |
| `backend/llm/src/main/kotlin/com/mytetz/llm/AnthropicLlmClient.kt` | The model default moves to `claude-sonnet-5` through a testable resolver | 2 |
| `.env.example` | Documents the new model and the migration flag | 2, 5 |
| `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt` | Gains one delete method for orphaned model families | 3 |
| `backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt` | Gains `prewarmSeed`, which generates a seed and creates no session | 4 |
| `backend/api/src/main/kotlin/com/mytetz/api/Components.kt` | Runs the two migration steps at boot when the flag is set | 5 |
| `docs/deploy.md` | The operator runbook for the one-time migration | 5 |

---

## Task 1: Give `quota` a per-caller allowance

`quota` holds one flat `dailyExplains` today. Specification section 4.4 needs a caller to pass a count and a window, so that `billing` can answer "which allowance" in a later slice without `quota` learning what a tier is.

**Files:**
- Create: `backend/quota/src/main/kotlin/com/mytetz/quota/Allowance.kt`
- Modify: `backend/quota/src/main/kotlin/com/mytetz/quota/QuotaConfig.kt`
- Modify: `backend/quota/src/main/kotlin/com/mytetz/quota/QuotaService.kt:85-135`
- Test: `backend/quota/src/test/kotlin/com/mytetz/quota/QuotaServiceTest.kt`

**Interfaces:**
- Consumes: `PrincipalId`, `QuotaDecision`, `QuotaRepository` — all unchanged.
- Produces:
  - `data class Allowance(val generations: Int, val windowMillis: Long)`
  - `QuotaConfig.defaultAllowance: Allowance`
  - `suspend fun QuotaService.checkGeneration(principalId: PrincipalId, allowance: Allowance = config.defaultAllowance): QuotaDecision`
  - `suspend fun QuotaService.recordGeneration(principalId: PrincipalId, costMicros: Long, allowance: Allowance = config.defaultAllowance)`

> **The parameter order on `recordGeneration` is load-bearing.** `costMicros` stays second and `allowance` goes third. `SessionRoutes.kt:728` calls `recordGeneration(principal, spentMicros)` positionally, and that call must keep compiling and keep meaning what it means. Putting `allowance` second silently re-binds a cost to an allowance.

- [ ] **Step 1: Write the failing test for the value type**

Add to `QuotaServiceTest.kt`, in a new section at the end of the file:

```kotlin
    // ------------------------------------------------------------------ allowance

    @Test
    fun `an allowance refuses a non-positive count`() {
        assertFailsWith<IllegalArgumentException> { Allowance(generations = 0, windowMillis = DAY_MILLIS) }
        assertFailsWith<IllegalArgumentException> { Allowance(generations = -1, windowMillis = DAY_MILLIS) }
    }

    @Test
    fun `an allowance refuses a non-positive window`() {
        assertFailsWith<IllegalArgumentException> { Allowance(generations = 5, windowMillis = 0) }
        assertFailsWith<IllegalArgumentException> { Allowance(generations = 5, windowMillis = -1) }
    }

    @Test
    fun `the default allowance carries the config's own numbers`() {
        assertEquals(Allowance(generations = 3, windowMillis = DAY_MILLIS), config.defaultAllowance)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```
./gradlew :backend:quota:test --tests "com.mytetz.quota.QuotaServiceTest"
```

Expected: FAIL. `Unresolved reference: Allowance`.

- [ ] **Step 3: Create the value type**

`backend/quota/src/main/kotlin/com/mytetz/quota/Allowance.kt`:

```kotlin
package com.mytetz.quota

/**
 * How many generations a principal may make, and the length of the window they share.
 *
 * This type keeps [QuotaService] free of a tier, a trial and a subscription. A caller resolves an
 * allowance. The caller then gives it to this module. This module counts. This module refuses.
 * The `billing` module owns the question "which allowance".
 *
 * This class checks both bounds. The call site does not.
 *
 * A count of zero refuses every generation for ever. A window of zero makes every stored window
 * already expired. [QuotaRepository.rollWindowIfExpired] then resets the counter on every call.
 * The allowance disappears. The log says nothing. [QuotaConfig] holds the same reasoning.
 */
data class Allowance(val generations: Int, val windowMillis: Long) {

    init {
        require(generations > 0) { "generations must be positive, was $generations" }
        require(windowMillis > 0) { "windowMillis must be positive, was $windowMillis" }
    }
}
```

- [ ] **Step 4: Add `defaultAllowance` to the config**

In `QuotaConfig.kt`, after the `init` block and before the `companion object`:

```kotlin
    /**
     * What a caller gets when it names no allowance.
     *
     * The `billing` module does not exist yet. Every principal therefore gets this allowance. It
     * holds the two fields that this class validates above. It is a computed property and not a
     * stored one. It therefore cannot disagree with them.
     */
    val defaultAllowance: Allowance get() = Allowance(dailyExplains, windowMillis)
```

- [ ] **Step 5: Run the tests to verify they pass**

```
./gradlew :backend:quota:test --tests "com.mytetz.quota.QuotaServiceTest"
```

Expected: PASS.

- [ ] **Step 6: Write the failing test for a non-default allowance**

Add to the same section of `QuotaServiceTest.kt`:

```kotlin
    @Test
    fun `a named allowance overrides the config's count`() = runTest {
        val generous = Allowance(generations = 5, windowMillis = DAY_MILLIS)

        // Five recorded generations. The config allows three, the named allowance allows five.
        repeat(5) { service.recordGeneration(alice, costMicros = 1, allowance = generous) }

        assertIs<QuotaDecision.PrincipalExceeded>(
            service.checkGeneration(alice, generous),
            "the fifth generation fills the named allowance of five",
        )
        assertIs<QuotaDecision.PrincipalExceeded>(
            service.checkGeneration(alice),
            "the default allowance of three was passed long before",
        )
    }

    @Test
    fun `a named allowance below the count still admits`() = runTest {
        val generous = Allowance(generations = 5, windowMillis = DAY_MILLIS)

        repeat(4) { service.recordGeneration(alice, costMicros = 1, allowance = generous) }

        assertEquals(QuotaDecision.Allowed, service.checkGeneration(alice, generous))
    }

    @Test
    fun `a named window decides the stored expiry`() = runTest {
        val week = Allowance(generations = 40, windowMillis = 7 * DAY_MILLIS)

        service.recordGeneration(alice, costMicros = 1, allowance = week)

        val counter = assertNotNull(repository.findCounter(alice.value))
        assertEquals(
            T0 + 7 * DAY_MILLIS,
            counter.windowExpiresAtEpochMillis,
            "the trial pool in specification section 6.1 is this shape: 40 over seven days",
        )
    }

    @Test
    fun `the default allowance leaves today's behaviour exactly as it was`() = runTest {
        repeat(3) { service.recordGeneration(alice, costMicros = 1) }

        assertIs<QuotaDecision.PrincipalExceeded>(service.checkGeneration(alice))

        val counter = assertNotNull(repository.findCounter(alice.value))
        assertEquals(T0 + DAY_MILLIS, counter.windowExpiresAtEpochMillis)
    }
```

- [ ] **Step 7: Run the tests to verify they fail**

```
./gradlew :backend:quota:test --tests "com.mytetz.quota.QuotaServiceTest"
```

Expected: FAIL. `No value passed for parameter 'allowance'` is wrong — the real message is `Too many arguments` or `Cannot find a parameter with this name: allowance`, because the methods take no such parameter yet.

- [ ] **Step 8: Add the parameter to both methods**

In `QuotaService.kt`, replace the signature and the two field reads in `checkGeneration`:

```kotlin
    suspend fun checkGeneration(
        principalId: PrincipalId,
        allowance: Allowance = config.defaultAllowance,
    ): QuotaDecision {
        if (dailySpendMicros() >= config.globalDailyCostCeilingMicros) {
            return QuotaDecision.SpendLimitReached
        }

        val now = clock()
        val counter = repository.findCounter(principalId.value)

        if (counter == null || now >= counter.windowExpiresAtEpochMillis) return QuotaDecision.Allowed

        return if (counter.explainCount >= allowance.generations) {
            QuotaDecision.PrincipalExceeded(
                // Floored at 1: the true answer rounds to 0 inside the last second of the window,
                // and a Retry-After of 0 invites an immediate retry that would still be refused.
                retryAfterSeconds = ((counter.windowExpiresAtEpochMillis - now) / 1000).coerceAtLeast(1),
            )
        } else {
            QuotaDecision.Allowed
        }
    }
```

And in `recordGeneration`:

```kotlin
    suspend fun recordGeneration(
        principalId: PrincipalId,
        costMicros: Long,
        allowance: Allowance = config.defaultAllowance,
    ) {
        // A negative cost would walk the ledger backwards and un-trip a breaker that had already
        // tripped. It is also the unstated premise of the class KDoc's "monotone within a UTC day".
        require(costMicros >= 0) { "costMicros must not be negative, was $costMicros" }

        val now = clock()
        repository.incrementLedger(today(), costMicros)
        repository.rollWindowIfExpired(principalId.value, now, allowance.windowMillis)
        repository.incrementCounter(principalId.value, now, allowance.windowMillis, costMicros)
    }
```

- [ ] **Step 9: Add the KDoc note that records why the order is what it is**

Add this paragraph to the class KDoc of `QuotaService`, at the end, before the closing `*/`:

```
 * ## The allowance is a parameter and not a field
 *
 * A tier decides an allowance. This module must not learn what a tier is. The caller therefore
 * resolves an [Allowance] and gives it to this class. [QuotaConfig.defaultAllowance] keeps every
 * caller that has no tier unchanged.
 *
 * On [recordGeneration] the allowance is the THIRD parameter. `costMicros` stays the second.
 * `SessionRoutes` calls the method positionally. An allowance in the second position binds a cost
 * to an allowance. Both values are numbers. The compiler therefore reports nothing.
```

- [ ] **Step 10: Run the whole quota suite**

```
./gradlew :backend:quota:test
```

Expected: PASS, with the four new tests green and every existing test untouched.

- [ ] **Step 11: Run the whole build to prove nothing else moved**

```
./gradlew build
```

Expected: PASS. The default arguments keep `SessionRoutes.kt:678` and `SessionRoutes.kt:728` compiling with no edit.

- [ ] **Step 12: Commit**

```bash
git add backend/quota/src/main/kotlin/com/mytetz/quota/Allowance.kt \
        backend/quota/src/main/kotlin/com/mytetz/quota/QuotaConfig.kt \
        backend/quota/src/main/kotlin/com/mytetz/quota/QuotaService.kt \
        backend/quota/src/test/kotlin/com/mytetz/quota/QuotaServiceTest.kt
git commit -m "feat(quota): let a caller name the allowance

The quota module holds one flat daily count today. A tier needs a count and
a window for each caller, and this module must not learn what a tier is.

Both methods now accept an Allowance. The default comes from QuotaConfig, so
every existing caller keeps its behaviour and needs no edit.

On recordGeneration the allowance is the third parameter. SessionRoutes calls
it positionally, and an allowance in the second position binds a cost to an
allowance without a compiler error."
```

---

## Task 2: Move the default model to `claude-sonnet-5`

Specification section 3.2 records the defect: 20 explanations each day on `claude-opus-5` costs $10.50 each month against $10.29 of net revenue.

`fly.toml` sets neither `MYTETZ_MODEL_ID` nor `MYTETZ_MODEL_FAMILY`, so **production runs on the code default**. Changing `.env.example` alone changes nothing that is deployed.

**Files:**
- Modify: `backend/llm/src/main/kotlin/com/mytetz/llm/AnthropicLlmClient.kt:50-54`
- Modify: `.env.example:22-23`
- Test: `backend/llm/src/test/kotlin/com/mytetz/llm/AnthropicLlmClientTest.kt`
- Test: `backend/llm/src/test/kotlin/com/mytetz/llm/PricingTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `AnthropicLlmClient.Companion.DEFAULT_MODEL: String`, `AnthropicLlmClient.Companion.resolveModel(raw: String?): String` (internal).

- [ ] **Step 1: Write the failing test for the resolver**

Add to `AnthropicLlmClientTest.kt`, at the end of the class:

```kotlin
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
        // Pricing falls back to the dearest known rate for an unknown model. A typo in the default
        // would therefore over-report every cost silently rather than fail. This is the check that
        // makes the fallback safe to keep.
        val oneMillionOut = LlmUsage(inputTokens = 0, outputTokens = 1_000_000)
        assertEquals(
            15_000_000L,
            Pricing.costMicros(AnthropicLlmClient.DEFAULT_MODEL, oneMillionOut),
            "the default model must bill at Sonnet 5's published output rate of \$15 for each 1M tokens",
        )
    }
```

Add this import to the top of the file if it is absent:

```kotlin
import kotlin.test.assertEquals
```

- [ ] **Step 2: Run the tests to verify they fail**

```
./gradlew :backend:llm:test --tests "com.mytetz.llm.AnthropicLlmClientTest"
```

Expected: FAIL. `Unresolved reference: resolveModel`.

- [ ] **Step 3: Add the resolver and change the default**

In `AnthropicLlmClient.kt`, replace lines 50 to 54:

```kotlin
class AnthropicLlmClient(
    private val client: AnthropicClient = defaultClient(),
    override val modelId: String = resolveModel(System.getenv(MODEL_ID_ENV)),
    override val modelFamily: String = resolveModel(System.getenv(MODEL_FAMILY_ENV)),
) : LlmClient {
```

Then add to the class's `companion object` — create one if the class has none, beside `defaultClient()` and `DEFAULT_TIMEOUT_SECONDS`:

```kotlin
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
         * whole explanation store. The design intends that behaviour — see section 13 of the same
         * document. Read that section before you change this value.
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
```

- [ ] **Step 4: Run the tests to verify they pass**

```
./gradlew :backend:llm:test --tests "com.mytetz.llm.AnthropicLlmClientTest"
```

Expected: PASS.

- [ ] **Step 5: Add the pricing regression test**

Specification section 15 asks for it. A silent rate change removes the margin with no signal.

Add to `PricingTest.kt`:

```kotlin
    @Test
    fun `sonnet 5 bills at the published rate`() {
        // $3 for each 1M input tokens and $15 for each 1M output tokens, in micro-dollars for one
        // token. If this test fails, the published rate moved and section 3 of the monetization
        // specification needs recomputing before the change is accepted.
        val usage = LlmUsage(inputTokens = 1_000_000, outputTokens = 1_000_000)

        assertEquals(3_000_000L + 15_000_000L, Pricing.costMicros("claude-sonnet-5", usage))
    }

    @Test
    fun `one explanation on sonnet 5 costs about a cent`() {
        // The shape section 3.1 of the specification measures: ~1000 in, ~500 out.
        val usage = LlmUsage(inputTokens = 1_000, outputTokens = 500)

        assertEquals(10_500L, Pricing.costMicros("claude-sonnet-5", usage), "10 500 micro-dollars is \$0.0105")
    }
```

- [ ] **Step 6: Run the pricing tests**

```
./gradlew :backend:llm:test --tests "com.mytetz.llm.PricingTest"
```

Expected: PASS. `Pricing.kt:17` already holds `Rate(input = 3.0, output = 15.0)` for `claude-sonnet-5`, so no production code changes here.

- [ ] **Step 7: Update `.env.example`**

Replace lines 22 and 23:

```
MYTETZ_MODEL_ID=claude-sonnet-5
MYTETZ_MODEL_FAMILY=claude-sonnet-5
```

Add this comment directly above them:

```
# The model. The family below is hashed into every content key.
#
# Sonnet 5 costs $3 and $15 for each 1M tokens. Opus 5 costs $5 and $25. One explanation is about
# 1000 input tokens and 500 output tokens. The two therefore cost $0.0105 and $0.0175. At 25
# explanations each day the month costs $7.88 and $13.13. Net revenue on a €10 subscription is
# $10.29.
#
# CHANGING MYTETZ_MODEL_FAMILY ORPHANS EVERY EXPLANATION IN THE STORE. The design intends that.
# The family is part of the content key. A change therefore invalidates the cache with no
# migration. Read section 13 of docs/superpowers/specs/2026-08-07-monetization-design.md before you
# change it. Then run the migration in MYTETZ_MIGRATE_ON_BOOT below. It removes the stranded
# documents.
```

- [ ] **Step 8: Run the whole build**

```
./gradlew build
```

Expected: PASS. `ExplanationRepositoryTest.kt:31-32` builds a fixture with `modelFamily = "claude-opus-5"` as a literal, which is a fixture value and not the default, so it keeps passing.

- [ ] **Step 9: Commit**

```bash
git add backend/llm/src/main/kotlin/com/mytetz/llm/AnthropicLlmClient.kt \
        backend/llm/src/test/kotlin/com/mytetz/llm/AnthropicLlmClientTest.kt \
        backend/llm/src/test/kotlin/com/mytetz/llm/PricingTest.kt \
        .env.example
git commit -m "feat(llm): move the default model to claude-sonnet-5

Twenty explanations each day on claude-opus-5 costs \$10.50 each month. Net
revenue on a €10 subscription is \$10.29. The margin at full use is negative.

Sonnet 5 costs 60% of Opus 5 for each explanation and keeps the reasoning
that contextual isolation needs. Twenty-five each day then costs \$7.88.

fly.toml sets neither model variable, so production runs on the code default.
The change therefore had to happen here and not only in .env.example.

The default now goes through a resolver, in the shape GraphConfig and
QuotaConfig already use, so a test can pin it without an environment.

This changes modelFamily, which is hashed into every content key, so it
orphans the whole explanation store. Task 3 removes the stranded documents."
```

---

## Task 3: Delete the orphaned explanations

Specification section 13. Two sets of orphans exist after Task 2: the documents from the earlier key change that the handover records, and every document Task 2 just stranded. Both share one predicate — the model family is not the current one.

**Files:**
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt`
- Test: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt`

**Interfaces:**
- Consumes: nothing from Tasks 1 or 2.
- Produces: `suspend fun ExplanationRepository.deleteWhereModelFamilyIsNot(modelFamily: String): Long`

- [ ] **Step 1: Write the failing test**

First widen the file's fixture. It is `private fun explanation(key: String, body: String)` at line 18 and it hardcodes `modelFamily = "claude-opus-5"` at line 31. Add a third parameter with that literal as its default, so all nine existing call sites keep compiling unchanged:

```kotlin
    private fun explanation(
        key: String,
        body: String,
        modelFamily: String = "claude-opus-5",
    ) = Explanation(
```

and change the one field on line 31 from the literal to the parameter:

```kotlin
        modelFamily = modelFamily,
```

Leave `modelId = "claude-opus-5"` as it is. Only the family decides the key.

Then add these tests at the end of the class:

```kotlin
    @Test
    fun `deleting by family removes every other family and reports the count`() = runTest {
        repository.insertIfAbsent(explanation("a", "an old body", modelFamily = "claude-opus-5"))
        repository.insertIfAbsent(explanation("b", "another old body", modelFamily = "claude-opus-5"))
        repository.insertIfAbsent(explanation("c", "a current body", modelFamily = "claude-sonnet-5"))

        val deleted = repository.deleteWhereModelFamilyIsNot("claude-sonnet-5")

        assertEquals(2, deleted)
        assertNull(repository.findByKey("a"))
        assertNull(repository.findByKey("b"))
        assertNotNull(repository.findByKey("c"), "the current family survives")
    }

    @Test
    fun `deleting by family is idempotent`() = runTest {
        repository.insertIfAbsent(explanation("a", "an old body", modelFamily = "claude-opus-5"))

        assertEquals(1, repository.deleteWhereModelFamilyIsNot("claude-sonnet-5"))
        assertEquals(
            0,
            repository.deleteWhereModelFamilyIsNot("claude-sonnet-5"),
            "a second run finds nothing; an operator can run it twice with no consequence",
        )
    }

    @Test
    fun `deleting by family on an empty store reports zero`() = runTest {
        assertEquals(0, repository.deleteWhereModelFamilyIsNot("claude-sonnet-5"))
    }
```

Add this import — the file imports `assertEquals` and `assertNull` today but not `assertNotNull`:

```kotlin
import kotlin.test.assertNotNull
```

- [ ] **Step 2: Run the tests to verify they fail**

```
./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"
```

Expected: FAIL. `Unresolved reference: deleteWhereModelFamilyIsNot`.

- [ ] **Step 3: Add the method**

In `ExplanationRepository.kt`, after `incrementRequestCount`:

```kotlin
    /**
     * Removes every explanation that a change of model family stranded. Reports how many it removed.
     *
     * The content key holds `modelFamily`. A document written under a different family is therefore
     * unreachable. No key that a caller can compute finds it. The predicate is exact and not a
     * heuristic. The operation loses nothing that the system can serve.
     *
     * You can run this method twice. The second run matches nothing.
     *
     * **Do not run it while two application versions are live on different families.** Each version
     * deletes the other's documents. `fly.toml` runs one machine. The caller in
     * `Components.bootstrap` also sits behind an explicit flag. A mistake here costs a regeneration
     * and not a corruption. The system can reproduce every deleted document from its inputs.
     */
    suspend fun deleteWhereModelFamilyIsNot(modelFamily: String): Long =
        collection.deleteMany(Filters.ne("modelFamily", modelFamily)).deletedCount
```

- [ ] **Step 4: Run the tests to verify they pass**

```
./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"
```

Expected: PASS.

- [ ] **Step 5: Run the whole build**

```
./gradlew build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt
git commit -m "feat(graph): remove explanations stranded by a model family change

The model family is hashed into the content key. A document written under
another family is therefore unreachable, because no key a caller can compute
will find it.

The handover records that the explanations collection has no eviction and
that an earlier key change already left orphans. Moving to claude-sonnet-5
strands a second set. One predicate removes both.

The method is idempotent. An operator can run it twice."
```

---

## Task 4: Generate a missing seed without creating a session

Specification section 8.3. A published topic must have a seed explanation in the store. `SessionService.createWillGenerate` already reports whether one is absent; nothing can generate one except `create`, which also inserts a `LearningSession` that nobody asked for.

**Files:**
- Modify: `backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt`
- Test: `backend/session/src/test/kotlin/com/mytetz/session/SessionServiceTest.kt`

**Interfaces:**
- Consumes: `SessionService.requirePublishedTopic`, `SessionService.seedRequest`, `ExplanationGraph.keyFor`, `ExplanationGraph.getOrGenerate`, `GraphChunk` — all private or existing.
- Produces: `suspend fun SessionService.prewarmSeed(topicSlug: String, onSpend: suspend (Long) -> Unit): Boolean`

- [ ] **Step 1: Write the failing test**

Add these at the end of `SessionServiceTest.kt`. The file's `reset()` drops the `explanations` collection and seeds the catalogue, so the store starts with no seed and `quantum-physics` starts published. `llm.bodyByPromptSubstring["1 to 3 sentences that introduce"]` is already set to `seedBody`, so a seed generation succeeds.

```kotlin
    // ------------------------------------------------------------------ prewarmSeed

    @Test
    fun `prewarming a missing seed generates it and reports the spend`() = runTest {
        val spent = mutableListOf<Long>()

        val generated = service.prewarmSeed("quantum-physics") { spent += it }

        assertTrue(generated, "the store held no seed; this call generated one")
        assertEquals(1, spent.size, "the cost is reported once, the instant it is known")
        assertTrue(spent.single() > 0, "FakeLlmClient reports real token counts")
    }

    @Test
    fun `prewarming stores a seed that a later create finds`() = runTest {
        service.prewarmSeed("quantum-physics") { }

        val spent = mutableListOf<Long>()
        service.create("anon:alice", "quantum-physics") { spent += it }

        assertEquals(emptyList(), spent, "create found the pre-warmed seed and called no model")
        assertEquals(1, llm.calls.size, "the one call is the pre-warm's own")
    }

    @Test
    fun `prewarming an existing seed generates nothing and spends nothing`() = runTest {
        service.prewarmSeed("quantum-physics") { }

        val spent = mutableListOf<Long>()
        val generated = service.prewarmSeed("quantum-physics") { spent += it }

        assertFalse(generated, "the second call found the seed and stopped")
        assertEquals(emptyList(), spent, "a cache hit costs nothing and must record nothing")
    }

    @Test
    fun `prewarming creates no learning session`() = runTest {
        service.prewarmSeed("quantum-physics") { }

        assertEquals(
            0,
            database.getCollection<org.bson.Document>("sessions").countDocuments(),
            "a maintenance step must not leave a session that no learner started",
        )
    }

    @Test
    fun `prewarming an unknown topic raises exactly as create does`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.prewarmSeed("no-such-topic") { }
        }
    }

    @Test
    fun `prewarming a draft topic raises exactly as create does`() = runTest {
        topics.upsert(
            Topic(
                slug = "a-draft-topic",
                title = "A Draft Topic",
                category = "test",
                summary = "not ready for browsing",
                status = TopicStatus.DRAFT,
            )
        )

        assertFailsWith<IllegalArgumentException> {
            service.prewarmSeed("a-draft-topic") { }
        }
    }
```

> `requirePublishedTopic` at `SessionService.kt:540` raises `IllegalArgumentException` for both an unknown topic and a draft one. It is not a bespoke exception type. Do not invent one for this task — the API layer's `ErrorMapping` already maps what it raises.
>
> If `TopicRepository` has no plain `upsert` — it does have `upsertPreservingStatus`, which is the wrong one here because it preserves the stored status — write the draft topic through the driver directly instead:
>
> ```kotlin
> database.getCollection<Topic>("topics").insertOne(
>     Topic(slug = "a-draft-topic", title = "A Draft Topic", category = "test",
>           summary = "not ready for browsing", status = TopicStatus.DRAFT)
> )
> ```

- [ ] **Step 2: Run the tests to verify they fail**

```
./gradlew :backend:session:test --tests "com.mytetz.session.SessionServiceTest"
```

Expected: FAIL. `Unresolved reference: prewarmSeed`.

- [ ] **Step 3: Add the method**

In `SessionService.kt`, directly after `createWillGenerate`:

```kotlin
    /**
     * Generates this topic's seed when the store holds none. Creates no session.
     *
     * A published topic must have a seed. No other method here can establish that. [create] is the
     * only other path to a seed. [create] also inserts a [LearningSession]. A maintenance loop
     * built on [create] therefore leaves one abandoned session for each topic.
     *
     * The method returns true when this call generated the seed. It returns false when this call
     * found one.
     *
     * The method calls [onSpend] with this caller's own cost at the instant the cost is known. It
     * does this before any later step can fail. [create]'s parameter of the same name holds the
     * same contract for the same reason.
     *
     * The method raises for an unknown topic and for an unpublished topic. [create] and
     * [createWillGenerate] both do the same.
     */
    suspend fun prewarmSeed(topicSlug: String, onSpend: suspend (Long) -> Unit): Boolean {
        val topic = requirePublishedTopic(topicSlug)
        val request = seedRequest(topic)

        if (explanations.findByKey(graph.keyFor(request)) != null) return false

        var generated = false
        graph.getOrGenerate(request).collect { chunk ->
            when (chunk) {
                is GraphChunk.Spent -> {
                    generated = true
                    onSpend(chunk.costMicros)
                }
                is GraphChunk.Done, is GraphChunk.Meta, is GraphChunk.Delta, is GraphChunk.Superseded -> Unit
            }
        }
        return generated
    }
```

> `generated` is driven by `Spent` and not by the absent-key check above it. Between the two, another caller can persist the same key, and this call is then served from the store and spends nothing. Reporting true there would over-count the migration in the log.

- [ ] **Step 4: Run the tests to verify they pass**

```
./gradlew :backend:session:test --tests "com.mytetz.session.SessionServiceTest"
```

Expected: PASS.

- [ ] **Step 5: Run the whole build**

```
./gradlew build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt \
        backend/session/src/test/kotlin/com/mytetz/session/SessionServiceTest.kt
git commit -m "feat(session): generate a topic's seed without a session

A published topic must have a seed explanation in the store. Only create()
could make one. It also inserts a learning session. A maintenance loop built
on it therefore leaves one abandoned session for every topic.

prewarmSeed generates the seed and nothing else. It reports the cost through
the same onSpend contract that create() uses. The spend ledger therefore
sees it.

It reports true only when it spent. Another caller can persist the key
between the check and the generation. That call is then a cache hit."
```

---

## Task 5: Run the migration one time at boot, behind a flag

The two migration steps need to run on the deployed machine, one time, in a known order: delete the orphans first, then generate the seeds. Running them on every boot is wrong — `fly.toml` scales to zero, so a boot happens on any request after an idle period, and `Components` documents that catalogue browsing must never need `ANTHROPIC_API_KEY`.

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Components.kt`
- Test: `backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt`
- Modify: `.env.example`
- Modify: `docs/deploy.md`

**Interfaces:**
- Consumes: `Allowance` (Task 1), `AnthropicLlmClient.DEFAULT_MODEL` (Task 2), `ExplanationRepository.deleteWhereModelFamilyIsNot` (Task 3), `SessionService.prewarmSeed` (Task 4).
- Produces: `Components.migrateOnBoot: Boolean`, `suspend fun Components.migrate()`

- [ ] **Step 1: Write the failing test**

Add to `ComponentsTest.kt`:

```kotlin
    @Test
    fun `the migration is off unless the flag says otherwise`() {
        assertFalse(Components.resolveMigrateOnBoot(null))
        assertFalse(Components.resolveMigrateOnBoot(""))
        assertFalse(Components.resolveMigrateOnBoot("false"))
        assertFalse(Components.resolveMigrateOnBoot("yes"), "only the exact word true turns it on")
        assertFalse(Components.resolveMigrateOnBoot("1"))
    }

    @Test
    fun `the migration is on for the exact word true`() {
        assertTrue(Components.resolveMigrateOnBoot("true"))
        assertTrue(Components.resolveMigrateOnBoot("TRUE"))
        assertTrue(Components.resolveMigrateOnBoot("  true \n"), "a fly secret carries a trailing newline")
    }
```

> The polarity here is the opposite of `PrincipalCookieConfig.resolveSecure`, and deliberately. There the strict value is the safe one, so anything unrecognised means on. Here the step deletes documents and spends money, so anything unrecognised means off.

- [ ] **Step 2: Run the test to verify it fails**

```
./gradlew :backend:api:test --tests "com.mytetz.api.ComponentsTest"
```

Expected: FAIL. `Unresolved reference: resolveMigrateOnBoot`.

- [ ] **Step 3: Add the resolver**

In `Components.kt`, add a `companion object` to the class:

```kotlin
    companion object {

        const val MIGRATE_ON_BOOT_ENV: String = "MYTETZ_MIGRATE_ON_BOOT"

        /**
         * Only the exact word `true` turns the migration on.
         *
         * This polarity is the opposite of `PrincipalCookieConfig.resolveSecure`. The safe value is
         * also the opposite one. There, an unrecognised value keeps a protection. Here, an
         * unrecognised value keeps the migration off. The migration deletes documents. It also
         * calls a metered API. It must never start by accident.
         */
        internal fun resolveMigrateOnBoot(raw: String?): Boolean =
            raw?.trim()?.equals("true", ignoreCase = true) == true
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```
./gradlew :backend:api:test --tests "com.mytetz.api.ComponentsTest"
```

Expected: PASS.

- [ ] **Step 5: Add the migration itself**

In `Components.kt`, add the constructor parameter beside the others:

```kotlin
open class Components(
    val mongo: Mongo = Mongo(MongoConfig.fromEnv()),
    val cookies: PrincipalCookieConfig = PrincipalCookieConfig(),
    val clientAddresses: ClientAddressConfig = ClientAddressConfig(),
    llmFactory: () -> LlmClient = { AnthropicLlmClient() },
    val migrateOnBoot: Boolean = resolveMigrateOnBoot(System.getenv(MIGRATE_ON_BOOT_ENV)),
) {
```

`explanations` is currently a `private val`. Leave it private and add the migration inside the class, after `bootstrap()`:

```kotlin
    /**
     * The one-time migration for slice B0 of the monetization specification.
     *
     * It runs only when [migrateOnBoot] is true. An operator sets that flag for one deployment and
     * then removes it.
     *
     * This is not an ordinary boot step. It deletes documents. It calls a metered API. It also
     * builds the lazy model client. An unconditional version would therefore make catalogue
     * browsing need `ANTHROPIC_API_KEY`.
     *
     * The order is load-bearing. The delete runs first. The first half then cannot delete a seed
     * that the second half generates.
     *
     * Both halves are idempotent. A second run is therefore safe. The seeds cost real money. The
     * loop asks the quota gate before each seed. It stops when the global spend breaker trips.
     * The allowance it names holds a whole catalogue. It also bounds a runaway.
     */
    suspend fun migrate() {
        if (!migrateOnBoot) return

        val deleted = explanations.deleteWhereModelFamilyIsNot(llm.modelFamily)
        log.info("MIGRATION removed {} explanation(s) stranded by a model family change", deleted)

        val maintenance = PrincipalId.user("maintenance")
        val budget = Allowance(generations = 10_000, windowMillis = 86_400_000)

        var generated = 0
        var spentMicros = 0L
        for (topic in catalog.listPublished(category = null, query = null)) {
            if (quota.checkGeneration(maintenance, budget) != QuotaDecision.Allowed) {
                log.warn("MIGRATION stopped early: the spend breaker refused before '{}'", topic.slug)
                break
            }
            val didGenerate = sessions.prewarmSeed(topic.slug) { cost ->
                spentMicros += cost
                quota.recordGeneration(maintenance, cost, budget)
            }
            if (didGenerate) generated++
        }

        log.info(
            "MIGRATION pre-warmed {} seed(s) at a cost of {} micro-dollars; remove {} now",
            generated,
            spentMicros,
            MIGRATE_ON_BOOT_ENV,
        )
    }
```

Add these imports to the top of `Components.kt`:

```kotlin
import com.mytetz.quota.Allowance
import com.mytetz.quota.PrincipalId
import com.mytetz.quota.QuotaDecision
```

- [ ] **Step 6: Call it from the bootstrap path**

`bootstrap()` runs in the background and `/api/health` reports `ready` from it. The migration belongs after the catalogue seed, because it iterates published topics.

Change the last line of `bootstrap()`:

```kotlin
    open suspend fun bootstrap() {
        topics.ensureIndexes()
        topicRequests.ensureIndexes()
        explanations.ensureIndexes()
        sessionRepository.ensureIndexes()
        quotaRepository.ensureIndexes()
        catalog.seedFromResource()
        migrate()
    }
```

- [ ] **Step 7: Write the test that the flag actually gates the work**

`ComponentsTest` builds components through a private helper at line 41. Widen it with the new flag, defaulted off so every existing call site keeps its meaning:

```kotlin
    private fun components(name: String, migrateOnBoot: Boolean = false) = Components(
        mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_$name")),
        cookies = TestFixtures.cookieConfig,
        // Never AnthropicLlmClient: its default constructor calls `AnthropicOkHttpClient.fromEnv()`,
        // which demands a real key at construction time and would put a paid call one slip away.
        llmFactory = { FakeLlmClient() },
        migrateOnBoot = migrateOnBoot,
    )
```

Then add these two tests:

```kotlin
    @Test
    fun `bootstrap builds no model client when the migration is off`() = runTest {
        var clientBuilds = 0

        val components = Components(
            mongo = Mongo(MongoConfig(uri = TestFixtures.connectionString, databaseName = "test_api_no_migrate")),
            cookies = TestFixtures.cookieConfig,
            // The migration is the only thing in bootstrap that forces the lazy model client. A
            // client that was never built therefore proves the migration did not run — and proves
            // that the catalogue still boots with no ANTHROPIC_API_KEY, which is the property this
            // class's KDoc protects.
            llmFactory = { clientBuilds++; FakeLlmClient() },
            migrateOnBoot = false,
        )

        components.bootstrap()

        assertEquals(0, clientBuilds, "bootstrap must not build a model client when the flag is off")
    }

    @Test
    fun `the migration removes a stranded explanation and pre-warms every seed`() = runTest {
        val components = components("migrate", migrateOnBoot = true)
        val explanations = components.mongo.database.getCollection<Document>("explanations")
        explanations.drop()

        // A document from a model family nobody runs any more. No key a caller can compute finds it.
        explanations.insertOne(
            Document()
                .append("_id", "stranded")
                .append("topicSlug", "quantum-physics")
                .append("modelFamily", "claude-opus-5")
                .append("body", "an unreachable body"),
        )

        components.bootstrap()

        assertEquals(
            0,
            explanations.countDocuments(Filters.eq("modelFamily", "claude-opus-5")),
            "the stranded document is gone",
        )
        assertTrue(
            explanations.countDocuments(Filters.eq("modelFamily", "fake-model")) >= 20,
            "every published topic in the catalogue now has a seed under the current family",
        )
    }
```

Add this import — the file imports `Document` already but not the filter builder:

```kotlin
import com.mongodb.client.model.Filters
```

> The second test runs a seed generation for every published topic against `FakeLlmClient`, which is about 29 in-memory calls. Its default `nextBody` is 48 characters, which sits inside `ExplanationValidator`'s 40 to 600 bound, so each one is accepted.

- [ ] **Step 8: Run the api tests**

```
./gradlew :backend:api:test --tests "com.mytetz.api.ComponentsTest"
```

Expected: PASS.

- [ ] **Step 9: Document the flag in `.env.example`**

Add at the end of the file:

```
# ONE-TIME MIGRATION. Leave this unset.
#
# Set it to exactly `true` for one deployment after you change MYTETZ_MODEL_FAMILY, then remove it.
# It does two things at boot, in this order:
#
#   1. Deletes every explanation whose model family is not the current one. Those documents are
#      unreachable, because the family is part of the content key.
#   2. Generates a seed explanation for every published topic that has none.
#
# Step 2 spends real money — about $0.30 for 29 topics — and it forces the model client to build,
# so ANTHROPIC_API_KEY must be set and the account must hold credit. The loop stops early if the
# global spend breaker trips.
#
# Both steps are idempotent. Grep the boot log for "MIGRATION" to see what happened.
#
#MYTETZ_MIGRATE_ON_BOOT=true
```

- [ ] **Step 10: Write the operator runbook**

Add a section to `docs/deploy.md`:

```markdown
## The B0 model migration

Do this one time, after the deployment that carries the `claude-sonnet-5` default.

The model family is part of every content key, so the change makes every stored explanation
unreachable. The migration removes them and generates a fresh seed for each published topic.

1. Confirm the Anthropic account holds credit. Step 2 of the migration makes about 29 model calls.

2. Turn the migration on and deploy.

   ```
   fly secrets set MYTETZ_MIGRATE_ON_BOOT=true --app mytetz
   fly deploy --local-only --ha=false --app mytetz
   ```

3. Wait for the health check to report `ready`.

   ```
   curl -s https://mytetz.com/api/health
   ```

   The answer must be `{"status":"ok","mongo":true,"ready":true}`.

4. Read what the migration did.

   **In bash.**

   ```bash
   fly logs --app mytetz --no-tail | grep MIGRATION
   ```

   **In PowerShell.**

   ```powershell
   fly logs --app mytetz --no-tail | Select-String MIGRATION
   ```

   You must see two lines: one count of removed explanations and one count of pre-warmed seeds.
   The `--no-tail` flag is necessary, because the lines are already in the past.

5. Turn the migration off.

   ```
   fly secrets unset MYTETZ_MIGRATE_ON_BOOT --app mytetz
   ```

   The machine restarts. The migration is idempotent, so a boot that runs it again costs nothing,
   but leaving the flag set makes every cold start do two extra collection scans.

**If the second log line reports fewer seeds than the catalogue holds,** the spend breaker stopped
the loop. Check the day's ledger, raise `MYTETZ_GLOBAL_DAILY_COST_CEILING_USD_MICROS` if it is
correct to do so, and run the migration again tomorrow. The seeds that exist are kept.
```

- [ ] **Step 11: Run the whole build**

```
./gradlew build
```

Expected: PASS, with 325 existing tests plus the new ones.

- [ ] **Step 12: Run the frontend and end-to-end suites to prove nothing moved**

```
cd frontend && npm test -- --watch=false && npx playwright test
```

Expected: PASS. 103 unit tests and 6 end-to-end tests. B0 changes no wire format, so both must be green with no edit.

- [ ] **Step 13: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/Components.kt \
        backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt \
        .env.example docs/deploy.md
git commit -m "feat(api): run the B0 migration one time behind a flag

The model family change strands every explanation and leaves every published
topic without a seed. Both need fixing on the deployed machine, in that
order, one time.

The step sits behind MYTETZ_MIGRATE_ON_BOOT. Only the exact word true turns
it on. An unrecognised value means off. That polarity is the opposite of the
cookie's secure flag. Here the step deletes documents. It also spends money.

It is not an unconditional boot step. fly.toml scales to zero. A boot
therefore happens on any request after an idle period. The migration also
builds the lazy model client. An unconditional version would make catalogue
browsing need ANTHROPIC_API_KEY. Components' documentation forbids that.

The seed loop asks the quota gate before each generation and stops when the
spend breaker trips. docs/deploy.md carries the runbook."
```

---

## Verification after B0

Run these against the deployment before you start B1.

| # | Check | Pass condition |
|---|---|---|
| 1 | `curl -s https://mytetz.com/api/health` | `{"status":"ok","mongo":true,"ready":true}` |
| 2 | Open the catalogue | More than 20 topics. The search filters them. |
| 3 | Open a topic | The introductory text appears at once, with no generation delay |
| 4 | Read the `explanations` collection in Atlas | Every document carries `modelFamily: "claude-sonnet-5"`. No document carries another value. |
| 5 | Read the `explanations` collection in Atlas | One seed document exists for each published topic |
| 6 | Highlight a phrase and press Explain | The text arrives word by word |
| 7 | Read the new document's `costMicros` | Approximately 10 500, which is $0.0105. A number near 17 500 means the model did not change. |
| 8 | Check the central promise | "microscopic realm" under Quantum Physics describes the subatomic scale. Under Microbiology it describes cells. The two documents have different `_id` values. |

Check 8 is the one that matters. Sonnet 5 is a different model from the one that produced the answers the acceptance run verified, and contextual isolation is a reasoning task. **If the two answers agree, stop and report it.** The model change is then wrong and the plan's economic argument needs revisiting against Opus 5 with a smaller allowance.

---

## What B0 does not do

These belong to the later slices and must not appear in this branch.

| Item | Slice |
|---|---|
| `QuotaRepository.resetCounter`, for the trial-to-paid window change | B2 |
| The `account` and `billing` modules | B1, B2 |
| Any gate, any new HTTP status, any new error code | B1 |
| The allowance of 25 for a subscriber, and the trial pool of 40 | B2 |
| `MYTETZ_DAILY_EXPLAINS = 25` | B2 |

`MYTETZ_DAILY_EXPLAINS` stays at 20 through B0. Raising it before a gate exists raises the allowance of every anonymous visitor, which spends money to no purpose.
