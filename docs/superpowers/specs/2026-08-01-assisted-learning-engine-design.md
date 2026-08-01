# Assisted Learning Engine — Design

**Project:** mytetz.com
**Date:** 2026-08-01
**Status:** Approved for planning
**Spec:** A of four (see Scope)

---

## 1. Purpose

An interactive workbook that teaches a topic sentence by sentence. The user picks a topic, reads an introductory sentence, highlights any word, phrase or symbol they don't know, and presses an action button to get a deeper explanation — one that resolves *inside the context they came from*, not as a standalone lookup.

The worked example from the source brief, which is also the acceptance criterion for the whole engine:

> Topic: **Quantum Physics** → seed text mentions *"fundamental physical theory"* → user highlights it → explanation mentions *"microscopic realm"* → user highlights that → the explanation must describe the subatomic scale, **not bacteria and cells**.

Contextual resolution is the product. Everything in this design exists to make it correct, cheap and repeatable.

---

## 2. Scope

The overall product decomposes into four specs. Each gets its own design → plan → implementation cycle.

| | Subsystem | Status |
|---|---|---|
| **A** | **Learning engine** — topic seeds, contextual explain, action verbs, context chain, assessment | **This document** |
| **B** | Accounts, 7-day trial, Freemius subscription (€10/month), entitlement gating | Later spec |
| **C** | Public SEO/AEO surface — server-rendered indexable pages, sitemaps, structured data | Later spec |
| **D** | Infrastructure — fly.io, Cloudflare, Atlas, CI, observability | Thin slice folded into this spec (Slice 0); expanded later |

### In scope

Topic catalogue and seeds; the content-addressed explanation graph; the Explain / Dig Deeper / Broader Picture / Side View verbs; Test Me and Exam; Visualize; streaming delivery; cost and abuse guardrails; a deployable skeleton.

### Explicitly out of scope

Authentication and user accounts; trial logic; Freemius integration; billing; server-side rendering; sitemaps and structured data; personalised explanations; spaced repetition; multi-language.

### Interface to spec B

The engine works against an abstract **principal** — an identifier with a quota attached, which may be anonymous or authenticated. Spec B supplies real identity and tier-specific quota values. No engine code changes when it lands.

### Interface to spec C

Explanations are user-independent and immutable by construction, so spec C publishes them directly. It needs no separate content pipeline. The `requestCount` field provides the demand signal for deciding what is worth publishing.

---

## 3. Locked product decisions

| Decision | Choice | Rationale |
|---|---|---|
| Content source | LLM-generated, with an optional web tool | No licensing constraints, uniform voice, unique text for SEO, fully cacheable |
| LLM hosting | Commercial API, backend-only | Zero fixed cost; scales with revenue. A self-hosted GPU costs $900–2,500/month before the first subscriber, which purely organic acquisition cannot support |
| Topic entry | **Curated catalogue only** | Predictable cost, quality control, no free-text path into a prompt. Trade-off accepted: catalogue size is the SEO ceiling |
| Visualize | LLM-authored diagrams + Wikimedia Commons lookup | Diagram-as-code is cheap, deterministic, accessible and re-renderable; Commons supplies real imagery where it genuinely helps, free and licence-clean |
| Reading surface | Focus card + breadcrumb + session trail rail | The breadcrumb *is* the context chain sent to the model, so UI and prompt stay in lockstep; survives depth and mobile |
| v1 verbs | All text verbs + quizzes; Visualize as a separate slice | Text verbs are near-free once Explain exists; Visualize is its own vertical and must not block launch |
| Backend | Kotlin + **Ktor** | ~50 MB heap and sub-second cold start suit fly.io scale-to-zero; first-class SSE; no coroutine/Reactor impedance mismatch |
| Content model | Content-addressed explanation graph | Caching, SEO publishing and testability all fall out of one boundary |

---

## 4. Architecture

### 4.1 Deployment

One Ktor application on fly.io serving both the JSON/SSE API and the compiled Angular bundle, with Cloudflare in front for DNS, CDN, WAF, cache rules and bot control. MongoDB Atlas for persistence. Two moving parts.

When spec C requires server-side rendering, an Angular SSR service joins as a second fly process. To keep that possible, **the Angular reader must not touch `window`, `document` or `localStorage` on its render path**. Designing for it now is free; retrofitting it is a rewrite.

### 4.2 Kotlin modules

Separate Gradle modules, so the compiler enforces dependency direction rather than a convention that erodes.

| Module | Responsibility | Depends on |
|---|---|---|
| `api` | Ktor routes, SSE framing, serialization, error mapping. The only module aware HTTP exists | all below |
| `session` | Per-principal traversal tree; assembles the context chain. Never calls the LLM directly | `graph`, `catalog` |
| `assess` | Test Me and Exam; quiz validation | `session`, `graph`, `llm` |
| `quota` | Principal abstraction, per-principal limits, global spend breaker, cost ledger | `persistence` |
| `graph` | Content-addressed explanation store; key derivation; get-or-generate. **No knowledge of users** | `llm`, `persistence` |
| `catalog` | Curated topics, slug resolution, seed references | `persistence` |
| `llm` | Vendor-agnostic port: streaming completion, structured output, tool calls. One adapter behind it | — |
| `persistence` | Mongo client, collection accessors, index definitions | — |

### 4.3 The load-bearing boundary

`graph` has no concept of a user. It answers exactly one question: *given this topic, this ancestor chain, this highlighted span and this verb, what is the explanation?* Same inputs, same answer, for everyone.

This single boundary does three jobs:

- **Cache.** The second user down a path pays nothing.
- **SEO pipeline.** Explanations are already user-independent, so spec C publishes them as-is.
- **Testability.** With a stubbed `llm` port, `graph` is a pure function of its inputs.

`session` sits above it, holding the per-user traversal as a tree of pointers. Prose never enters a session document.

### 4.4 Angular structure

Standalone components with signals for state. No NgRx — the state here is a tree plus a stream, not a redux problem.

| Feature | Responsibility |
|---|---|
| `catalog` | Browse and select a topic |
| `reader` | Focus card, breadcrumb, session trail rail |
| `selection` | DOM `Range` → stable character offsets in the source body |
| `stream` | SSE client, progressive rendering |
| `assess` | Test Me and Exam UI, scoring |
| `core` | API client, session store, error surface |

---

## 5. Data model

### 5.1 Key derivation

```
contentKey = sha256( parentKey | span | verb | variant | promptVersion | modelFamily )
```

In the `span` position, a `SEED` carries the **topic slug** instead of highlighted text, and its `parentKey` is the empty string:

```
seedKey = sha256( "" | topicSlug | SEED | 0 | promptVersion | modelFamily )
```

Every non-seed explanation inherits its topic through `parentKey`, so the slug never needs to appear again.

This is a Merkle chain. The parent's hash carries the entire ancestry in 32 bytes, so:

- Identity is O(1) to compute at any depth.
- `"microscopic realm"` under *Quantum Physics* is structurally incapable of colliding with the same phrase under *Microbiology*. **Context isolation is a property of the key, not a prompt-engineering hope.**
- Including `promptVersion` and `modelFamily` gives cache invalidation with no migration: improving a prompt changes every downstream key, old documents orphan harmlessly, new ones generate lazily. Rollback is reverting a string.

**`modelFamily`, not `modelId`, is in the key — deliberately.** A vendor's routine point releases should not invalidate the entire corpus; only a deliberate move between model families should. `modelId` is still recorded on every document for forensics and cost attribution.

### 5.1.1 Variants

`variant` defaults to `0`. It exists so that asking for another perspective produces a *new* key rather than overwriting an immutable document.

The explain request may carry an optional `variant`. The UI's **Side View** action sends `n + 1`, where `n` is the highest variant already present in the session for that `(parentNodeId, span)` pair; navigating back to a view already seen simply re-requests its known key and hits cache. The server rejects `variant > MAX_VARIANTS`, which bounds what would otherwise be an unlimited regenerate button pointed at a metered API.

The topic seed **is an explanation** — one with no parent and `verb = SEED`. Collapsing what would be two content types into one gives caching, versioning, publishing and rendering a single code path.

### 5.2 Collections

**`topics`**

```
{
  _id: ObjectId,
  slug: String,                  // unique, URL-safe
  title: String,
  aliases: [String],
  category: String,
  summary: String,               // one line, for catalogue listings
  seedKey: String,               // contentKey of the SEED explanation
  status: "draft" | "published",
  sortWeight: Int,
  createdAt, updatedAt: Instant
}
```

**`explanations`** — immutable, never updated except `requestCount`

```
{
  _id: String,                   // contentKey, sha256 hex
  topicSlug: String,
  parentKey: String?,            // null for SEED
  span: String?,                 // null for SEED
  spanSentence: String?,         // the sentence the span was highlighted in
  verb: Verb,
  variant: Int,                  // Side View / regenerate increment this
  depth: Int,
  body: String,
  media: Media?,                 // Slice 4 only
  grounded: Boolean,
  sources: [ { url, title, license, attribution } ],
  promptVersion: String,
  modelFamily: String,
  modelId: String,
  tokensIn: Int,
  tokensOut: Int,
  costMicros: Long,
  requestCount: Long,            // atomic $inc on every hit
  createdAt: Instant
}
```

`Verb = SEED | EXPLAIN | DIG_DEEPER | BROADER_PICTURE | SIDE_VIEW | VISUALIZE`

**`Media`** (Slice 4)

```
{
  kind: "MERMAID" | "SVG" | "IMAGE",
  source: String?,               // diagram source for MERMAID / SVG
  imageUrl, thumbUrl: String?,   // Commons, for IMAGE
  title: String?,
  license: String?,
  attributionHtml: String?,
  commonsPageUrl: String?
}
```

**`sessions`**

```
{
  _id: ObjectId,
  principalId: String,           // "anon:<uuid>" or "user:<id>"
  topicSlug: String,
  rootNodeId: String,
  currentNodeId: String,
  nodes: [ {
    nodeId: String,
    parentNodeId: String?,
    explanationKey: String,
    span: String?,
    verb: Verb,
    createdAt: Instant
  } ],
  startedAt, lastActiveAt: Instant,
  status: "active" | "completed"
}
```

Nodes are embedded and capped at 200, keeping reads to a single fetch and documents far below the 16 MB limit.

**`quizTemplates`** — content-addressed, exactly like explanations

```
{
  _id: String,                   // sha256( scopeKeys | kind | promptVersion | modelFamily )
  kind: "TEST_ME" | "EXAM",
  scopeKeys: [String],           // explanation contentKeys, ordered
  questions: [ {
    questionId: String,
    stem: String,
    options: [String],           // exactly 4
    correctIndex: Int,
    sourceKey: String,           // must be a member of scopeKeys
    rationale: String
  } ],
  promptVersion, modelFamily, modelId: String,
  tokensIn, tokensOut: Int,
  costMicros: Long,
  requestCount: Long,
  createdAt: Instant
}
```

Test Me scopes to a single explanation, so it caches heavily. Exam scopes to the whole ordered traversal, so it caches rarely — but identical traversals still share, for free.

**`quizAttempts`**

```
{
  _id: ObjectId,
  principalId: String,
  sessionId: ObjectId,
  templateId: String,
  answers: [ { questionId, chosenIndex: Int } ],
  score: Int,
  total: Int,
  createdAt, submittedAt: Instant
}
```

**`principals`**

```
{
  _id: String,                   // "anon:<uuid>" or "user:<id>"
  kind: "anon" | "user",
  windowStart: Instant,
  windowExpiresAt: Instant,      // TTL index
  explainCount: Int,
  quizCount: Int,
  costMicros: Long,
  ipBucket: String?              // fallback identity for anonymous quota
}
```

**`costLedger`** — drives the global breaker

```
{ _id: "2026-08-01", costMicros: Long, generations: Int }   // $inc per generation
```

**`topicRequests`** — demand signal for catalogue growth

```
{ _id, rawText, normalizedText, count, firstSeenAt, lastSeenAt }
```

### 5.3 Indexes

| Collection | Index |
|---|---|
| `topics` | `{slug: 1}` unique; `{status: 1, category: 1, sortWeight: 1}` |
| `explanations` | `{topicSlug: 1, requestCount: -1}`; `{createdAt: 1}` |
| `sessions` | `{principalId: 1, lastActiveAt: -1}`; `{topicSlug: 1}` |
| `quizAttempts` | `{principalId: 1, createdAt: -1}`; `{sessionId: 1}` |
| `principals` | TTL on `{windowExpiresAt: 1}` |
| `topicRequests` | `{normalizedText: 1}` unique; `{count: -1}` |

---

## 6. The explain pipeline

`POST /api/sessions/{id}/explain` with `{ parentNodeId, span: { text, start, end }, verb }`, responding as SSE.

1. **Span validation.** Confirm `span.text` occupies `[start, end)` in the parent explanation's `body`. Mismatch → `400`. This is the injection gate: the client can only point at text we generated, never supply its own.
2. **Quota and breaker.** Principal window exceeded → `429` with `retryAfter`. Global daily ceiling tripped → `503`, while cache hits continue to serve.
3. **Chain assembly.** `session` walks parent pointers to the root, yielding the ordered ancestor chain and `parentKey`. Depth cap enforced here.
4. **Key derivation** per §5.1.
5. **`graph.getOrGenerate(key)`**
   - **Hit** — `$inc requestCount`, then emit `meta` with `cached: true`, the whole body as one `delta`, and `done`. Same endpoint and same client code as a miss; the UI never branches on cache state.
   - **Miss** — acquire a coroutine `Mutex` keyed by `contentKey` to collapse a same-instance stampede. Across instances the unique `_id` is the backstop: the losing insert fails with a duplicate-key error and is discarded. Wasteful, never wrong.
6. **Stream while accumulating.** Tokens forward to the browser as they arrive; the full body assembles server-side in parallel.
7. **Post-validate, then persist.** Non-empty, within length bounds, no refusal pattern. Insert immutably under `_id = contentKey`. Record token counts and cost; `$inc` the day's `costLedger`.
8. **Append session node.** Pointer only.

Because identity derives from inputs, **retries are idempotent by construction**: a failed generation leaves no trace, and a retry either regenerates cleanly or finds another caller's successful result already present.

### 6.1 SSE protocol

```
event: meta    data: {"nodeId":"n7","contentKey":"c05be8d1…","cached":false}
event: delta   data: {"t":"The microscopic realm studied by "}
event: done    data: {"tokensOut":63,"grounded":false,"sources":[]}
event: error   data: {"code":"QUOTA_EXCEEDED","retryAfter":3600}
```

Error codes: `SPAN_MISMATCH`, `QUOTA_EXCEEDED`, `SPEND_LIMIT`, `DEPTH_LIMIT`, `SESSION_FULL`, `VARIANT_LIMIT`, `GENERATION_FAILED`, `QUIZ_UNAVAILABLE`, `UPSTREAM_UNAVAILABLE`.

---

## 7. Prompts and verbs

One template, parameterised by verb. It receives:

- the topic title;
- each ancestor as a `span → explanation body` pair, root-first;
- the target span **and the exact sentence it was highlighted in** — "microscopic realm" resolves differently depending on whether its sentence concerned scale or measurement;
- the verb instruction.

Output is **plain prose, not JSON.** Prose streams token by token; JSON does not render until structurally complete. Since users highlight arbitrary spans themselves, model-suggested terms add nothing.

| Verb | Instruction | Effect on key |
|---|---|---|
| `SEED` | Introduce the topic in 1–3 sentences, accessible to a curious beginner | Root of the chain |
| `EXPLAIN` | Define the span **within the ancestor context**, 1–3 sentences | New child |
| `DIG_DEEPER` | Same subject, one level more specific or technical | New child |
| `BROADER_PICTURE` | Zoom out: the parent's place in a wider framework, sibling and competing accounts | New child |
| `SIDE_VIEW` | Re-explain the same thing from a different angle or analogy | `variant + 1`, so a new key rather than an overwrite |

`promptVersion` is a constant in code, bumped whenever any template changes.

### 7.1 Web tool

The `llm` port exposes an optional web-lookup tool, capped at **one round-trip per explanation**. When used, the explanation records `grounded: true` and its `sources`, which the UI cites.

**Ships flag-disabled.** A tool call adds seconds of latency and breaks the clean stream, and on a curated catalogue of well-established topics the model rarely needs it. Build it, measure where explanations are weak, enable per category.

---

## 8. Assessment

**Test Me** scopes to the current node. **Exam** scopes to every node in the session, in order.

Both use structured output — `{ stem, options[4], correctIndex, sourceKey, rationale }` — and both pass through a hard validation layer:

- every `sourceKey` must be a member of `scopeKeys`;
- exactly four options;
- `correctIndex` within range;
- non-empty stem and rationale.

Invalid questions are dropped. One retry with a corrective nudge. If nothing valid survives, return `QUIZ_UNAVAILABLE` and hide the action rather than show a broken quiz.

The source brief's rule — *questions may only cover material presented earlier* — survives prompt drift only because it is enforced in code. The prompt is a suggestion; the validator is the rule.

---

## 9. Visualize (Slice 4)

`VISUALIZE` is a verb like any other and produces an explanation document with a populated `media` field.

**Diagram path.** The model emits Mermaid or inline SVG. Server-side validation parses the source and rejects anything malformed before persisting; SVG is sanitised against an allowlist of elements and attributes — no `<script>`, no external references, no event handlers. Angular renders it client-side, zoomable and screen-reader accessible.

**Image path.** Query Wikimedia Commons scoped by the ancestor chain. Filter results by relevance and licence. Store `imageUrl`, `title`, `license`, `attributionHtml` and `commonsPageUrl`. The UI **must** display attribution alongside the image; this is a licence obligation, not a nicety.

**Degradation.** If Commons is unavailable or returns nothing relevant, Visualize serves diagram-only. It never blocks and never errors the session.

---

## 10. Guardrails

### 10.1 Cost

Five layers. A €10/month product with an uncapped model behind it is one bad day from a four-figure invoice.

1. **Per-principal quota** — rolling 24-hour window on a TTL counter document. Slice 1 ships the mechanism with a generous default; spec B sets trial and subscriber tiers without touching engine code.
2. **Global daily spend breaker** — every generation records `tokensIn`, `tokensOut`, `costMicros` and `$inc`s `costLedger`. Past the ceiling, generation returns `503` while cache hits keep serving. The site stays up and largely usable; only new paths pause.
3. **Depth cap** and **session node cap**. Drilling should converge, not run forever.
4. **Short outputs by design.** 1–3 sentences is roughly 120 tokens. The brief's format is also the cost control.
5. **Per-principal cost visibility** from day one, so real unit economics are known before the price is fixed.

### 10.2 Configuration

| Setting | Value |
|---|---|
| `MAX_DEPTH` | 12 |
| `MAX_SESSION_NODES` | 200 |
| `MAX_VARIANTS` | 3 |
| `MAX_OUTPUT_TOKENS` | 200 |
| `EXPLANATION_MIN_CHARS` / `MAX_CHARS` | 40 / 600 |
| `QUOTA_WINDOW` | 24 h rolling |
| `DEFAULT_DAILY_EXPLAINS` | 20 (Slice 1 default; spec B overrides per tier) |
| `GLOBAL_DAILY_COST_CEILING` | configurable, initial €50/day |
| `LLM_TIMEOUT` / `LLM_RETRIES` | 30 s / 1 |
| `WEB_TOOL_ENABLED` | `false` |
| `MAX_TOOL_ROUNDS` | 1 |

All values are environment-configurable, not compiled constants.

### 10.3 Abuse

The curated catalogue does real security work: there is **no free-text path into a prompt anywhere in the product**. Span validation closes the other one. Beyond that:

- Cloudflare rate limiting, WAF and bot control at the edge.
- Anonymous principals keyed by a signed HTTP-only cookie, with an IP bucket as fallback so clearing cookies does not reset the meter.
- No user-supplied content is ever echoed into a prompt.

---

## 11. Error handling

| Failure | Behaviour |
|---|---|
| LLM timeout or 5xx | One retry with backoff, then SSE `error`. Trail intact, UI offers Retry |
| Stream dies mid-generation | Nothing persisted. Client keeps what arrived, marked incomplete, with Retry |
| Duplicate key on insert | Benign race — discard ours, read the winner, continue |
| Model refuses or returns empty | Post-validation catches it before persistence. One nudged retry, then a real error — never a junk document |
| Mongo unavailable | Fast `503`. No degraded write path; a corrupted session tree is worse than a brief outage |
| Quiz validation fails twice | Serve the valid subset; if none survive, `QUIZ_UNAVAILABLE` and hide the action |
| Commons unavailable (Slice 4) | Degrade to diagram-only |
| Depth or node cap reached | `DEPTH_LIMIT` / `SESSION_FULL`; UI suggests starting a fresh session on the same topic |

---

## 12. API surface

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/health` | Liveness, including a Mongo round-trip |
| `GET` | `/api/catalog/topics` | Published topics; optional `category`, `q` |
| `GET` | `/api/catalog/topics/{slug}` | Topic detail with resolved seed |
| `POST` | `/api/sessions` | `{topicSlug}` → new session with root node |
| `GET` | `/api/sessions/{id}` | Full traversal tree with resolved explanations |
| `POST` | `/api/sessions/{id}/explain` | **SSE.** `{parentNodeId, span, verb}` |
| `POST` | `/api/sessions/{id}/quizzes` | `{kind, nodeId?}` → quiz template + attempt |
| `POST` | `/api/sessions/{id}/quizzes/{attemptId}/answers` | `{answers[]}` → score |
| `POST` | `/api/topic-requests` | `{text}` → demand signal capture |

---

## 13. Testing strategy

Test-driven throughout. The `llm` port is what makes it cheap: everything below the model is deterministic and needs no network.

**Highest value first:**

- **Key derivation as property tests.** Same inputs → same key; any single input change → different key; identical span under different ancestry → different key. Pure function, so exhaustive testing is nearly free. This is the correctness core.
- **The brief's own example, executable.** "microscopic realm" via *Quantum Physics* versus via *Microbiology* must derive different keys and, against recorded responses, yield materially different bodies. The product's central promise becomes a red-green test.
- **Quiz validation, adversarially.** Out-of-scope `sourceKey`, wrong option count, `correctIndex` out of range, empty sets. These fixtures enforce the "no random trivia" rule.
- **The duplicate-key race, for real.** Testcontainers Mongo, two concurrent generations at one key; assert exactly one document exists and both callers receive the same body.
- **`selection` in the frontend.** DOM `Range` → stable offsets across whitespace and markup is the most bug-prone code in the Angular app. jsdom fixtures, tested hard.
- **API surface** via Ktor `testApplication`, including SSE framing, error mapping and explicit negative tests on span validation.
- **Quota and breaker** — window rollover, ceiling trip, and the requirement that cache hits keep serving while generation is blocked.

**Deliberately not tested:** exact model prose. Assert structural properties — length bounds, non-empty, references the ancestor context — never string equality.

**Contract suite:** a small recorded-cassette set run on demand, not in CI, catching silent prompt or model regressions.

**End-to-end:** two or three Playwright paths against a stubbed backend — pick topic, highlight, explain, drill twice; and take a Test Me.

---

## 14. Delivery slices

| | Slice | Contents |
|---|---|---|
| **0** | Walking skeleton | Gradle multi-module Ktor + Angular, deployed to fly.io behind Cloudflare on mytetz.com, Atlas connected, health check round-tripping through Mongo, CI green. No product code |
| **1** | Seed + Explain | ~20 hand-curated topics, catalogue and topic pages, seed, highlight → Explain, SSE streaming, focus-card reader with breadcrumb and trail rail, content-addressed graph with cache, quota and breaker. **First demoable product** |
| **2** | Remaining text verbs | Dig Deeper, Broader Picture, Side View — prompt templates and buttons |
| **3** | Test Me + Exam | Structured output, validation layer, quiz UI, scoring, attempts |
| **4** | Visualize | Mermaid/SVG generation, sanitisation and renderer; Wikimedia lookup, relevance filter, attribution UI |
| **5** | Catalogue scale-up + warming | Grow to the few hundred topics SEO needs; pre-warm hot paths from `requestCount`. Hands off into spec C |

**Curating the catalogue is real work, not a config file.** Choosing topics, generating seeds, and reading them for accuracy and tone — with curated-only entry, that content *is* the product surface and the entire SEO ceiling. It gets budgeted effort in Slice 5, not an afternoon.

---

## 15. Known trade-offs

- **Curated-only entry caps reach.** "I want to learn X" fails whenever X is absent, at the highest-intent moment in the funnel. `topicRequests` captures that demand so the catalogue can grow from evidence; revisiting the decision is a live option once there is traffic.
- **Explanations are not personalised.** Everyone down the same path reads the same words. *Side View* is the escape hatch. Reversing this would forfeit the cache and the SEO pipeline together.
- **The web tool is off by default.** Some explanations will be weaker than a grounded system would produce. Deliberate: latency and stream integrity are worth more at this stage, and the flag flips per category once there is evidence.
- **Diagram-as-code cannot produce photographs.** Commons covers part of the gap; some topics will remain text-only.

---

## 16. Next steps

1. Implementation plan for Slices 0 and 1 (writing-plans).
2. Spec B — accounts, trial, Freemius.
3. Spec C — SEO/AEO surface, building on the published explanation graph.
