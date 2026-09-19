# Visualize (Slice 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to run this plan task by task. A checkbox (`- [ ]`)
> marks each step.

**Goal:** Give the `VISUALIZE` verb a real answer. The model writes a short prose sentence and an
inline SVG diagram in one call. The server refuses a malformed or unsafe SVG, then looks up one
licensed image from Wikimedia Commons for the same span. The document holds the diagram always and
the image when Commons found one. The API sends both to the browser. Angular renders the diagram,
zoomable and accessible, with the image's attribution beside it. The verb picker gains its fifth
row.

**Architecture:** `VISUALIZE` stays inside `ExplanationGraph.generate`, next to the four existing
verbs, and produces one more `Explanation` document — content addressed, immutable, cached, exactly
like every other verb. Two new pieces sit beside it in `:backend:graph`: an allowlist SVG sanitiser
that runs before persistence, and a Wikimedia Commons client that runs after the SVG passes and
before the document is stored. `Explanation.media` is null for every verb but `VISUALIZE`. The API
layer widens two wire shapes to carry it. The frontend adds one interface, one picker row, and one
new renderer component.

**Tech Stack:** Kotlin, Ktor, kotlinx.serialization, MongoDB Kotlin coroutine driver, the Anthropic
Java SDK's forced tool call (`LlmClient.structured`, already in the codebase since slice 3), Ktor's
`HttpClient(CIO)` for the Commons lookup, Angular 21 standalone components with signals, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-01-assisted-learning-engine-design.md`, section 9
(Visualize), section 5.2 (the `Media` shape), section 11 (error handling — the "model refuses or
returns empty" row and the "Commons unavailable" row) and section 14 (slice 4's own line). GitHub
issue #17 is the task brief and is authoritative over the spec wherever the two disagree — the spec
gives `Media.kind` one value and asks for 40 minimum characters; the issue's own evidence corrects
both, and this plan follows the issue.

---

## Decisions

The issue asks this plan to record six decisions before any code. Each one names its options, the
option that wins, and the reason.

### 1. Mermaid: later, or never

**Options:**
- **A.** Build Mermaid support now, alongside SVG.
- **B.** Never build Mermaid support; SVG is the only diagram format.
- **C.** Defer Mermaid. Ship SVG only in this slice. Revisit only if the SVG path proves too weak.

**Winner: C.**

**Reason:** The issue's own requirements say the diagram path comes first with inline SVG, and
Mermaid is "a second step, only if the SVG path proves too weak." Building both formats now doubles
the security surface — two parsers, two sanitisers, two sets of refusal tests — for a format the
project has not yet needed. `Media.kind` in the spec already reserves `MERMAID` as a value, and the
`DiagramMedia.kind` enum this plan adds (Decision 3) keeps that same reservation, so adding Mermaid
later needs no migration of stored documents: old documents keep `kind: SVG` and a new document can
carry `kind: MERMAID` the day it is built.

### 2. The transport of the prose plus the SVG

**Options:**
- **A.** `llm.stream` with a delimiter: one stream, prose first, a marker string, then the SVG
  source.
- **B.** `LlmClient.structured`, the forced tool call slice 3 added for the quiz feature.

**Winner: B.**

**Reason:** An SVG document is not usable until it is complete — a half-arrived `<path d="M10 1`
renders nothing, the same argument `docs/superpowers/plans/2026-09-15-quiz-assess.md`'s own
Architecture section makes for quiz JSON. Streaming buys nothing for the part of the answer that
cannot be shown early, and it costs real risk for the part that can: a delimiter is a string the
model must remember to emit exactly once, in exactly the right place, and never accidentally inside
either the prose or the SVG source. A forced tool call with a JSON Schema removes that risk by
construction — the model can only answer inside the two named fields.

There is a second, sharper reason a delimiter is the wrong shape here specifically.
`ExplanationValidator.validate(rawBody, stopReason)` treats anything but the stop reason
`"end_turn"` as a refusal or a truncation (`ExplanationValidator.kt:49-64`) — a defence against a
refusal that arrives as a plausible-looking HTTP 200 body. A forced tool call's own successful stop
reason is `"tool_use"`, not `"end_turn"` — `AnthropicLlmClientTest`'s own `toolUseMessage` fixture in
the quiz plan pins exactly this shape. Unmodified, `ExplanationValidator` would therefore reject
every successful `VISUALIZE` generation. This is not a reason to prefer the delimiter — it is a
reason the delimiter looked simpler than it is. See Task 4 for the fix this plan adopts: the
stop-reason gate is not needed on the structured path at all, because `LlmClient.structured` already
turns "the model did not really answer" into a thrown `LlmStructuredOutputMissingException`
(`AnthropicLlmClient.structured`'s own contract) before a `StructuredResult` ever exists. The
refusal-disguised-as-200 failure mode the gate defends against cannot occur here.

**Consequence for the stream contract.** `ExplanationGraph.generate` currently emits the prose as it
arrives, in `LlmChunk.Delta` pieces (`ExplanationGraph.kt:325-333`). A structured call returns once,
with the whole JSON answer. The `VISUALIZE` branch therefore emits the whole validated prose sentence
as a single `GraphChunk.Delta`, once, right after the model call returns and the prose passes
validation. A client that renders every `Delta` it receives sees the sentence appear as one piece
instead of token by token — acceptable, because the sentence is short (the same 25–600-character
rule as every other verb) and the SVG itself, which is the point of the verb, was never going to
stream usefully anyway.

### 3. The `Media` shape for a diagram plus an image

**Options:**
- **A.** Keep the spec's flat shape — one `kind` field holding `MERMAID | SVG | IMAGE` — and add a
  new combined value such as `SVG_WITH_IMAGE` for the case both exist.
- **B.** Replace the flat `kind` with a container that holds a mandatory diagram and an optional
  image: `Media(diagram: DiagramMedia, image: ImageMedia?)`.
- **C.** Store the diagram and the image as two separate documents.

**Winner: B.**

**Reason:** The requirements ask for a diagram and an image together, and a single tag cannot name
that combination without a new value for every future combination — `SVG_WITH_IMAGE`,
`MERMAID_WITH_IMAGE`, and so on, one per diagram format the project ever adds. A container with two
slots says the same thing without the combinatorics. The diagram slot is not nullable: a `VISUALIZE`
generation that produced no usable SVG is rejected before persistence (Task 4), so every document
this feature stores has a diagram. The image slot is nullable, which is exactly the shape the
requirements' own degradation rule needs — "when Commons is unavailable or returns nothing,
Visualize serves the diagram only" — a null `image` on an already-valid document, not a second kind
of failure. Option C is rejected because `Explanation` has no edit path (`ExplanationValidator.kt`'s
own class KDoc: "There is no edit path and no delete path") and a second document would need one, to
attach an image that a first pass could not find.

The spec's `Media` also lists `thumbUrl`, which the requirements do not mention. This plan drops it:
nothing in the renderer task (Task 12) needs a thumbnail, and a nullable field is additive — it can
be added the day a caller needs it, without touching a stored document.

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
- **A.** Fold `media` into the existing `explanations: Map<String, String>` by changing its value
  type to a richer object holding both the body and the media.
- **B.** Add a second, sparse map to `SessionView`, and a nullable field to `DoneEvent`, both
  additive.

**Winner: B.**

**Reason:** `SessionView.explanations` (`SessionRoutes.kt:63-71`) is `Map<String, String>` today, and
every existing frontend caller reads it as plain text. Option A breaks that contract for every verb,
not only `VISUALIZE`, to carry a field that only one verb ever populates. Option B is purely
additive: `SessionView` gains `media: Map<String, MediaView> = emptyMap()`, populated only for the
content keys whose `Explanation.media` is not null — the same sparse-by-key shape `explanations`
itself already uses, so no client pays for an empty field on every ordinary text node.

`DoneEvent` (`SessionRoutes.kt:101-102`) needs its own field, separately, because it describes the
live stream and not the session tree: a learner watching a `VISUALIZE` generation must see the
diagram the instant it is done, without waiting for the `GET /api/sessions/{id}` re-read the class
KDoc says a client makes afterwards for the new node id (`SessionRoutes.kt:80-83`). So `DoneEvent`
gains `media: MediaView? = null`, populated from the same `Explanation.media` the `Done` chunk's own
document carries (`GraphChunk.Done(chunk.explanation)`, `SessionRoutes.kt:720-723`).

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

### 5. The `PromptBuilder.VERSION` bump

**Decision:** Bump `PromptBuilder.VERSION` (`PromptBuilder.kt:41`, currently `"v3"`) when the
`VISUALIZE` instruction changes, exactly as its own KDoc requires: "Bump on ANY change to the system
prompt or a verb instruction" (`PromptBuilder.kt:36-40`).

**Effect on stored seeds.** `VERSION` is not scoped to one verb. `ContentKey.derive` and
`ContentKey.seed` both take one `promptVersion` string shared by every verb
(`ExplanationGraph.keyFor`, `ExplanationGraph.kt:198-211`), and `GraphConfig.promptVersion` defaults
to `PromptBuilder.VERSION`. Bumping it therefore orphans every stored `Explanation` of every verb and
every stored seed of every topic — not only the `VISUALIZE` documents this task changes. Nothing is
deleted; the old documents simply stop being reachable by any key a running server can compute, and a
future `deleteWhereModelFamilyIsNot`-style sweep is the only thing that would ever remove them.

**Cost, with the pre-warm from #18.** Issue #18 pre-warms the seed for every published topic on each
boot. The deploy that ships this bump makes the very next boot's pre-warm find that every seed's key
has changed, so it regenerates the entire catalogue from scratch — a real, one-time spend, not a
no-op. This is not a defect to fix in code; it is the documented cost of the KDoc's own rule, and it
recurs on every future bump. The owner should time the deploy that ships this change deliberately —
off-peak, with the daily spend ceiling (`GLOBAL_DAILY_COST_CEILING`, spec section 10.2) confirmed
wide enough for a full-catalogue regeneration in one boot — and should coordinate with whoever
implements #18, since the two issues now share one cost event.

**An alternative considered and rejected:** give `VISUALIZE` its own prompt version, independent of
`PromptBuilder.VERSION`, the way `QuizPromptBuilder.VERSION` is independent of `PromptBuilder.VERSION`
for quizzes. This would confine a future `VISUALIZE`-only prompt change to `VISUALIZE`'s own
documents. It is rejected for this slice because `ContentKey.derive` and `ContentKey.seed` take one
`promptVersion` parameter for every verb, not one per verb — splitting it is a structural change to
the content-addressing scheme itself, larger than the 26 implementation steps in the issue authorize.
If `VISUALIZE`'s prompt turns out to churn often, a follow-up issue should propose per-verb prompt
versioning on its own; this plan does not build it.

### 6. The module that holds the Wikimedia Commons client

**Options:**
- **A.** `:backend:api`, beside `FreemiusApiClient`, which already depends on Ktor's client.
- **B.** `:backend:graph`, beside `ExplanationGraph`, adding a new Ktor client dependency there.

**Winner: B.**

**Reason:** The Commons lookup is one step inside one verb's generation pipeline — it runs inside
`ExplanationGraph.generate`, before `repository.insertIfAbsent`, exactly where `llm: LlmClient`
already runs. `:backend:api` depends on `:backend:graph`, never the other way around, so a client
living in `:backend:api` could not be called from inside `ExplanationGraph` without inverting that
dependency — the same layering rule
`docs/superpowers/plans/2026-09-15-quiz-assess.md`'s Global Constraints state for `:backend:assess`
("must not depend on `quota` or `billing`"). `backend/graph/build.gradle.kts` has no Ktor client
today (`implementation(project(":backend:persistence"))`, `implementation(project(":backend:llm"))`,
and the two Mongo lines only), so this task adds
`implementation(libs.ktor.client.core)` and `implementation(libs.ktor.client.cio)` there — the same
catalog aliases `backend/api/build.gradle.kts` already uses — plus `testImplementation(libs.ktor.client.mock)`
for the client's own tests.

---

## Global Constraints

- Prose, KDoc and commit bodies are in ASD-STE100 Simplified Technical English (short sentences,
  active voice, one instruction per sentence — see the project's `CLAUDE.md`).
- Security is the main risk of this slice. The SVG sanitiser is an allowlist, never a blocklist: an
  element or an attribute not named on the list is dropped, not merely checked against a list of bad
  ones. See Task 2 for the allowlist itself and the four refusal cases.
- The explanation validator's rule is 25 to 600 characters
  (`ExplanationValidator.kt:28-29`), and not 40 to 600 as the spec's configuration table states
  (spec section 10.2). This plan uses 25 throughout, because the code and its own KDoc are the
  authority the issue names, and `ExplanationValidator.kt:19-25`'s own comment gives the reason: 25
  characters is the shortest a genuinely correct, complete answer can be under this project's "1 to
  3 sentences" rule, and a higher floor would make a good terse answer fail validation for no benefit.
- The Angular version is 21. (The model plan this plan follows,
  `docs/superpowers/plans/2026-09-15-quiz-assess.md`, states 18 in its Tech Stack line; that is wrong
  for this project today, and this plan does not repeat it.)
- `assess`'s own module-boundary rule extends to this slice by the same logic: `:backend:graph` may
  depend on `:backend:persistence` and `:backend:llm`, and now also on a Ktor HTTP client for the
  Commons lookup (Decision 6) — never on `:backend:api`, `:backend:quota` or `:backend:billing`.
- Every new Kotlin file matches the project's existing style: extensive "why" KDoc, `internal`
  helpers kept internal, config values overridable from the environment with a safe fallback (never
  a startup crash) — the same rule `GraphConfig` and `QuizConfig` already follow.
- Every `@Test fun name() = runBlocking { ... }` must resolve to `Unit`. If the block's last
  statement is `assertFailsWith<T> { ... }` (which evaluates to `T`, not `Unit`) or any other
  non-`Unit`-returning call, annotate the function `fun name(): Unit = runBlocking { ... }` instead of
  the bare `fun name() = runBlocking { ... }`. Kotlin otherwise infers a non-`Unit` return type, and
  the test then never runs — no failure, no skipped entry, just silence. Check every test against
  this before committing it, whichever task is being implemented. (This module's existing tests use
  `runTest`, not `runBlocking`; the rule still applies to any test written with `runBlocking`,
  including the Commons client's `MockEngine` tests in Task 6, which follow
  `FreemiusApiClientTest`'s own `runTest` pattern and so are unaffected, but a reviewer should still
  check every new test file against this rule by hand.)
- No user-supplied text ever reaches the Commons search query or the SVG sanitiser as free text; both
  receive only the highlighted span and its ancestry, which are themselves bounded by
  `ExplanationValidator`'s own 600-character ceiling on the body they came from — the same "no
  free-text path into a prompt" rule spec section 10.3 states for every other verb.

---

## File Structure

### Backend — `graph` module (modify + new)

| File | Responsibility |
|---|---|
| `backend/graph/src/main/kotlin/com/mytetz/graph/Media.kt` | New. `DiagramKind`, `DiagramMedia`, `ImageMedia`, `Media`. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt` | Adds a nullable `media: Media? = null`. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/SvgSanitizer.kt` | New. The allowlist parser and sanitiser. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/MediaValidator.kt` | New. Bounds the diagram source's size. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationValidator.kt` | Adds a `validateStructuredBody(rawBody: String): ValidationResult` overload that skips the stop-reason gate (Decision 2). |
| `backend/graph/src/main/kotlin/com/mytetz/graph/CommonsClient.kt` | New. The Wikimedia Commons lookup. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/PromptBuilder.kt` | Changes the `VISUALIZE` instruction; adds the structured-call schema; bumps `VERSION`. |
| `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationGraph.kt` | Adds the `VISUALIZE` branch to `generate`, before `repository.insertIfAbsent`. |
| `backend/graph/build.gradle.kts` | Adds `libs.ktor.client.core`, `libs.ktor.client.cio` (`implementation`) and `libs.ktor.client.mock` (`testImplementation`). |
| `backend/graph/src/test/kotlin/com/mytetz/graph/SvgSanitizerTest.kt` | New. The four refusal cases plus the well-formed case. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/MediaValidatorTest.kt` | New. The over-size case. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/CommonsClientTest.kt` | New. `MockEngine`, on the model of `FreemiusApiClientTest`. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt` | Adds a round-trip test with `media` set, and a decode test for a stored document with no `media` field. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt` | Adds the malformed-SVG test and the Commons-degradation test. |
| `backend/graph/src/test/kotlin/com/mytetz/graph/PromptBuilderTest.kt` | Updates line 208 if the changed instruction changes the word the existing test pins. |

### Backend — `api` module (modify)

| File | Responsibility |
|---|---|
| `backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt` | Adds `DiagramMediaView`, `ImageMediaView`, `MediaView`; widens `SessionView` and `DoneEvent`. |
| `backend/api/src/main/kotlin/com/mytetz/api/Components.kt` | Builds `HttpClient(CIO)` and wires `CommonsClient`, on the model of `turnstileFactory`. |
| `backend/api/src/test/kotlin/com/mytetz/api/SessionRoutesTest.kt` | Adds a test proving `media` reaches the client in the shape Decision 4 gives. |

### Frontend

| File | Responsibility |
|---|---|
| `frontend/src/app/core/models.ts` | Adds `Media`, `DiagramMedia`, `ImageMedia`; widens `SessionView`. |
| `frontend/src/app/ui/verb-picker.component.ts` | Adds the fifth `VISUALIZE` row; checks `PICKER_HEIGHT` against three grid rows. |
| `frontend/src/app/ui/verb-picker.component.spec.ts` | Updates the pinned four-verb list to five. |
| `frontend/src/app/reader/media-renderer.component.ts` | New. Renders the SVG (zoomable, with an accessible title) and the image with its attribution. |
| `frontend/src/app/reader/media-renderer.component.spec.ts` | New. Component tests. |
| `frontend/src/app/reader/focus-card.component.ts` | Shows `MediaRendererComponent` when the node in focus carries `media`. |
| `frontend/src/app/reader/reader-page.component.ts` | Changes the `VISUALIZE` word in `VERB_WORDS` (`reader-page.component.ts:435`) so it agrees with `trail-rail.component.ts:10`. |
| `frontend/src/app/reader/trail-rail.component.ts` | Changes the `VISUALIZE` word in `VERB_LABELS` (`trail-rail.component.ts:10`) so it agrees. |
| `frontend/e2e/quiz.spec.ts` pattern → `frontend/e2e/visualize.spec.ts` | New. Stubbed Playwright run using `stubCatalogueAndSession`, `openQuantumPhysicsSession`, `selectPhrase`, `mockExplainStream`. |

---

## Task 1: `Media` and the nullable `Explanation.media` field

**Files:**
- Create: `backend/graph/src/main/kotlin/com/mytetz/graph/Media.kt`
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt`
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt`

**Interfaces:**
- Produces: `DiagramKind` (`SVG`, `MERMAID`), `DiagramMedia(kind, source)`, `ImageMedia(imageUrl,
  title, license, attributionHtml, commonsPageUrl)`, `Media(diagram, image = null)` — see Decision 3
  for the full code.
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
    // Simulates every explanation this project has ever stored: a raw document with no "media"
    // key at all, not a document whose "media" key is explicitly null.
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

- [ ] **Step 2: Run the tests and confirm the failure**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected: compile failure — `Media`, `DiagramMedia`, `ImageMedia` and `DiagramKind` are unresolved,
and `Explanation.copy(media = ...)` has no such parameter.

- [ ] **Step 3: Write `Media.kt` and widen `Explanation`**

Add the code from Decision 3 to `Media.kt`. In `Explanation.kt`, add `val media: Media? = null` as
the last constructor parameter, after `createdAtEpochMillis`, so every existing call site that builds
an `Explanation` by position keeps compiling.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationRepositoryTest"`
Expected: PASS, all tests including the pre-existing ones — this step must not regress
`findByKey returns null when absent` or any other existing case in the file.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/Media.kt \
        backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationRepositoryTest.kt
git commit -m "feat(graph): add the Media shape and a nullable Explanation.media field"
```

---

## Task 2: The allowlist SVG sanitiser

**Files:**
- Create: `backend/graph/src/test/kotlin/com/mytetz/graph/SvgSanitizerTest.kt`
- Create: `backend/graph/src/main/kotlin/com/mytetz/graph/SvgSanitizer.kt`

**Interfaces:**
- Produces: `sealed interface SvgSanitizeResult { data class Clean(val svg: String) :
  SvgSanitizeResult; data class Refused(val reason: String) : SvgSanitizeResult }`,
  `SvgSanitizer.sanitize(rawSource: String): SvgSanitizeResult`.

**The allowlist.** Only these elements pass: `svg`, `title`, `desc`, `defs`, `g`, `path`, `rect`,
`circle`, `ellipse`, `line`, `polyline`, `polygon`, `text`, `tspan`, `marker`, `linearGradient`,
`radialGradient`, `stop`, `clipPath`. Only these attributes pass, on any allowed element:
`id`, `class`, `viewBox`, `xmlns`, `width`, `height`, `x`, `y`, `x1`, `y1`, `x2`, `y2`, `cx`, `cy`,
`r`, `rx`, `ry`, `d`, `points`, `transform`, `fill`, `fill-opacity`, `stroke`, `stroke-width`,
`stroke-linecap`, `stroke-linejoin`, `stroke-opacity`, `opacity`, `font-family`, `font-size`,
`font-weight`, `text-anchor`, `offset`, `stop-color`, `stop-opacity`, `gradientUnits`,
`gradientTransform`, `markerWidth`, `markerHeight`, `refX`, `refY`, `orient`, `role`, `aria-label`,
`aria-hidden`. Every other element or attribute is dropped from the tree; the element's children, if
any, are kept and re-parented, since a dropped wrapper must not silently delete legitimate content
under it — only `<script>` and `<foreignObject>` (see below) drop their subtree entirely.

**The four refusal cases the acceptance criteria name, and how each is caught:**

1. **`<script>`** — not on the allowlist, and additionally checked by name so it is refused with its
   whole subtree removed rather than merely unwrapped, since a text child of `<script>` must never
   reach the DOM as text either.
2. **An event handler attribute** (`onload`, `onclick`, `onerror`, `onmouseover`, and every other
   attribute name starting `on`, case-insensitively) — refused by a name check independent of the
   allowlist, because a future allowlist edit must not be able to let one through by omission alone;
   the check is "does this attribute's name start with `on`", not "is this attribute in a list of
   known-bad names".
3. **An external `<image href>`** — `<image>` is not on the element allowlist at all, so any `<image>`
   element, external or not, is dropped. This is stricter than the requirement asks for the SVG path:
   the image path is Wikimedia Commons (Task 6), never an inline `<image>` inside the diagram, so
   there is no legitimate reason for the diagram source to carry one.
4. **`<foreignObject>`** — not on the allowlist, and additionally checked by name, refused with its
   whole subtree removed, for the same reason as `<script>`: it can carry arbitrary HTML, including a
   `<script>` one level down that a naive "drop this element, keep its children" rule would let
   through.

**Beyond the four named cases — defence the parser itself must provide.** The source is parsed as
XML before any allowlist walk runs, and the parser is configured to refuse a `<!DOCTYPE`
declaration outright and to resolve no external entity and load no external DTD. An untrusted XML
parse that allows external entities is the classic XXE vulnerability — a crafted `<!DOCTYPE>` can
read a file off the server or make it open a network connection — and nothing about "sanitise the
elements this document contains" defends against a payload that never reaches the element walk
because it was consumed by entity expansion first. This is written down because it is not one of the
four acceptance-criteria cases and would be easy to miss purely by working from that list; a
malformed or DOCTYPE-bearing document is refused before the allowlist ever runs, which also satisfies
the separate requirement that "the server parses the source and refuses a malformed document."

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
    fun `a script element is removed or the document is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><script>alert('x')</script><circle cx="1" cy="1" r="1"/></svg>"""
        )
        when (result) {
            is SvgSanitizeResult.Clean -> assertTrue("<script" !in result.svg && "alert" !in result.svg)
            is SvgSanitizeResult.Refused -> Unit
        }
    }

    @Test
    fun `an onload event handler is removed or the document is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg onload="alert('x')"><circle cx="1" cy="1" r="1"/></svg>"""
        )
        when (result) {
            is SvgSanitizeResult.Clean -> assertTrue("onload" !in result.svg)
            is SvgSanitizeResult.Refused -> Unit
        }
    }

    @Test
    fun `an external image href is removed or the document is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><image href="https://evil.example/tracker.png"/></svg>"""
        )
        when (result) {
            is SvgSanitizeResult.Clean -> assertTrue("evil.example" !in result.svg)
            is SvgSanitizeResult.Refused -> Unit
        }
    }

    @Test
    fun `a foreignObject is removed or the document is refused`() {
        val result = SvgSanitizer.sanitize(
            """<svg><foreignObject><body xmlns="http://www.w3.org/1999/xhtml"><script>alert('x')</script></body></foreignObject></svg>"""
        )
        when (result) {
            is SvgSanitizeResult.Clean -> assertTrue("foreignObject" !in result.svg && "script" !in result.svg)
            is SvgSanitizeResult.Refused -> Unit
        }
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
    fun `an element not on the allowlist is dropped but its safe children survive`() {
        val result = SvgSanitizer.sanitize(
            """<svg><a href="https://example.com"><circle cx="1" cy="1" r="1"/></a></svg>"""
        )
        val clean = assertIs<SvgSanitizeResult.Clean>(result)
        assertTrue("<circle" in clean.svg)
        assertTrue("href" !in clean.svg)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.SvgSanitizerTest"`
Expected: compile failure — `SvgSanitizer` and `SvgSanitizeResult` are unresolved.

- [ ] **Step 3: Write `SvgSanitizer.kt`**

Parse with `javax.xml.parsers.DocumentBuilderFactory`, configured before use:
`setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`,
`setExpandEntityReferences(false)`, `setXIncludeAware(false)`,
`setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)`. Walk the resulting `Document` recursively;
for each element, refuse (return `Refused`) if its local name, case-insensitively, is `script` or
`foreignobject`; otherwise, if it is not on the element allowlist, remove it from the tree and
re-parent its children in its place; otherwise keep it and filter its attributes down to the
allowlist, additionally dropping any attribute whose name starts with `on`, case-insensitively,
regardless of allowlist membership. Serialize the resulting `Document` back to a string with
`javax.xml.transform.TransformerFactory` (also with `FEATURE_SECURE_PROCESSING` set) and return
`Clean(serialized)`. Catch any parse exception and return `Refused(message)`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.SvgSanitizerTest"`
Expected: PASS, all nine tests.

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
  `.validate(source: String): ValidationResult` (reusing the `ValidationResult` sealed type from
  `ExplanationValidator.kt` rather than a second one of the same shape).

A diagram is a handful of simple shapes, not an illustration; a legitimate SVG this project would
ever ask the model for is a few thousand characters. `DEFAULT_MAX_SOURCE_CHARS` defaults to `20_000`,
overridable from the environment with a safe fallback, the same rule every config value in this
project follows — a limit exists to bound worst-case storage and worst-case sanitiser CPU on an
adversarial or looping generation, not to constrain an ordinary diagram.

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

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.MediaValidatorTest"`
Expected: compile failure — `MediaValidator` is unresolved.

- [ ] **Step 3: Write `MediaValidator.kt`**

A class of one method: return `ValidationResult.Invalid("diagram source longer than $maxSourceChars
characters")` when `source.length > maxSourceChars`, otherwise `ValidationResult.Valid(source)`.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.MediaValidatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/MediaValidator.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/MediaValidatorTest.kt
git commit -m "feat(graph): add the media source size validator"
```

---

## Task 4: The `VISUALIZE` branch in `ExplanationGraph.generate`

**Files:**
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationValidator.kt`
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationGraph.kt`
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt`

**Interfaces:**
- Consumes: `Media`, `DiagramMedia`, `DiagramKind` (Task 1); `SvgSanitizer.sanitize` (Task 2);
  `MediaValidator.validate` (Task 3); `LlmClient.structured`, `StructuredRequest`, `StructuredResult`
  (already in `:backend:llm`, per `ExplanationGraphTest.kt:11-12`'s own imports).
- Produces: `ExplanationValidator.validateStructuredBody(rawBody: String): ValidationResult`.

**Step 0 — the validator fix Decision 2 requires.** `ExplanationValidator.validate` gates on
`stopReason` first (`ExplanationValidator.kt:49-64`) because a refusal or a truncation can arrive as
a plausible-looking `end_turn`-shaped body on the streaming path. A forced tool call cannot arrive
that way: `LlmClient.structured` either returns a `StructuredResult` because the model filled the
tool's required fields, or it throws `LlmStructuredOutputMissingException` — there is no third,
silent case for a stop-reason gate to catch. Extract `ExplanationValidator`'s length, tag and
refusal-prefix checks (the body of the function from `val body = rawBody.trim()` at
`ExplanationValidator.kt:66` onward) into a `private fun checkBody(rawBody: String):
ValidationResult`, keep `validate(rawBody, stopReason)` calling it after its own gate exactly as
today, and add `fun validateStructuredBody(rawBody: String): ValidationResult = checkBody(rawBody)`
for the `VISUALIZE` path to call instead.

- [ ] **Step 1: Write the failing malformed-SVG test**

Append to `ExplanationGraphTest.kt`, near the other validation-failure tests
(`a body the validator rejects is not persisted`, `ExplanationGraphTest.kt:627`):

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
```

(`llm` here is `FakeLlmClient`, already imported at `ExplanationGraphTest.kt:3`; `nextStructuredJson`
is its own field for a `structured` fixture, the same shape `nextBody` gives `stream`. The malformed
SVG above is deliberately unbalanced — a missing `</circle>` closer — exactly like this task's own
`SvgSanitizerTest`'s `malformed XML is refused` case.)

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationGraphTest"`
Expected before this task's code: compile failure or an uncaught assertion, because `generate` still
calls `llm.stream` unconditionally for every verb and never inspects `nextStructuredJson`.

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

    val mediaValidator = MediaValidator()
    val validatedSvg = when (val r = mediaValidator.validate(sanitized)) {
        is ValidationResult.Valid -> r.body
        is ValidationResult.Invalid ->
            throw GenerationFailedException("visualize SVG too large for $key: ${r.reason}")
    }

    // Commons lookup happens here, before the document is built — see Task 6. A Commons
    // failure or an empty result is degradation, not a generation failure: `image` stays null
    // and the diagram-only document is still persisted. See Task 7's test for the contract.
    val image = commonsClient?.let { runCatching { it.findImage(request.span, request.ancestors) }.getOrNull() }

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
        promptVersion = config.promptVersion,
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
svg: String)`, added to `PromptBuilder.kt` or a new small file beside it. `commonsClient` is a
constructor parameter added to `ExplanationGraph`, nullable, defaulting to `null` so every existing
test that builds an `ExplanationGraph` without one keeps compiling (Task 6 wires a real one; Task 7
tests both a present client that fails and an absent one, and both must degrade the same way).

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationGraphTest"`
Expected: PASS, all tests including the pre-existing `EXPLAIN`/`DIG_DEEPER`/etc. cases — this step
must not regress the `llm.stream` path for every other verb.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationValidator.kt \
        backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationGraph.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt
git commit -m "feat(graph): generate VISUALIZE through a structured call, sanitised before persistence"
```

---

## Task 5: The `VISUALIZE` prompt and the `VERSION` bump

**Files:**
- Modify: `backend/graph/src/main/kotlin/com/mytetz/graph/PromptBuilder.kt`
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/PromptBuilderTest.kt`

**Interfaces:**
- Produces: `PromptBuilder.visualizeSystem(): String`, `PromptBuilder.visualizeUser(context:
  PromptContext): String`, `PromptBuilder.visualizeSchema(): Map<String, Any>`,
  `PromptBuilder.VISUALIZE_TOOL_NAME`, `PromptBuilder.VISUALIZE_TOOL_DESCRIPTION`.

The existing `instructionFor(Verb.VISUALIZE)` branch (`PromptBuilder.kt:142-143`) asks for prose
inside `system()`'s "1 to 3 sentences, no markdown" contract (`PromptBuilder.kt:76-88`) — the exact
conflict the issue's evidence names, since an SVG document is neither prose nor markdown-free text.
This task replaces that branch's use for `VISUALIZE` with a dedicated system prompt and a JSON Schema
for the forced tool call, rather than patching the shared `system()`/`user()` pair to somehow serve
both shapes.

- [ ] **Step 1: Write the failing test**

```kotlin
// In PromptBuilderTest.kt, a new test near the existing VISUALIZE-related ones:
@Test
fun `the visualize schema requires both an explanation and an svg field`() {
    @Suppress("UNCHECKED_CAST")
    val schema = PromptBuilder.visualizeSchema()
    assertTrue(schema.containsKey("explanation"))
    assertTrue(schema.containsKey("svg"))
}

@Test
fun `the visualize system prompt asks for one short sentence, not one to three`() {
    // The shared system() prompt says "1 to 3 sentences"; the visualize path asks for one,
    // since the sentence sits beside a diagram rather than carrying the whole answer.
    assertTrue("one" in PromptBuilder.visualizeSystem().lowercase())
}
```

Read `PromptBuilderTest.kt:208` first and update whatever word it pins if this task's instruction
text changes it — the issue names this line explicitly as one that may need to change.

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.PromptBuilderTest"`
Expected: compile failure — `visualizeSchema` and `visualizeSystem` are unresolved.

- [ ] **Step 3: Write the prompt and bump `VERSION`**

Change `PromptBuilder.VERSION` from `"v3"` to `"v4"`. Add the new members, and update
`instructionFor`'s `Verb.VISUALIZE` branch's comment to say it is unreachable for `VISUALIZE` once
`ExplanationGraph` branches before calling `PromptBuilder.user` for that verb (Task 4) — or remove
the branch outright and make `instructionFor` a `when` over the remaining five verbs, whichever
reads more clearly once Task 4's diff exists; a `when` that is no longer exhaustive over `Verb` is a
compile error, which is exactly the signal that every call site was found.

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

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.PromptBuilderTest"`
Expected: PASS, including the pre-existing tests for the other four verbs' instructions.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/main/kotlin/com/mytetz/graph/PromptBuilder.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/PromptBuilderTest.kt
git commit -m "feat(graph): give VISUALIZE its own prompt and schema, and bump PromptBuilder.VERSION"
```

---

## Task 6: The Wikimedia Commons client

**Files:**
- Create: `backend/graph/src/test/kotlin/com/mytetz/graph/CommonsClientTest.kt`
- Create: `backend/graph/src/main/kotlin/com/mytetz/graph/CommonsClient.kt`
- Modify: `backend/graph/build.gradle.kts`

**Interfaces:**
- Produces: `CommonsClient(httpClient: HttpClient)`, `.findImage(span: String, ancestors:
  List<Ancestor>): ImageMedia?`.

**Vendor facts used below, and their source.** Read at
`https://www.mediawiki.org/wiki/API:Main_page`, `https://commons.wikimedia.org/wiki/Commons:API`,
`https://www.mediawiki.org/wiki/API:Imageinfo` and
`https://www.mediawiki.org/wiki/Extension:CommonsMetadata` on 2026-09-19:

- The endpoint is `https://commons.wikimedia.org/w/api.php`
  (`https://commons.wikimedia.org/wiki/Commons:API`: "the MediaWiki API… is accessible at" this
  path; confirmed again at `https://www.mediawiki.org/wiki/API:Main_page`).
- `format=json` and `formatversion=2` select the modern JSON response shape
  (`https://www.mediawiki.org/wiki/API:Main_page`).
- A keyword search over pages is `action=query&list=search&srsearch=<terms>`
  (`https://www.mediawiki.org/wiki/API:Main_page`).
- Image metadata for one title is `action=query&prop=imageinfo`; `iiprop=url` "gives URL to the file
  and the description page", and `iiprop=extmetadata` gives "formatted metadata combined from
  multiple sources… HTML formatted" and is "an expensive property to request"
  (`https://www.mediawiki.org/wiki/API:Imageinfo`).
- `extmetadata`'s fields include `LicenseShortName` ("short human-readable license name"),
  `LicenseUrl`, `Artist`, `Credit` ("source"), and `Attribution` ("custom attribution that should
  replace Artist + Credit") (`https://www.mediawiki.org/wiki/Extension:CommonsMetadata`).

**Confirm before use — facts this plan could not verify from the pages read:**

- The exact `srsearch` scoping needed to search only the `File:` namespace (commonly `srnamespace=6`
  on other MediaWiki search calls) was not stated in the pages read. **Confirm before use** against
  `action=help&modules=query+search` on the live API (`https://commons.wikimedia.org/w/api.php
  ?action=help&modules=query%2Bsearch`) before Step 3 below is written.
- The exact JSON field name `imageinfo` uses for the description page URL (distinct from the file's
  own binary URL) was not quoted in the `API:Imageinfo` page as fetched. **Confirm before use**
  against `action=help&modules=query+imageinfo` before `commonsPageUrl` is populated in Step 3.
- Wikimedia's rate-limit and required `User-Agent` policy for anonymous API callers were not stated on
  either page fetched. **Confirm before use** against
  `https://api.wikimedia.org/wiki/Documentation/Core_concepts/Request_limits` (or whatever page the
  live site's own linked policy names at implementation time) before this client ships, and set a
  descriptive `User-Agent` header naming this project and a contact — an unset or generic one is
  routinely rate-limited or blocked outright by Wikimedia's infrastructure.
- The precise relevance-filtering rule ("filtered by relevance and licence", spec section 9) is a
  product decision, not a vendor fact — this plan defers its exact shape to Step 3's implementer,
  with the floor that only a result carrying a `LicenseShortName` recognised as free-reusable
  (for example, a `CC BY`, `CC BY-SA` or public-domain short name) is ever returned; anything else is
  treated the same as no result.

- [ ] **Step 1: Add the Ktor client dependency**

In `backend/graph/build.gradle.kts`, add `implementation(libs.ktor.client.core)`,
`implementation(libs.ktor.client.cio)` and `testImplementation(libs.ktor.client.mock)` — the same
catalog aliases `backend/api/build.gradle.kts` already uses.

- [ ] **Step 2: Write the failing tests**

On the model of `backend/api/src/test/kotlin/com/mytetz/api/FreemiusApiClientTest.kt:56-88`,
one success, one non-2xx status, one thrown `IOException`, and one empty-result case, each of the
last three asserting `null`:

```kotlin
package com.mytetz.graph

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
        // Confirm-before-use: the exact response shape below follows the fields this plan's own
        // "vendor facts" section confirmed — extmetadata.LicenseShortName, Artist/Credit — and the
        // task's own note that the description-page URL field name needs live confirmation. Update
        // this fixture once that field name is confirmed.
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

        val image = clientReturning(body).findImage("escape velocity", emptyList())

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
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.CommonsClientTest"`
Expected: compile failure — `CommonsClient` is unresolved.

- [ ] **Step 4: Write `CommonsClient.kt`**

On the model of `FreemiusApiClient.fetchState`
(`backend/api/src/main/kotlin/com/mytetz/api/FreemiusApiClient.kt:168-227`): a `try`/`catch` around
the whole call, logging the exception's class name only (never its message, for the same reason
`FreemiusApiClient.kt:206-219` gives — a vendor error body can carry text this project must not put
in a log line, and here that risk is smaller but the pattern is kept for consistency), a non-2xx
status logged and answered as `null`, and a decode failure caught the same way. Use the search
endpoint and the `imageinfo` endpoint confirmed above, and mark the two "confirm before use" items
with a `// CONFIRM BEFORE USE:` comment at the exact line each one affects, so a reviewer can find
them without re-reading this plan.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.CommonsClientTest"`
Expected: PASS, all four tests.

- [ ] **Step 6: Commit**

```bash
git add backend/graph/build.gradle.kts \
        backend/graph/src/main/kotlin/com/mytetz/graph/CommonsClient.kt \
        backend/graph/src/test/kotlin/com/mytetz/graph/CommonsClientTest.kt
git commit -m "feat(graph): add the Wikimedia Commons image lookup client"
```

---

## Task 7: Degradation — a graph test for a Commons failure

**Files:**
- Modify: `backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt`

**Interfaces:**
- Consumes: `CommonsClient` (Task 6), the `commonsClient` constructor parameter on
  `ExplanationGraph` (Task 4).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `a Commons failure stores the diagram only and the flow still ends with Done`() = runTest {
    llm.nextStructuredJson = """{"explanation":"A short valid sentence about the span.","svg":"<svg><circle cx=\"1\" cy=\"1\" r=\"1\"/></svg>"}"""
    val failingCommons = CommonsClient(HttpClient(MockEngine { throw java.io.IOException("down") }))
    val graphWithCommons = graphWith().let {
        ExplanationGraph(repository, llm, ExplanationValidator(), config, commonsClient = failingCommons)
    }
    val visualizeRequest = request(verb = Verb.VISUALIZE)

    val chunks = graphWithCommons.getOrGenerate(visualizeRequest).toList()

    val done = chunks.filterIsInstance<GraphChunk.Done>().single()
    assertNotNull(done.explanation.media?.diagram)
    assertNull(done.explanation.media?.image)
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationGraphTest"`
Expected before Task 4/6's `commonsClient` wiring: compile failure — `ExplanationGraph` has no
`commonsClient` parameter yet. After Task 4/6 land first, this specific test still needs writing and
should fail if `generate`'s Commons call is not wrapped in `runCatching` (an unhandled `IOException`
propagating out of `getOrGenerate` instead of `null`).

- [ ] **Step 3: Confirm the `runCatching` wrapping from Task 4 covers this case**

Task 4's own sketch already wraps the Commons call in `runCatching { }.getOrNull()`. This step is
verification, not new production code: run the test and confirm it needs no further change. If it
does, the fix belongs in Task 4's diff, not a new one — the two tasks describe one code change.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :backend:graph:test --tests "com.mytetz.graph.ExplanationGraphTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/graph/src/test/kotlin/com/mytetz/graph/ExplanationGraphTest.kt
git commit -m "test(graph): prove a Commons failure degrades to a diagram-only document"
```

---

## Task 8: Wire the Commons client in `Components.kt`

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Components.kt`

**Interfaces:**
- Consumes: `CommonsClient` (Task 6).

- [ ] **Step 1: Add the factory and pass it to `ExplanationGraph`**

On the model of `turnstileFactory` (`Components.kt:104`, `HttpClient(CIO)`, built eagerly because
`CommonsClient`'s own construction never throws — it takes no required credential, unlike
`freemiusApiClientFactory`):

```kotlin
commonsClientFactory: () -> CommonsClient = { CommonsClient(HttpClient(CIO)) },
```

and

```kotlin
val commonsClient: CommonsClient = commonsClientFactory()
```

Pass `commonsClient` to wherever `ExplanationGraph` is built inside `Components` today (find the
existing construction site next to where `ExplanationRepository` and `ExplanationValidator` are
already assembled).

- [ ] **Step 2: Run the module's existing tests**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.ComponentsTest"`
Expected: PASS, no regression — this task adds a component and changes no existing behaviour.

- [ ] **Step 3: Commit**

```bash
git add backend/api/src/main/kotlin/com/mytetz/api/Components.kt
git commit -m "feat(api): wire the Commons client into ExplanationGraph"
```

---

## Task 9: The wire shape — `SessionRoutes.kt`

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/SessionRoutesTest.kt`

**Interfaces:**
- Produces: `DiagramMediaView`, `ImageMediaView`, `MediaView` (Decision 4's code).

- [ ] **Step 1: Write the failing route test**

Add to `SessionRoutesTest.kt`, on the model of whatever existing test already drives a `VISUALIZE`
request end to end through the test app (search the file for `Verb.VISUALIZE` or `"VISUALIZE"`
first; if none exists yet, build the request the same way the file's existing explain tests do):

```kotlin
@Test
fun `a VISUALIZE explanation carries media on both the done event and the session view`() = runTest {
    // ... existing test-app setup (FakeLlmClient configured with a VISUALIZE structured answer,
    // a signed-in principal, an entitled subscription) ...

    val doneEvent = /* parse the "done" SSE event from the explain response */
    assertNotNull(doneEvent.media)

    val sessionView = /* GET /api/sessions/{id} */
    assertTrue(sessionView.media.containsKey(doneEvent.contentKey))
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.SessionRoutesTest"`
Expected: compile failure — `DoneEvent` and `SessionView` have no `media` property yet.

- [ ] **Step 3: Widen the two wire types**

Add the code from Decision 4 to `SessionRoutes.kt`. Update `eventFor`'s `GraphChunk.Done` branch
(`SessionRoutes.kt:720-723`) to build the `DoneEvent` with `media = chunk.explanation.media?.toView()`,
and `LearningSession.toView` (`SessionRoutes.kt:952-961`) to also build the sparse `media` map from
whatever the caller already has loaded — this needs the bodies map's own caller (`load`,
`SessionRoutes.kt:441`) to also carry each node's `Explanation.media`, so trace that call up to
`SessionService.load` and widen its return type the same way, keeping the existing `bodies:
Map<String, Explanation>`-shaped value if that is what it already returns (check before assuming a
new field is needed there at all — `bodies.mapValues { it.value.body }` at `SessionRoutes.kt:442`
already reads a full `Explanation` per key, so `media` may already be reachable with no change to
`SessionService`).

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :backend:api:test --tests "com.mytetz.api.SessionRoutesTest"`
Expected: PASS, and no regression on the existing `SessionView`/`DoneEvent` tests — both new fields
default to empty/null, so a caller that never asks for `VISUALIZE` sees no change in its own
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
- Produces: `DiagramMedia`, `ImageMedia`, `Media` (TypeScript mirrors of Decision 3's Kotlin types);
  widens `SessionView`.

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

- [ ] **Step 2: Run the frontend build**

Run: `cd frontend && npx tsc --noEmit`
Expected: PASS. This task adds types only; nothing yet constructs or reads a `Media` value, so there
is no failing test to write first here — the "test" is the type-checker accepting every existing
caller of `SessionView` unchanged, since `media` is additive.

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

**Interfaces:** none new; `VERBS` gains one entry.

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

- [ ] **Step 2: Run the test to see it fail**

Run: `cd frontend && npm test -- --watch=false --include='**/verb-picker.component.spec.ts'`
Expected: FAIL — the rendered list still has four entries.

- [ ] **Step 3: Add the fifth entry**

In `VERBS` (`verb-picker.component.ts:41-46`), using the exact name and caption confirmed against
`docs/mytetz-design-reference.html`'s own picker row ("Show me a diagram" /
"A sketch instead of a paragraph", confirmed by reading that file directly — its "section 5b" label
in the issue is not itself confirmed, but the row's own text is present verbatim):

```typescript
{ verb: 'VISUALIZE', name: 'Show me a diagram', caption: 'A sketch instead of a paragraph' },
```

Update the block comment above `VERBS` (`verb-picker.component.ts:37-40`) to drop the now-outdated
"`VISUALIZE` is slice 4… that row arrives with the feature" note, since the feature has arrived.

- [ ] **Step 4: Check `PICKER_HEIGHT` against three grid rows**

`PICKER_HEIGHT` is `240` (`verb-picker.component.ts:35`) and the CSS repeats it at
`max-height: 240px` (`verb-picker.component.ts:111`). The grid is two columns
(`grid-template-columns: 1fr 1fr`, `verb-picker.component.ts:130`), so four verbs fill two rows and
five verbs fill three. Measure a rendered three-row grid manually (Step 6 of this task) against the
existing 240px cap plus the `16px` padding and `10px` gap already in the styles
(`verb-picker.component.ts:113,116`); if three rows overflow 240px, raise both literals together —
they are two copies of one number by the component's own design (`verb-picker.component.ts:31-34`'s
comment already states this), so change the `const` and the `styles` string in the same commit.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `cd frontend && npm test -- --watch=false --include='**/verb-picker.component.spec.ts'`
Expected: PASS.

- [ ] **Step 6: Manually verify the picker's height in a running app**

Start the app per this project's `run` skill, highlight a phrase, open the picker, and confirm the
fifth row is visible without the picker needing to scroll on an ordinary viewport. Adjust
`PICKER_HEIGHT` per Step 4 if it does not fit.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/ui/verb-picker.component.ts \
        frontend/src/app/ui/verb-picker.component.spec.ts
git commit -m "feat(ui): add the fifth verb-picker row for Visualize"
```

---

## Task 12: The media renderer component

**Files:**
- Create: `frontend/src/app/reader/media-renderer.component.spec.ts`
- Create: `frontend/src/app/reader/media-renderer.component.ts`

**Interfaces:**
- Produces: `MediaRendererComponent`, `input.required<Media>('media')`.

**Angular's `[innerHTML]` sanitiser, confirmed at `https://angular.dev/best-practices/security` on
2026-09-19.** The documentation confirms Angular sanitizes an untrusted value bound through
`[innerHTML]` by default and gives the general case: "Angular recognizes the value as unsafe and
automatically sanitizes it, which removes the `script` element but keeps safe content." It does
**not** state, in the text read, the exact element-by-element and attribute-by-attribute rule Angular
applies to an `<svg>` subtree specifically — whether an event-handler attribute or a
`<foreignObject>` inside an already-parsed `<svg>` is stripped the same way `<script>` is. **Confirm
before use:** before Task 13 binds any SVG through `[innerHTML]`, write a component test in this
task's own spec file that binds a string carrying `onload="…"` and a `<foreignObject>` through
`[innerHTML]` in a real (non-mocked) `TestBed`-rendered component, and read the actual resulting DOM
— not the documentation's prose — to confirm what Angular's sanitizer does with it on the Angular
version this project runs.

That confirmation changes nothing about where the real security boundary sits: `SvgSanitizer`
(Task 2), running on the server, is the boundary this plan relies on, because it is the one place
this project controls and tests directly. Angular's own sanitizer is depended on for defence in
depth only. Concretely: `MediaRendererComponent` binds `media().diagram.source` — a string that has
already passed `SvgSanitizer` on the server, and travelled over the wire as plain JSON text, never as
a value the frontend itself marked trusted — through `[innerHTML]`, and does **not** call
`DomSanitizer.bypassSecurityTrustHtml` on it. Calling `bypassSecurityTrustHtml` would tell Angular to
skip its own sanitizer entirely for that value, which is the opposite of defence in depth: it would
make the server-side allowlist the *only* check standing between the model's own output and the DOM,
with nothing to catch a defect in `SvgSanitizer` itself.

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

  it('renders the SVG with an accessible title', () => {
    fixture.componentRef.setInput('media', media());
    fixture.detectChanges();
    const svg = fixture.nativeElement.querySelector('svg');
    expect(svg?.querySelector('title')?.textContent).toBe('A circle');
  });

  it('shows the attribution whenever media.kind is IMAGE-bearing, i.e. image is present', () => {
    fixture.componentRef.setInput(
      'media',
      media({
        image: {
          imageUrl: 'https://upload.wikimedia.org/example.jpg',
          title: 'Example.jpg',
          license: 'CC BY-SA 4.0',
          attributionHtml: 'By Example Author, CC BY-SA 4.0',
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

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd frontend && npm test -- --watch=false --include='**/media-renderer.component.spec.ts'`
Expected: compile failure — `MediaRendererComponent` does not exist yet.

- [ ] **Step 3: Write `media-renderer.component.ts`**

A standalone component, `input.required<Media>('media')`, binding `media().diagram.source` through
`[innerHTML]` inside a zoomable wrapper (a plain CSS `overflow: auto` container is enough for "look
at it larger"; a pinch/scroll-zoom control is a polish item, not required by the acceptance
criteria), and rendering the `image` block — `<img>`, the license text, and `attributionHtml` bound
through `[innerHTML]` too, since it is server-produced from `extmetadata` text and not learner input
— only when `media().image` is not null.

- [ ] **Step 4: Run the tests and confirm they pass**

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

Add a test to `focus-card.component.spec.ts` asserting that, when the node in focus carries `media`,
`app-media-renderer` appears in the rendered output, and that it does not appear for a node with no
`media`. Read the existing spec file first to match its own fixture-building style before writing
this.

- [ ] **Step 2: Run the test to see it fail**

Run: `cd frontend && npm test -- --watch=false --include='**/focus-card.component.spec.ts'`
Expected: FAIL — `app-media-renderer` is never rendered today.

- [ ] **Step 3: Show the renderer**

Add `MediaRendererComponent` to the component's `imports`, and, near wherever the body text itself
renders, add:

```html
@if (media(); as m) {
  <app-media-renderer [media]="m" />
}
```

where `media` is a new `computed` signal reading whatever the store already exposes for the node in
focus's content key — trace how `explanations` is read today in this file to find the matching
signal for `media`.

- [ ] **Step 4: Run the tests and confirm they pass**

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

**Decision:** use **"Diagram"** in both places, not "Visual". `reader-page.component.ts:435` already
says "Diagram"; `trail-rail.component.ts:10` says "Visual". "Diagram" names the artifact the verb
produces, matching the concrete-noun pattern the picker's own new row uses ("Show me a diagram");
"Visual" is vaguer and could as easily describe the image half alone.

- [ ] **Step 1: Write the failing tests**

If either component's spec file asserts the current word for `VISUALIZE` anywhere (search both spec
files for `'Visual'` and `'Diagram'` first), update that assertion to expect `"Diagram"` in both
places before changing the source.

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd frontend && npm test -- --watch=false --include='**/trail-rail.component.spec.ts'`
Expected: FAIL, if a test pinned "Visual"; otherwise no test exists yet for this word and this step
is skipped in favour of Step 4's manual check.

- [ ] **Step 3: Change `trail-rail.component.ts:10`**

`VISUALIZE: 'Visual'` becomes `VISUALIZE: 'Diagram'`.

- [ ] **Step 4: Run the tests and confirm they pass, or verify manually**

Run: `cd frontend && npm test -- --watch=false`
Expected: PASS, no regression.

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

Read every one of the four named helpers' signatures before writing the spec, so the stub shapes this
test builds match what they actually expect rather than a guess.

- [ ] **Step 2: Write `visualize.spec.ts`**

Follow the exact structure an existing e2e spec already uses (`find frontend/e2e -name '*.spec.ts'`,
read the first result fully, the same first step `docs/superpowers/plans/2026-09-15-quiz-assess.md`'s
own Task 13 takes). The scenario:

1. `stubCatalogueAndSession(...)` for one topic and one seed node.
2. `openQuantumPhysicsSession(...)`.
3. `selectPhrase(...)` on the seed's text.
4. Choose "Show me a diagram" from the picker.
5. `mockExplainStream(...)` with a `done` event carrying a `media` field shaped by Decision 4 — an
   SVG source and no image, the diagram-only case, since that is the case every layer below already
   guarantees degrades cleanly.
6. Assert an `<svg>` is visible on the focus card after the stream completes.

Write the actual Playwright code once Step 1's helpers are in hand — guessing their exact call shape
here would risk exactly the kind of untested placeholder this project's own plans rule out.

- [ ] **Step 3: Run it**

Run: `cd frontend && CI=1 npx playwright test visualize.spec.ts`
Expected: PASS. (`CI=1`, per this project's own note that port 4300 is sometimes held by another
process and Playwright's dev-server reuse behaves differently without it.)

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

- [ ] **Step 4: Re-read the issue's acceptance criteria and check each one against what was built**

- Sanitiser tests for `<script>`, `onload`, an external `<image href>` and `<foreignObject>` —
  Task 2.
- A malformed SVG is refused before persistence — Task 4's malformed-SVG test.
- A Commons failure yields a diagram-only document and no error — Task 7.
- The attribution renders whenever `media.kind` is `IMAGE` (in this plan's shape: whenever
  `media.image` is present) — Task 12.
- A stubbed end-to-end test shows a diagram after Visualize — Task 15.
- All three suites are green — Steps 1-3 above.

If any box is unchecked, that is a gap this plan missed — add the task, do not close the issue with a
known gap.

- [ ] **Step 5: Push the branch and open the pull request**

Follow this repository's own PR conventions (`gh pr list --state merged --limit 5` for title/body
style). Reference issue #17 in the PR body so it closes on merge.
