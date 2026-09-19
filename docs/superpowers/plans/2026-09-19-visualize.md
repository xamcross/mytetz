# Visualize (Slice 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to run this plan task by task. A checkbox (`- [ ]`)
> marks each step.

**Goal:** Give the `VISUALIZE` verb a real answer. The model writes one short sentence and one
inline SVG diagram in one call. The server refuses a malformed or unsafe SVG. The server then looks
up one licensed image from Wikimedia Commons for the same span. The document holds the diagram
always. The document holds the image only when Commons finds one. The API sends both to the
browser. Angular shows the diagram, zoomable and with an accessible title. Angular shows the
image's attribution beside it. The verb picker gains its fifth row.

**Architecture:** `VISUALIZE` stays inside `ExplanationGraph.generate`, next to the four existing
verbs. `VISUALIZE` produces one more `Explanation` document. The document is content addressed,
immutable and cached, the same as every other verb's document. Two new pieces sit beside it in
`:backend:graph`: an allowlist SVG sanitiser, and a small port for an image lookup. The port takes
no HTTP client dependency — see Decision 6. `Explanation.media` is null for every verb but
`VISUALIZE`. The API layer widens two wire shapes to carry it. The frontend adds one interface, one
picker row, and one new renderer component.

**Tech Stack:** Kotlin, Ktor, kotlinx.serialization, the MongoDB Kotlin coroutine driver, the
Anthropic Java SDK's forced tool call (`LlmClient.structured`, already in the codebase since
slice 3), Ktor's `HttpClient(CIO)` for the Commons lookup, Angular 21 standalone components with
signals, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-01-assisted-learning-engine-design.md`, section 9
(Visualize), section 5.2 (the `Media` shape), section 11 (error handling) and section 14 (slice 4's
own line). GitHub issue #17 is the task brief. The issue is the authority where the spec and the
issue disagree. The spec gives `Media.kind` one value and asks for 40 minimum characters. The
issue's own evidence corrects both. This plan follows the issue.

---

## Decisions

The issue asks this plan to record decisions before any code. Each decision names its options, the
option that wins, and the reason. A first review round added Decision 7 and changed the winners of
Decisions 5 and 6; both changes are marked below.

### 1. Mermaid: later, or never

**Options:**
- **A.** Build Mermaid support now, alongside SVG.
- **B.** Never build Mermaid support. SVG is the only diagram format.
- **C.** Defer Mermaid. Ship SVG only in this slice. Revisit Mermaid only if the SVG path proves too
  weak.

**Winner: C.**

**Reason:** The issue's own requirements put the diagram path first, with inline SVG. The
requirements name Mermaid as "a second step, only if the SVG path proves too weak." Building both
formats now doubles the security surface: two parsers, two sanitisers, two sets of refusal tests,
for a format the project does not yet need. `Media.kind` in the spec already reserves `MERMAID` as
a value. The `DiagramKind` enum this plan adds (Decision 3) keeps that same reservation. A later
Mermaid addition needs no migration of stored documents: an old document keeps `kind: SVG`, and a
new document can carry `kind: MERMAID` on the day the team builds it.

### 2. The transport of the prose plus the SVG

**Options:**
- **A.** `llm.stream` with a delimiter: one stream, the prose first, a marker string, then the SVG
  source.
- **B.** `LlmClient.structured`, the forced tool call slice 3 added for the quiz feature.

**Winner: B.**

**Reason:** An SVG document is not usable until it is complete. A half-arrived `<path d="M10 1`
renders nothing. `docs/superpowers/plans/2026-09-15-quiz-assess.md`'s own Architecture section
makes the same argument for quiz JSON. Streaming gives no benefit for a part of the answer that
cannot render early. A delimiter also adds risk: the model must emit one marker string exactly
once, in exactly the right place, and never inside the prose or the SVG source by accident. A
forced tool call with a JSON Schema removes that risk. The model can answer only inside the two
named fields.

A second problem rules out the delimiter for this case specifically.
`ExplanationValidator.validate(rawBody, stopReason)` treats every stop reason but `"end_turn"` as a
refusal or a truncation (`ExplanationValidator.kt:49-64`). This check defends against a refusal that
arrives as a plausible-looking HTTP 200 body. A forced tool call's own successful stop reason is
`"tool_use"`, not `"end_turn"` — the quiz plan's own `AnthropicLlmClientTest` fixture,
`toolUseMessage`, pins this exact shape. `ExplanationValidator` would reject every successful
`VISUALIZE` generation, unmodified. This is not a reason to prefer the delimiter. It is a reason the
delimiter looks simpler than it is. Task 4 states the fix: the stop-reason gate is not needed on the
structured path. `LlmClient.structured` already turns "the model gave no real answer" into a thrown
`LlmStructuredOutputMissingException`, before a `StructuredResult` exists. The refusal-disguised-as-
200 failure this gate defends against cannot occur here.

**Consequence for the stream contract.** `ExplanationGraph.generate` today emits the prose as it
arrives, in `LlmChunk.Delta` pieces (`ExplanationGraph.kt:325-333`). A structured call returns once,
with the whole JSON answer. The `VISUALIZE` branch therefore emits the whole validated prose
sentence as one `GraphChunk.Delta`, once, right after the model call returns and the prose passes
validation. A client that renders every `Delta` it receives sees the sentence appear as one piece,
not word by word. This is an acceptable trade: the sentence is short, the same 25–600-character rule
as every other verb, and the SVG itself was never going to render usefully as a stream in the first
place.

### 3. The `Media` shape for a diagram plus an image

**Options:**
- **A.** Keep the spec's flat shape, one `kind` field holding `MERMAID | SVG | IMAGE`. Add a new
  combined value, such as `SVG_WITH_IMAGE`, for the case where both exist.
- **B.** Replace the flat `kind` with a container. The container holds a mandatory diagram and an
  optional image: `Media(diagram: DiagramMedia, image: ImageMedia?)`.
- **C.** Store the diagram and the image as two separate documents.

**Winner: B.**

**Reason:** The requirements ask for a diagram and an image together. One tag cannot name that
combination without a new value for every future format combination: `SVG_WITH_IMAGE`,
`MERMAID_WITH_IMAGE`, and one more value for each diagram format the project ever adds. A container
with two slots says the same thing without that growth. The diagram slot is not nullable: Task 4
rejects a `VISUALIZE` generation that produces no usable SVG, before persistence, so every stored
document carries a diagram. The image slot is nullable. This is exactly the shape the requirements'
own degradation rule needs: "when Commons is unavailable or returns nothing, Visualize serves the
diagram only." A null `image` on an already-valid document is not a second kind of failure. Option C
fails for a structural reason: `Explanation` has no edit path
(`ExplanationValidator.kt`'s own class KDoc states this). A second document would need one, to
attach an image a first pass could not find.

The spec's `Media` also lists `thumbUrl`. The requirements do not mention it. This plan drops it.
No task needs a thumbnail. A nullable field is additive. The team can add it the day a caller needs
it, with no change to a stored document.

```kotlin
// backend/graph/src/main/kotlin/com/mytetz/graph/Media.kt (new file)

enum class DiagramKind { SVG, MERMAID } // MERMAID unused until Decision 1 is revisited

@Serializable
data class DiagramMedia(val kind: DiagramKind, val source: String)

@Serializable
data class ImageMedia(
    val imageUrl: String,
    val title: String,
    val license: String,
    val attributionHtml: String,
    val commonsPageUrl: String,
)

@Serializable
data class Media(val diagram: DiagramMedia, val image: ImageMedia? = null)
```

### 4. The wire shape that carries `media` to the browser

**Options:**
- **A.** Fold `media` into the existing `explanations: Map<String, String>`. Change its value type
  to a richer object that holds both the body and the media.
- **B.** Add a second, sparse map to `SessionView`. Add a nullable field to `DoneEvent`. Both
  additions are additive.

**Winner: B.**

**Reason:** `SessionView.explanations` (`SessionRoutes.kt:63-71`) is `Map<String, String>` today.
Every existing frontend caller reads it as plain text. Option A breaks that contract for every verb,
not only `VISUALIZE`, to carry a field only one verb ever populates. Option B adds a field and
changes nothing else: `SessionView` gains `media: Map<String, MediaView> = emptyMap()`. The map
holds an entry only for a content key whose `Explanation.media` is not null. This mirrors the
sparse-by-key shape `explanations` already uses. A client that never asks for `VISUALIZE` pays
nothing for the new field.

`DoneEvent` (`SessionRoutes.kt:101-102`) needs its own field, for a separate reason: it describes
the live stream, not the session tree. A learner watching a `VISUALIZE` generation must see the
diagram the instant the generation ends. The learner must not wait for the `GET
/api/sessions/{id}` re-read the class KDoc names for the new node id
(`SessionRoutes.kt:80-83`). So `DoneEvent` gains `media: MediaView? = null`. The API layer fills this
field from the same `Explanation.media` the `Done` chunk's own document carries
(`GraphChunk.Done(chunk.explanation)`, `SessionRoutes.kt:720-723`).

```kotlin
// backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt — additive changes

@Serializable
data class DiagramMediaView(val kind: String, val source: String)

@Serializable
data class ImageMediaView(
    val imageUrl: String,
    val title: String,
    val license: String,
    val attributionHtml: String,
    val commonsPageUrl: String,
)

@Serializable
data class MediaView(val diagram: DiagramMediaView, val image: ImageMediaView? = null)

@Serializable
data class SessionView(
    val sessionId: String,
    val topicSlug: String,
    val rootNodeId: String,
    val currentNodeId: String,
    val nodes: List<NodeView>,
    val explanations: Map<String, String>,
    val media: Map<String, MediaView> = emptyMap(),
)

@Serializable
data class DoneEvent(val contentKey: String, val grounded: Boolean, val media: MediaView? = null)
```

### 5. The `PromptBuilder.VERSION` bump — revised after review

**The first review round found two problems with this decision as first written. Both are fixed
below.**

**Problem 1 — the stated cost was too small.** A bump of the shared `PromptBuilder.VERSION` does
not only orphan the seed catalogue. `ContentKey.derive` hashes one `promptVersion` string into
every non-seed explanation too (`ExplanationGraph.keyFor`, `ExplanationGraph.kt:198-211`). The
explanation cache is the product's own cost model: a repeated path answers at once and costs
nothing (`ExplanationGraph.kt:213-249`, the store-then-lock lookup). A shared-version bump turns
every previously cached explanation of every verb, at every depth, into a miss. The next learner to
retrace any existing path pays a fresh generation for it. This cost has no fixed size. It scales
with the total number of explanations the live store holds, a number this plan cannot read from a
worktree — it lives in production Mongo. The first review round is correct that the original wording
understated this.

**Problem 2 — the rejection of a verb-scoped version was wrong.** The plan first said a per-verb
version needs "a structural change to the content-addressing scheme." This claim does not hold up
against the code. `ContentKey.derive` takes `promptVersion` as one plain `String` parameter
(`ContentKey.kt:34-41`), not a value it derives on its own. `ExplanationGraph.keyFor` passes
`config.promptVersion` into it (`ExplanationGraph.kt:200,208`). `GraphConfig.promptVersion` is
itself a plain field, read once (`GraphConfig.kt:14`). Nothing here stops `keyFor` from choosing a
different string for one verb. `Explanation.promptVersion` is a plain `String` field on the stored
document (`Explanation.kt`); it records whatever value produced the key, and does not itself decide
what that value is. `SessionService.seedRequest` (`SessionService.kt:604-612`) always builds a
`Verb.SEED` request, never `VISUALIZE`, so a `VISUALIZE`-only version change cannot touch a seed key
under any design. The one test that pins a version-changes-the-key rule,
`keyFor is decided by the identity-bearing fields and nothing else`
(`ExplanationGraphTest.kt:271-307`, the assertion at lines 289-293), builds its request with the
default verb, `EXPLAIN` (`ExplanationGraphTest.kt:78`), so a `VISUALIZE`-only version change leaves
that test's own assertion true and unchanged.

**Options, re-evaluated, with the cost of each in numbers.** The catalogue holds 29 published
topics today (`backend/catalog/src/main/resources/topics.json`, 29 `title` entries). Take
$0.0105 as the cost of one generation, a figure the review round gave for this comparison.

- **A. Bump the shared `PromptBuilder.VERSION`.** Cost: at least 29 × $0.0105 ≈ $0.31 to
  regenerate every seed on the next pre-warmed boot (issue #18). Plus the unbounded cost from
  Problem 1 above: every cached non-seed explanation of every verb becomes a miss, and each one
  costs one fresh generation the next time a learner reaches it, until the store refills itself at
  the new version. This plan cannot size that second number from the code alone; it states only
  that it is real, and larger than the seed cost.
- **B. Give `VISUALIZE` its own version, independent of `PromptBuilder.VERSION`.** Cost: $0 to the
  existing cache. No seed key changes, since a seed request never carries the verb `VISUALIZE`. No
  `EXPLAIN`/`DIG_DEEPER`/`BROADER_PICTURE`/`SIDE_VIEW` key changes, since none of them reads the new
  version. The only keys the new version can ever affect are `VISUALIZE` keys, and this plan adds
  the first one; there is nothing yet to invalidate.

**Winner: B.**

**What changes to reach option B:**

- `GraphConfig` gains a second field, `visualizePromptVersion: String =
  PromptBuilder.VISUALIZE_VERSION`, the same override shape `promptVersion` already has.
- `PromptBuilder` gains `const val VISUALIZE_VERSION: String = "v1"`, a fresh, independent counter.
  `PromptBuilder.VERSION` (`"v3"`) does not change, because this task changes no text any other
  verb's prompt carries.
- `ExplanationGraph.keyFor`'s non-seed branch selects the version by verb:
  `if (request.verb == Verb.VISUALIZE) config.visualizePromptVersion else config.promptVersion`.
  A seed request can never carry `Verb.VISUALIZE`, so the seed branch needs no change.
- The old `Verb.VISUALIZE ->` line inside `PromptBuilder.instructionFor` (`PromptBuilder.kt:142-143`)
  becomes unreachable, because Task 4's new branch calls `PromptBuilder.visualizeSystem()` and
  `PromptBuilder.visualizeUser()` before it ever calls `PromptBuilder.user()` for `VISUALIZE`. A
  `when` over `Verb` must stay exhaustive, so the line cannot simply disappear. It becomes a guard
  that fails loudly on a wrong call, instead of silently sending the model a stale instruction:

  ```kotlin
  Verb.VISUALIZE -> error(
      "VISUALIZE never reaches PromptBuilder.user; see ExplanationGraph.generate " +
          "and PromptBuilder.visualizeUser"
  )
  ```
- The shared `system()` prompt (`PromptBuilder.kt:76-88`) needs no text change. Its "1 to 3
  sentences" rule and its "no markdown" rule stay exactly as written, because `VISUALIZE` no longer
  reads them.
- No coordination with issue #18 is needed for this reason. The pre-warm regenerates nothing extra,
  because no seed key moves.

### 6. The module that holds the Wikimedia Commons client — revised after review

**The first review round found the reasoning against `:backend:api` was wrong.** The plan first
argued that `:backend:graph` cannot call a client living in `:backend:api`, because
`:backend:api` depends on `:backend:graph` and not the other way around. That statement about the
dependency direction is true, and the conclusion drawn from it is not: a port defined inside
`:backend:graph` inverts nothing, because `:backend:graph` never imports the concrete client. The
codebase already uses exactly this idiom. `Reconciliation.reconcile` in `:backend:billing` takes
`fetchState: suspend (Subscription) -> FreemiusSubscriptionState?`
(`backend/billing/src/main/kotlin/com/mytetz/billing/Reconciliation.kt:97-101`). Its own KDoc states
the rule directly: "`:backend:billing` holds no HTTP client and calls Freemius for nothing on its
own" (`Reconciliation.kt:37-38`). `Components.kt` builds the real `FreemiusApiClient` and passes a
lambda: `Reconciliation.reconcile(billingRepository, limit = RECONCILE_LIMIT) { subscription ->
client.fetchState(subscription) }` (`Components.kt:354-355`). `:backend:billing`'s
`build.gradle.kts` needs no Ktor dependency for this, and `Reconciliation`'s own tests need no
`MockEngine`.

**Options:**
- **A.** `:backend:api`, beside `FreemiusApiClient`, which already depends on Ktor's client. Call it
  directly from `ExplanationGraph`.
- **B.** `:backend:graph`, beside `ExplanationGraph`. Add a new Ktor client dependency to
  `:backend:graph`.
- **C.** A port in `:backend:graph` — a plain `suspend` function type, not a class and not an HTTP
  client — with the real Ktor-backed client living in `:backend:api`. `Components` wires the two
  together with a lambda, on the model of `Reconciliation.reconcile`'s `fetchState`.

**Winner: C.**

**Reason:** Option C gives the same freedom from a dependency inversion that Option B was trying to
buy, at a lower cost: `:backend:graph` needs no Ktor dependency at all, so
`backend/graph/build.gradle.kts` needs no change for this feature. The Commons client's own tests
move to `:backend:api`, next to `FreemiusApiClientTest`, and reuse the `libs.ktor.client.mock`
dependency that module already declares — no new test dependency anywhere. `ExplanationGraphTest.kt`
needs no `MockEngine` for its Commons-degradation test (Task 7); it passes a plain lambda.

```kotlin
// backend/graph/src/main/kotlin/com/mytetz/graph/CommonsLookup.kt (new file)

/**
 * Looks up one licensed image for a span, or answers null.
 *
 * `:backend:graph` holds no HTTP client and calls Wikimedia for nothing on its own — the same rule
 * `Reconciliation.reconcile`'s own `fetchState` parameter states for Freemius
 * (`backend/billing/src/main/kotlin/com/mytetz/billing/Reconciliation.kt:37-38`). A lookup that
 * fails, and a lookup with nothing to find, both answer null. `ExplanationGraph` treats both the
 * same way: serve the diagram only.
 */
typealias CommonsLookup = suspend (span: String, ancestors: List<Ancestor>) -> ImageMedia?
```

`ExplanationGraph` gains a constructor parameter `private val commonsLookup: CommonsLookup = { _, _
-> null }`. The default keeps every existing test construction of `ExplanationGraph` compiling
unchanged. `Components.kt` builds the real client (Task 6) and passes `{ span, ancestors ->
commonsClient.findImage(span, ancestors) }` as the real `commonsLookup`.

### 7. How the browser renders the diagram — added after review

The first review round named this a second security boundary the plan had left open, and asked for
a decision with the same rigour as Decision 2.

**Options:**
- **A.** Bind the sanitised SVG source through `[innerHTML]`, and rely on Angular's own sanitiser.
- **B.** Build a `data:image/svg+xml` URL from the sanitised SVG source. Bind it to an `<img>`
  element's `src` property.
- **C.** Bind the sanitised SVG source through `[innerHTML]`, marked trusted with
  `DomSanitizer.bypassSecurityTrustHtml`, so Angular applies no sanitiser of its own.

**Winner: B.**

**Reason, with the source read for each claim:**

Option A fails outright. Angular's own HTML sanitiser excludes `<svg>` by design. Its source states
this directly: `backend/core/src/sanitization/html_sanitizer.ts` in the `angular/angular` repository
(read at `https://raw.githubusercontent.com/angular/angular/main/packages/core/src/sanitization/html_sanitizer.ts`
on 2026-09-19) builds its `VALID_ELEMENTS` allowlist from `VOID_ELEMENTS`, `BLOCK_ELEMENTS`,
`INLINE_ELEMENTS` and `OPTIONAL_END_TAG_ELEMENTS`. None of these lists names `svg`. A comment in the
file states the reason: "this currently consciously doesn't support SVG." Angular strips or mangles
an `<svg>` element bound through plain `[innerHTML]`. The diagram would not render. This confirms
the risk the first review round named: a developer who hits this dead end reaches for
`bypassSecurityTrustHtml` next, which is Option C.

Option C renders correctly, but it removes the one client-side check this content would otherwise
get: Angular's own sanitiser runs a second, independent check on any HTML content it does not
distrust outright, and `bypassSecurityTrustHtml` turns that check off for the value it marks. A
search of this repository for a `Content-Security-Policy` header
(`grep -rn "Content-Security-Policy" backend/`) finds none. So a defect in the server's own
`SvgSanitizer` (Task 2) would reach the DOM with nothing standing behind it.

Option B needs no bypass call at all, and gives a second, browser-enforced boundary behind
`SvgSanitizer`.

- Angular's own URL sanitiser allows a `data:` URL on an ordinary `[src]` binding. Its source,
  `packages/core/src/sanitization/url_sanitizer.ts` in the same repository (read at
  `https://raw.githubusercontent.com/angular/angular/main/packages/core/src/sanitization/url_sanitizer.ts`
  on 2026-09-19), matches a URL against `/^(?!javascript:)(?:[a-z0-9+.-]+:|[^&:\/?#]*(?:[\/?#]|$))/i`
  and rejects only a `javascript:` scheme. A `data:` URL matches the generic scheme branch and
  passes with no bypass call needed.
- The browser itself blocks a script and an external reference inside an SVG used as an image, not
  inlined as markup. MDN's own page on this (read at
  `https://developer.mozilla.org/en-US/docs/Web/SVG/Guides/SVG_as_an_image` on 2026-09-19) states:
  "JavaScript is disabled," and "External resources (e.g., images, stylesheets) cannot be loaded."
  The same page states these restrictions apply to an `<img>`, and do not apply to SVG "viewed
  directly" or embedded through an `<iframe>`, an `<object>` or an `<embed>` element — so the
  renderer must use `<img>` and none of those three.

This gives the defence in depth the first review round asked for, with no `Content-Security-Policy`
header in the chain: `SvgSanitizer` on the server is the first boundary, and the browser's own
image-rendering rules are the second, independent of any header this project sends.

**Consequences for Tasks 12 and 13:**

- The renderer builds `'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(source)` and binds
  it to the `<img>`'s `src`. It calls no `DomSanitizer` method.
- An `<img>` carries no `<title>` element of its own. The renderer meets the "accessible title"
  requirement with the `<img>`'s `alt` attribute instead: it parses the already-sanitised SVG string
  once, with the browser's own `DOMParser`, reads the first `<title>` element's text, and uses that
  text as `alt`. It falls back to a fixed generic word, such as "Diagram," when no `<title>` element
  is present. This step never inserts the parsed content into the live document; it only reads a
  text value out of it.
- Zoom stays vector-sharp at any scale, because the browser still renders the referenced SVG
  resource, not a fixed-resolution raster copy of it.
- `attributionHtml` is a separate problem: it comes from Wikimedia's own `extmetadata`, and any
  Commons editor can write it. The plan first called this "not learner input," as if that made it
  safe. The first review round correctly rejects that reasoning: it is untrusted third-party HTML
  either way. Task 6 now reduces it on the server, before it ever reaches `ImageMedia`, to plain
  text plus at most one `https` link, and rebuilds a small, self-authored HTML string from those two
  parts — never a pass-through of the vendor's own markup. The renderer then binds that rebuilt
  string through an ordinary `[innerHTML]` binding, with no bypass call. Unlike an `<svg>` element,
  a plain anchor and plain text sit inside Angular's supported allowlist, so Angular's own sanitiser
  is a genuine second check here, not a dead end the way Option A is for the diagram.

---

## Global Constraints

- Write every sentence of this plan's prose, and every KDoc comment in a code block, in ASD-STE100
  Simplified Technical English. Use short sentences. Use the active voice. Give one instruction in
  one sentence. Always use an article. The project's `CLAUDE.md` states the full rule set.
- Security is the main risk of this slice. The SVG sanitiser is an allowlist, not a blocklist: the
  code drops an element or an attribute that the list does not name. Task 2 states the full
  allowlist, the value rule, and the four required refusal cases.
- `ExplanationValidator`'s rule for the prose body is 25 to 600 characters
  (`ExplanationValidator.kt:28-29`). The spec's configuration table states 40 to 600 (spec section
  10.2). This plan uses 25 throughout, because the code and its own KDoc are the authority the issue
  names. `ExplanationValidator.kt:19-25`'s own comment gives the reason: 25 characters is the
  shortest a correct, complete answer can be, under this project's "1 to 3 sentences" rule.
- The Angular version is 21. (`docs/superpowers/plans/2026-09-15-quiz-assess.md`, the model this
  plan follows, states 18 in its Tech Stack line. That number is wrong for this project today. This
  plan does not repeat it.)
- The `assess` module's own boundary rule extends to this slice. `:backend:graph` may depend on
  `:backend:persistence` and `:backend:llm`. `:backend:graph` may never depend on `:backend:api`,
  `:backend:quota` or `:backend:billing`. Decision 6 keeps `:backend:graph` free of an HTTP client
  entirely, through a port.
- Give every new Kotlin file the project's existing style: full "why" KDoc, `internal` helpers kept
  internal, and every config value overridable from the environment with a safe fallback. Never let
  a bad environment value crash the process at startup. `GraphConfig` and `QuizConfig` already
  follow this rule.
- Give every `@Test fun name() = runBlocking { ... }` a return type of `Unit`. When the block's last
  statement is `assertFailsWith<T> { ... }`, or any other call that does not return `Unit`, write
  `fun name(): Unit = runBlocking { ... }` instead of the bare form. Kotlin otherwise infers a
  non-`Unit` return type, and the test never runs — with no failure and no skipped entry. Check every
  test written under this plan against this rule, before you commit it. (This module's existing
  tests use `runTest`, not `runBlocking`, so this rule mainly binds the Commons client's `MockEngine`
  tests in Task 6, which follow `FreemiusApiClientTest`'s own `runTest` pattern and so are already
  safe. Check each new test file by hand regardless.)
- No user-supplied text ever reaches the Commons search query or the SVG sanitiser as free text.
  Both receive only the highlighted span and its ancestry. `ExplanationValidator`'s own 600-character
  ceiling already bounds that text. Spec section 10.3 states the same "no free-text path into a
  prompt" rule for every other verb.

---

## File Structure

### Backend — `graph` module (modify + new)

| File | Responsibility |
|---|---|
| `backend/graph/src/main/kotlin/com/mytetz/graph/Media.kt` | New. `DiagramKind`, `DiagramMedia`, `ImageMedia`, `Media`. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt` | Adds a nullable `media: Media? = null`. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/SvgSanitizer.kt` | New. The allowlist parser and sanitiser. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/MediaValidator.kt` | New. Bounds the diagram source's size. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationValidator.kt` | Adds a `validateStructuredBody(rawBody: String): ValidationResult` overload. The overload skips the stop-reason gate (Decision 2). |
| `backend/graph/src/main/kotlin/com/mytetz/graph/CommonsLookup.kt` | New. The port — a `typealias`, no HTTP client (Decision 6). |
| `backend/graph/src/main/kotlin/com/mytetz/graph/GraphConfig.kt` | Adds `visualizePromptVersion` (Decision 5). |
| `backend/graph/src/main/kotlin/com/mytetz/graph/PromptBuilder.kt` | Adds `VISUALIZE_VERSION`, `visualizeSystem`, `visualizeUser`, `visualizeSchema`. Turns the dead `VISUALIZE` line in `instructionFor` into a guard. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationGraph.kt` | Adds the `VISUALIZE` branch to `generate`, before `repository.insertIfAbsent`. Adds the `commonsLookup` constructor parameter. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/SvgSanitizerTest.kt` | New. The four required refusal cases, the value rule, the namespace cases, and the depth bound. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/MediaValidatorTest.kt` | New. The over-size case. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt` | Adds a round-trip test with `media` set, and a decode test for a document with no `media` field. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt` | Adds the malformed-SVG test, the version-scoping tests, and the Commons-degradation test. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/PromptBuilderTest.kt` | Adds the `visualizeSchema`/`visualizeSystem` tests. Updates line 208 if the changed instruction changes the pinned word. |

### Backend — `api` module (modify + new)

| File | Responsibility |
|---|---|
| `backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt` | Adds `DiagramMediaView`, `ImageMediaView`, `MediaView`. Widens `SessionView` and `DoneEvent`. |
| `backend/api/src/main/kotlin/com/mytetz/api/CommonsClient.kt` | New. The real, Ktor-backed Wikimedia Commons client (Decision 6). |
| `backend/api/src/main/kotlin/com/mytetz/api/Components.kt` | Builds `CommonsClient` and adapts it to `CommonsLookup` with a lambda, on the model of `Reconciliation.reconcile`'s own `fetchState` parameter. |
| `backend/api/src/test/kotlin/com/mytetz/api/CommonsClientTest.kt` | New. `MockEngine`, on the model of `FreemiusApiClientTest`. |
| `backend/api/src/test/kotlin/com/mytetz/api/SessionRoutesTest.kt` | Adds a test proving `media` reaches the client in the shape Decision 4 gives. |

### Frontend

| File | Responsibility |
|---|---|
| `frontend/src/app/core/models.ts` | Adds `Media`, `DiagramMedia`, `ImageMedia`. Widens `SessionView`. |
| `frontend/src/app/ui/verb-picker.component.ts` | Adds the fifth `VISUALIZE` row. Checks `PICKER_HEIGHT` against three grid rows. |
| `frontend/src/app/ui/verb-picker.component.spec.ts` | Updates the pinned four-verb list to five. |
| `frontend/src/app/reader/media-renderer.component.ts` | New. Renders the diagram through an `<img>` with a `data:` URL (Decision 7), and the image with its attribution. |
| `frontend/src/app/reader/media-renderer.component.spec.ts` | New. Component tests. |
| `frontend/src/app/reader/focus-card.component.ts` | Shows `MediaRendererComponent` when the node in focus carries `media`. |
| `frontend/src/app/reader/reader-page.component.ts` | Changes the `VISUALIZE` word in `VERB_WORDS` (`reader-page.component.ts:435`) to agree with `trail-rail.component.ts:10`. |
| `frontend/src/app/reader/trail-rail.component.ts` | Changes the `VISUALIZE` word in `VERB_LABELS` (`trail-rail.component.ts:10`) to agree. |
| `frontend/e2e/visualize.spec.ts` | New. A stubbed Playwright run, using `stubCatalogueAndSession`, `openQuantumPhysicsSession`, `selectPhrase`, `mockExplainStream`. |

---

## Task 1: `Media` and the nullable `Explanation.media` field

**Files:**
- Create: `backend/graph/src/main/kotlin/com/mytetz/graph/Media.kt`
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt`
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt`

**Interfaces:**
- Produces: `DiagramKind` (`SVG`, `MERMAID`), `DiagramMedia(kind, source)`, `ImageMedia(imageUrl,
  title, license, attributionHtml, commonsPageUrl)`, `Media(diagram, image = null)`. Decision 3 gives
  the full code.
- Consumes: nothing new. `Explanation` gains one field: `val media: Media? = null`.

- [ ] **Step 1: Write the failing repository tests**

Append two tests to `ExplanationRepositoryTest.kt`, next to `insertIfAbsent stores and findByKey
reads it back` (`ExplanationRepositoryTest.kt:56-64`):

```kotlin
@Test
fun `a document with media round-trips through insertIfAbsent and findByKey`() = runTest {
    val withMedia = explanation("k-media", "A diagram of the phrase.").copy(
        media = Media(
            diagram = DiagramMedia(DiagramKind.SVG, "<svg viewBox=\"0 0 10 10\"></svg>"),
            image = ImageMedia(
                imageUrl = "https://upload.wikimedia.org/example.jpg",
                title = "Example.jpg",
                license = "CC BY-SA 4.0",
                attributionHtml = "By Example Author, CC BY-SA 4.0",
                commonsPageUrl = "https://commons.wikimedia.org/wiki/File:Example.jpg",
            ),
        )
    )

    repository.insertIfAbsent(withMedia)

    val found = repository.findByKey("k-media")
    assertEquals(DiagramKind.SVG, found?.media?.diagram?.kind)
    assertEquals("CC BY-SA 4.0", found?.media?.image?.license)
}

@Test
fun `a document stored before this field existed decodes with media null`() = runTest {
    // Simulates every explanation this project has stored so far: a raw document with no
    // "media" key at all, not a document whose "media" key is explicitly null.
    database.getCollection<org.bson.Document>("explanations").insertOne(
        org.bson.Document(
            mapOf(
                "_id" to "k-legacy",
                "topicSlug" to "quantum-physics",
                "parentKey" to null,
                "span" to null,
                "spanSentence" to null,
                "verb" to "SEED",
                "variant" to 0,
                "depth" to 0,
                "body" to "Quantum mechanics is…",
                "grounded" to false,
                "sources" to emptyList<org.bson.Document>(),
                "promptVersion" to "v1",
                "modelFamily" to "claude-opus-5",
                "modelId" to "claude-opus-5",
                "inputTokens" to 10L,
                "outputTokens" to 20L,
                "costMicros" to 550L,
                "requestCount" to 0L,
                "createdAtEpochMillis" to 1_700_000_000_000L,
            )
        )
    )

    val found = repository.findByKey("k-legacy")

    assertNotNull(found)
    assertNull(found?.media)
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected: a compile failure. `Media`, `DiagramMedia`, `ImageMedia` and `DiagramKind` are unresolved.
`Explanation.copy(media = ...)` has no such parameter.

- [ ] **Step 3: Write `Media.kt`. Widen `Explanation`**

Add the code from Decision 3 to `Media.kt`. In `Explanation.kt`, add `val media: Media? = null` as
the last constructor parameter, after `createdAtEpochMillis`. Every existing call site that builds
an `Explanation` by position keeps compiling.

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected: PASS, including every pre-existing test in the file. This step must not break `findByKey
returns null when absent`, or any other existing case.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/Media.kt \
        backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt
git commit -m "feat(graph): add the Media shape and a nullable Explanation.media field"
```

---

## Task 2: The allowlist SVG sanitiser — revised after review

**Files:**
- Create: `backend/graph/src/test/kotlin/com/mytetz/graph/SvgSanitizerTest.kt`
- Create: `backend/graph/src/main/kotlin/com/mytetz/graph/SvgSanitizer.kt`

**Interfaces:**
- Produces: `sealed interface SvgSanitizeResult { data class Clean(val svg: String) :
  SvgSanitizeResult; data class Refused(val reason: String) : SvgSanitizeResult }`,
  `SvgSanitizer.sanitize(rawSource: String): SvgSanitizeResult`.

**The parser is namespace-aware.** `DocumentBuilderFactory.setNamespaceAware(true)`. Every name
check in the walk below reads a node's **local name**
(`org.w3c.dom.Node.getLocalName()`), never its qualified name. This closes a bypass a plain string
check would miss: `<svg:script>` and `<x:script xmlns:x="…">` both carry the local name `script`,
whatever prefix the model writes. Every name check also folds case first, so `<SCRIPT>` and
`<ScRiPt>` match the same rule as `<script>` — XML is case-sensitive by grammar, and a model or an
adversary can pick a case a naive check misses.

**The element allowlist:** `svg`, `title`, `desc`, `defs`, `g`, `path`, `rect`, `circle`, `ellipse`,
`line`, `polyline`, `polygon`, `text`, `tspan`, `marker`, `linearGradient`, `radialGradient`, `stop`,
`clipPath`, `use`.

**Refused outright, by local name, case-folded, wherever the element appears:** `script`,
`foreignobject`, `style`. Each one ends the whole sanitisation with `Refused`, not a per-element
drop. `<script>` and `<foreignObject>` can carry executable or arbitrary content as children; a
per-element drop of the wrapper alone is not enough, since a rule that just deletes a tag and keeps
its children would let a `<script>`'s or a `<foreignObject>`'s content re-enter as a bare text node
or a re-parented element. `<style>` gets the same treatment: an SVG diagram in this project uses
presentation attributes, never an embedded stylesheet, so a `<style>` element has no legitimate use
here, and CSS carries its own history of injection tricks (an external `url()` reference, or a
CSS-based value that turns into an information leak in some renderers).

**Every other element not on the allowlist is dropped, and its children take its place.** A wrapper
this rule removes is not a threat; its safe children are real content and must survive under the
allowlisted parent.

**The attribute allowlist, checked by local name, on any allowed element:** `id`, `class`,
`viewBox`, `xmlns`, `width`, `height`, `x`, `y`, `x1`, `y1`, `x2`, `y2`, `cx`, `cy`, `r`, `rx`, `ry`,
`d`, `points`, `transform`, `fill`, `fill-opacity`, `stroke`, `stroke-width`, `stroke-linecap`,
`stroke-linejoin`, `stroke-opacity`, `opacity`, `font-family`, `font-size`, `font-weight`,
`text-anchor`, `offset`, `stop-color`, `stop-opacity`, `gradientUnits`, `gradientTransform`,
`markerWidth`, `markerHeight`, `refX`, `refY`, `orient`, `role`, `aria-label`, `aria-hidden`,
`marker-start`, `marker-mid`, `marker-end`, `clip-path`, `href`, `xlink:href`.

The first review round found the earlier list inconsistent: it allowed the elements `marker` and
`clipPath`, but not the attributes `marker-end` and `clip-path` that reference them. An arrow needs
`marker-end`. This list adds the four reference attributes above, together with the value rule
below, which is what makes them safe to allow.

**Refused by name regardless of the allowlist, on any element:** an attribute whose local name
starts with `on`, case-folded — `onload`, `onclick`, `onerror`, and every other event handler. The
check runs on the name's start, not against a fixed list of known bad names, so a future handler
name this list does not yet know still gets caught. This attribute is dropped; the check does not
refuse the whole document over it.

**The value rule.** The requirement asks this sanitiser to remove "every external reference." A name
check alone cannot do that: an allowed attribute's own value can still carry one, for example
`fill="url(https://evil.example/x)"`. So, for `fill`, `stroke`, `marker-start`, `marker-mid`,
`marker-end`, and `clip-path`: a value that contains the text `url(` passes only when it matches
`^url\(#[A-Za-z0-9_-]+\)$` exactly — a reference to an element inside the same document, by its
`id`, and nothing else. For `href` and `xlink:href`: a value passes only when it starts with `#` and
carries no other scheme marker (no `:`, no `//`). A value that fails its rule is dropped: the
attribute is removed, and the element that carried it survives. A `<use>` element that loses its
`href` this way renders nothing, which is the safe outcome, not a document-wide refusal — the same
choice this plan already makes for a dropped, unlisted element.

**A comment, a CDATA section, and a processing instruction are each dropped.** The walk visits only
`Element` and `Text` nodes. Every `Comment`, every `CDATASection`, and every
`ProcessingInstruction` node — for example `<?xml-stylesheet href="evil.css"?>`, itself an external
reference — is removed outright. A processing instruction can name an external resource on its own,
outside any element or attribute this plan's other rules check.

**Beyond the four named cases: the parser itself must refuse an entity attack.** The source is
parsed as XML before the walk runs. The parser refuses a `<!DOCTYPE` declaration outright, and
resolves no external entity and loads no external DTD.
`setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`,
`setExpandEntityReferences(false)`, `setXIncludeAware(false)`, and
`setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)` together close this. An XML parser that
allows external entities carries the classic XXE weakness: a crafted `<!DOCTYPE>` can read a file
from the server, or make the server open a network connection of the attacker's choosing. This risk
sits outside the four acceptance-criteria cases, so this plan states it separately, to keep a reader
from missing it by working from that list alone. This same rule also meets the requirement that "the
server parses the source and refuses a malformed document": a `<!DOCTYPE`-bearing or malformed
source is refused before the walk, not partly sanitised.

**A depth bound, next to Task 3's size bound.** Task 3 bounds the diagram source to 20,000
characters by default. That bound already limits the total element count to a low number of
thousands, since a bare element's own markup costs at least a handful of characters — enough for
this project's own "a handful of shapes" diagrams, and enough to bound the sanitiser's own CPU cost.
It does not bound nesting depth on its own: a document built almost entirely from one repeated,
short, self-closing pair, such as `<g></g>` seven characters at a time, can nest several thousand
levels deep inside that same 20,000-character budget. A naive recursive walk of a tree that deep can
overflow the JVM's own call stack, well before the character bound is reached. So `SvgSanitizer`
counts depth as it walks, and refuses the whole document once depth passes 40 — a generous ceiling
for any diagram this feature asks the model to draw, and far below the depth a character-count bound
alone would allow.

**The four refusal cases the acceptance criteria name, pinned to the outcome the rules above
decide:**

1. **`<script>`** — `Refused`. Matched by local name, case-folded, wherever it appears.
2. **An event handler attribute** — `Clean`, with the attribute dropped. This is a name-based
   attribute drop, not a structural threat a subtree can hide inside, so this plan drops the one
   attribute rather than refusing the whole document.
3. **An external `<image href>`** — `Clean`, with the whole `<image>` element dropped. `image` does
   not appear on the element allowlist at all: the image path in this feature is Wikimedia Commons
   (Decision 6), never an inline `<image>` element inside a diagram, so there is no legitimate case
   for the diagram source to carry one, external or not.
4. **`<foreignObject>`** — `Refused`. Matched by local name, case-folded, for the same reason as
   `<script>`: it can carry arbitrary content, including a `<script>` one level down that a
   drop-the-wrapper-keep-the-children rule would let through.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.mytetz.graph

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SvgSanitizerTest {

    @Test
    fun `a well-formed diagram passes clean`() {
        val result = SvgSanitizer.sanitize(
            """<svg viewBox="0 0 100 100"><title>A circle</title><circle cx="50" cy="50" r="40"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("<circle" in clean.svg)
    }

    @Test
    fun `a script element is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><script>alert('x')</script><circle cx="1" cy="1" r="1"/></svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a script element with a namespace prefix is refused by its local name`() {
        val result = SvgSanitizer.sanitize(
            """<svg xmlns:x="http://example.com"><x:script>alert('x')</x:script></svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `an uppercase SCRIPT element is refused the same as lowercase`() {
        val result = SvgSanitizer.sanitize("""<svg><SCRIPT>alert('x')</SCRIPT></svg>""")
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a style element is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><style>circle { fill: url(https://evil.example/x); }</style><circle cx="1" cy="1" r="1"/></svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a style attribute is dropped, not refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><circle cx="1" cy="1" r="1" style="fill:url(https://evil.example/x)"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("style" !in clean.svg)
    }

    @Test
    fun `an onload event handler is dropped, not refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg onload="alert('x')"><circle cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("onload" !in clean.svg)
    }

    @Test
    fun `an external image href is dropped with its element, not refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><image href="https://evil.example/tracker.png"/><circle cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("evil.example" !in clean.svg)
        assertTrue("<circle" in clean.svg)
    }

    @Test
    fun `a foreignObject is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><foreignObject><body xmlns="http://www.w3.org/1999/xhtml"><script>alert('x')</script></body></foreignObject></svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a fill value with an external url is dropped, a local url reference is kept`() {
        val result = SvgSanitizer.sanitize(
            """<svg><defs><linearGradient id="g1"/></defs>
               <rect fill="url(https://evil.example/x)" width="1" height="1"/>
               <rect fill="url(#g1)" width="1" height="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("evil.example" !in clean.svg)
        assertTrue("url(#g1)" in clean.svg)
    }

    @Test
    fun `a marker-end referencing a local marker is kept`() {
        val result = SvgSanitizer.sanitize(
            """<svg><defs><marker id="arrow"/></defs><path d="M0 0" marker-end="url(#arrow)"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("marker-end=\"url(#arrow)\"" in clean.svg)
    }

    @Test
    fun `a use href with a local fragment is kept, an external href is dropped`() {
        val result = SvgSanitizer.sanitize(
            """<svg><defs><circle id="c1" r="1"/></defs><use href="#c1"/><use href="https://evil.example/x.svg"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("href=\"#c1\"" in clean.svg)
        assertTrue("evil.example" !in clean.svg)
    }

    @Test
    fun `an xlink colon href is checked by the same value rule as href`() {
        val result = SvgSanitizer.sanitize(
            """<svg xmlns:xlink="http://www.w3.org/1999/xlink"><use xlink:href="https://evil.example/x.svg"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("evil.example" !in clean.svg)
    }

    @Test
    fun `a comment is dropped`() {
        val result = SvgSanitizer.sanitize("""<svg><!-- a comment --><circle cx="1" cy="1" r="1"/></svg>""")
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("comment" !in clean.svg)
    }

    @Test
    fun `a CDATA section is dropped`() {
        val result = SvgSanitizer.sanitize(
            """<svg><title><![CDATA[hello]]></title><circle cx="1" cy="1" r="1"/></svg>"""
        )
        assertIs<SvgSanitizeResult.Clean>(result)
    }

    @Test
    fun `a processing instruction is dropped`() {
        val result = SvgSanitizer.sanitize(
            """<?xml-stylesheet href="evil.css"?><svg><circle cx="1" cy="1" r="1"/></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("evil.css" !in clean.svg)
    }

    @Test
    fun `malformed XML is refused, not partially sanitised`() {
        val result = SvgSanitizer.sanitize("""<svg><circle cx="1" cy="1" r="1"></svg>""")
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `a DOCTYPE declaration is refused outright`() {
        val result = SvgSanitizer.sanitize(
            """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><svg>&xxe;</svg>"""
        )
        assertIs<SvgSanitizeResult.Refused>(result)
    }

    @Test
    fun `an element not on the allowlist is dropped, its safe children survive`() {
        val result = SvgSanitizer.sanitize(
            """<svg><a href="https://example.com"><circle cx="1" cy="1" r="1"/></a></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("<circle" in clean.svg)
        assertTrue("href" !in clean.svg)
    }

    @Test
    fun `nesting past the depth bound is refused`() {
        val nested = "<g>".repeat(41) + "<circle cx=\"1\" cy=\"1\" r=\"1\"/>" + "</g>".repeat(41)
        val result = SvgSanitizer.sanitize("<svg>$nested</svg>")
        assertIs<SvgSanitizeResult.Refused>(result)
    }
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.SvgSanitizerTest"`
Expected: a compile failure. `SvgSanitizer` and `SvgSanitizeResult` are unresolved.

- [ ] **Step 3: Write `SvgSanitizer.kt`**

Parse with a namespace-aware `javax.xml.parsers.DocumentBuilderFactory`, configured as this task
states above. Walk the resulting `Document` recursively, tracking depth. At each node: if depth
exceeds 40, return `Refused`. If the node is a `Comment`, a `CDATASection`, or a
`ProcessingInstruction`, remove it. If the node is an `Element` whose local name, case-folded, is
`script`, `foreignobject` or `style`, return `Refused`. If the node is an `Element` not on the
element allowlist, remove it and re-parent its children. Otherwise keep the element, and filter its
attributes: drop an attribute whose local name starts with `on`, case-folded; keep an attribute on
the attribute allowlist only when its value passes the value rule that applies to it; drop every
other attribute. Serialize the surviving `Document` with `javax.xml.transform.TransformerFactory`,
also configured with `FEATURE_SECURE_PROCESSING`, and return `Clean(serialized)`. Catch a parse
exception and return `Refused(message)`.

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.SvgSanitizerTest"`
Expected: PASS, every test.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/SvgSanitizer.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/SvgSanitizerTest.kt
git commit -m "feat(graph): add the allowlist SVG sanitiser"
```

---

## Task 3: `MediaValidator` — the diagram source size bound

**Files:**
- Create: `backend/graph/src/test/kotlin/com/mytetz/graph/MediaValidatorTest.kt`
- Create: `backend/graph/src/main/kotlin/com/mytetz/graph/MediaValidator.kt`

**Interfaces:**
- Produces: `MediaValidator(maxSourceChars: Int = MediaValidator.DEFAULT_MAX_SOURCE_CHARS)`,
  `.validate(source: String): ValidationResult`. This reuses the `ValidationResult` sealed type from
  `ExplanationValidator.kt`, rather than a second type of the same shape.

A diagram is a handful of simple shapes, not an illustration. A legitimate SVG this project asks the
model for is a few thousand characters at most. `DEFAULT_MAX_SOURCE_CHARS` defaults to `20_000`, and
is overridable from the environment with a safe fallback, the rule every config value in this
project follows. Task 2 explains why this bound is enough for total element count, and why it is not
enough for nesting depth on its own — Task 2's own depth bound covers that gap.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mytetz.graph

import kotlin.test.Test
import kotlin.test.assertIs

class MediaValidatorTest {

    private val validator = MediaValidator(maxSourceChars = 10)

    @Test
    fun `a source within the limit is valid`() {
        assertIs<ValidationResult.Valid>(validator.validate("<svg/>"))
    }

    @Test
    fun `a source over the limit is invalid`() {
        assertIs<ValidationResult.Invalid>(validator.validate("<svg>" + "x".repeat(20) + "</svg>"))
    }
}
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.MediaValidatorTest"`
Expected: a compile failure. `MediaValidator` is unresolved.

- [ ] **Step 3: Write `MediaValidator.kt`**

Write one method. Return `ValidationResult.Invalid("diagram source longer than $maxSourceChars
characters")` when `source.length > maxSourceChars`. Return `ValidationResult.Valid(source)`
otherwise.

- [ ] **Step 4: Run the test. Confirm it passes**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.MediaValidatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/MediaValidator.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/MediaValidatorTest.kt
git commit -m "feat(graph): add the media source size validator"
```

---

## Task 4: The `VISUALIZE` branch in `ExplanationGraph.generate` — revised after review

**Files:**
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationValidator.kt`
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/GraphConfig.kt`
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationGraph.kt`
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt`

**Interfaces:**
- Consumes: `Media`, `DiagramMedia`, `DiagramKind` (Task 1); `SvgSanitizer.sanitize` (Task 2);
  `MediaValidator.validate` (Task 3); `CommonsLookup` (Decision 6); `LlmClient.structured`,
  `StructuredRequest`, `StructuredResult` (already in `:backend:llm`).
- Produces: `ExplanationValidator.validateStructuredBody(rawBody: String): ValidationResult`;
  `GraphConfig.visualizePromptVersion` (Decision 5).

**Step 0 — the validator fix Decision 2 requires.** `ExplanationValidator.validate` checks
`stopReason` first (`ExplanationValidator.kt:49-64`), because a refusal or a truncation can arrive
looking like a plausible `end_turn`-shaped body on the streaming path. A forced tool call cannot
arrive that way: `LlmClient.structured` either returns a `StructuredResult`, because the model filled
the tool's required fields, or it throws `LlmStructuredOutputMissingException`. There is no third,
silent case for a stop-reason gate to catch. Extract the length, tag and refusal-prefix checks — the
body of the function from `val body = rawBody.trim()` at `ExplanationValidator.kt:66` onward — into
a `private fun checkBody(rawBody: String): ValidationResult`. Keep `validate(rawBody, stopReason)`
calling it after its own gate, unchanged. Add `fun validateStructuredBody(rawBody: String):
ValidationResult = checkBody(rawBody)` for the `VISUALIZE` path.

**Step 0b — `GraphConfig` gains a second version field (Decision 5).**

```kotlin
data class GraphConfig(
    val promptVersion: String = PromptBuilder.VERSION,
    val visualizePromptVersion: String = PromptBuilder.VISUALIZE_VERSION,
    val maxOutputTokens: Long = resolveMaxOutputTokens(System.getenv(MAX_OUTPUT_TOKENS_ENV)),
    val effort: LlmEffort = resolveEffort(System.getenv(EFFORT_ENV)),
) { /* companion unchanged */ }
```

- [ ] **Step 1: Write the failing malformed-SVG test**

Append to `ExplanationGraphTest.kt`, near the other validation-failure tests (`a body the validator
rejects is not persisted`, `ExplanationGraphTest.kt:627`):

```kotlin
@Test
fun `a malformed SVG from the model fails generation and persists nothing`() = runTest {
    llm.nextStructuredJson = """{"explanation":"A short valid sentence about the span.","svg":"<svg><circle cx=\"1\" cy=\"1\" r=\"1\"></svg>"}"""
    val visualizeRequest = request(verb = Verb.VISUALIZE)
    val key = graph.keyFor(visualizeRequest)

    assertFailsWith<GenerationFailedException> {
        graph.getOrGenerate(visualizeRequest).toList()
    }

    assertNull(repository.findByKey(key))
}

@Test
fun `a VISUALIZE key moves when visualizePromptVersion changes, and not when promptVersion changes`() {
    val visualizeRequest = request(verb = Verb.VISUALIZE)
    val key = graph.keyFor(visualizeRequest)

    assertNotEquals(
        key,
        graphWith(config = config.copy(visualizePromptVersion = "vNext")).keyFor(visualizeRequest),
        "bumping visualizePromptVersion must move a VISUALIZE key",
    )
    assertEquals(
        key,
        graphWith(config = config.copy(promptVersion = "vNext")).keyFor(visualizeRequest),
        "bumping the shared promptVersion must not move a VISUALIZE key",
    )
}

@Test
fun `an EXPLAIN key does not move when visualizePromptVersion changes`() {
    val explainRequest = request(verb = Verb.EXPLAIN)
    val key = graph.keyFor(explainRequest)

    assertEquals(
        key,
        graphWith(config = config.copy(visualizePromptVersion = "vNext")).keyFor(explainRequest),
        "bumping visualizePromptVersion must not move any other verb's key",
    )
}
```

(`llm` is `FakeLlmClient` (`ExplanationGraphTest.kt:3,44`). `nextStructuredJson` is its own field for
a `structured` fixture, the shape `nextBody` already gives `stream`. The malformed SVG above is
deliberately unbalanced — a missing `</circle>` closer — matching `SvgSanitizerTest`'s own `malformed
XML is refused` case.)

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationGraphTest"`
Expected before this task's code: a compile failure, or an uncaught assertion. `generate` still
calls `llm.stream` for every verb today, and never reads `nextStructuredJson`.

- [ ] **Step 3: Add the `VISUALIZE` branch**

In `ExplanationGraph.generate` (`ExplanationGraph.kt:295-421`), branch on `request.verb` before the
existing `llm.stream(...)` call:

```kotlin
if (request.verb == Verb.VISUALIZE) {
    val structured = try {
        llm.structured(
            StructuredRequest(
                system = PromptBuilder.visualizeSystem(),
                userPrompt = PromptBuilder.visualizeUser(
                    PromptContext(
                        topicTitle = request.topicTitle,
                        ancestors = request.ancestors,
                        span = request.span,
                        spanSentence = request.spanSentence,
                        verb = request.verb,
                    )
                ),
                toolName = PromptBuilder.VISUALIZE_TOOL_NAME,
                toolDescription = PromptBuilder.VISUALIZE_TOOL_DESCRIPTION,
                inputSchema = PromptBuilder.visualizeSchema(),
                requiredFields = listOf("explanation", "svg"),
                maxTokens = config.maxOutputTokens,
                effort = config.effort,
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw GenerationFailedException("visualize call failed for $key", e)
    }

    val costMicros = Pricing.costMicros(llm.modelId, structured.usage)
    emit(GraphChunk.Spent(costMicros))

    val answer = Json.decodeFromString<VisualizeAnswer>(structured.json)

    val validatedBody = when (val r = validator.validateStructuredBody(answer.explanation)) {
        is ValidationResult.Valid -> r.body
        is ValidationResult.Invalid ->
            throw GenerationFailedException("invalid visualize prose for $key: ${r.reason}")
    }

    val sanitized = when (val r = SvgSanitizer.sanitize(answer.svg)) {
        is SvgSanitizeResult.Clean -> r.svg
        is SvgSanitizeResult.Refused ->
            throw GenerationFailedException("refused visualize SVG for $key: ${r.reason}")
    }

    val validatedSvg = when (val r = MediaValidator().validate(sanitized)) {
        is ValidationResult.Valid -> r.body
        is ValidationResult.Invalid ->
            throw GenerationFailedException("visualize SVG too large for $key: ${r.reason}")
    }

    // The lookup happens here, before the document is built. A failed lookup, and an empty
    // lookup, both answer null — see Decision 6's own CommonsLookup KDoc. This is degradation,
    // not a generation failure: image stays null, and the diagram-only document is still
    // persisted. Task 7's test states the contract.
    val image = runCatching { commonsLookup(request.span, request.ancestors) }.getOrNull()

    emit(GraphChunk.Delta(validatedBody))

    val explanation = Explanation(
        key = key,
        topicSlug = request.topicSlug,
        parentKey = request.parentKey,
        span = request.span,
        spanSentence = request.spanSentence,
        verb = request.verb,
        variant = request.variant,
        depth = request.depth,
        body = validatedBody,
        media = Media(diagram = DiagramMedia(DiagramKind.SVG, validatedSvg), image = image),
        grounded = false,
        sources = emptyList(),
        promptVersion = config.visualizePromptVersion,
        modelFamily = llm.modelFamily,
        modelId = llm.modelId,
        inputTokens = structured.usage.inputTokens,
        outputTokens = structured.usage.outputTokens,
        costMicros = costMicros,
        requestCount = 0,
        createdAtEpochMillis = clock(),
    )

    val winner = repository.insertIfAbsent(explanation)
    if (winner.body != explanation.body) emit(GraphChunk.Superseded(winner.body))
    return GraphChunk.Done(winner)
}
```

`VisualizeAnswer` is a small `@Serializable data class VisualizeAnswer(val explanation: String, val
svg: String)`, added beside `PromptBuilder.kt`. `keyFor` (Decision 5) must select
`config.visualizePromptVersion` for a `VISUALIZE` request, so the value used here to build the
`Explanation` matches the value used to derive its own key.

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationGraphTest"`
Expected: PASS, every test, including the pre-existing cases for `EXPLAIN`, `DIG_DEEPER` and the
other verbs. This step must not change the `llm.stream` path for any of them.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationValidator.kt \
        backend/graph/src/main/kotlin/com/mytetz/graph/GraphConfig.kt \
        backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationGraph.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt
git commit -m "feat(graph): generate VISUALIZE through a structured call, sanitised before persistence"
```

---

## Task 5: `PromptBuilder.VISUALIZE_VERSION` and the visualize prompt — revised after review

**Files:**
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/PromptBuilder.kt`
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/PromptBuilderTest.kt`

**Interfaces:**
- Produces: `PromptBuilder.VISUALIZE_VERSION`, `PromptBuilder.visualizeSystem(): String`,
  `PromptBuilder.visualizeUser(context: PromptContext): String`,
  `PromptBuilder.visualizeSchema(): Map<String, Any>`, `PromptBuilder.VISUALIZE_TOOL_NAME`,
  `PromptBuilder.VISUALIZE_TOOL_DESCRIPTION`.

Decision 5 states why `VISUALIZE` needs its own version, apart from `PromptBuilder.VERSION`, and
why the shared `system()` prompt (`PromptBuilder.kt:76-88`) does not change. This task adds the new
members, and turns the now-unreachable `Verb.VISUALIZE ->` line inside `instructionFor`
(`PromptBuilder.kt:142-143`) into a guard.

- [ ] **Step 1: Write the failing tests**

```kotlin
// In PromptBuilderTest.kt, new tests near the existing VISUALIZE-related ones:
@Test
fun `the visualize schema requires both an explanation and an svg field`() {
    val schema = PromptBuilder.visualizeSchema()
    assertTrue(schema.containsKey("explanation"))
    assertTrue(schema.containsKey("svg"))
}

@Test
fun `the visualize system prompt asks for one short sentence, not one to three`() {
    // The shared system() prompt asks for "1 to 3 sentences." The visualize path asks for one,
    // since the sentence sits beside a diagram and does not carry the whole answer.
    assertTrue("one" in PromptBuilder.visualizeSystem().lowercase())
}

@Test
fun `PromptBuilder user must never be called for VISUALIZE`() {
    assertFailsWith<IllegalStateException> {
        PromptBuilder.user(
            PromptContext(
                topicTitle = "t",
                ancestors = emptyList(),
                span = "s",
                spanSentence = "ss",
                verb = Verb.VISUALIZE,
            )
        )
    }
}
```

Read `PromptBuilderTest.kt:208` first. Update whatever word it pins, if the new guard changes it —
the issue names this line as one that may need a change.

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.PromptBuilderTest"`
Expected: a compile failure. `visualizeSchema` and `visualizeSystem` are unresolved.

- [ ] **Step 3: Write the prompt. Add `VISUALIZE_VERSION`**

Add `const val VISUALIZE_VERSION: String = "v1"`. Do not change `PromptBuilder.VERSION`. Add the new
members:

```kotlin
const val VISUALIZE_TOOL_NAME: String = "submit_diagram"
const val VISUALIZE_TOOL_DESCRIPTION: String =
    "Submit one short sentence and one inline SVG diagram for the highlighted phrase."

fun visualizeSystem(): String = """
    You are an expert teacher drawing a simple diagram for a curious beginner.

    Rules, in order of priority:
    1. Write one short sentence describing what the diagram shows. Never more than one.
    2. Draw the diagram as a single, valid, self-contained inline SVG document. Use only
       basic shapes and text — no external references, no scripts, no embedded HTML.
    3. Keep the diagram simple: a handful of shapes the learner can read in a glance.
""".trimIndent()

fun visualizeUser(context: PromptContext): String = buildString {
    appendLine("Topic: ${flattened(context.topicTitle)}")
    appendLine()
    appendLine("Draw a diagram of: ${quoted(context.span)}")
    appendLine("It appeared in this sentence: ${quoted(context.spanSentence)}")
}.trim()

fun visualizeSchema(): Map<String, Any> = mapOf(
    "explanation" to mapOf("type" to "string"),
    "svg" to mapOf("type" to "string"),
)
```

Change the `Verb.VISUALIZE ->` branch inside `instructionFor` to a guard:

```kotlin
Verb.VISUALIZE -> error(
    "VISUALIZE never reaches PromptBuilder.user; see ExplanationGraph.generate " +
        "and PromptBuilder.visualizeUser"
)
```

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.PromptBuilderTest"`
Expected: PASS, including the pre-existing tests for the other four verbs' instructions.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/PromptBuilder.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/PromptBuilderTest.kt
git commit -m "feat(graph): give VISUALIZE its own prompt and its own version"
```

---

## Task 6: The Wikimedia Commons client — revised after review, now in `:backend:api`

**Files:**
- Create: `backend/api/src/test/kotlin/com/mytetz/api/CommonsClientTest.kt`
- Create: `backend/api/src/main/kotlin/com/mytetz/api/CommonsClient.kt`

**Interfaces:**
- Produces: `CommonsClient(httpClient: HttpClient)`, `.findImage(span: String, ancestors:
  List<Ancestor>): ImageMedia?`, matching the `CommonsLookup` port (Decision 6).

Decision 6 moves this client to `:backend:api`, beside `FreemiusApiClient`, which already declares
`libs.ktor.client.core`, `libs.ktor.client.cio` and `libs.ktor.client.mock`
(`backend/api/build.gradle.kts:18-19,47`). No new Gradle dependency is needed anywhere for this
task.

**Vendor facts used below, and the source read for each, on 2026-09-19:**

- The endpoint is `https://commons.wikimedia.org/w/api.php`
  (`https://commons.wikimedia.org/wiki/Commons:API`; confirmed again at
  `https://www.mediawiki.org/wiki/API:Main_page`).
- `format=json` and `formatversion=2` select the modern JSON response shape
  (`https://www.mediawiki.org/wiki/API:Main_page`).
- A keyword search over pages is `action=query&list=search&srsearch=<terms>`
  (`https://www.mediawiki.org/wiki/API:Main_page`).
- Image metadata for one title is `action=query&prop=imageinfo`. `iiprop=url` gives the file's own
  URL and the description page's URL. `iiprop=extmetadata` gives combined, HTML-formatted metadata,
  and the page names it "an expensive property to request"
  (`https://www.mediawiki.org/wiki/API:Imageinfo`).
- `extmetadata`'s fields include `LicenseShortName` ("short human-readable license name"),
  `LicenseUrl`, `Artist`, `Credit` ("source"), and `Attribution` ("custom attribution that should
  replace Artist + Credit") (`https://www.mediawiki.org/wiki/Extension:CommonsMetadata`).

**Confirm before use — facts this plan could not verify from the pages read:**

- The `srsearch` scoping needed to search only the `File:` namespace (commonly `srnamespace=6` on
  other MediaWiki search calls) was not stated on the pages read. Confirm this against
  `action=help&modules=query+search` on the live API, before Step 3 below is written.
- The exact JSON field name `imageinfo` uses for the description page's URL, apart from the file's
  own binary URL, was not quoted on the page read. Confirm this against
  `action=help&modules=query+imageinfo` before `commonsPageUrl` is filled in Step 3.
- Wikimedia's rate-limit and `User-Agent` policy for an API caller were not stated on either page
  read. Confirm this against Wikimedia's own request-limit documentation before this client ships,
  and set a descriptive `User-Agent` header naming this project and a contact address. An unset or a
  generic header is routinely rate-limited or blocked by Wikimedia's own infrastructure.
- The relevance-filtering rule ("filtered by relevance and licence," spec section 9) is a product
  decision, not a vendor fact. This plan sets one floor: return a result only when it carries a
  `LicenseShortName` this project recognises as freely reusable — for example a `CC BY`, a `CC
  BY-SA`, or a public-domain short name. Treat every other result the same as no result.

**The attribution reduction (Decision 7's own consequence for this task).** `extmetadata`'s `Artist`
and `Credit` fields can carry "complex HTML," in the vendor's own words. This client never stores
that HTML as-is in `ImageMedia.attributionHtml`. It reduces the source fields to two plain parts: the
visible text, with every tag stripped, and at most one `https` link found inside the source HTML.
It then builds a small HTML string of its own from those two parts — an escaped text node, and, when
a link was found, one escaped `<a href="…">` element — and stores that string. `ImageMedia.attributionHtml`
therefore always holds HTML this server wrote, never HTML a Commons editor wrote. Decision 7 states
why this matters, and how the renderer binds the result. The exact parsing approach for a loose,
real-world `extmetadata` HTML fragment — a strict XML parse on the model of `SvgSanitizer`, or a
lenient HTML library such as Jsoup, added as a new dependency for this one purpose — needs a decision
against real Commons sample data at implementation time; this plan states the safety rule (text plus
at most one `https` link, rebuilt, never passed through) and leaves the parsing method open rather
than guessing which one real Commons markup needs.

- [ ] **Step 1: Write the failing tests**

On the model of `backend/api/src/test/kotlin/com/mytetz/api/FreemiusApiClientTest.kt:56-88`: one
success, one non-2xx status, one thrown `IOException`, one empty result. The last three each answer
`null`.

```kotlin
package com.mytetz.api

import com.mytetz.graph.ImageMedia
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommonsClientTest {

    private fun clientReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK): CommonsClient {
        val engine = MockEngine {
            respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CommonsClient(HttpClient(engine))
    }

    @Test
    fun `a relevant, freely licensed result is returned as ImageMedia`() = runTest {
        // Confirm before use: the exact field name for the description-page URL, per this
        // task's own note above. Update this fixture once that field name is confirmed.
        val body = """
            {"query":{"pages":[{"title":"File:Example.jpg","imageinfo":[
                {"url":"https://upload.wikimedia.org/example.jpg",
                 "extmetadata":{
                    "LicenseShortName":{"value":"CC BY-SA 4.0"},
                    "Artist":{"value":"Example Author"},
                    "Credit":{"value":"Wikimedia Commons"}
                 }}
            ]}]}}
        """.trimIndent()

        val image: ImageMedia? = clientReturning(body).findImage("escape velocity", emptyList())

        assertEquals("CC BY-SA 4.0", image?.license)
    }

    @Test
    fun `a non-2xx status answers null`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        assertNull(CommonsClient(HttpClient(engine)).findImage("escape velocity", emptyList()))
    }

    @Test
    fun `a thrown IOException answers null`() = runTest {
        val engine = MockEngine { throw IOException("simulated network failure") }
        assertNull(CommonsClient(HttpClient(engine)).findImage("escape velocity", emptyList()))
    }

    @Test
    fun `an empty search result answers null`() = runTest {
        val image = clientReturning("""{"query":{"pages":[]}}""").findImage("escape velocity", emptyList())
        assertNull(image)
    }

    @Test
    fun `the Artist field's HTML is reduced to text plus one https link, never passed through`() = runTest {
        val body = """
            {"query":{"pages":[{"title":"File:Example.jpg","imageinfo":[
                {"url":"https://upload.wikimedia.org/example.jpg",
                 "extmetadata":{
                    "LicenseShortName":{"value":"CC BY-SA 4.0"},
                    "Artist":{"value":"<a href=\"https://example.com/artist\" onclick=\"alert(1)\">Example Author</a>"}
                 }}
            ]}]}}
        """.trimIndent()

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        assertEquals(false, image?.attributionHtml?.contains("onclick"))
        assertEquals(true, image?.attributionHtml?.contains("Example Author"))
    }
}
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.CommonsClientTest"`
Expected: a compile failure. `CommonsClient` is unresolved.

- [ ] **Step 3: Write `CommonsClient.kt`**

On the model of `FreemiusApiClient.fetchState`
(`backend/api/src/main/kotlin/com/mytetz/api/FreemiusApiClient.kt:168-227`): a `try`/`catch` around
the whole call, a non-2xx status logged and answered `null`, and a decode failure caught the same
way. Log the caught exception's class name only, never its own message — the same rule
`FreemiusApiClient.kt:206-219` gives, since a vendor error body or a decode failure's message can
quote text this server must not place in a log line. Mark each "confirm before use" item from this
task with a `// CONFIRM BEFORE USE:` comment at the exact line it affects. Build `ImageMedia` with
the attribution reduction this task states above.

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.CommonsClientTest"`
Expected: PASS, every test.

- [ ] **Step 5: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/CommonsClient.kt \
        backend/api/src/test/kotlin/com/mytetz/api/CommonsClientTest.kt
git commit -m "feat(api): add the Wikimedia Commons image lookup client"
```

---

## Task 7: Degradation — a graph test for a Commons failure — simplified after review

**Files:**
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt`

**Interfaces:**
- Consumes: the `commonsLookup` constructor parameter on `ExplanationGraph` (Decision 6, Task 4).
  This test needs no `MockEngine` and no `CommonsClient`: the port is a plain function, and a test
  can supply one directly.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `a Commons failure stores the diagram only, and the flow still ends with Done`() = runTest {
    llm.nextStructuredJson = """{"explanation":"A short valid sentence about the span.","svg":"<svg><circle cx=\"1\" cy=\"1\" r=\"1\"/></svg>"}"""
    val graphWithFailingCommons = ExplanationGraph(
        repository = repository,
        llm = llm,
        validator = ExplanationValidator(),
        config = config,
        commonsLookup = { _, _ -> throw java.io.IOException("down") },
    )

    val chunks = graphWithFailingCommons.getOrGenerate(request(verb = Verb.VISUALIZE)).toList()

    val done = chunks.filterIsInstance<GraphChunk.Done>().single()
    assertNotNull(done.explanation.media?.diagram)
    assertNull(done.explanation.media?.image)
}
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationGraphTest"`
Expected before Task 4's `commonsLookup` parameter exists: a compile failure. After Task 4 lands,
this test still needs writing, and should fail if the lookup call in `generate` is not wrapped in
`runCatching` — an unhandled `IOException` would then propagate out of `getOrGenerate` instead of
leaving `image` null.

- [ ] **Step 3: Confirm Task 4's `runCatching` wrapping already covers this case**

Task 4's own code already wraps the lookup call in `runCatching { }.getOrNull()`. This step is
verification, not new production code. Run the test and confirm it needs no further change. If it
does, make the fix inside Task 4's own diff, not a new one — the two tasks describe one code change.

- [ ] **Step 4: Run the test. Confirm it passes**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationGraphTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt
git commit -m "test(graph): prove a Commons failure degrades to a diagram-only document"
```

---

## Task 8: Wire the Commons client into `ExplanationGraph` — revised after review

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Components.kt`

**Interfaces:**
- Consumes: `CommonsClient` (Task 6), `CommonsLookup` (Decision 6).

- [ ] **Step 1: Add the factory. Adapt it to the port with a lambda**

On the model of `turnstileFactory` (`Components.kt:104`, built eagerly, since `CommonsClient`'s own
construction takes no required credential and cannot throw) and on the model of `Components.kt`'s
own `Reconciliation.reconcile(billingRepository, limit = RECONCILE_LIMIT) { subscription ->
client.fetchState(subscription) }` (`Components.kt:354-355`):

```kotlin
commonsClientFactory: () -> CommonsClient = { CommonsClient(HttpClient(CIO)) },
```

```kotlin
val commonsClient: CommonsClient = commonsClientFactory()
```

Pass `commonsLookup = { span, ancestors -> commonsClient.findImage(span, ancestors) }` to wherever
`ExplanationGraph` is built inside `Components` today — find the existing construction site next to
where `ExplanationRepository` and `ExplanationValidator` are already assembled.

- [ ] **Step 2: Run the module's existing tests**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.ComponentsTest"`
Expected: PASS, with no change to existing behaviour. This task only adds a component.

- [ ] **Step 3: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/Components.kt
git commit -m "feat(api): wire the Commons client into ExplanationGraph through the port"
```

---

## Task 9: The wire shape — `SessionRoutes.kt`

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/SessionRoutesTest.kt`

**Interfaces:**
- Produces: `DiagramMediaView`, `ImageMediaView`, `MediaView` — Decision 4 gives the full code.

- [ ] **Step 1: Write the failing route test**

Add to `SessionRoutesTest.kt`, on the model of an existing test that drives a request end to end
through the test app (search the file for `Verb.VISUALIZE` first; build the request the same way an
existing explain test does, if none exists yet):

```kotlin
@Test
fun `a VISUALIZE explanation carries media on both the done event and the session view`() = runTest {
    // ... existing test-app setup: FakeLlmClient configured with a VISUALIZE structured answer,
    // a signed-in principal, an entitled subscription ...

    val doneEvent = /* parse the "done" SSE event from the explain response */
    assertNotNull(doneEvent.media)

    val sessionView = /* GET /api/sessions/{id} */
    assertTrue(sessionView.media.containsKey(doneEvent.contentKey))
}
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.SessionRoutesTest"`
Expected: a compile failure. `DoneEvent` and `SessionView` carry no `media` property yet.

- [ ] **Step 3: Widen the two wire types**

Add the code from Decision 4 to `SessionRoutes.kt`. Update `eventFor`'s `GraphChunk.Done` branch
(`SessionRoutes.kt:720-723`) to build `DoneEvent` with `media = chunk.explanation.media?.toView()`.
Update `LearningSession.toView` (`SessionRoutes.kt:952-961`) to build the sparse `media` map from the
same data its caller already loads. Check `SessionService.load`'s own return type first: the caller
at `SessionRoutes.kt:441-442` already reads a full `Explanation` per key
(`bodies.mapValues { it.value.body }`), so `media` may already be reachable there with no change to
`SessionService` at all.

- [ ] **Step 4: Run the test. Confirm it passes**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.SessionRoutesTest"`
Expected: PASS, with no regression on the existing `SessionView`/`DoneEvent` tests. Both new fields
default to empty or null, so a caller that never asks for `VISUALIZE` sees no change to its own
assertions.

- [ ] **Step 5: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt \
        backend/api/src/test/kotlin/com/mytetz/api/SessionRoutesTest.kt
git commit -m "feat(api): carry Media to the browser on SessionView and DoneEvent"
```

---

## Task 10: The frontend `Media` interface

**Files:**
- Modify: `frontend/src/app/core/models.ts`

**Interfaces:**
- Produces: `DiagramMedia`, `ImageMedia`, `Media`, the TypeScript mirrors of Decision 3's Kotlin
  types. Widens `SessionView`.

- [ ] **Step 1: Add the types**

```typescript
export interface DiagramMedia {
  kind: 'SVG' | 'MERMAID';
  source: string;
}

export interface ImageMedia {
  imageUrl: string;
  title: string;
  license: string;
  attributionHtml: string;
  commonsPageUrl: string;
}

export interface Media {
  diagram: DiagramMedia;
  image: ImageMedia | null;
}
```

Widen `SessionView` (`models.ts:21-28`) with `media: Record<string, Media>;`.

- [ ] **Step 2: Run the frontend type check**

Run: `cd frontend && npx tsc --noEmit`
Expected: PASS. This task only adds types. Nothing yet constructs or reads a `Media` value, so
there is no failing test to write first. The check here is that the type checker still accepts every
existing caller of `SessionView` unchanged — `media` is additive.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/core/models.ts
git commit -m "feat(frontend): add the Media interface and widen SessionView"
```

---

## Task 11: The verb picker's fifth row

**Files:**
- Modify: `frontend/src/app/ui/verb-picker.component.spec.ts`
- Modify: `frontend/src/app/ui/verb-picker.component.ts`

**Interfaces:** none new. `VERBS` gains one entry.

- [ ] **Step 1: Change the failing test**

Change `verb-picker.component.spec.ts:49-55`'s pinned list:

```typescript
it('offers the five verbs and no other', () => {
  const verbs = Array.from(fixture.nativeElement.querySelectorAll('button[data-verb]')).map((b) =>
    (b as HTMLElement).getAttribute('data-verb'),
  );
  expect(verbs).toEqual(['EXPLAIN', 'DIG_DEEPER', 'BROADER_PICTURE', 'SIDE_VIEW', 'VISUALIZE']);
});
```

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `cd frontend && npm test -- --watch=false --include='**/verb-picker.component.spec.ts'`
Expected: FAIL. The rendered list still has four entries.

- [ ] **Step 3: Add the fifth entry**

In `VERBS` (`verb-picker.component.ts:41-46`), use the exact name and caption read from
`docs/mytetz-design-reference.html`'s own picker row: "Show me a diagram" and "A sketch instead of a
paragraph." The row's own text is present verbatim in that file. (The issue's own "section 5b" label
for the file has no visible section numbering in the file itself; only the row's own text was
confirmed, not that label.)

```typescript
{ verb: 'VISUALIZE', name: 'Show me a diagram', caption: 'A sketch instead of a paragraph' },
```

Update the block comment above `VERBS` (`verb-picker.component.ts:37-40`). Drop the note that
`VISUALIZE` "arrives with the feature," since the feature now exists.

- [ ] **Step 4: Check `PICKER_HEIGHT` against three grid rows**

`PICKER_HEIGHT` is `240` (`verb-picker.component.ts:35`). The CSS repeats the number at `max-height:
240px` (`verb-picker.component.ts:111`). The grid holds two columns
(`grid-template-columns: 1fr 1fr`, `verb-picker.component.ts:130`), so four verbs fill two rows and
five verbs fill three. Measure a rendered three-row grid by hand (Step 6) against the existing
240px cap, the `16px` padding, and the `10px` gap already in the styles
(`verb-picker.component.ts:113,116`). Raise both literals together when three rows overflow 240px —
they are two copies of one number by the component's own design
(`verb-picker.component.ts:31-34`'s own comment states this). Change the `const` and the `styles`
string in the same commit.

- [ ] **Step 5: Run the tests. Confirm they pass**

Run: `cd frontend && npm test -- --watch=false --include='**/verb-picker.component.spec.ts'`
Expected: PASS.

- [ ] **Step 6: Verify the picker's height in a running app**

Start the app, per this project's `run` skill. Highlight a phrase. Open the picker. Confirm the
fifth row is visible with no scroll, on an ordinary viewport. Raise `PICKER_HEIGHT` per Step 4 if it
does not fit.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/ui/verb-picker.component.ts \
        frontend/src/app/ui/verb-picker.component.spec.ts
git commit -m "feat(ui): add the fifth verb-picker row for Visualize"
```

---

## Task 12: The media renderer component — revised after review

**Files:**
- Create: `frontend/src/app/reader/media-renderer.component.spec.ts`
- Create: `frontend/src/app/reader/media-renderer.component.ts`

**Interfaces:**
- Produces: `MediaRendererComponent`, `input.required<Media>('media')`.

Decision 7 states the renderer's design: an `<img>` bound to a `data:image/svg+xml` URL for the
diagram, and an ordinary, non-bypassed `[innerHTML]` binding for the already-reduced
`attributionHtml`. Neither binding calls `DomSanitizer.bypassSecurityTrustHtml` or
`bypassSecurityTrustUrl`.

- [ ] **Step 1: Write the failing tests**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MediaRendererComponent } from './media-renderer.component';
import { Media } from '../core/models';

describe('MediaRendererComponent', () => {
  let fixture: ComponentFixture<MediaRendererComponent>;

  const media = (overrides: Partial<Media> = {}): Media => ({
    diagram: { kind: 'SVG', source: '<svg viewBox="0 0 10 10"><title>A circle</title><circle cx="5" cy="5" r="4"/></svg>' },
    image: null,
    ...overrides,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [MediaRendererComponent] });
    fixture = TestBed.createComponent(MediaRendererComponent);
  });

  it('renders the diagram as an image with an accessible alt text taken from the SVG title', () => {
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();
    const img = fixture.nativeElement.querySelector('img');
    expect(img?.getAttribute('src')).toContain('data:image/svg+xml');
    expect(img?.getAttribute('alt')).toBe('A circle');
  });

  it('falls back to a fixed alt text when the SVG carries no title', () => {
    fixture.componentRef.setInput('media', media({ diagram: { kind: 'SVG', source: '<svg><circle cx="1" cy="1" r="1"/></svg>' } }));
    fixture.detectChanges();
    const img = fixture.nativeElement.querySelector('img');
    expect(img?.getAttribute('alt')).toBeTruthy();
  });

  it('shows the attribution whenever an image is present', () => {
    fixture.componentRef.setInput(
      'media',
      media({
        image: {
          imageUrl: 'https://upload.wikimedia.org/example.jpg',
          title: 'Example.jpg',
          license: 'CC BY-SA 4.0',
          attributionHtml: 'By Example Author',
          commonsPageUrl: 'https://commons.wikimedia.org/wiki/File:Example.jpg',
        },
      }),
    );
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('CC BY-SA 4.0');
  });

  it('shows no attribution block when there is no image', () => {
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="attribution"]')).toBeNull();
  });
});
```

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `cd frontend && npm test -- --watch=false --include='**/media-renderer.component.spec.ts'`
Expected: a compile failure. `MediaRendererComponent` does not exist yet.

- [ ] **Step 3: Write `media-renderer.component.ts`**

A standalone component, with `input.required<Media>('media')`. Build the image source as
`'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(media().diagram.source)`, and bind it to
the `<img>`'s `[src]`. Parse `media().diagram.source` once, with the browser's own `DOMParser`, to
read the first `<title>` element's text for `[alt]`; fall back to a fixed word, such as "Diagram,"
when none is found. Wrap the `<img>` in a plain `overflow: auto` container for "look at it larger" —
a pinch or a scroll-to-zoom control is a later polish item, not a requirement this task must meet.
Render the `image` block — the `<img>`, the license text, and `attributionHtml` bound through an
ordinary `[innerHTML]` with no bypass — only when `media().image` is not null.

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `cd frontend && npm test -- --watch=false --include='**/media-renderer.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/reader/media-renderer.component.ts \
        frontend/src/app/reader/media-renderer.component.spec.ts
git commit -m "feat(reader): add the media renderer for a VISUALIZE explanation"
```

---

## Task 13: Wire the renderer into the focus card

**Files:**
- Modify: `frontend/src/app/reader/focus-card.component.ts`
- Modify: `frontend/src/app/reader/focus-card.component.spec.ts`

**Interfaces:**
- Consumes: `MediaRendererComponent` (Task 12).

- [ ] **Step 1: Write the failing test**

Add a test to `focus-card.component.spec.ts`. Assert that `app-media-renderer` appears when the node
in focus carries `media`. Assert that it does not appear otherwise. Read the existing spec file
first, to match its own fixture style.

- [ ] **Step 2: Run the test. Confirm the failure**

Run: `cd frontend && npm test -- --watch=false --include='**/focus-card.component.spec.ts'`
Expected: FAIL. `app-media-renderer` never renders today.

- [ ] **Step 3: Show the renderer**

Add `MediaRendererComponent` to the component's `imports`. Add, near where the body text itself
renders:

```html
@if (media(); as m) {
  <app-media-renderer [media]="m" />
}
```

`media` is a new `computed` signal. It reads whatever the store already exposes for the node in
focus's content key. Trace how `explanations` is read today in this file, to find the matching
signal for `media`.

- [ ] **Step 4: Run the tests. Confirm they pass**

Run: `cd frontend && npm test -- --watch=false --include='**/focus-card.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/reader/focus-card.component.ts \
        frontend/src/app/reader/focus-card.component.spec.ts
git commit -m "feat(reader): show the diagram and image on the focus card"
```

---

## Task 14: Make the two verb labels agree

**Files:**
- Modify: `frontend/src/app/reader/reader-page.component.ts`
- Modify: `frontend/src/app/reader/trail-rail.component.ts`

**Decision:** use "Diagram" in both places, not "Visual." `reader-page.component.ts:435` already
says "Diagram." `trail-rail.component.ts:10` says "Visual." "Diagram" names the artifact the verb
produces. This matches the concrete-noun pattern of the picker's own new row, "Show me a diagram."
"Visual" is vaguer, and could equally describe the image half alone.

- [ ] **Step 1: Write the failing tests**

Search both spec files for `'Visual'` and `'Diagram'` first. Update any assertion that pins the
current word for `VISUALIZE` to expect `"Diagram"` in both places, before you change the source.

- [ ] **Step 2: Run the tests. Confirm the failure**

Run: `cd frontend && npm test -- --watch=false --include='**/trail-rail.component.spec.ts'`
Expected: FAIL, if a test pins "Visual." Otherwise, no test exists yet for this word; skip to Step
4's manual check.

- [ ] **Step 3: Change `trail-rail.component.ts:10`**

Change `VISUALIZE: 'Visual'` to `VISUALIZE: 'Diagram'`.

- [ ] **Step 4: Run the tests. Confirm they pass, or verify by hand**

Run: `cd frontend && npm test -- --watch=false`
Expected: PASS, with no regression.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/reader/reader-page.component.ts \
        frontend/src/app/reader/trail-rail.component.ts
git commit -m "fix(reader): use one word, Diagram, for VISUALIZE in both the focus card and the trail rail"
```

---

## Task 15: Playwright end-to-end coverage

**Files:**
- Read first: `frontend/e2e/support.ts`, for `stubCatalogueAndSession`, `openQuantumPhysicsSession`,
  `selectPhrase` and `mockExplainStream`.
- Create: `frontend/e2e/visualize.spec.ts`

**Interfaces:**
- Consumes: the four helpers named above, exactly as the issue names them.

- [ ] **Step 1: Read `support.ts` in full**

Read every one of the four helpers' signatures before you write the spec. This keeps the stub shapes
this test builds matched to what the helpers actually expect, not a guess.

- [ ] **Step 2: Write `visualize.spec.ts`**

Follow the exact structure an existing e2e spec already uses. Run `find frontend/e2e -name
'*.spec.ts'`, and read the first result in full — the same first step
`docs/superpowers/plans/2026-09-15-quiz-assess.md`'s own Task 13 takes. The scenario:

1. `stubCatalogueAndSession(...)` for one topic and one seed node.
2. `openQuantumPhysicsSession(...)`.
3. `selectPhrase(...)` on the seed's text.
4. Choose "Show me a diagram" from the picker.
5. `mockExplainStream(...)` with a `done` event that carries a `media` field, shaped by Decision 4 —
   an SVG source and no image, the diagram-only case, since every layer below already guarantees
   that case degrades cleanly.
6. Assert an `<img>` is visible on the focus card once the stream completes.

Write the actual Playwright code once Step 1's helpers are in hand. Guessing their exact call shape
here would risk exactly the kind of untested placeholder this project's own plans rule out.

- [ ] **Step 3: Run it**

Run: `cd frontend && CI=1 npx playwright test visualize.spec.ts`
Expected: PASS. (`CI=1`, since this project's own note states that port 4300 is sometimes held by
another process, and Playwright's dev-server reuse behaves differently without it.)

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/visualize.spec.ts
git commit -m "test(e2e): show a diagram after Visualize, stubbed end to end"
```

---

## Task 16: Full acceptance pass

**Files:** none — verification only.

- [ ] **Step 1: Backend**

Run: `./gradlew build`
Expected: PASS, every module, including the widened `:backend:graph` and `:backend:api`.

- [ ] **Step 2: Frontend format and unit tests**

Run: `cd frontend && npm run format:check`
Run: `cd frontend && npm test -- --watch=false`
Expected: PASS.

- [ ] **Step 3: End-to-end**

Run: `cd frontend && CI=1 npx playwright test`
Expected: PASS.

- [ ] **Step 4: Re-read the issue's acceptance criteria. Check each one against what this plan
  builds**

- Sanitiser tests for `<script>`, `onload`, an external `<image href>` and `<foreignObject>` — Task
  2, each pinned to `Clean` or `Refused`.
- A malformed SVG is refused before persistence — Task 4's malformed-SVG test.
- A Commons failure yields a diagram-only document, and no error — Task 7.
- The attribution renders whenever `media.image` is present (this plan's own shape for "`media.kind`
  is `IMAGE`," per Decision 3) — Task 12.
- A stubbed end-to-end test shows a diagram after Visualize — Task 15.
- All three suites are green — Steps 1 to 3 above.

Add a task for any box left unchecked. Do not close the issue with a known gap.

- [ ] **Step 5: Push the branch. Open the pull request**

Follow this repository's own pull-request conventions. Run `gh pr list --state merged --limit 5` for
its title and body style. Name issue #17 in the pull request's body, so the issue closes on merge.
