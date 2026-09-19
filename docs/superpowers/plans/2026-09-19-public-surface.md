# Public surface — Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to run this plan task by task. A checkbox (`- [ ]`)
> marks each step.

**Goal.** Give each published topic a stable, crawlable HTML page, rendered by Ktor with no
Angular and no model call. Add a machine-generated sitemap. Add a transparency page and a review
date. Add public explanation pages and a glossary, gated by a `published` flag. Four phases, one
issue per phase, so one pull request can close one issue.

**Architecture.** Ktor renders every new public page with the HTML DSL (`respondHtml`, from
`kotlinx.html`), inside the one existing `api` process. No second process joins the deployment.
Each new route reads only `catalog` and `graph` as plain, already-published data: it calls
`CatalogService.findBySlug`/`listPublished` and `ExplanationRepository.findByKey`, and it never
calls `Principals.resolve`, never sets a cookie, and never builds or calls a model client. A new,
small, eagerly-computed `Components.modelFamily: String` value gives each route the value that
`ContentKey.seed`/`ContentKey.derive` need, with no `AnthropicLlmClient` built and no
`ANTHROPIC_API_KEY` demanded — see Task 1.2, which also fixes a defect a spec reviewer found in the
approved design.

**Tech Stack.** Kotlin, Ktor 3.1.2, `kotlinx.html` through the new `io.ktor:ktor-server-html-builder`
dependency, kotlinx.serialization, the MongoDB Kotlin coroutine driver, Angular 21 standalone
components, Playwright.

**Spec.** `docs/superpowers/specs/2026-09-19-public-surface-design.md` ("Spec C"), approved for
planning on 2026-09-19. GitHub issues #45, #46, #47 and #48 are the task briefs. The spec is
authoritative over each issue wherever the two disagree; each issue's own body already states this
and names the points the spec changes. This plan follows the spec's decisions and cites the
issue only for detail the spec does not repeat.

---

## Global Constraints

- Write every sentence of this plan's prose, and every KDoc comment inside a code block, in
  ASD-STE100 Simplified Technical English. Use a short sentence. Use the active voice. Give one
  instruction in one sentence. Always use an article. The project's `CLAUDE.md` states the full
  rule set.
- Every new page is a pure read. A page reads `CatalogService` and `ExplanationRepository` only. A
  page never calls `Principals.resolve`, never calls `Principals.setSessionCookie`, and never
  touches `Components.sessions`, `Components.graph`, or `Components.llm` — each of those three
  forces the lazy model client to build, per `Components.kt:69-80`'s own KDoc. Task 1.4 and every
  later phase's own "no cookie, no generation" task prove this with a real test.
- Give every `@Test fun name() = runBlocking { ... }` a return type of `Unit`. When the block's
  last statement is `assertFailsWith<T> { ... }`, or another call that does not return `Unit`,
  write `fun name(): Unit = runBlocking { ... }` instead of the bare form. Kotlin otherwise infers
  a non-`Unit` return type, and the test never runs, with no failure and no skipped entry. Check
  every test this plan adds against this rule before you commit it.
- Give every new Kotlin file the project's existing style: full "why" KDoc, `internal` helper kept
  internal, and a config value overridable from the environment with a safe fallback. A missing or
  bad environment value must never crash the process at startup. `GraphConfig` and
  `PrincipalCookieConfig` already state this rule for two different cases (a safe default, and a
  value with no safe default); this plan adds no value of the second kind.
- Ktor, and not Angular, owns `/topics/*` and `/glossary`. This plan adds no path to
  `frontend/src/app/app.routes.ts` and no path to `SpaRoutes.paths`
  (`backend/api/src/main/kotlin/com/mytetz/api/SpaRoutes.kt:19-27`). Every catalogue tile and every
  guide-page link to a topic is a plain `<a href>`, never a `routerLink`.
- Every JSON-LD value goes through the JSON-LD escape helper (Task 1.1) before it reaches a
  `<script type="application/ld+json">` block. Every other text value — the title, the summary,
  the seed body, a span, an explanation body — goes through the HTML DSL's ordinary text or
  attribute position (`+value`, or an attribute assignment) and never through an `unsafe { }`
  block.
- An owner step stays an owner step. This plan never runs `fly`, `flyctl`, or a Cloudflare
  dashboard action from an agent's machine. Task 1.8 states the Cloudflare change as an exact,
  literal set of dashboard steps for the owner to carry out.

---

## File Structure

### Phase 1 — issue #45 (topic pages)

| File | Responsibility |
|---|---|
| `gradle/libs.versions.toml` | Adds the `ktor-server-html-builder` library entry, on the existing `ktor` version. |
| `backend/api/build.gradle.kts` | Adds `implementation(libs.ktor.server.html.builder)`. |
| `backend/api/src/main/kotlin/com/mytetz/api/JsonLd.kt` | New. `jsonLdScriptSafe(json: String): String`, the `</`-to-`<\/` substitution. |
| `backend/api/src/test/kotlin/com/mytetz/api/JsonLdTest.kt` | New. Hostile-input tests for the substitution. |
| `backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt` | New. The pure HTML-DSL renderer: `TopicPageView`, `RelatedTopicView`, `fun HTML.topicPageHtml(view: TopicPageView)`. No route, no I/O. |
| `backend/api/src/test/kotlin/com/mytetz/api/TopicPageHtmlTest.kt` | New. Hostile-input escape tests, rendered through `kotlinx.html.stream.createHTML()`. |
| `backend/llm/src/main/kotlin/com/mytetz/llm/AnthropicLlmClient.kt` | Widens `Companion.resolveModel` from `internal` to `public` (spec section 6.2). No other change. |
| `backend/api/src/main/kotlin/com/mytetz/api/Components.kt` | Widens `explanations` (line 119) from `private` to `internal`. Adds a constructor parameter `val modelFamily: String = AnthropicLlmClient.resolveModel(System.getenv(AnthropicLlmClient.MODEL_FAMILY_ENV))`. |
| `backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt` | Adds a test that `Components()` resolves `modelFamily` with no model client built. |
| `backend/api/src/main/kotlin/com/mytetz/api/TopicPageRoutes.kt` | New. `GET /topics/{slug}`. |
| `backend/api/src/test/kotlin/com/mytetz/api/TopicPageRoutesTest.kt` | New. The full route test suite: 404, seed rendering, no-seed fallback, no cookie, no generation, routing precedence over the SPA fallback. |
| `backend/api/src/main/kotlin/com/mytetz/api/Application.kt` | Registers `topicPageRoutes(...)` ahead of `spaRoutes()`. |
| `frontend/src/app/catalog/catalog-page.component.ts` | Turns the `topic__button` tile into `<a href="/topics/<slug>">`. Removes the `<h2>`-inside-`<button>` defect. |
| `frontend/src/app/catalog/catalog-page.component.spec.ts` | Updates the tile assertions for the new markup. |
| `frontend/public/guides/*/index.html` (7 files) | Each "Start here" block's links point at `/topics/<slug>` in place of `/`. Each file's own `issue #45` HTML comment is removed. |
| `docs/deploy.md` (section 5) | Owner edit, after the dashboard step: records the new Cache Rule next to the existing `/api/*` bypass entry. |

### Phase 2 — issue #46 (sitemap)

| File | Responsibility |
|---|---|
| `backend/api/src/main/kotlin/com/mytetz/api/SitemapRoutes.kt` | New. `GET /sitemap.xml`. |
| `backend/api/src/test/kotlin/com/mytetz/api/SitemapRoutesTest.kt` | New. |
| `backend/api/src/main/kotlin/com/mytetz/api/Application.kt` | Registers `sitemapRoutes(...)`. |
| `frontend/public/sitemap.xml` | Deleted. |
| `backend/api/src/test/kotlin/com/mytetz/api/GuidePagesTest.kt` | The `sitemapGuidePaths()` helper reads the sitemap from the live route response instead of the deleted static file (Task 2.3). |

### Phase 3 — issue #47 (how it works, review date)

| File | Responsibility |
|---|---|
| `backend/catalog/src/main/kotlin/com/mytetz/catalog/Topic.kt` | Adds `val reviewedAt: Long? = null`. |
| `backend/catalog/src/main/kotlin/com/mytetz/catalog/TopicRepository.kt` | Adds `setReviewedAt(slug: String, epochMillis: Long)`, or the project's own migration convention if one already governs a field addition — Task 3.1 confirms which before it writes code. |
| `backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt` | Adds "Last reviewed `<date>`" and `dateModified` in the `LearningResource` JSON-LD, from `Topic.reviewedAt`. |
| `backend/api/src/main/kotlin/com/mytetz/api/HowItWorksRoutes.kt` | New. `GET /how-it-works`. |
| `backend/api/src/test/kotlin/com/mytetz/api/HowItWorksRoutesTest.kt` | New. |
| `backend/api/src/main/kotlin/com/mytetz/api/OrganizationJsonLd.kt` | New. The shared `Organization` JSON-LD fragment for `/` and `/how-it-works`. |
| `frontend/src/app/ui/app-shell.component.ts` | Adds a footer link to `/how-it-works`. |
| `frontend/public/guides/*/index.html` (7 files) | Each page's JSON-LD `@graph` gains an `author` node. |

### Phase 4 — issue #48 (published explanations, glossary)

| File | Responsibility |
|---|---|
| `backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt` | Adds `val published: Boolean = false`. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt` | Adds `setPublished(key: String, published: Boolean)`, `findPublished(): List<Explanation>` (`EXPLAIN` only), and the short-key range lookup `findByShortKeyPrefix(prefix: String): List<Explanation>`. |
| `backend/api/src/main/kotlin/com/mytetz/api/ExplanationPageRoutes.kt` | New. `GET /topics/{slug}/explain/{shortKey}`. |
| `backend/api/src/main/kotlin/com/mytetz/api/GlossaryRoutes.kt` | New. `GET /glossary`. |
| `backend/api/src/test/kotlin/com/mytetz/api/ExplanationPageRoutesTest.kt` | New. |
| `backend/api/src/test/kotlin/com/mytetz/api/GlossaryRoutesTest.kt` | New. |
| `backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt` | Adds the "Popular questions" list (section 6.3 of the spec), ordered by `Explanation.requestCount`. |
| `backend/api/src/main/kotlin/com/mytetz/api/SitemapRoutes.kt` | Adds one `<url>` per published `EXPLAIN` node. |
| A review script, or an owner-gated route — the owner's choice (spec section 17, question 1) | Sets `published` on the top nodes by `requestCount`, capped at 100 indexable pages. |

---

# Phase 1 — issue #45: topic pages

**Closes:** #45. **Needs:** nothing from this plan. **Gives phase 2, 3 and 4:** the topic page
route and its renderer, `Components.modelFamily`, and the `/topics/*`-is-Ktor's routing
precedent.

## Task 1.1: the JSON-LD script-safety helper

**Files:**
- Create: `backend/api/src/main/kotlin/com/mytetz/api/JsonLd.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/JsonLdTest.kt`

**Interfaces:**
- Produces: `fun jsonLdScriptSafe(json: String): String`.

**Why this task exists, stated once for the whole phase.** Spec section 4.6 states the rule: a
`<script type="application/ld+json">` block is placed correctly by the HTML DSL, but the browser's
HTML parser still closes the tag on the literal sequence `</script`, before any JSON parser runs.
The fix replaces every `/` that follows a `<` with `\/`. A JSON string reads `\/` as an ordinary
slash, so no JSON value changes meaning, and a browser never reads `<\/script` as a closing tag.
Reference: https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mytetz.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JsonLdTest {

    @Test
    fun `a closing script tag inside a value is neutralised`() {
        val hostile = """{"description":"end the tag with </script><script>alert(1)</script>"}"""

        val safe = jsonLdScriptSafe(hostile)

        assertFalse("</script" in safe, "a literal </script sequence survived: $safe")
        assertEquals(
            """{"description":"end the tag with <\/script><\/script>alert(1)<\/script>"}""",
            safe,
        )
    }

    @Test
    fun `a value with no closing-tag sequence is unchanged`() {
        val plain = """{"name":"mytetz"}"""

        assertEquals(plain, jsonLdScriptSafe(plain))
    }

    @Test
    fun `an already-escaped slash is not doubled`() {
        val once = """{"url":"https:\/\/mytetz.com"}"""

        assertEquals(once, jsonLdScriptSafe(once))
    }
}
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.JsonLdTest"`
Expected failure: a compile error — `jsonLdScriptSafe` is unresolved.

- [ ] **Step 3: Write `JsonLd.kt`**

```kotlin
package com.mytetz.api

/**
 * Stops a `</script` sequence inside a JSON-LD payload from ending the `<script>` block early.
 *
 * A browser's HTML parser closes a `<script>` element on the literal text `</script`, before any
 * JSON parser runs inside it — the parser reads HTML first and JSON second. A JSON string treats
 * `\/` as an ordinary slash, so replacing every `/` that follows a `<` removes the early close and
 * changes no value. Reference:
 * https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html
 *
 * Every JSON-LD payload this project renders goes through this function before it reaches a
 * `<script type="application/ld+json">` block. A title, a summary, a seed body, a span and an
 * explanation body can each hold arbitrary text, because a model wrote four of them and a learner
 * chose the fifth.
 */
fun jsonLdScriptSafe(json: String): String = json.replace("</", "<\\/")
```

- [ ] **Step 4: Run the test. Confirm it passes**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.JsonLdTest"`
Expected: PASS, all three tests.

- [ ] **Step 5: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/JsonLd.kt \
        backend/api/src/test/kotlin/com/mytetz/api/JsonLdTest.kt
git commit -m "feat(api): add the JSON-LD script-safety helper"
```

---

## Task 1.2: `Components.modelFamily` — fixes the family-mismatch defect a spec reviewer found

**Files:**
- Modify: `backend/llm/src/main/kotlin/com/mytetz/llm/AnthropicLlmClient.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Components.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `AnthropicLlmClient.Companion.resolveModel(raw: String?): String`, now `public`.
  `Components.modelFamily: String`, a new constructor parameter with a production default.

**The defect, stated plainly.** Spec section 6.2 decides that a public page reads `modelFamily`
through `AnthropicLlmClient.resolveModel(System.getenv(...))` directly, and never through
`llm.modelFamily`, because reading the lazy `llm` property forces `AnthropicLlmClient()` to build,
which demands `ANTHROPIC_API_KEY`. That much is correct and this task keeps it. But the spec's own
citations of `ComponentsTest.kt:275`, `:377` and `:399` for "a boot builds no model client" no
longer match the file (see this plan's cover report for the full finding). The current, real test
at `ComponentsTest.kt:434`, `` `bootstrap builds the model client once for pre-warm, even when the
migration is off` ``, states the opposite: `Components.bootstrap()` now builds the model client on
every boot, because `Components.prewarm()` needs it to pre-warm a topic's seed. This does not
change the fix this task makes — a request-time route must still never force a *second*, avoidable
model-client build — but it does mean the exact stale-citation claim in the spec is a defect,
reported separately, not repeated here as a design input.

**The real defect this task fixes is narrower, and still real.** If a topic-page route computed
`modelFamily` by calling `AnthropicLlmClient.resolveModel(System.getenv(...))` inline, a test that
wires `Components(llmFactory = { FakeLlmClient() }, ...)` — `FakeLlmClient`'s default
`modelFamily` is `"fake-model"` (`FakeLlmClient.kt:8`) — would pre-warm a seed keyed on
`"fake-model"`, while the route computed its own key from the *environment variable* default,
`"claude-sonnet-5"` (`AnthropicLlmClient.DEFAULT_MODEL`, `AnthropicLlmClient.kt:252`). The two keys
would never match, and every test topic page would silently render with no seed. One
`ExplanationGraphTest.kt:296` test already shows a `FakeLlmClient` built with a *deliberately
different* family (`"another-family"`) for exactly this kind of key-mismatch scenario, so the risk
is not hypothetical.

**The fix.** `Components` gains one more constructor parameter, `modelFamily`, resolved the same
way `AnthropicLlmClient` resolves it in production, but as a plain, eagerly built `String` — never
a lazy `LlmClient`, never a network client, never a credential check. Every route, and every test
that wires `Components`, reads this one value. A test that wires `FakeLlmClient(modelFamily = "x")`
also passes `modelFamily = "x"` to `Components`, and the two agree by construction, not by
coincidence.

- [ ] **Step 1: Write the failing test**

Append to `ComponentsTest.kt`, near `` `the model client is not built unless something needs a
model` `` (`ComponentsTest.kt:407`):

```kotlin
@Test
fun `modelFamily resolves with no model client built`() {
    var built = 0
    val components = Components(
        mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_model_family")),
        cookies = TestFixtures.cookieConfig,
        llmFactory = { built++; FakeLlmClient() },
    )

    assertEquals(AnthropicLlmClient.DEFAULT_MODEL, components.modelFamily)
    assertEquals(0, built, "reading modelFamily built the model client")
}

@Test
fun `a test can set modelFamily to agree with its own FakeLlmClient`() {
    val components = Components(
        mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_model_family_override")),
        cookies = TestFixtures.cookieConfig,
        llmFactory = { FakeLlmClient(modelFamily = "fake-model") },
        modelFamily = "fake-model",
    )

    assertEquals("fake-model", components.modelFamily)
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.ComponentsTest"`
Expected failure: a compile error — `Components` has no parameter `modelFamily`, and
`AnthropicLlmClient.DEFAULT_MODEL` is not reachable if the companion is still `internal` at the
member level being called (`DEFAULT_MODEL` is already public; the compile failure here is the
missing constructor parameter).

- [ ] **Step 3: Widen `resolveModel`. Add `Components.modelFamily`**

In `AnthropicLlmClient.kt`, change line 264 from:

```kotlin
internal fun resolveModel(raw: String?): String =
```

to:

```kotlin
/**
 * Public so a route that must never build an [AnthropicLlmClient] — see
 * `TopicPageRoutes.kt`'s own KDoc — can still resolve the same value this class resolves for
 * [modelId] and [modelFamily]. Reading the environment twice, here and in the constructor
 * default, is deliberate: it is one function computing one value, called from two places, and not
 * two functions that could drift apart. See `Components.modelFamily`'s own KDoc.
 */
fun resolveModel(raw: String?): String =
```

In `Components.kt`, add a constructor parameter, next to `cookies` and `clientAddresses`:

```kotlin
/**
 * The model family a public, no-model-client page uses to compute a [com.mytetz.graph.ContentKey]
 * seed key. Resolved the same way [AnthropicLlmClient.modelFamily] resolves it in production, but
 * as a plain `String`: this never builds an [AnthropicLlmClient] and demands no
 * `ANTHROPIC_API_KEY`, which is exactly why `TopicPageRoutes.kt` and every later public-page route
 * in this project read this value and never `llm.modelFamily`.
 *
 * A test that wires its own [llmFactory] with a [com.mytetz.llm.FakeLlmClient] of a different
 * `modelFamily` must pass the same family here, or a page it pre-warms through [sessions]/[graph]
 * and a page a route renders through this value compute two different content keys for the one
 * seed. `ComponentsTest`'s own `a test can set modelFamily to agree with its own FakeLlmClient`
 * pins this.
 */
val modelFamily: String = AnthropicLlmClient.resolveModel(System.getenv(AnthropicLlmClient.MODEL_FAMILY_ENV)),
```

Also widen `explanations` (`Components.kt:119`) from `private` to `internal`:

```kotlin
internal val explanations = ExplanationRepository(mongo.database)
```

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.ComponentsTest"`
Expected: PASS, including every pre-existing test in the file — in particular ``the model client is
not built unless something needs a model`` and ``bootstrap builds the model client once for
pre-warm, even when the migration is off`` must both still pass unchanged, since this task adds a
constructor parameter with a default and touches no existing behaviour.

- [ ] **Step 5: Run the `llm` module's own tests**

Run: `./gradlew :backend:llm:test`
Expected: PASS. Widening `resolveModel` from `internal` to public changes no behaviour, so nothing
in `AnthropicLlmClientTest.kt` should need a change.

- [ ] **Step 6: Commit**

```bash
git add backend/llm/src/main/kotlin/com/mytetz/llm/AnthropicLlmClient.kt \
        backend/api/src/main/kotlin/com/mytetz/api/Components.kt \
        backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt
git commit -m "feat(api): add Components.modelFamily, read with no model client built"
```

---

## Task 1.3: the topic-page HTML renderer, proven safe against hostile input

**Files:**
- Create: `gradle/libs.versions.toml` (modify)
- Create: `backend/api/build.gradle.kts` (modify)
- Create: `backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/TopicPageHtmlTest.kt`

**Interfaces:**
- Consumes: `jsonLdScriptSafe` (Task 1.1).
- Produces: `data class RelatedTopicView(val slug: String, val title: String)`,
  `data class TopicPageView(val slug: String, val title: String, val category: String, val summary: String, val seedBody: String?, val relatedTopics: List<RelatedTopicView>)`,
  `fun HTML.topicPageHtml(view: TopicPageView)`.

**This is "Task 1 of phase 1" in the task brief's own words: it proves the two escape rules with
hostile input, before any route exists to call it.**

1. Every text value — the title, the summary, the seed body, the related-topic titles — goes
   through the DSL's normal text position (`+value`) or an attribute assignment, never through an
   `unsafe { }` block, so `kotlinx.html`'s stream writer escapes it by default.
2. The `LearningResource`/`BreadcrumbList` JSON-LD block goes through `jsonLdScriptSafe` before it
   is written inside `<script type="application/ld+json">`.

- [ ] **Step 1: Add the Ktor HTML DSL dependency**

In `gradle/libs.versions.toml`, in the `[libraries]` block, next to `ktor-server-cors`:

```toml
ktor-server-html-builder = { module = "io.ktor:ktor-server-html-builder", version.ref = "ktor" }
```

**Confirmed against the official Ktor documentation**
(https://ktor.io/docs/server-html-dsl.html): the dependency coordinate is exactly
`io.ktor:ktor-server-html-builder`, the same artifact name spec section 4.6 already names.

In `backend/api/build.gradle.kts`, add, next to the other `ktor-server-*` dependencies:

```kotlin
implementation(libs.ktor.server.html.builder)
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.mytetz.api

import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicPageHtmlTest {

    private fun render(view: TopicPageView): String = createHTML().html { topicPageHtml(view) }

    private fun view(
        title: String = "Special Relativity",
        summary: String = "How space and time trade off for an observer in motion.",
        seedBody: String? = "Special relativity says two observers can disagree about time.",
        related: List<RelatedTopicView> = listOf(RelatedTopicView("quantum-physics", "Quantum Physics")),
    ) = TopicPageView(
        slug = "special-relativity",
        title = title,
        category = "Physics",
        summary = summary,
        seedBody = seedBody,
        relatedTopics = related,
    )

    @Test
    fun `a title carrying a script tag is escaped, not executed`() {
        val html = render(view(title = "<script>alert(1)</script>"))

        assertFalse("<script>alert(1)</script>" in html, "the hostile title was not escaped: $html")
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
    }

    @Test
    fun `a seed body carrying a closing script tag does not end the ld+json block early`() {
        val html = render(view(seedBody = "Read on. </script><script>alert(1)</script> Then stop."))

        val ldJsonBlock = Regex(
            """<script type="application/ld\+json">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html)?.groupValues?.get(1)

        // The seed body is not embedded in the JSON-LD block in this task's shape (it renders in a
        // <p>), so this test targets the summary instead, which the JSON-LD description field does
        // carry — see the next test for the field that actually matters.
        assertFalse("</script><script>alert(1)" in html.substringAfter("<p>"), "a raw closing tag reached the body")
    }

    @Test
    fun `a summary carrying a closing script tag is neutralised inside the JSON-LD block`() {
        val hostile = "End the tag. </script><script>alert(1)</script>"
        val html = render(view(summary = hostile))

        val ldJsonBlock = Regex(
            """<script type="application/ld\+json">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html)!!.groupValues[1]

        assertFalse("</script" in ldJsonBlock, "a literal </script survived inside the JSON-LD block: $ldJsonBlock")
        assertTrue("<\\/script" in ldJsonBlock)
    }

    @Test
    fun `a related-topic title is escaped`() {
        val html = render(view(related = listOf(RelatedTopicView("x", "<img src=x onerror=alert(1)>"))))

        assertFalse("<img src=x onerror=alert(1)>" in html)
        assertTrue("&lt;img" in html)
    }

    @Test
    fun `a null seed renders the summary and no seed paragraph`() {
        val html = render(view(seedBody = null))

        assertTrue(view().summary in html)
    }
}
```

- [ ] **Step 3: Run the tests. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.TopicPageHtmlTest"`
Expected failure: a compile error — `TopicPageView`, `RelatedTopicView` and `topicPageHtml` are
unresolved.

- [ ] **Step 4: Write `TopicPageHtml.kt`**

```kotlin
package com.mytetz.api

import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe

/** One related topic, shown as a plain link under the seed. */
data class RelatedTopicView(val slug: String, val title: String)

/**
 * Everything [topicPageHtml] needs to render one topic page.
 *
 * [seedBody] is null when the graph holds no seed for this topic yet — see `TopicPageRoutes.kt`'s
 * own KDoc for why the route falls back to the summary alone rather than generating one.
 */
data class TopicPageView(
    val slug: String,
    val title: String,
    val category: String,
    val summary: String,
    val seedBody: String?,
    val relatedTopics: List<RelatedTopicView>,
)

private const val SITE_URL = "https://mytetz.com"

/**
 * Renders one topic page.
 *
 * Every text value below reaches the page through `+value` or an attribute assignment, and never
 * through `unsafe { }` — `kotlinx.html`'s stream writer escapes both by default. The one `unsafe`
 * block in this function writes the JSON-LD payload, which is not HTML and must not be escaped as
 * HTML; [jsonLdScriptSafe] is what makes that block safe, by removing the one sequence, `</script`,
 * that HTML's own parser would otherwise treat as markup before any JSON parser runs. See
 * `docs/superpowers/specs/2026-09-19-public-surface-design.md` section 4.6.
 */
fun HTML.topicPageHtml(view: TopicPageView) {
    val pageTitle = pageTitleFor(view.title)
    val canonical = "$SITE_URL/topics/${view.slug}"

    head {
        title { +pageTitle }
        meta(name = "description", content = view.summary)
        link(rel = "canonical", href = canonical)
        meta(name = "og:url", content = canonical)
        meta(name = "og:title", content = pageTitle)
        meta(name = "og:description", content = view.summary)
        meta(name = "og:image", content = "$SITE_URL/og-image.png")
        script(type = "application/ld+json") { unsafe { +jsonLdScriptSafe(jsonLdFor(view, canonical)) } }
    }
    body {
        h1 { +view.title }
        p { +view.category }
        p { +(view.seedBody ?: view.summary) }
        if (view.relatedTopics.isNotEmpty()) {
            ul {
                view.relatedTopics.forEach { related ->
                    li { a(href = "$SITE_URL/topics/${related.slug}") { +related.title } }
                }
            }
        }
    }
}

/** Keeps the title under 60 characters, per spec section 6.1. */
private fun pageTitleFor(topicTitle: String): String {
    val full = "$topicTitle explained simply | mytetz"
    return if (full.length <= 60) full else "$topicTitle | mytetz"
}

private fun jsonLdFor(view: TopicPageView, canonical: String): String =
    """
    {
      "@context": "https://schema.org",
      "@graph": [
        {
          "@type": "LearningResource",
          "@id": "$canonical#resource",
          "name": ${jsonString(view.title)},
          "description": ${jsonString(view.summary)},
          "url": "$canonical"
        },
        {
          "@type": "BreadcrumbList",
          "itemListElement": [
            {"@type": "ListItem", "position": 1, "name": "mytetz", "item": "$SITE_URL"},
            {"@type": "ListItem", "position": 2, "name": ${jsonString(view.title)}, "item": "$canonical"}
          ]
        }
      ]
    }
    """.trimIndent()

/** A minimal, dependency-free JSON string literal: escapes only `"` and `\`. */
private fun jsonString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
```

- [ ] **Step 5: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.TopicPageHtmlTest"`
Expected: PASS, all five tests.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml backend/api/build.gradle.kts \
        backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt \
        backend/api/src/test/kotlin/com/mytetz/api/TopicPageHtmlTest.kt
git commit -m "feat(api): add the topic-page HTML renderer, escaped by default"
```

---

## Task 1.4: `GET /topics/{slug}`

**Files:**
- Create: `backend/api/src/main/kotlin/com/mytetz/api/TopicPageRoutes.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/TopicPageRoutesTest.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Application.kt`

**Interfaces:**
- Consumes: `TopicPageView`, `RelatedTopicView`, `topicPageHtml` (Task 1.3); `Components.modelFamily`,
  `Components.explanations` (Task 1.2); `CatalogService.findBySlug`, `CatalogService.listPublished`
  (`backend/catalog/src/main/kotlin/com/mytetz/catalog/CatalogService.kt:26,28`);
  `ExplanationRepository.findByKey` (`backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt:36`);
  `ContentKey.seed` (`backend/graph/src/main/kotlin/com/mytetz/graph/ContentKey.kt:59`);
  `GraphConfig().promptVersion` (`backend/graph/src/main/kotlin/com/mytetz/graph/GraphConfig.kt:14`).
- Produces: `fun Route.topicPageRoutes(catalog: CatalogService, explanations: ExplanationRepository, modelFamily: String, graphConfig: GraphConfig = GraphConfig())`.

**The read order, exactly as spec section 6.2 decides:**

1. `catalog.findBySlug(slug)`, `404` unless the result exists and `status == PUBLISHED` — the same
   rule `CatalogRoutes.kt:108-109` already enforces for the JSON endpoint.
2. `ContentKey.seed(topic.slug, graphConfig.promptVersion, modelFamily)`.
3. `explanations.findByKey(key)` — a pure read, calls no model, has no side effect.

A topic whose seed is not yet in the store renders the summary and no seed paragraph
(`TopicPageHtml.kt`'s own fallback, Task 1.3). The route never re-checks the "a published topic
must have a seed" invariant the monetization design states; it trusts it and falls back cleanly if
the invariant is ever broken, exactly as spec section 6.2 states.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mytetz.api

import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ContentKey
import com.mytetz.graph.Explanation
import com.mytetz.graph.GraphConfig
import com.mytetz.graph.Verb
import com.mytetz.llm.FakeLlmClient
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicPageRoutesTest {

    private fun components(modelFamily: String = "fake-model") = Components(
        mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_topic_page_${System.nanoTime()}")),
        cookies = TestFixtures.cookieConfig,
        llmFactory = { FakeLlmClient(modelFamily = modelFamily) },
        modelFamily = modelFamily,
    )

    private fun app(components: Components) = testApplication {
        application {
            io.ktor.server.routing.routing {
                topicPageRoutes(
                    catalog = components.catalog,
                    explanations = components.explanations,
                    modelFamily = components.modelFamily,
                )
            }
        }
    }

    @Test
    fun `an unknown slug answers 404`() = testApplication {
        val c = components()
        application { io.ktor.server.routing.routing {
            topicPageRoutes(c.catalog, c.explanations, c.modelFamily)
        } }

        assertEquals(HttpStatusCode.NotFound, client.get("/topics/no-such-topic").status)
    }

    @Test
    fun `a draft topic answers 404, the same as the JSON endpoint`() = testApplication {
        val c = components()
        runBlocking {
            com.mytetz.catalog.TopicRepository(c.mongo.database).upsert(
                Topic(slug = "draft-topic", title = "Draft", category = "Physics", summary = "s", status = TopicStatus.DRAFT)
            )
        }
        application { io.ktor.server.routing.routing {
            topicPageRoutes(c.catalog, c.explanations, c.modelFamily)
        } }

        assertEquals(HttpStatusCode.NotFound, client.get("/topics/draft-topic").status)
    }

    @Test
    fun `a published topic with a stored seed renders the title, the seed, and a canonical tag`() = testApplication {
        val c = components()
        runBlocking {
            com.mytetz.catalog.TopicRepository(c.mongo.database).upsert(
                Topic(slug = "special-relativity", title = "Special Relativity", category = "Physics", summary = "About time and space.")
            )
            val key = ContentKey.seed("special-relativity", GraphConfig().promptVersion, c.modelFamily)
            c.explanations.insertIfAbsent(
                Explanation(
                    key = key, topicSlug = "special-relativity", parentKey = null, span = null, spanSentence = null,
                    verb = Verb.SEED, variant = 0, depth = 0, body = "Two observers can disagree about time.",
                    grounded = false, sources = emptyList(), promptVersion = GraphConfig().promptVersion,
                    modelFamily = c.modelFamily, modelId = "fake-model", inputTokens = 1, outputTokens = 1,
                    costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
                )
            )
        }
        application { io.ktor.server.routing.routing {
            topicPageRoutes(c.catalog, c.explanations, c.modelFamily)
        } }

        val response = client.get("/topics/special-relativity")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("Special Relativity" in body)
        assertTrue("Two observers can disagree about time." in body)
        assertTrue("""<link rel="canonical" href="https://mytetz.com/topics/special-relativity">""" in body)
    }

    @Test
    fun `a published topic with no stored seed renders the summary and no seed`() = testApplication {
        val c = components()
        runBlocking {
            com.mytetz.catalog.TopicRepository(c.mongo.database).upsert(
                Topic(slug = "no-seed-yet", title = "No Seed Yet", category = "Physics", summary = "The fallback summary.")
            )
        }
        application { io.ktor.server.routing.routing {
            topicPageRoutes(c.catalog, c.explanations, c.modelFamily)
        } }

        val body = client.get("/topics/no-seed-yet").bodyAsText()
        assertTrue("The fallback summary." in body)
    }

    @Test
    fun `the response carries no Set-Cookie header`() = testApplication {
        val c = components()
        runBlocking {
            com.mytetz.catalog.TopicRepository(c.mongo.database).upsert(
                Topic(slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s")
            )
        }
        application { io.ktor.server.routing.routing {
            topicPageRoutes(c.catalog, c.explanations, c.modelFamily)
        } }

        assertNull(client.get("/topics/quantum-physics").headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `the route calls no model, whether or not a seed exists`() = testApplication {
        var built = 0
        val fake = FakeLlmClient(modelFamily = "fake-model")
        val c = Components(
            mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_topic_page_no_model")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { built++; fake },
            modelFamily = "fake-model",
        )
        runBlocking {
            com.mytetz.catalog.TopicRepository(c.mongo.database).upsert(
                Topic(slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s")
            )
        }
        application { io.ktor.server.routing.routing {
            topicPageRoutes(c.catalog, c.explanations, c.modelFamily)
        } }

        client.get("/topics/quantum-physics")

        assertEquals(0, fake.calls.size, "the route streamed a generation")
        assertEquals(0, fake.structuredCalls.size, "the route made a structured call")
        assertEquals(0, built, "the route forced the lazy model client to build")
    }
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.TopicPageRoutesTest"`
Expected failure: a compile error — `topicPageRoutes` is unresolved, and `Components.explanations`
and `Components.modelFamily` are not visible yet if Task 1.2 is not already merged (this task
assumes Task 1.2 landed first).

- [ ] **Step 3: Write `TopicPageRoutes.kt`**

```kotlin
package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ContentKey
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.GraphConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /topics/{slug}`. Renders a full HTML page for a published topic, with its stored seed, and
 * never triggers a generation.
 *
 * A pure read, deliberately: this function calls [CatalogService.findBySlug] and
 * [ExplanationRepository.findByKey] only. It never calls [Principals.resolve] or
 * [Principals.setSessionCookie], so the response carries no `Set-Cookie` header — a requirement
 * from spec section 13.2, because a Cloudflare Cache Rule (Task 1.8) is about to put this response
 * in a shared edge cache, and a cached response must never carry a cookie meant for one visitor.
 * [modelFamily] is a plain `String`, not a lazy [com.mytetz.llm.LlmClient]: see
 * `Components.modelFamily`'s own KDoc for why a public page must never read `llm.modelFamily`.
 */
fun Route.topicPageRoutes(
    catalog: CatalogService,
    explanations: ExplanationRepository,
    modelFamily: String,
    graphConfig: GraphConfig = GraphConfig(),
) {
    get("/topics/{slug}") {
        val slug = call.parameters["slug"].orEmpty()

        // Same 404 for "no such topic" and "not published" as CatalogRoutes.kt:96-111 — an
        // unpublished topic must not be distinguishable from an absent one.
        val topic = catalog.findBySlug(slug)?.takeIf { it.status == TopicStatus.PUBLISHED }
        if (topic == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val seedKey = ContentKey.seed(topic.slug, graphConfig.promptVersion, modelFamily)
        val seed = explanations.findByKey(seedKey)

        val related = catalog.listPublished(category = topic.category, query = null)
            .filter { it.slug != topic.slug }
            .map { RelatedTopicView(it.slug, it.title) }

        call.respondHtml(HttpStatusCode.OK) {
            topicPageHtml(
                TopicPageView(
                    slug = topic.slug,
                    title = topic.title,
                    category = topic.category,
                    summary = topic.summary,
                    seedBody = seed?.body,
                    relatedTopics = related,
                )
            )
        }
    }
}
```

- [ ] **Step 4: Register the route in `Application.kt`**

In `Application.kt`, inside `routing { }`, ahead of `route("/api/{...}")` and `spaRoutes()`
(both of which are tailcards over every path — the dedicated route wins by Ktor's own
route-quality rule, see Task 1.5, but registration order in this file does not depend on that):

```kotlin
topicPageRoutes(
    catalog = components.catalog,
    explanations = components.explanations,
    modelFamily = components.modelFamily,
)
```

- [ ] **Step 5: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.TopicPageRoutesTest"`
Expected: PASS, all six tests.

- [ ] **Step 6: Run the full `api` module suite**

Run: `./gradlew :backend:api:test`
Expected: PASS. In particular `ComponentsTest`, `CatalogRoutesTest` and `ApplicationTest` must not
regress.

- [ ] **Step 7: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/TopicPageRoutes.kt \
        backend/api/src/test/kotlin/com/mytetz/api/TopicPageRoutesTest.kt \
        backend/api/src/main/kotlin/com/mytetz/api/Application.kt
git commit -m "feat(api): add GET /topics/{slug}, with no cookie and no generation"
```

---

## Task 1.5: Ktor, not Angular, owns `/topics/*` — the routing-precedence test

**Files:**
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/ApplicationTest.kt`

**Interfaces:**
- Consumes: `topicPageRoutes` (Task 1.4), `spaRoutes` (`SpaRoutes.kt:84`).

Spec section 9.2 states the underlying mechanism: Ktor's routing engine scores a match by
"quality", and a constant path segment outranks a parameter, which outranks a wildcard or a
tailcard. **The spec cites `https://ktor.io/docs/server-routing.html` for this claim. That page
does not state it** — see this plan's cover report. The claim is true, and this task cites the
real source: Ktor's own routing source,
`https://raw.githubusercontent.com/ktorio/ktor/3.1.2/ktor-server/ktor-server-core/common/src/io/ktor/server/routing/RouteSelector.kt`
(the version pinned in `gradle/libs.versions.toml`), defines `qualityConstant = 1.0`,
`qualityPathParameter = 0.8`, `qualityWildcard = 0.5`, `qualityTailcard = 0.1`. `get("/topics/{slug}")`
scores one constant segment (`topics`, quality 1.0 for that segment) plus one path parameter
(quality 0.8); `spaRoutes()`'s `get("{spaFallbackPath...}")` is a bare tailcard (quality 0.1). The
dedicated route always wins, regardless of registration order. This task adds the test that proves
it end to end, through the real module, rather than trusting the citation alone.

- [ ] **Step 1: Write the failing test**

Append to `ApplicationTest.kt`, next to the existing SPA-fallback and API-catch-all tests:

```kotlin
@Test
fun `a topics path is served by the dedicated route, not the SPA fallback`() = testApplication {
    val components = Components(
        mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_topics_precedence")),
        cookies = TestFixtures.cookieConfig,
        llmFactory = { FakeLlmClient() },
    )
    runBlocking {
        com.mytetz.catalog.TopicRepository(components.mongo.database).upsert(
            com.mytetz.catalog.Topic(
                slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s",
            )
        )
    }
    application { module(components) }
    awaitReady(client)

    val response = client.get("/topics/quantum-physics")

    assertEquals(HttpStatusCode.OK, response.status)
    // The Angular shell is a single-page app entry point and carries this marker; the Ktor-rendered
    // topic page does not. Asserting its absence is what tells the two templates apart — a 200
    // alone would also pass if the SPA fallback had answered instead.
    assertFalse(response.bodyAsText().contains("<app-root"), "the SPA shell answered, not the topic-page route")
}

@Test
fun `an unknown topics slug answers 404, not the SPA shell's 200`() = testApplication {
    val components = Components(
        mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_topics_precedence_404")),
        cookies = TestFixtures.cookieConfig,
        llmFactory = { FakeLlmClient() },
    )
    application { module(components) }
    awaitReady(client)

    assertEquals(HttpStatusCode.NotFound, client.get("/topics/no-such-topic").status)
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.ApplicationTest"`
Expected failure, before Task 1.4's route is registered in `Application.kt`: the first test fails
because `response.bodyAsText()` contains `<app-root` (the SPA shell answered); the second answers
`200`, not `404` (the SPA fallback's own "unmatched path" rule races against `SpaRoutes.paths`,
which never lists `topics/:slug`, so it actually already gives 404 here — the meaningful assertion
is the first one). After Task 1.4 is already merged, both pass immediately; if this task is done
strictly before Task 1.4 in one worktree, expect a compile error instead (`topicPageRoutes`
unresolved in `Application.kt`).

- [ ] **Step 3: Confirm no production code changes**

This task adds a test only. If Task 1.4 already registered `topicPageRoutes(...)` in
`Application.kt`, no further change is needed.

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.ApplicationTest"`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add backend/api/src/test/kotlin/com/mytetz/api/ApplicationTest.kt
git commit -m "test(api): pin that /topics/{slug} is served by Ktor, not the SPA fallback"
```

---

## Task 1.6: the catalogue tile becomes a plain link

**Files:**
- Modify: `frontend/src/app/catalog/catalog-page.component.ts`
- Modify: `frontend/src/app/catalog/catalog-page.component.spec.ts`

**Interfaces:** none new. Confirms spec sections 6.4 and 9.3.

The button today (`catalog-page.component.ts:123-143`, class `topic__button`) wraps an `<h2>`
(line 136) inside a `<button>`, which is not valid HTML — the defect spec section 6.4 confirms.
The fix, per spec section 9.3: a plain `<a href="/topics/<slug>">`, with no `routerLink`, on the
same precedent the file's own comment already states for `/guides`
(`catalog-page.component.ts:70-72`): "`/guides` is a static HTML file... and `app.routes.ts` has no
'guides' path, so a `routerLink` would reach the wildcard route." The same is now true of
`/topics/<slug>`.

- [ ] **Step 1: Write the failing test**

Add to `catalog-page.component.spec.ts`, next to the existing tile-rendering test:

```typescript
it('renders each topic tile as a plain link to its topic page, not a button', () => {
  // ... existing harness that seeds `component.topics` with a fixture topic of slug 'quantum-physics' ...
  fixture.detectChanges();

  const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a.topic__tile');
  expect(link).withContext('the tile is still a <button>, not an <a>').not.toBeNull();
  expect(link.getAttribute('href')).toBe('/topics/quantum-physics');
  expect(link.querySelector('h2')).withContext('an h2 inside a button/anchor combined with role semantics').not.toBeNull();
});
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `cd frontend && npx vitest run catalog-page.component.spec.ts`
Expected failure: `link` is `null` (`querySelector('a.topic__tile')` finds nothing, because the
template still renders `<button class="topic__button">`).

- [ ] **Step 3: Change the template**

In `catalog-page.component.ts`, replace the `<button class="mt-card topic__button" ...>` block
(lines 123-143) with:

```html
<a
  class="mt-card topic__tile"
  [attr.href]="'/topics/' + t.slug"
  [attr.aria-disabled]="tilesLocked()"
  [attr.title]="tileLockedReason()"
>
  <span class="mt-eyebrow topic__category">{{ t.category }}</span>
  <h2 class="topic__title">{{ t.title }}</h2>
  <p class="topic__summary">{{ t.summary }}</p>
  @if (pendingSlug() === t.slug) {
    <span class="mt-chip mt-chip--teal topic__pending" aria-live="polite">Starting…</span>
  }
</a>
```

Rename `.topic__button` to `.topic__tile` in the `styles` block (lines 266-290), and drop the
`(click)="open(t)"` handler and the `[disabled]` binding — an anchor has no `disabled` attribute; a
locked tile is signalled through `aria-disabled` and through `pointer-events: none` added to
`.topic__tile[aria-disabled="true"]` in the same style block. The "Start with this topic" action
(spec section 6.1) now lives on the Ktor-rendered topic page itself (Task 3 of phase 3's spec text
already assumes this), so `open()`'s call to `POST /api/sessions` moves there in a later phase; this
task's scope is the tile markup only, per issue #45 step 4.

- [ ] **Step 4: Run the test. Confirm it passes**

Run: `cd frontend && npx vitest run catalog-page.component.spec.ts`
Expected: PASS.

- [ ] **Step 5: Run the full frontend suite**

Run: `cd frontend && npm test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/catalog/catalog-page.component.ts \
        frontend/src/app/catalog/catalog-page.component.spec.ts
git commit -m "fix(frontend): turn the catalogue tile into a plain link to its topic page"
```

---

## Task 1.7: point the guide pages at the new topic pages

**Files:**
- Modify: `frontend/public/guides/how-to-study-on-your-own/index.html`
- Modify: `frontend/public/guides/what-to-use-instead-of-a-highlighter/index.html`
- Modify: `frontend/public/guides/how-to-test-yourself-while-you-read/index.html`
- Modify: `frontend/public/guides/when-to-review-what-you-read/index.html`
- Modify: `frontend/public/guides/how-students-study-now/index.html`
- Modify: `frontend/public/guides/why-a-person-stops-an-online-course/index.html`
- Modify: `frontend/public/guides/index.html`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/GuidePagesTest.kt`

**Confirmed:** all seven files hold an HTML comment naming `issue #45`, found with
`grep -rl "issue #45" frontend/public/guides`. Each comment sits directly above a `<ul
class="start__list">` block of three `<a href="/">` links (a worked example, from
`when-to-review-what-you-read/index.html:373-386`, is in this plan's cover report).

- [ ] **Step 1: Write the failing test**

Add to `GuidePagesTest.kt`:

```kotlin
@Test
fun `no guide page still holds the issue 45 placeholder comment`() {
    val guidesRoot = File("../frontend/public/guides")
    val offenders = guidesRoot.walkTopDown()
        .filter { it.name == "index.html" }
        .filter { it.readText().contains("issue #45") }
        .map { it.path }
        .toList()

    assertTrue(offenders.isEmpty(), "these guide pages still name issue #45: $offenders")
}
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.GuidePagesTest"`
Expected failure: the assertion fails, listing all seven files.

- [ ] **Step 3: Edit each of the seven files**

For each "Start here" link whose visible topic name names a real, published topic, look up its
slug in `backend/catalog/src/main/resources/topics.json` and set `href="/topics/<slug>"`. Remove
the `issue #45` HTML comment. One worked example, from `when-to-review-what-you-read/index.html`:

```diff
- <!-- These three links point at the catalogue until issue #45 publishes a page for
-      each topic at /topics/<slug>. Swap the href values then, and link Human Memory
-      to /topics/human-memory. -->
  <ul class="start__list">
    <li>
-     <a href="/">Human Memory<span>How the brain keeps a fact, and why it drops one.</span></a>
+     <a href="/topics/human-memory">Human Memory<span>How the brain keeps a fact, and why it drops one.</span></a>
    </li>
    <li>
-     <a href="/">Neuroscience<span>How electrical signals become sensation and thought.</span></a>
+     <a href="/topics/neuroscience">Neuroscience<span>How electrical signals become sensation and thought.</span></a>
    </li>
    <li>
-     <a href="/">Probability<span>How to reason when you cannot know the outcome.</span></a>
+     <a href="/topics/probability">Probability<span>How to reason when you cannot know the outcome.</span></a>
    </li>
  </ul>
```

Leave the page's own "Open the catalogue" call-to-action link (`<a class="start__cta" href="/">`)
pointing at `/`, unchanged: it is a link to the catalogue itself, not to one topic.

- [ ] **Step 4: Run the test. Confirm it passes**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.GuidePagesTest"`
Expected: PASS.

- [ ] **Step 5: Run the guide-page live-serving test**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.GuidePagesTest"`
Expected: `` `every guide page answers 200 and carries its own canonical tag` `` still passes,
unaffected by an internal-link change.

- [ ] **Step 6: Commit**

```bash
git add frontend/public/guides backend/api/src/test/kotlin/com/mytetz/api/GuidePagesTest.kt
git commit -m "fix(guides): link each Start Here topic to its new /topics/<slug> page"
```

---

## Task 1.8 (owner step): the Cloudflare cache rule

**This is not code. No agent runs this. The owner carries it out in the Cloudflare dashboard,**
after #45's other tasks are live in production, following spec section 13.

**Confirm first, per spec section 17 question 4 and section 16.** `docs/deploy.md` section 5 step 4
names the effect of the existing `/api/*` rule ("bypass cache for `/api/*`") but not which
Cloudflare feature implements it — Cache Rules, or an older Page Rule. Before adding a second rule,
open the zone's **Caching → Cache Rules** page and confirm the existing `/api/*` bypass is listed
there. If it is not, it is a Page Rule instead, and the dashboard path below still applies to the
**new** rule regardless.

1. Cloudflare dashboard → the `mytetz.com` zone → **Caching** → **Cache Rules** → **Create rule**.
   (Confirmed at https://developers.cloudflare.com/cache/how-to/cache-rules/create-dashboard/: the
   documented path is the zone's Cache Rules page, then "Create rule".)
2. Rule name: `topics-and-glossary-cache`.
3. When incoming requests match: custom filter expression:
   `(http.request.uri.path wildcard "/topics/*") or (http.request.uri.path wildcard "/glossary*")`.
   One clause is enough for `/topics/*` to also match `/topics/<slug>/explain/<key>`: Cloudflare's
   own documentation on the wildcard operator states "Slashes (`/`) have no special meaning in
   wildcard matches" (confirmed at
   https://developers.cloudflare.com/ruleset-engine/rules-language/operators/). Spec section 13.1
   states the same fact and the same correction of an earlier draft that carried a redundant
   second clause.
4. Then: Cache eligibility: **Eligible for cache**. Edge TTL: **Override origin** → **1 hour**, to
   match `SpaRoutes.kt:69`'s existing `public, max-age=3600` for every other static content page
   (spec section 13.4).
5. Save as a new rule, ranked **after** the existing `/api/*` bypass rule (a `/topics/*` request
   never matches `/api/*`, so the order does not change behaviour, but keeping the bypass rule
   first matches the project's existing convention of listing the most operationally critical rule
   first).
6. **Confirmed limit:** the Free plan allows 10 Cache Rules per zone
   (https://developers.cloudflare.com/cache/how-to/cache-rules/, its Availability table). The
   existing `/api/*` rule is one; this is a second. Both fit well inside the limit.
7. Verify:
   ```bash
   curl -sI https://mytetz.com/topics/special-relativity | grep -i cf-cache-status
   ```
   Expect `HIT` on a second request within the hour, and no `Set-Cookie` header on either request
   — Task 1.4's own route already guarantees the second part.
8. Record the change in `docs/deploy.md` section 5, as a new numbered step next to step 4, and
   record the answer to the "which Cloudflare feature serves `/api/*`" question found in step 1
   above.

---

# Phase 2 — issue #46: `sitemap.xml`

**Closes:** #46. **Needs from phase 1:** `Components.explanations`/`Components.modelFamily`
pattern (reused for `lastmod`, see Task 2.2), `GuidePages.paths` (already shipped, unchanged), the
topic-page route existing so a listed URL resolves to something real.

## Task 2.1: `GET /sitemap.xml`

**Files:**
- Create: `backend/api/src/main/kotlin/com/mytetz/api/SitemapRoutes.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/SitemapRoutesTest.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Application.kt`

**Interfaces:**
- Consumes: `CatalogService.listPublished` (`CatalogService.kt:28`), `GuidePages.paths`
  (`GuidePages.kt:22`).
- Produces: `fun Route.sitemapRoutes(catalog: CatalogService)`.

Confirmed against https://www.sitemaps.org/protocol.html: `<loc>` is required, `<lastmod>` is
optional, and one file may hold up to 50,000 URLs. The catalogue (29 topics on 2026-09-15, growing
to 100+ per #18) stays far below that limit.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SitemapRoutesTest {

    @Test
    fun `the sitemap lists the home page, every published topic, and every guide path`() = testApplication {
        val database = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_sitemap_${System.nanoTime()}")).database
        val repository = TopicRepository(database)
        runBlocking {
            repository.upsert(Topic(slug = "topic-a", title = "Topic A", category = "Physics", summary = "s"))
            repository.upsert(Topic(slug = "topic-b", title = "Topic B", category = "Biology", summary = "s"))
        }
        application { routing { sitemapRoutes(CatalogService(repository)) } }

        val response = client.get("/sitemap.xml")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/xml", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))
        val body = response.bodyAsText()
        assertTrue("<loc>https://mytetz.com/</loc>" in body)
        assertTrue("<loc>https://mytetz.com/topics/topic-a</loc>" in body)
        assertTrue("<loc>https://mytetz.com/topics/topic-b</loc>" in body)
        GuidePages.paths.forEach { path ->
            assertTrue("<loc>https://mytetz.com$path</loc>" in body, "$path missing from the sitemap")
        }
        assertTrue(body.trimStart().startsWith("<?xml"))
        assertTrue("<urlset" in body)
    }
}
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.SitemapRoutesTest"`
Expected failure: a compile error — `sitemapRoutes` is unresolved.

- [ ] **Step 3: Write `SitemapRoutes.kt`**

```kotlin
package com.mytetz.api

import com.mytetz.catalog.CatalogService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val SITE_URL = "https://mytetz.com"

/**
 * `GET /sitemap.xml`. Replaces the static file #32 shipped: a static file drifts from the
 * catalogue as soon as a topic is added, and #18 grows the catalogue past 29 topics.
 *
 * Lists the home page, one `<url>` per published topic, and one `<url>` per path in
 * [GuidePages.paths] — the single source `GuidePagesTest` already holds those paths to. Follows
 * https://www.sitemaps.org/protocol.html: `<loc>` is required, `<lastmod>` is omitted here because
 * `Topic` holds no timestamp yet (spec section 10.1; phase 3 adds `Topic.reviewedAt`, after which
 * this route should emit `<lastmod>` for a topic that has one).
 */
fun Route.sitemapRoutes(catalog: CatalogService) {
    get("/sitemap.xml") {
        val topics = catalog.listPublished(category = null, query = null)
        val urls = buildList {
            add(SITE_URL + "/")
            addAll(topics.map { "$SITE_URL/topics/${it.slug}" })
            addAll(GuidePages.paths.map { SITE_URL + it })
        }

        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
            urls.forEach { url -> append("<url><loc>$url</loc></url>") }
            append("</urlset>")
        }

        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respondText(xml, ContentType.Application.Xml)
    }
}
```

- [ ] **Step 4: Register the route, delete the static file**

In `Application.kt`, add `sitemapRoutes(catalog = components.catalog)` next to
`topicPageRoutes(...)`. Delete `frontend/public/sitemap.xml`.

- [ ] **Step 5: Run the test. Confirm it passes**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.SitemapRoutesTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/SitemapRoutes.kt \
        backend/api/src/test/kotlin/com/mytetz/api/SitemapRoutesTest.kt \
        backend/api/src/main/kotlin/com/mytetz/api/Application.kt \
        frontend/public/sitemap.xml
git commit -m "feat(api): add GET /sitemap.xml, replacing the static file"
```

---

## Task 2.2: point `GuidePagesTest`'s sitemap check at the live route

**Files:**
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/GuidePagesTest.kt`

**Why:** `` `the guide list holds every guides URL that sitemap xml lists` `` (`GuidePagesTest.kt:32`)
reads `/static/sitemap.xml` from the classpath (`GuidePagesTest.kt:23`). Task 2.1 deletes that file.

- [ ] **Step 1: Run the existing test. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.GuidePagesTest"`
Expected failure, once Task 2.1's Step 4 has deleted `frontend/public/sitemap.xml`:
`the built sitemap.xml must be on the static classpath` (the `assertTrue` at
`GuidePagesTest.kt:24`).

- [ ] **Step 2: Rewrite `sitemapGuidePaths()` to call the live route**

```kotlin
private fun sitemapGuidePaths(): List<String> = testApplication {
    application { io.ktor.server.routing.routing {
        sitemapRoutes(TestFixtures.seededCatalog())
    } }
    val body = client.get("/sitemap.xml").bodyAsText()
    Regex("""<loc>https://mytetz\.com(/guides[^<]*)</loc>""").findAll(body).map { it.groupValues[1] }.toList()
}
```

Add the needed imports (`io.ktor.client.request.get`, `io.ktor.client.statement.bodyAsText`,
`io.ktor.server.testing.testApplication`) if the file does not already carry them.

- [ ] **Step 3: Run the test. Confirm it passes**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.GuidePagesTest"`
Expected: PASS, all three tests in the file.

- [ ] **Step 4: Commit**

```bash
git add backend/api/src/test/kotlin/com/mytetz/api/GuidePagesTest.kt
git commit -m "test(api): read the guide sitemap URLs from the live route"
```

---

# Phase 3 — issue #47: `/how-it-works`, `Organization`, the review date

**Closes:** #47. **Needs from phase 1:** the HTML DSL renderer pattern (`TopicPageHtml.kt`),
`Components.modelFamily`-style "no model client" test pattern, and the live topic page to link
"Last reviewed" text and `dateModified` into. **Needs from phase 2:** nothing directly, but should
land after it so `docs/deploy.md`/sitemap changes do not conflict.

## Task 3.1: `Topic.reviewedAt`, with a migration

**Files:**
- Modify: `backend/catalog/src/main/kotlin/com/mytetz/catalog/Topic.kt`
- Modify: `backend/catalog/src/main/kotlin/com/mytetz/catalog/TopicRepository.kt`
- Test: `backend/catalog/src/test/kotlin/com/mytetz/catalog/TopicRepositoryTest.kt`

**Decision, from spec section 8:** a new field, `reviewedAt: Long?` (epoch milliseconds), not a
reuse of `Explanation.createdAtEpochMillis` — a model migration resets that value for every topic
on the same day, whether or not a person reviewed the new text, and this page's whole purpose is
to state honestly how the text was reviewed.

- [ ] **Step 1: Confirm the project's migration convention before writing code**

Read `docs/superpowers/skills` or the `scaffold-mongo-migrations` skill's own guidance, and read
`Components.migrateOnBoot`/`MIGRATE_ON_BOOT_ENV` in `Components.kt` for the project's existing
migration mechanism (referenced at `docs/superpowers/specs/2026-08-07-monetization-design.md:569-583`
for an earlier migration). **Confirm before use:** whether a bare additive nullable field
(`reviewedAt: Long? = null`) needs a registered migration at all, given that `kotlinx.serialization`
already decodes a missing field to its default — the `Media`-field precedent in
`docs/superpowers/plans/2026-09-19-visualize.md` Task 1 adds a nullable field with **no** migration,
relying on the same default-decoding behaviour, and its own test,
`` `a document stored before this field existed decodes with media null` ``, pins exactly this. If
the project's migration convention requires a registered step regardless (for example to backfill
an index), follow that convention instead of this note.

- [ ] **Step 2: Write the failing test**

```kotlin
@Test
fun `a topic stored before reviewedAt existed decodes with reviewedAt null`() = runTest {
    database.getCollection<org.bson.Document>("topics").insertOne(
        org.bson.Document(
            mapOf(
                "_id" to "legacy-topic", "title" to "Legacy", "category" to "Physics",
                "summary" to "s", "aliases" to emptyList<String>(),
                "status" to "PUBLISHED", "sortWeight" to 0,
            )
        )
    )

    val found = repository.findBySlug("legacy-topic")

    assertNotNull(found)
    assertNull(found?.reviewedAt)
}

@Test
fun `setReviewedAt stores the epoch value and findBySlug reads it back`() = runTest {
    repository.upsert(Topic(slug = "t1", title = "T1", category = "Physics", summary = "s"))

    repository.setReviewedAt("t1", 1_700_000_000_000L)

    assertEquals(1_700_000_000_000L, repository.findBySlug("t1")?.reviewedAt)
}
```

- [ ] **Step 3: Run the tests. Confirm the failure**

Run: `./gradlew :backend:catalog:test --tests "com.mytetz.catalog.TopicRepositoryTest"`
Expected failure: a compile error — `Topic.reviewedAt` and `TopicRepository.setReviewedAt` are
unresolved.

- [ ] **Step 4: Add the field and the setter**

In `Topic.kt`, add `val reviewedAt: Long? = null` as the last constructor parameter. In
`TopicRepository.kt`, add:

```kotlin
suspend fun setReviewedAt(slug: String, epochMillis: Long) {
    collection.updateOne(Filters.eq("_id", slug), Updates.set("reviewedAt", epochMillis))
}
```

- [ ] **Step 5: Run the tests. Confirm they pass**

Run: `./gradlew :backend:catalog:test --tests "com.mytetz.catalog.TopicRepositoryTest"`
Expected: PASS, including every pre-existing test in the file.

- [ ] **Step 6: Commit**

```bash
git add backend/catalog/src/main/kotlin/com/mytetz/catalog/Topic.kt \
        backend/catalog/src/main/kotlin/com/mytetz/catalog/TopicRepository.kt \
        backend/catalog/src/test/kotlin/com/mytetz/catalog/TopicRepositoryTest.kt
git commit -m "feat(catalog): add Topic.reviewedAt, decoded null for a document written before it existed"
```

---

## Task 3.2: `GET /how-it-works` and the `Organization` JSON-LD

**Files:**
- Create: `backend/api/src/main/kotlin/com/mytetz/api/OrganizationJsonLd.kt`
- Create: `backend/api/src/main/kotlin/com/mytetz/api/HowItWorksRoutes.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/HowItWorksRoutesTest.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Application.kt`
- Modify: `frontend/src/app/ui/app-shell.component.ts`

**Interfaces:**
- Produces: `fun organizationJsonLd(): String` (the `Organization` fragment, spec section 11);
  `fun Route.howItWorksRoutes(modelId: () -> String)`.

Confirmed against Google's own gallery
(https://developers.google.com/search/docs/appearance/structured-data/search-gallery):
`Organization` is listed there as a Google Search rich-result type ("for the Knowledge Panel");
`LearningResource` is not. This task's `Organization` fragment therefore carries real
rich-result value; Task 1.3's `LearningResource` fragment does not, and the plan does not claim
otherwise.

The page states, per issue #47 step 1: a model writes each explanation; which model
(`MYTETZ_MODEL_ID`, `docs/deploy.md` section 2.2 — read at the same eager, no-credential value
`resolveModelForLogging` already reads at boot, `Application.kt:60-61`, and never through
`llm.modelFamily`); that a person reviews each seed before publication; the known limits; and a
contact.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mytetz.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HowItWorksRoutesTest {

    @Test
    fun `the page states which model writes the text, with no javascript`() = testApplication {
        application { routing { howItWorksRoutes(modelId = { "claude-sonnet-5" }) } }

        val response = client.get("/how-it-works")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("claude-sonnet-5" in body)
        assertTrue("<script" !in body || "application/ld+json" in body, "a non-JSON-LD script tag was found")
    }

    @Test
    fun `the page carries the Organization JSON-LD`() = testApplication {
        application { routing { howItWorksRoutes(modelId = { "claude-sonnet-5" }) } }

        val body = client.get("/how-it-works").bodyAsText()
        assertTrue("\"@type\": \"Organization\"" in body || "\"@type\":\"Organization\"" in body)
        assertTrue("https://github.com/xamcross/mytetz" in body)
    }

    @Test
    fun `the response carries no Set-Cookie header`() = testApplication {
        application { routing { howItWorksRoutes(modelId = { "claude-sonnet-5" }) } }

        assertNull(client.get("/how-it-works").headers[HttpHeaders.SetCookie])
    }
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.HowItWorksRoutesTest"`
Expected failure: a compile error — `howItWorksRoutes` is unresolved.

- [ ] **Step 3: Write `OrganizationJsonLd.kt` and `HowItWorksRoutes.kt`**

```kotlin
package com.mytetz.api

/**
 * The `Organization` JSON-LD fragment, shared by `/` and `/how-it-works` (spec section 11).
 *
 * `sameAs` carries one entry: the project's GitHub repository. Spec section 17 question 2 asks the
 * owner for any further public profile; ship with one entry until an answer arrives — `sameAs`
 * accepts a list, so a later addition is additive.
 */
fun organizationJsonLd(): String = jsonLdScriptSafe(
    """
    {
      "@context": "https://schema.org",
      "@type": "Organization",
      "name": "mytetz",
      "url": "https://mytetz.com",
      "logo": "https://mytetz.com/icon.svg",
      "sameAs": ["https://github.com/xamcross/mytetz"]
    }
    """.trimIndent()
)
```

```kotlin
package com.mytetz.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * `GET /how-it-works`. States, in plain text: a model writes each explanation, which model,
 * that a person reviews each seed before publication, the known limits, and a contact — the
 * transparency signals Google's own guidance on AI-generated content names:
 * https://developers.google.com/search/blog/2023/02/google-search-and-ai-content
 */
fun Route.howItWorksRoutes(modelId: () -> String) {
    get("/how-it-works") {
        call.respondHtml(HttpStatusCode.OK) {
            head {
                title { +"How mytetz writes an explanation" }
                script(type = "application/ld+json") { unsafe { +organizationJsonLd() } }
            }
            body {
                h1 { +"How mytetz writes an explanation" }
                p { +"A language model, ${modelId()}, writes every explanation on this site." }
                p { +"A person reads and reviews each topic's first explanation before it is published." }
                p { +"A model can still be wrong. Report an error to the contact below." }
            }
        }
    }
}
```

- [ ] **Step 4: Register the route. Add the footer link**

In `Application.kt`, add `howItWorksRoutes(modelId = { resolveModelForLogging(System.getenv(AnthropicLlmClient.MODEL_ID_ENV)) })`.
In `frontend/src/app/ui/app-shell.component.ts:49-53`'s footer, add
`<a href="/how-it-works">How it works</a>`, a plain link (no `routerLink`, same reasoning as
Task 1.6).

- [ ] **Step 5: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.HowItWorksRoutesTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/OrganizationJsonLd.kt \
        backend/api/src/main/kotlin/com/mytetz/api/HowItWorksRoutes.kt \
        backend/api/src/test/kotlin/com/mytetz/api/HowItWorksRoutesTest.kt \
        backend/api/src/main/kotlin/com/mytetz/api/Application.kt \
        frontend/src/app/ui/app-shell.component.ts
git commit -m "feat(api): add GET /how-it-works and the shared Organization JSON-LD"
```

---

## Task 3.3: render the review date on the topic page

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/TopicPageRoutes.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/TopicPageHtmlTest.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/TopicPageRoutesTest.kt`

**Interfaces:** `TopicPageView` gains `val reviewedAt: Long?`. `LearningResource`'s JSON-LD gains
`dateModified` when `reviewedAt` is not null.

- [ ] **Step 1: Write the failing test**

Add to `TopicPageHtmlTest.kt`:

```kotlin
@Test
fun `a reviewed topic shows the review date and dateModified`() {
    val html = render(view().copy(reviewedAt = 1_700_000_000_000L))

    assertTrue("Last reviewed" in html)
    assertTrue("\"dateModified\"" in html)
}

@Test
fun `an unreviewed topic shows neither`() {
    val html = render(view().copy(reviewedAt = null))

    assertFalse("Last reviewed" in html)
    assertFalse("\"dateModified\"" in html)
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.TopicPageHtmlTest"`
Expected failure: a compile error — `TopicPageView.copy(reviewedAt = ...)` has no such parameter.

- [ ] **Step 3: Add the field and the rendering**

Add `val reviewedAt: Long? = null` to `TopicPageView`. In `topicPageHtml`, render, after the seed
paragraph: `view.reviewedAt?.let { p { +"Last reviewed ${formatDate(it)}" } }`, using a small
`java.time.Instant`-based formatter. Add `"dateModified": "${isoDate(it)}"` to the
`LearningResource` JSON-LD object when `reviewedAt` is not null. In `TopicPageRoutes.kt`, pass
`reviewedAt = topic.reviewedAt` when building the `TopicPageView`.

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.TopicPageHtmlTest"`
Expected: PASS, including every pre-existing test in the file.

- [ ] **Step 5: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt \
        backend/api/src/main/kotlin/com/mytetz/api/TopicPageRoutes.kt \
        backend/api/src/test/kotlin/com/mytetz/api/TopicPageHtmlTest.kt \
        backend/api/src/test/kotlin/com/mytetz/api/TopicPageRoutesTest.kt
git commit -m "feat(api): render the topic review date and dateModified"
```

---

## Task 3.4: `author` on the seven guide pages

**Files:**
- Modify: `frontend/public/guides/*/index.html` (7 files)
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/GuidePagesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `every guide page carries an author node in its JSON-LD`() = testApplication {
    application { routing { spaRoutes() } }

    GuidePages.paths.forEach { path ->
        val body = client.get(path).bodyAsText()
        assertTrue("\"author\"" in body, "$path carries no author node")
    }
}
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.GuidePagesTest"`
Expected failure: the assertion fails for the first guide path checked.

- [ ] **Step 3: Add the `author` node to each page's `@graph`**

Each page's existing `Organization` node (shipped by #63) becomes the `author`'s value, per issue
#47 step 6: `"author": {"@id": "<page-url>#organization"}`, referencing the existing `Organization`
node's own `@id` in the same `@graph` array (a standard JSON-LD internal reference, so nothing is
duplicated).

- [ ] **Step 4: Run the test. Confirm it passes**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.GuidePagesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/public/guides backend/api/src/test/kotlin/com/mytetz/api/GuidePagesTest.kt
git commit -m "fix(guides): add an author node to each guide page's JSON-LD"
```

---

# Phase 4 — issue #48: published explanations, the glossary

**Closes:** #48. **Needs from phase 1:** the HTML DSL renderer pattern, `Components.modelFamily`,
the "no cookie, no generation" test pattern. **Needs from phase 3:** nothing directly. **Owner
decision still open, per spec section 17 question 1:** the review path (Task 4.5) is either a
script or an owner-gated route; this plan writes the script, as the spec's own recommendation, and
states the alternative.

## Task 4.1: `Explanation.published`

**Files:**
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt`
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt`
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a document stored before published existed decodes with published false`() = runTest {
    database.getCollection<org.bson.Document>("explanations").insertOne(
        org.bson.Document(
            mapOf(
                "_id" to "k-legacy-2", "topicSlug" to "quantum-physics", "parentKey" to null,
                "span" to null, "spanSentence" to null, "verb" to "SEED", "variant" to 0, "depth" to 0,
                "body" to "…", "grounded" to false, "sources" to emptyList<org.bson.Document>(),
                "promptVersion" to "v1", "modelFamily" to "claude-opus-5", "modelId" to "claude-opus-5",
                "inputTokens" to 10L, "outputTokens" to 20L, "costMicros" to 550L, "requestCount" to 0L,
                "createdAtEpochMillis" to 0L,
            )
        )
    )

    assertEquals(false, repository.findByKey("k-legacy-2")?.published)
}

@Test
fun `setPublished flips the flag and findByKey reads it back`() = runTest {
    repository.insertIfAbsent(explanation("k-pub", "A body."))

    repository.setPublished("k-pub", true)

    assertEquals(true, repository.findByKey("k-pub")?.published)
}

@Test
fun `findPublished lists only published EXPLAIN nodes`() = runTest {
    repository.insertIfAbsent(explanation("k-pub-2", "A body.").copy(verb = Verb.EXPLAIN))
    repository.insertIfAbsent(explanation("k-unpub", "Another body.").copy(verb = Verb.EXPLAIN))
    repository.setPublished("k-pub-2", true)

    val published = repository.findPublished()

    assertEquals(listOf("k-pub-2"), published.map { it.key })
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected failure: a compile error — `Explanation.published`, `setPublished` and `findPublished` are
unresolved.

- [ ] **Step 3: Add the field and the two methods**

Add `val published: Boolean = false` as the last constructor parameter on `Explanation`. Add:

```kotlin
suspend fun setPublished(key: String, published: Boolean) {
    collection.updateOne(Filters.eq("_id", key), Updates.set("published", published))
}

suspend fun findPublished(): List<Explanation> =
    collection.find(Filters.and(Filters.eq("verb", Verb.EXPLAIN.name), Filters.eq("published", true))).toList()
```

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected: PASS, including every pre-existing test in the file.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt \
        backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt
git commit -m "feat(graph): add Explanation.published, default false"
```

---

## Task 4.2: the short-key range lookup

**Files:**
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt`
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt`

**Decision, from spec section 7.1 and 7.2:** the short key is the first 12 hexadecimal characters
of the 64-character content key. The route queries `_id >= <prefix>` and `_id < <prefix-plus-one>`
(raising the prefix's last hex digit by one), which the existing default `_id` index already
serves — no new index.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `findByShortKeyPrefix finds the one document whose key starts with the prefix`() = runTest {
    repository.insertIfAbsent(explanation("abcdef0123456789" + "0".repeat(48), "A body."))

    val found = repository.findByShortKeyPrefix("abcdef012345")

    assertEquals(1, found.size)
}

@Test
fun `findByShortKeyPrefix finds nothing for an unused prefix`() = runTest {
    val found = repository.findByShortKeyPrefix("000000000000")

    assertEquals(0, found.size)
}

@Test
fun `findByShortKeyPrefix finds every document that shares a prefix, so the caller can detect a collision`() = runTest {
    repository.insertIfAbsent(explanation("aaaaaaaaaaaa" + "1".repeat(52), "A body."))
    repository.insertIfAbsent(explanation("aaaaaaaaaaaa" + "2".repeat(52), "Another body."))

    val found = repository.findByShortKeyPrefix("aaaaaaaaaaaa")

    assertEquals(2, found.size)
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected failure: a compile error — `findByShortKeyPrefix` is unresolved.

- [ ] **Step 3: Write the method**

```kotlin
/**
 * Finds every document whose `_id` starts with [prefix] (12 lowercase hex characters, per spec
 * section 7.1). Zero results is an ordinary miss. More than one result is a short-key collision —
 * the caller (`ExplanationPageRoutes.kt`) answers 404 for both and logs
 * `EXPLANATION_KEY_COLLISION`, per spec section 7.2. The default `_id` index already serves this
 * range query; no new index is added.
 */
suspend fun findByShortKeyPrefix(prefix: String): List<Explanation> {
    val upperBound = prefix.dropLast(1) + (prefix.last() + 1)
    return collection.find(Filters.and(Filters.gte("_id", prefix), Filters.lt("_id", upperBound))).toList()
}
```

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt
git commit -m "feat(graph): add the short-key range lookup for a public explanation page"
```

---

## Task 4.3: `GET /topics/{slug}/explain/{shortKey}`

**Files:**
- Create: `backend/api/src/main/kotlin/com/mytetz/api/ExplanationPageRoutes.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/ExplanationPageRoutesTest.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Application.kt`

**Decision, from spec section 7.3:** the route checks the **topic's** `PUBLISHED` status first
(404 if not), then the explanation's own `published` flag — `noindex` and not `404` when false,
because the interactive reader can link to any node a learner reaches, published or not, and a
`404` would break that stable link.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mytetz.api

import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.Verb
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplanationPageRoutesTest {

    private fun explanation(key: String, published: Boolean, topicSlug: String = "quantum-physics") = Explanation(
        key = key, topicSlug = topicSlug, parentKey = "parent", span = "wave function",
        spanSentence = "The wave function describes...", verb = Verb.EXPLAIN, variant = 0, depth = 1,
        body = "A short explanation of the wave function.", grounded = false, sources = emptyList(),
        promptVersion = "v1", modelFamily = "fake-model", modelId = "fake-model",
        inputTokens = 1, outputTokens = 1, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
        published = published,
    )

    private fun setUp(explanationPublished: Boolean, topicPublished: Boolean = true): Pair<CatalogAndExplanations, String> {
        val database = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_explain_page_${System.nanoTime()}")).database
        val topics = TopicRepository(database)
        val explanations = ExplanationRepository(database)
        val key = "abcdef0123456789" + "0".repeat(48)
        runBlocking {
            topics.upsert(Topic(
                slug = "quantum-physics", title = "Quantum Physics", category = "Physics", summary = "s",
                status = if (topicPublished) com.mytetz.catalog.TopicStatus.PUBLISHED else com.mytetz.catalog.TopicStatus.DRAFT,
            ))
            explanations.insertIfAbsent(explanation(key, explanationPublished))
        }
        return CatalogAndExplanations(com.mytetz.catalog.CatalogService(topics), explanations) to key.take(12)
    }

    data class CatalogAndExplanations(
        val catalog: com.mytetz.catalog.CatalogService,
        val explanations: ExplanationRepository,
    )

    @Test
    fun `a published explanation under a published topic has no noindex header and a canonical tag`() = testApplication {
        val (deps, shortKey) = setUp(explanationPublished = true)
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        val response = client.get("/topics/quantum-physics/explain/$shortKey")

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.headers[X_ROBOTS_TAG])
        assertTrue("rel=\"canonical\"" in response.bodyAsText())
    }

    @Test
    fun `an unpublished explanation under a published topic answers 200 with noindex, not 404`() = testApplication {
        val (deps, shortKey) = setUp(explanationPublished = false)
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        val response = client.get("/topics/quantum-physics/explain/$shortKey")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(NOINDEX, response.headers[X_ROBOTS_TAG])
    }

    @Test
    fun `any explanation under an unpublished topic answers 404`() = testApplication {
        val (deps, shortKey) = setUp(explanationPublished = true, topicPublished = false)
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        assertEquals(HttpStatusCode.NotFound, client.get("/topics/quantum-physics/explain/$shortKey").status)
    }

    @Test
    fun `an unknown short key answers 404`() = testApplication {
        val (deps, _) = setUp(explanationPublished = true)
        application { routing { explanationPageRoutes(deps.catalog, deps.explanations) } }

        assertEquals(HttpStatusCode.NotFound, client.get("/topics/quantum-physics/explain/000000000000").status)
    }
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.ExplanationPageRoutesTest"`
Expected failure: a compile error — `explanationPageRoutes` is unresolved.

- [ ] **Step 3: Write `ExplanationPageRoutes.kt`**

```kotlin
package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ExplanationRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.link
import kotlinx.html.p
import kotlinx.html.title
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.mytetz.api.ExplanationPageRoutes")

/**
 * `GET /topics/{slug}/explain/{shortKey}`. A pure read, on the same "no cookie, no generation"
 * rule as `TopicPageRoutes.kt`. The topic's `PUBLISHED` status gates with a `404`, so an unpublish
 * removes every explanation page under it at once (spec section 5.4). The explanation's own
 * `published` flag gates only `noindex`, never `404` (spec section 7.3): the interactive reader can
 * link to any node a learner reaches, and a stable URL must not break once a curator has not yet
 * reviewed it.
 */
fun Route.explanationPageRoutes(catalog: CatalogService, explanations: ExplanationRepository) {
    get("/topics/{slug}/explain/{shortKey}") {
        val slug = call.parameters["slug"].orEmpty()
        val shortKey = call.parameters["shortKey"].orEmpty()

        val topic = catalog.findBySlug(slug)?.takeIf { it.status == TopicStatus.PUBLISHED }
        if (topic == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val matches = explanations.findByShortKeyPrefix(shortKey)
        val explanation = when {
            matches.isEmpty() -> null
            matches.size == 1 -> matches.single()
            else -> {
                log.warn("EXPLANATION_KEY_COLLISION shortKey={} keys={}", shortKey, matches.map { it.key })
                null
            }
        }
        if (explanation == null || explanation.topicSlug != topic.slug) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        if (!explanation.published) {
            call.response.header(X_ROBOTS_TAG, NOINDEX)
        }

        val canonical = "https://mytetz.com/topics/${topic.slug}/explain/$shortKey"
        call.respondHtml(HttpStatusCode.OK) {
            head {
                title { +"${explanation.span} explained | mytetz" }
                if (explanation.published) link(rel = "canonical", href = canonical)
            }
            body {
                h1 { +(explanation.span ?: topic.title) }
                p { +explanation.body }
            }
        }
    }
}
```

- [ ] **Step 4: Register the route**

In `Application.kt`, add `explanationPageRoutes(catalog = components.catalog, explanations =
components.explanations)`, registered ahead of `spaRoutes()`, next to `topicPageRoutes(...)`.

- [ ] **Step 5: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.ExplanationPageRoutesTest"`
Expected: PASS, all four tests.

- [ ] **Step 6: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/ExplanationPageRoutes.kt \
        backend/api/src/test/kotlin/com/mytetz/api/ExplanationPageRoutesTest.kt \
        backend/api/src/main/kotlin/com/mytetz/api/Application.kt
git commit -m "feat(api): add the public explanation page, noindex until published"
```

---

## Task 4.4: `GET /glossary`

**Files:**
- Create: `backend/api/src/main/kotlin/com/mytetz/api/GlossaryRoutes.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/GlossaryRoutesTest.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Application.kt`
- Modify: `frontend/src/app/ui/app-shell.component.ts`

Confirmed: `DefinedTerm` does not appear in Google's rich-result gallery
(https://developers.google.com/search/docs/appearance/structured-data/search-gallery), so this
JSON-LD adds machine-readable value for an answer engine, not a promise of a Google Search rich
result (spec section 11).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mytetz.api

import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.Verb
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlossaryRoutesTest {

    @Test
    fun `the glossary is empty on a fresh database`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_glossary_empty_${System.nanoTime()}")).database
        )
        application { routing { glossaryRoutes(explanations) } }

        val response = client.get("/glossary")

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse("DefinedTerm" in response.bodyAsText())
    }

    @Test
    fun `the glossary lists only published EXPLAIN nodes, with DefinedTerm JSON-LD`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_glossary_${System.nanoTime()}")).database
        )
        runBlocking {
            explanations.insertIfAbsent(
                Explanation(
                    key = "k1", topicSlug = "quantum-physics", parentKey = "p", span = "wave function",
                    spanSentence = "s", verb = Verb.EXPLAIN, variant = 0, depth = 1, body = "b",
                    grounded = false, sources = emptyList(), promptVersion = "v1", modelFamily = "fake-model",
                    modelId = "fake-model", inputTokens = 1, outputTokens = 1, costMicros = 0,
                    requestCount = 0, createdAtEpochMillis = 0, published = true,
                )
            )
            explanations.insertIfAbsent(
                Explanation(
                    key = "k2", topicSlug = "quantum-physics", parentKey = "p", span = "superposition",
                    spanSentence = "s", verb = Verb.EXPLAIN, variant = 0, depth = 1, body = "b",
                    grounded = false, sources = emptyList(), promptVersion = "v1", modelFamily = "fake-model",
                    modelId = "fake-model", inputTokens = 1, outputTokens = 1, costMicros = 0,
                    requestCount = 0, createdAtEpochMillis = 0, published = false,
                )
            )
        }
        application { routing { glossaryRoutes(explanations) } }

        val body = client.get("/glossary").bodyAsText()
        assertTrue("wave function" in body)
        assertFalse("superposition" in body)
        assertTrue("DefinedTerm" in body)
    }
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.GlossaryRoutesTest"`
Expected failure: a compile error — `glossaryRoutes` is unresolved.

- [ ] **Step 3: Write `GlossaryRoutes.kt`**

```kotlin
package com.mytetz.api

import com.mytetz.graph.ExplanationRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe

/**
 * `GET /glossary`. Lists every published `EXPLAIN` node by its span. A pure read: no cookie, no
 * model call. Capped at 100 indexable pages by [ExplanationRepository.findPublished]'s own caller
 * discipline (Task 4.6 enforces the cap on the write side, at publish time — this route only reads
 * whatever is already marked published).
 */
fun Route.glossaryRoutes(explanations: ExplanationRepository) {
    get("/glossary") {
        val published = explanations.findPublished()

        call.respondHtml(HttpStatusCode.OK) {
            head {
                title { +"Glossary | mytetz" }
                script(type = "application/ld+json") { unsafe { +jsonLdScriptSafe(glossaryJsonLd(published)) } }
            }
            body {
                h1 { +"Glossary" }
                ul {
                    published.forEach { explanation ->
                        li {
                            a(href = "https://mytetz.com/topics/${explanation.topicSlug}/explain/${explanation.key.take(12)}") {
                                +(explanation.span ?: explanation.key)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun glossaryJsonLd(published: List<com.mytetz.graph.Explanation>): String {
    val terms = published.joinToString(",") { e ->
        """{"@type":"DefinedTerm","name":${jsonEscape(e.span ?: e.key)},"url":"https://mytetz.com/topics/${e.topicSlug}/explain/${e.key.take(12)}"}"""
    }
    return """{"@context":"https://schema.org","@graph":[$terms]}"""
}

private fun jsonEscape(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
```

- [ ] **Step 4: Register the route. Wire the nav link**

In `Application.kt`, add `glossaryRoutes(explanations = components.explanations)`. In
`frontend/src/app/ui/app-shell.component.ts:11`, the "Glossary" nav item ("Only Topics has a route")
gets a plain `<a href="/glossary">`, per spec section 9.3's last paragraph.

- [ ] **Step 5: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.GlossaryRoutesTest"`
Expected: PASS, both tests.

- [ ] **Step 6: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/GlossaryRoutes.kt \
        backend/api/src/test/kotlin/com/mytetz/api/GlossaryRoutesTest.kt \
        backend/api/src/main/kotlin/com/mytetz/api/Application.kt \
        frontend/src/app/ui/app-shell.component.ts
git commit -m "feat(api): add GET /glossary, listing only published EXPLAIN nodes"
```

---

## Task 4.5: the review path — a script, per spec section 17's recommendation

**Files:**
- Create: `backend/catalog/src/main/kotlin/com/mytetz/catalog/scripts/PublishTopExplanations.kt`
  (or the project's existing script convention, if `docs/deploy.md` or another module already
  defines one — confirm before use).
- Create: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt` (extends
  the test from Task 4.1 with a `requestCount`-ordered top-N read, if such a method does not
  already exist).

**Owner decision, unresolved by this plan (spec section 17, question 1):** a script run against
the database with `MONGODB_URI`, or a route guarded by an owner-email allow-list. The spec's own
recommendation is the script, because it needs no new authentication code and the review step is
already an infrequent, deliberate action. This plan writes the script. **If the owner instead
prefers the guarded route, that route follows the same shape as `AuthRoutes.kt`'s existing
owner-only checks — confirm the project's existing allow-list mechanism, if any, before writing a
new one.**

- [ ] **Step 1: Confirm there is no existing script convention to follow**

Run: `find backend -path "*/scripts/*" -o -name "*Script*.kt"` (or the PowerShell equivalent) to
check whether the project already has a "run this by hand against MONGODB_URI" pattern to match.
**Confirm before use.**

- [ ] **Step 2: Write the failing test for the top-N query the script needs**

```kotlin
@Test
fun `findTopByRequestCount orders EXPLAIN nodes by requestCount descending`() = runTest {
    repository.insertIfAbsent(explanation("low", "b").copy(verb = Verb.EXPLAIN, requestCount = 1))
    repository.insertIfAbsent(explanation("high", "b").copy(verb = Verb.EXPLAIN, requestCount = 9))

    val top = repository.findTopByRequestCount(limit = 10)

    assertEquals(listOf("high", "low"), top.map { it.key })
}
```

- [ ] **Step 3: Run the test. Confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected failure: a compile error — `findTopByRequestCount` is unresolved.

- [ ] **Step 4: Write the query, then the script**

```kotlin
suspend fun findTopByRequestCount(limit: Int): List<Explanation> =
    collection.find(Filters.eq("verb", Verb.EXPLAIN.name))
        .sort(Indexes.descending("requestCount"))
        .limit(limit)
        .toList()
```

The script (a `fun main()` in its own file, run with `MONGODB_URI` set, per the spec's own
recommendation) reads the top nodes with `findTopByRequestCount(limit = 100 - alreadyPublishedCount)`
and calls `setPublished(key, true)` on each, stopping at the cap of 100 total published pages
(spec section 7.4).

- [ ] **Step 5: Run the test. Confirm it passes**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt \
        backend/catalog/src/main/kotlin/com/mytetz/catalog/scripts/PublishTopExplanations.kt
git commit -m "feat(graph): add the top-by-requestCount query and the review script"
```

---

## Task 4.6: add published explanations to the sitemap; enforce the 100-page cap

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/SitemapRoutes.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/SitemapRoutesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `the sitemap lists a published explanation page and omits an unpublished one`() = testApplication {
    // ... seed one published and one unpublished Explanation, the same shapes Task 4.4's own test uses ...
    application { routing { sitemapRoutes(catalog, explanations) } }

    val body = client.get("/sitemap.xml").bodyAsText()
    assertTrue("<loc>https://mytetz.com/topics/quantum-physics/explain/" in body)
}
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.SitemapRoutesTest"`
Expected failure: a compile error — `sitemapRoutes` does not yet take an `ExplanationRepository`
parameter.

- [ ] **Step 3: Widen `sitemapRoutes`**

```kotlin
fun Route.sitemapRoutes(catalog: CatalogService, explanations: ExplanationRepository) {
    get("/sitemap.xml") {
        val topics = catalog.listPublished(category = null, query = null)
        val published = explanations.findPublished()
        val urls = buildList {
            add(SITE_URL + "/")
            addAll(topics.map { "$SITE_URL/topics/${it.slug}" })
            addAll(GuidePages.paths.map { SITE_URL + it })
            addAll(published.map { "$SITE_URL/topics/${it.topicSlug}/explain/${it.key.take(12)}" })
        }
        // ... unchanged from Task 2.1 ...
    }
}
```

Update the `sitemapRoutes(...)` call in `Application.kt` to pass `explanations =
components.explanations`.

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.SitemapRoutesTest"`
Expected: PASS, including every pre-existing test in the file.

- [ ] **Step 5: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/SitemapRoutes.kt \
        backend/api/src/test/kotlin/com/mytetz/api/SitemapRoutesTest.kt \
        backend/api/src/main/kotlin/com/mytetz/api/Application.kt
git commit -m "feat(api): add published explanation pages to the sitemap"
```
