# Public surface — Design

**Project:** mytetz.com
**Date:** 2026-09-19
**Status:** Draft for owner review
**Spec:** C of four (see the Scope section of the learning-engine design)

This document uses ASD-STE100 Simplified Technical English.

---

## 1. Purpose

The site has real content, but a crawler cannot read it. The Angular shell sends empty HTML. A
visitor must start a private session to read one word of a topic. Section 17 of the monetization
design names this trade-off: "Between B3 and specification C the site converts only the visitors
that it already has."

This document decides how the public surface works:

- The rendering method for a page that a crawler can read.
- The topic page and the public explanation page.
- The sitemap, the structured data, and the Cloudflare cache rule.
- How the wall and the public pages coexist.
- The first measurement steps.

It does not decide the implementation plan. The plan follows this document, after the owner
approves it.

---

## 2. Scope

### In scope

The rendering method. The route `GET /topics/{slug}`. The route for a public explanation page.
`GET /sitemap.xml`. The JSON-LD for each new page type. The Cloudflare cache rule for the new HTML
pages. The Google Search Console setup and the first queries to watch. The `how-it-works` page and
the `Organization` record.

### Out of scope

The interactive reader. The account pages. Any change to the gate, the trial, or the subscription.
Those stay in specification B.

### Shipped parts this document does not decide again

Section 3 lists them.

---

## 3. Status on 2026-09-19

These parts of the public surface exist. This document records each one as decided, and it does
not open any of them again.

| Part | Shipped by |
|---|---|
| `sitemap.xml` and `robots.txt`, as static files | #32, #33 |
| A title, a description, Open Graph tags, and `WebSite` JSON-LD for the shell and every Angular route | #34, #35 |
| `404` for a path that matches no route | #36 |
| `X-Robots-Tag: noindex` on `/learn/`, `/account`, `/auth`, and on the `mytetz.fly.dev` host | #37 |
| Text under the catalogue heading | #38 |
| `llms.txt`, as a static file | #39 |
| `Cache-Control` set by the static handler | #40 |
| A redirect from `www` to the apex | #41 |
| A hub page and six guide pages under `/guides`, as static HTML that Ktor resolves from a folder `index.html` | #62, #63 |

**Why the guide pages matter to this document.** #62 and #63 prove that Ktor can serve public HTML
with real text and no Angular. Section 4 treats this as a precedent for the rendering method,
alongside the audit's own measurements.

**Verified in the code, 2026-09-19:**

- `backend/api/src/main/kotlin/com/mytetz/api/GuidePages.kt:22-30` lists seven paths under
  `/guides`. `frontend/public/guides/` holds one folder for each guide plus `index.html` for the
  hub, which matches the list.
- `backend/api/src/main/kotlin/com/mytetz/api/SpaRoutes.kt:84-124` is the static handler. It
  resolves a real file first, then a folder's `index.html`, then the Angular shell. An unmatched
  path gets the shell with status `404` (line 120), so a crawler drops it.
- `backend/api/src/main/kotlin/com/mytetz/api/NoIndexPlugin.kt:39-43` is the `noindex` rule. It
  runs in the `Plugins` phase, ahead of routing (line 52-58), so it applies the same way to a `200`
  and to a `404`.
- `frontend/public/sitemap.xml` holds 8 URLs on 2026-09-19: the home page and the seven `/guides`
  paths. This matches the audit's count.
- `frontend/src/app/app.routes.ts:12-79` lists seven Angular routes: `` (empty), `auth`, `account`,
  `learn/:sessionId`, `privacy`, `terms`, `imprint`, plus the wildcard `**` for "not found". This is
  the list that #36's route list (`SpaRoutes.kt:18-27`) must continue to match.

---

## 4. Decision — the rendering method

**Decision.** Ktor renders the public pages as plain HTML. Angular keeps the interactive reader
only. No new process joins the deployment.

### 4.1 The three options

| # | Option | What it needs |
|---|---|---|
| 1 | Angular SSR as a second fly process | A Node.js server that renders each request |
| 2 | Prerender at build time (`outputMode: "static"`) | Data available inside the Docker build |
| 3 | Ktor-rendered HTML for the public pages only | No new process; a Kotlin template |

### 4.2 Why option 1 loses

Angular's own guidance states that server-side rendering "requires a Node.js server" at run time,
separate from a build-time static export (https://angular.dev/guide/ssr). This app already runs on
one fly.io machine with 512 MB of memory.

- `fly.toml:64-66` sets `size = "shared-cpu-1x"` and `memory = "512mb"`. One machine, not two.
- `Dockerfile:97-98` sets `JAVA_OPTS="-XX:MaxRAMPercentage=75.0"`. The JVM alone claims 75% of the
  machine's memory. This is the same figure the audit comment gives.

A Node.js SSR process would share the remaining memory with the JVM, on a machine where the JVM
already claims most of the memory. The learning-engine design already names this expectation, at
`docs/superpowers/specs/2026-08-01-assisted-learning-engine-design.md:72`: "When spec C requires
server-side rendering, an Angular SSR service joins as a second fly process." This document
overrides that expectation. A second process does not fit the current machine. The reader's own
code keeps the option open on purpose:
`frontend/src/app/catalog/catalog-page.component.ts:11-14` states, in a comment, that the page
"does not read `window`, `document` or `localStorage` on its render path... kept that way on
purpose... for when spec C adds server rendering." A later move to Angular server-side rendering
stays possible if the machine grows. This document does not ask for that move now.

### 4.3 Why option 2 loses

Angular's own guidance for `outputMode: "static"` states that it "generates pre-rendered HTML files
for each route at build time" and needs no Node.js server at run time
(https://angular.dev/guide/ssr). Dynamic data at build time comes through a function named
`getPrerenderParams`, which runs during the build and needs a live source for that data.

The seed text is not available at build time.

- `Dockerfile:9-22` is the frontend build stage. It copies `frontend/package.json`,
  `frontend/package-lock.json`, and `frontend/`, then runs `npm run build`. No MongoDB connection
  string and no database driver reach this stage.
- The seed text lives in the `explanations` collection in MongoDB Atlas
  (`backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt:23-36`), which only the
  backend stage and the runtime container can reach.
- A new topic has no seed at build time in any case. The deploy pre-warms a topic's seed after the
  build, per #18 and per the pre-warm method already in code
  (`backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt:317-321`, `prewarmSeed`).

A build-time prerender would therefore ship a topic page with no seed text. The page would then
have nothing to show the visitor it was meant to inform.

### 4.4 Why option 3 wins

Ktor already holds every piece the public pages need, in modules that already exist:

- `catalog` resolves a slug to a published topic (`CatalogService.kt:26`,
  `backend/catalog/src/main/kotlin/com/mytetz/catalog/TopicRepository.kt:63`).
- `graph` resolves a content key to a stored explanation, as a pure read with no generation
  (`ExplanationRepository.kt:35-36`, `findByKey`).
- `SpaRoutes.kt` and `GuidePages.kt` already prove the pattern: a Ktor route that emits real HTML,
  served from inside the same jar, at no extra memory cost.

No second process, no second template language for the interactive reader, and no
data-availability problem. The cost is a topic card that exists in two templates: one in Kotlin for
the public page, one in Angular for the catalogue tile. That duplication costs far less than a
second fly machine.

### 4.5 What does not change

The Angular reader stays exactly as it is. It still avoids `window`, `document`, and
`localStorage` on its render path, per the learning-engine design's own rule
(`assisted-learning-engine-design.md:72`). That rule keeps a later move to Angular server-side
rendering possible, if the machine grows.

### 4.6 The template technique and the escape rule

**Decision.** Use Ktor's HTML DSL (`respondHtml`, from `kotlinx.html`), not a Kotlin string
template.

`gradle/libs.versions.toml` names no HTML builder today, so this adds one dependency:
`io.ktor:ktor-server-html-builder`, per the official Ktor documentation
(https://ktor.io/docs/server-html-dsl.html).

**What lost.**

| Option | Reason it lost |
|---|---|
| A Kotlin string template, for example `"""<h1>${title}</h1>"""` | Escapes nothing by itself. Every interpolated value needs a manual, easy-to-forget escape call, and a model wrote the seed body and the span text this page renders. |
| The HTML DSL (`respondHtml`) — **chosen** | `kotlinx.html`'s stream writer calls an escape function on every text node and on every attribute value by default (`HTMLStreamBuilder.onTagContent`, in the library's own `stream.kt`). The escape call is the default. Only an explicit `unsafe { }` block turns it off. |

**The escape rule, as a requirement.** Every value this page writes from a topic, a summary, a
seed, a span, or an explanation body goes through the DSL's normal text or attribute position
(`+value`, or an attribute assignment), never through an `unsafe { }` block. This covers the title,
the summary, the seed body, the span, and the explanation body: five values that can each hold
arbitrary text, because a model wrote four of them and a learner chose the fifth.

**The JSON-LD block needs one more rule, because what the DSL escapes by default is not enough
there.** A `<script type="application/ld+json">` block is placed correctly by the DSL, but the JSON
text inside it can still contain the literal characters `</script`, and a browser's HTML parser
closes the tag on that sequence before any JSON parser runs
(https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html).
**Requirement:** before the JSON-LD text goes inside the `<script>` block, replace every `/` that
follows a `<` with `\/`. A JSON string treats `\/` as an ordinary slash, and a browser never reads
it as the start of a closing tag. This one substitution removes the early close.

---

## 5. Decision — the owner decisions from the handover that this surface closes

`docs/slice-0-1-outstanding-actions.md` section 5 lists four decisions for the owner. This surface
answers two of them.

### 5.1 Decision 5.1 — Topic pages

`docs/slice-0-1-outstanding-actions.md:257-276` gives three options: add topic pages early, wait
and build them with the rendering work, or drop them. The document already recommends the middle
option: "Wait... Build both at one time" (lines 275-276).

This document is that wait, carried out. Section 4 decides the rendering method. Section 6 gives
the topic page. Both ship together, as the earlier document asked. The historical choice was
correct, and this document acts on it.

### 5.4 Decision 5.4 — What a curator's unpublish does to public content

`docs/slice-0-1-outstanding-actions.md:301-313` asks what a running session does when a curator
unpublishes a topic. That decision stays as written: a running session continues, and only a new
session stops.

This surface adds one case the earlier document could not consider, because no public topic page
existed then: **an unpublish must also remove the topic page and every one of its explanation
pages from public view.** `CatalogRoutes.kt:108-109` already answers `404` for an unpublished topic
on the JSON endpoint. Section 6.2 applies the identical check to the new HTML route, so an unpublish
removes a topic page from the origin at once. Section 13.4 states the one part that does not update
at once: a copy already held in the Cloudflare edge cache, for up to one hour.

### The other two decisions do not touch this surface

Decision 5.2 (when a session ends) and decision 5.3 (whether a repeated question spends the node
budget) both concern the interactive reader's session tree. Neither changes a page this document
adds. This document leaves both as they are.

---

## 6. Decision — topic pages

**Decision.** `GET /topics/{slug}` is a Ktor route. It renders a full HTML page for a published
topic, with the seed text, and it never triggers a generation.

### 6.1 What the page contains

- `<title>`: `<Topic title> explained simply | mytetz`, under 60 characters. A long title uses a
  shorter pattern.
- `<meta name="description">`: the topic's `summary` field (`Topic.kt:13`).
- `<link rel="canonical" href="https://mytetz.com/topics/<slug>">`, plus `og:url`, `og:title`,
  `og:description`, and `og:image`.
- An `<h1>` with the title, the category, and the seed text in a `<p>`.
- `LearningResource` JSON-LD (https://schema.org/LearningResource) and `BreadcrumbList` JSON-LD.
- A "Start with this topic" control that calls `POST /api/sessions` and opens `/learn/:sessionId`.
- "Last reviewed `<date>`", from the field section 8 decides.

### 6.2 How the page reads the seed, with no generation and no model credential

The route calls, in order:

1. `CatalogService.findBySlug(slug)` (`CatalogService.kt:26`), and it answers `404` unless the
   result exists and `status == PUBLISHED` — the same rule `CatalogRoutes.kt:108-109` already
   enforces for `GET /api/catalog/topics/{slug}`.
2. `ContentKey.seed(topic.slug, promptVersion, modelFamily)` (`ContentKey.kt:59-68`).
3. `ExplanationRepository.findByKey(key)` (`ExplanationRepository.kt:35-36`), which only reads and
   never calls the model.

A topic whose seed is not in the store renders the summary and no seed paragraph. This case should
not happen for a published topic once #18's pre-warm step runs, per the invariant the monetization
design already states: "A published topic must have a seed explanation in the store"
(`2026-08-07-monetization-design.md:441-442`). The route does not re-check that invariant. It
trusts it, and it falls back cleanly if the invariant is ever broken.

**How the route gets `modelFamily` with no model credential.** Step 2 needs `promptVersion` and
`modelFamily`. `promptVersion` is free: `GraphConfig().promptVersion` (`GraphConfig.kt:14`) builds
with no credential. `modelFamily` is not free today, and this document decides how the route reads
it so that it does not break an existing rule.

The existing rule: `Components.kt` builds the model client lazily, and states why: "a
catalogue-only deployment never builds one" (`Components.kt:66-67`), because
`AnthropicLlmClient()` "demands `ANTHROPIC_API_KEY`... during construction" (`Components.kt:60-61`).
Three tests pin this: `ComponentsTest.kt:275` ("module boots and serves without a model client"),
`ComponentsTest.kt:377` ("the model client is not built unless something needs a model"), and
`ComponentsTest.kt:399` ("bootstrap builds no model client when the migration is off").
`ExplanationGraph.keyFor` (`ExplanationGraph.kt:198-200`) reads `llm.modelFamily`, and that read is
exactly what builds the client on first use. A public page must read `modelFamily` and must not
read it through `llm.modelFamily`, or every visit to a topic page would demand
`ANTHROPIC_API_KEY`, which the three tests above forbid.

**Decision.** Widen `AnthropicLlmClient.Companion.resolveModel` (`AnthropicLlmClient.kt:259-260`)
from `internal` to public. The topic-page route then calls
`AnthropicLlmClient.resolveModel(System.getenv(AnthropicLlmClient.MODEL_FAMILY_ENV))` directly. This
reads the same environment variable and applies the same default, and it builds no client and opens
no connection. `MODEL_FAMILY_ENV` (`AnthropicLlmClient.kt:228`) is already a public constant, so
only the function that reads it needs to change.

**What lost.**

| Option | Reason it lost |
|---|---|
| Read `llm.modelFamily` through the lazy client | Builds `AnthropicLlmClient()` on the first page view, which demands `ANTHROPIC_API_KEY` and breaks the rule `Components.kt` and its three tests protect. |
| Add a second, small parsing function in the `api` module that reads `MYTETZ_MODEL_FAMILY` on its own | Two functions would decide one value. A later change to the default or the parsing rule in `AnthropicLlmClient` would not reach the copy, and the two could disagree with no test to catch it. |

### 6.3 The list under the seed — this document changes #45

This is a point where this document changes the page structure that #45 proposes, and the reason
is a mismatch inside the tracker itself.

- Issue #19's own requirements state: "Topic pages at `/topics/{slug}` with the seed and the
  most-requested explanations under it, ordered by `requestCount`."
- Issue #45's implementation step 2 instead asks for "a list of links to the other published
  topics in the same category."

Both lists are useful, and each one serves a different job. This document keeps both, in this
order:

1. **Popular questions about this topic** — the top `EXPLAIN` nodes under this topic, ordered by
   `Explanation.requestCount` descending (`Explanation.kt:28`). Each one links to its public
   explanation page (section 7). This is the list issue #19 asks for.
2. **Related topics** — the other published topics in the same category, as #45 proposes. This
   keeps the page useful for site navigation even before any explanation is published.

List 1 has no content until an explanation is `published` (section 7), which #48 delivers after
#45. #45 therefore ships list 2 alone. #48 must add list 1 to the same template once a published
explanation exists for that topic. This document directs #45 and #48 to update their own bodies to
reflect this two-list structure, per step 11 of issue #19 — a step this spec does not carry out
itself.

### 6.4 The catalogue tile's markup

#45 asks to change each catalogue tile from a `<button>` into a link. This document confirms that
change and corrects one fact #45 cites; section 9.3 decides what kind of link it must be.

- #45 cites the button at `catalog-page.component.ts:99-113` and an `<h2>` inside it at line 112.
  On 2026-09-19 the button is at lines 123-143 and the `<h2>` is at line 136. #38 added the
  paragraph at lines 26-35 after #45 was written, and that shifted every later line number. #45's
  implementer should locate the button by its class name, `topic__button`, and not by the line
  numbers the issue body gives.
- The defect #45 names is real: an `<h2>` element inside a `<button>` element is not valid HTML.
  `catalog-page.component.ts:136` confirms it.

---

## 7. Decision — public explanation pages and the glossary

**Decision.** A published `EXPLAIN` node gets a stable URL at
`/topics/<slug>/explain/<short-key>`, where `<short-key>` is the first 12 characters of the node's
64-character hexadecimal content key.

### 7.1 Why this shape, and what lost

`ContentKey.derive` (`ContentKey.kt:34-51`) returns a SHA-256 digest, written as 64 lowercase hex
characters (`ContentKey.kt:50`). Twelve of those characters hold 48 bits, so the short-key space
holds 2^48, about 2.81 × 10^14, values.

| Option | Reason it lost |
|---|---|
| The full 64-character key in the URL | Needlessly long. A shorter, still-unique path is easier for a crawler and for a person to read. |
| An 8-character, 32-bit prefix | Section 7.2's math shows a collision risk that starts small and grows fast with the store. Four more characters buy a much larger margin. |
| Drop the verb segment, since only `EXPLAIN` is published today | Rejected: it would force a URL-shape change if a later decision publishes a `DIG_DEEPER`, `BROADER_PICTURE`, or `SIDE_VIEW` node. The verb table already defines five verbs (`assisted-learning-engine-design.md:355-361`). The segment costs nothing to include now. |

This confirms the shape #48 proposes as an example ("for example
`/topics/<slug>/<verb>/<short-key>`") and settles its two open points: keep the verb segment, and
fix the key length at 12 hexadecimal characters.

### 7.2 How the route resolves a short key, and the real size of the collision risk

The route queries the `explanations` collection for `_id >= <prefix>` and `_id < <prefix-plus-one>`,
where `<prefix-plus-one>` raises the prefix's last hex digit by one. `_id` already carries
MongoDB's default index, sorted, so this is a small range scan and needs no new index.

- **Zero matches:** an ordinary `404`. The key names no document.
- **Exactly one match:** the route serves it.
- **More than one match:** a collision. The route answers `404` for both, and logs
  `EXPLANATION_KEY_COLLISION` at `WARN` with both keys, for an operator to read.

**The collision math, done again on the whole store and not on the published cap.** An unpublished
explanation is served too, with `noindex` (section 7.3), so every document in the `explanations`
collection is addressable, not only the 100 that section 7.4 allows to be indexed. The right count
for this math is the size of the whole collection, not the published cap.

| Store size (documents) | Collision probability across the whole store |
|---|---|
| 100 (the indexable cap alone — the wrong number for this math) | about 1 in 6 × 10^10 |
| 10,000 (a plausible near-term size, across every session ever run) | about 1 in 6 × 10^6 |
| 1,000,000 (a deliberately generous outer bound: at about 700 bytes per document, this many documents alone would need more than the whole 512 MB Atlas M0 limit, so the real store stays smaller) | about 1 in 560 |

The formula is the birthday approximation, `p ≈ n² / (2 × 2^48)`, accurate while `n` stays far
below 2^24. The store size is not measured today. Every row above is an estimate, not a count. The
first row is the mistake an earlier draft of this document made: it used the published cap of 100,
but every stored document is reachable, not only the published ones. The last row shows the risk is
not always small. The route's collision answer above, and not a longer key, is the first defence,
because a `404` on collision is safe and the event stays rare at today's scale. If the live store
approaches a size where this table's risk stops looking small, the fix is a longer prefix. `#50`'s
traffic review is a natural point to notice that the store has grown that large.

### 7.3 An unpublished page answers with `noindex`, not `404`

**Decision.** An unpublished explanation renders the same page as a published one, with
`X-Robots-Tag: noindex` added (`NoIndexPlugin.kt:11,14`) and no canonical tag. It does not answer
`404`. The route also checks the **topic's** `PUBLISHED` status, and not only the explanation's own
`published` flag: an explanation under an unpublished topic answers `404`, because section 5.4
requires an unpublish to remove every page under that topic.

**Why `noindex` and not `404`.**

1. **Precedent.** `/learn/`, `/account`, and `/auth` are real pages that already carry `noindex`
   instead of `404` (`NoIndexPlugin.kt:30`). This follows the pattern the codebase already uses for
   a real page that must stay out of a search index.
2. **A stable link.** The interactive reader can link to any node a learner reaches, published or
   not. A `404` would break that link. A `noindex` page does not.
3. **No redirect on publication.** The URL stays the same before and after `published` changes. A
   `404`-until-published design would need a second URL, or a redirect once the flag changes.

**What lost.** A `404` for an unpublished page. #48 does not propose this option; #48 already asks
for `noindex`. This document confirms that choice and states the reason in full, so the choice
does not need to reach the owner as an open question.

### 7.4 The glossary and the cap

`/glossary` lists every published `EXPLAIN` node by its span, each with `DefinedTerm` JSON-LD
(https://schema.org/DefinedTerm), per #48 step 4. This document confirms #48 step 7's cap of 100
indexable explanation pages until `#50` shows they earn traffic. The cap keeps the site inside
Google's scaled-content-abuse policy, which applies "when many pages are generated for the primary
purpose of manipulating search rankings and not helping users"
(https://developers.google.com/search/docs/essentials/spam-policies#scaled-content-abuse). A
capped, human-reviewed set of 100 pages is a curated set, not a scaled one. This cap bounds only the
**indexable** count. Section 7.2's collision math covers the whole store, which is larger, because
an unpublished page is stored and addressable too.

### 7.5 One item this document leaves to the owner

#48 step 5 asks the owner to choose between a script run against the database and a route guarded
by an owner allow-list, to set `published`. This document does not choose between them. It is
listed as an owner decision in section 17.

---

## 8. Decision — the review date

**Decision.** Add `reviewedAt: Long?` (epoch milliseconds) to `Topic`, as a new field, through a
migration. Do not reuse `Explanation.createdAtEpochMillis` as a stand-in for it.

### 8.1 Why a new field, and not the seed's own timestamp

The monetization design states a rule for this collection: "`topics` gains no field... A stored
`seedKey` would duplicate state that can drift" (`2026-08-07-monetization-design.md:437-439`). That
rule is about one specific value, the seed key, which the graph can always recompute and which a
stored copy could contradict. A review date is a different kind of fact: it records when a person
looked at the text, and no other field can supply that. A new `reviewedAt` field does not conflict
with `2026-08-07-monetization-design.md:437-439`, because it duplicates nothing the graph already
knows.

**What lost.** The seed explanation's `createdAtEpochMillis` (`Explanation.kt:29`), used as the
review date. This fails after a model migration: section 13 of the monetization design
(`2026-08-07-monetization-design.md:569-583`) describes a migration that regenerates every seed, so
`createdAtEpochMillis` resets for every topic on that day, whether or not a person reviewed the new
text. A page that then states "Last reviewed today" for a topic nobody looked at states something
false, on the one page meant to show honestly how the text was made — #47's own purpose.

### 8.2 What sets it, and what reads it

#18's review step sets `reviewedAt` when a curator confirms a topic's text. The topic page renders
"Last reviewed `<date>`" from it (section 6.1), and #47 step 4 sets `dateModified` in the
`LearningResource` JSON-LD from the same value. #46's sitemap route should use the **later** of
`reviewedAt` and the seed's `createdAtEpochMillis` for `lastmod`, once this field exists: either one
can be the true date of the last change a visitor would see. Before `reviewedAt` exists, #46 uses
`createdAtEpochMillis` alone, exactly as section 10.1 already decides.

---

## 9. Decision — who serves `/topics/*` and `/glossary`: Ktor, and not Angular

**Decision.** Ktor owns every path under `/topics/` and the `/glossary` path. `app.routes.ts`
gains no new route for either one. `SpaRoutes.paths`, the route list from #36, gains no new entry
for either one.

### 9.1 This changes #45 step 5

#45 step 5 asks to "Add `topics/:slug` to `app.routes.ts` and to the Kotlin route list from #36."
This document changes that step. Neither addition happens, for two separate reasons.

- **`app.routes.ts`.** A path in this file is a client-side Angular view. `/topics/{slug}` is a
  page Ktor renders and returns whole, on every request. A `topics/{slug}` entry in Angular's
  router would ask Angular to render a page it has no component for.
- **`SpaRoutes.paths` (`SpaRoutes.kt:18-27`).** This list lets the tailcard fallback route inside
  `spaRoutes()` (`SpaRoutes.kt:84-124`) tell an Angular route apart from an unknown path, and
  answer `200` or `404` accordingly. `/topics/{slug}` never reaches that fallback route, per
  section 9.2, so the list has no reason to name it.

### 9.2 Why the dedicated Ktor route wins over the fallback route

`spaRoutes()` registers its catch-all with `get("{spaFallbackPath...}")`, a tailcard. Ktor's own
routing documentation states that route resolution scores each match by specificity: a constant
path segment outranks a parameter, and a parameter outranks a wildcard or a tailcard
(https://ktor.io/docs/server-routing.html). A dedicated route such as `get("/topics/{slug}")` has
one constant segment, `topics`, and one parameter, both of higher quality than the fallback's bare
tailcard. Ktor therefore matches the dedicated route first for any request under `/topics/`,
regardless of which route the code registers first. The same reasoning applies to
`get("/glossary")`, once #48 ships it.

### 9.3 The catalogue tile is a plain link, not a router link

`catalog-page.component.ts:70-72` already holds the precedent this decision needs, for the
`/guides` link: "A plain href, and not a routerLink: /guides is a static HTML file... and
app.routes.ts has no 'guides' path, so a routerLink would reach the wildcard route and open
NotFoundPageComponent." The same fact now applies to `/topics/<slug>`, because section 9.1 keeps it
out of `app.routes.ts`. The tile becomes `<a href="/topics/<slug>">`, with no `routerLink`
directive. This confirms #45 step 4's intent, to turn the tile into a link, and adds the one detail
#45 does not state: which kind of link.

The "Glossary" nav item that `app-shell.component.ts:11` names but does not yet route ("Only
Topics has a route") links to `/glossary` the same way, once #48 ships that page: a plain
`<a href="/glossary">`.

---

## 10. Decision — `sitemap.xml` and `robots.txt`

**Decision.** `GET /sitemap.xml` becomes a Ktor route. `robots.txt` stays a static file.

### 10.1 The route

This document confirms #46 as written, with one fact checked and confirmed:

- #46 step 2 asks to set `lastmod` "from the `createdAt` of the seed explanation, or from a topic
  timestamp if `Topic` holds one," and to "confirm the field before you use it."
- **Confirmed:** `Topic` (`Topic.kt:9-17`) holds no timestamp field. `Explanation`
  (`Explanation.kt:29`) holds `createdAtEpochMillis`. #46 must use the seed explanation's
  `createdAtEpochMillis`, until section 8's `reviewedAt` field exists, after which #46 should take
  the later of the two.

The route writes one `<url>` for the home page, one for each published topic, one for each path in
`GuidePages.paths` (`GuidePages.kt:22-30`), and, after #48, one for each published explanation page.
It follows the sitemap protocol: `<loc>` is required, `<lastmod>` is optional, and one file may hold
up to 50,000 URLs (https://www.sitemaps.org/protocol.html). The catalogue stays far below that limit
even at the 100-topic scale #18 targets.

### 10.2 `robots.txt` and `llms.txt` stay static, with one open point

`robots.txt` needs no change: `Allow: /` already covers every new public path, and the `noindex`
pages are excluded through the `X-Robots-Tag` header, not through `robots.txt`
(`NoIndexPlugin.kt:39-43`).

`llms.txt` is a static file today (#39, `frontend/public/llms.txt`). #45 step 6 asks to add every
topic URL to it manually. This does not scale to the 100-topic catalogue #18 targets, for the same
reason #46 replaces the static sitemap. This document raises the point in section 17 and does not
decide it here, because no issue in 45-48 owns `llms.txt`'s long-term shape.

---

## 11. Decision — structured data and Open Graph

**Decision.** Each page type gets the JSON-LD listed below. All of it is valid schema.org markup.
Not all of it is a Google Search rich-result type, and this document states which is which, so
nobody expects a search result that will not appear.

| Page | JSON-LD | Google rich-result type? |
|---|---|---|
| Topic page | `LearningResource`, `BreadcrumbList` | `BreadcrumbList`: yes. `LearningResource`: not confirmed — see below. |
| Explanation page | `BreadcrumbList` | Yes |
| Glossary entry | `DefinedTerm` | Not confirmed — see below |
| `/` and `/how-it-works` | `Organization` | Yes, for the Knowledge Panel |

**Confirmed against Google's own gallery**
(https://developers.google.com/search/docs/appearance/structured-data/search-gallery):
`BreadcrumbList` and `Organization` both appear there as Google Search rich-result types.
`LearningResource` and `DefinedTerm` do not appear there. This does not make either type wrong to
use: schema.org defines both, and an answer engine other than Google Search may still read them.
It means neither one gives a Google Search rich-result appearance today. This document adds them
for their machine-readable value, not on a promise of a rich result.

`BreadcrumbList` needs at least two `ListItem` entries, each with `position`, `name`, and usually
`item` (https://developers.google.com/search/docs/appearance/structured-data/breadcrumb). A topic
page's trail (home, topic) meets this rule. An explanation page's trail (home, topic, explanation)
meets it too.

Every page keeps its Open Graph tags, per the pattern #34 and #35 already ship for the Angular
routes: `og:url`, `og:title`, `og:description`, `og:image`.

---

## 12. Decision — the wall and the public pages

**Decision.** No change to the gate. Section 9.3 of the monetization design already keeps the
catalogue and the topic detail open behind the rate limiter, and gates only `explain` and
`quizzes` (`2026-08-07-monetization-design.md:478-485`). The public topic page and the public
explanation page extend that same rule to two new page types:

- A visitor reads the seed and every published explanation with no account and no cookie check.
- A visitor who highlights a phrase inside the interactive reader meets the sign-in wall, exactly
  as section 9.1 of the monetization design already defines
  (`2026-08-07-monetization-design.md:454-462`).
- The "Start with this topic" control on a topic page creates a session the same way the catalogue
  tile does today, through `POST /api/sessions`, which stays open per the same section.

This is a confirmation, not a new decision: the public pages this document adds need no new row in
the gate table.

---

## 13. Decision — Cloudflare cache rules

**Decision.** Add one Cloudflare Cache Rule that matches `/topics/*` and `/glossary*`, and set a
long edge cache lifetime. Keep this rule separate from the existing `/api/*` bypass rule in
`docs/deploy.md` section 5, step 4.

### 13.1 The match pattern

`/topics/*` alone is enough. Cloudflare's own documentation on the wildcard operator states that a
slash "has no special meaning in wildcard matches"
(https://developers.cloudflare.com/ruleset-engine/rules-language/operators/), so `/topics/*`
already matches `/topics/special-relativity` and `/topics/special-relativity/explain/1a2b3c4d5e6f`
alike. A separate clause for the explanation path is redundant. An earlier draft of this document
carried one by mistake.

### 13.2 The rule must never cache a `Set-Cookie` response

A page in a shared edge cache must carry no `Set-Cookie` header, and no content meant for one
visitor only. `Principals.resolve` (`Principal.kt:148-165`) is the function that sets the anonymous
principal cookie, and it runs only inside a route handler that calls it, for example
`CatalogRoutes.kt:143`. `Application.kt` calls no such function outside a route handler, so any
route that never calls `Principals.resolve` or `setSessionCookie` (`Principal.kt:193-206`) sends no
`Set-Cookie` header of its own.

**Requirement:** the topic page, the explanation page, and the glossary page must not call
`Principals.resolve` or `setSessionCookie`. Each one reads the catalogue and the graph only. The
"Start with this topic" control still creates a session, but through a separate request,
`POST /api/sessions`, which the cache rule never touches: the rule matches a `GET` on `/topics/*`
and `/glossary*` alone.

### 13.3 Why one more rule fits inside the Free plan, and why a separate rule

`docs/deploy.md` section 5 already records that the zone runs on Cloudflare's Free plan, and that
Free allows only one rate-limiting rule (https://developers.cloudflare.com/waf/rate-limiting-rules/).
Cache Rules are a separate feature with a separate limit. Cloudflare's own documentation on Cache
Rules states that a Free plan zone may hold 10 Cache Rules
(https://developers.cloudflare.com/cache/how-to/cache-rules/). The existing `/api/*` bypass rule is
one. This document's new rule is a second. Both fit well inside the limit of 10.

A shared rule cannot express "cache this, bypass that" at once. The `/api/*` rule must continue to
bypass the cache, because a cached or buffered response breaks the server-sent-events stream.
The new rule caches only the new, unchanging HTML paths. Two rules serve two separate purposes.

### 13.4 The cache lifetime, and what a visitor sees while a page is stale

An edge cache lifetime of one hour matches `cacheControlFor`'s existing value for any other
`index.html` (`SpaRoutes.kt:69`, `public, max-age=3600`).

A curator's publish action and a model migration both take effect at the origin at once. The edge
cache does not know that. For up to one hour after either event, a visitor served from the
Cloudflare edge cache can still see the earlier page: the old `published` state, or the seed text
from before a migration regenerated it. `SpaRoutes.kt` already accepts the identical cost for a
static content page, and this document accepts it here for the same reason. No step here purges
the cache early.

### 13.5 A cached read never changes the demand signal

`incrementRequestCount` (`ExplanationRepository.kt:52-54`) is called only from
`ExplanationGraph.kt:218` and `ExplanationGraph.kt:237`, inside the path that serves a learner's own
highlight through a session. The topic page and the explanation page call only `findByKey`
(`ExplanationRepository.kt:35-36`), which has no side effect. A crawler, a cached copy, or any
number of visitors who read the same cached page therefore leave `requestCount` unchanged. The
demand signal that section 6.3 sorts by stays a record of real learner interaction only.

---

## 14. Decision — measurement

**Decision.** #42 owns Google Search Console ownership, and this document does not repeat that
step. This document names the first queries the owner should watch once #45 and #48 are live,
drawn from the audit's own evidence: `<topic> explained simply` (per issue #45's citation of issue
#44's finding, quoted on #19: "Small sites rank for '<topic> explained simply'"), the bare topic
name, and `what is <topic>`.

`#50` is the review of traffic against this list, after `#42` and this document's pages are live.
This document does not move that boundary.

---

## 15. Where this document confirms or changes #45 to #48

| Issue | Confirms | Changes |
|---|---|---|
| #45 | The route shape `/topics/{slug}`, the `404` rule, the seed-only read with no generation, the tile-to-link change, the JSON-LD types. | Adds the "popular questions" list ordered by `requestCount` (section 6.3), which #45's own step 2 omits. Corrects the line numbers #45 cites for the catalogue tile (section 6.4). Removes step 5's two additions to `app.routes.ts` and to `SpaRoutes.paths` (section 9.1). States that the tile link must carry no `routerLink` (section 9.3). |
| #46 | The route shape, the protocol reference, the deletion of the static file, the inclusion of `GuidePages.paths`. | Confirms `Topic` holds no timestamp (section 10.1), which resolves #46 step 2's own "confirm the field" instruction: use the seed explanation's `createdAtEpochMillis`, and later the later of that value and `reviewedAt` (section 8.2). |
| #47 | The `/how-it-works` page, the `Organization` JSON-LD, the web-tool sourcing rule. | Fixes the review-date storage: a new `reviewedAt: Long?` field on `Topic`, through a migration, and not a reuse of `Explanation.createdAtEpochMillis` (section 8). |
| #48 | The `published` flag, the `noindex` header, the glossary, the 100-page cap, the choice of `noindex` over `404` for an unpublished page. | Fixes the URL shape's two open points: keep the verb segment, and set the key length at 12 hexadecimal characters (section 7.1). Adds the collision-handling rule for a shared short-key prefix (section 7.2). Adds the check on the parent topic's `PUBLISHED` status (section 7.3). |

---

## 16. Facts this document could not confirm

- **Whether Google Search or another answer engine gives any ranking or citation credit to
  `LearningResource` or `DefinedTerm` JSON-LD.** Google's own rich-result gallery does not list
  either type as of this document's review
  (https://developers.google.com/search/docs/appearance/structured-data/search-gallery). No
  official source states what, if anything, an AI answer engine such as an AI Overview does with
  either type. **Not confirmed.**
- **Whether an AI answer engine outside Google Search publishes any official crawling or indexing
  specification at all.** This document found no equivalent to Google's Search Central
  documentation for another named answer engine. **Not confirmed.** Section 14's list of queries to
  watch is built on Google Search Console data only, for this reason.
- **The exact Cloudflare feature that implements the existing `/api/*` bypass** — Cache Rules, or
  an older Page Rule. `docs/deploy.md` section 5 step 4 names the effect, not the feature. This
  document assumes Cache Rules, because a newer Cloudflare account defaults to that feature, but it
  carries no citation that confirms which feature this zone actually uses. **Not confirmed; the owner
  should check the dashboard before the second rule is added.**

---

## 17. Questions for the owner

Only the owner answers these. Each one blocks a small, named part of implementation. The owner may
approve this document as a whole first, and answer each question later.

1. **The review path that sets `published` on an explanation (#48 step 5).** A script run
   against the database with `MONGODB_URI`, or a route guarded by an owner-email allow-list? **My
   recommendation:** the script. It needs no new authentication code, and the review step is
   already an infrequent, deliberate action.
2. **Any public profile beyond GitHub for the `Organization` JSON-LD's `sameAs` field (#47 step
   3).** #47 already asks this. This document repeats it because section 11 depends on the answer.
   **My recommendation:** ship with `https://github.com/xamcross/mytetz` alone, and add more later.
   `sameAs` accepts a list, and a later addition is not a breaking change.
3. **Whether `llms.txt` should become a Ktor route, generated the same way as `sitemap.xml`
   (section 10.2).** No issue in 45-48 owns this, and the file drifts once the catalogue passes a
   handful of topics. **My recommendation:** yes, as a small follow-up after #46, which reuses the
   same `CatalogService.listPublished()` call.
4. **Confirmation of the Cloudflare feature used for the existing `/api/*` bypass rule (section
   13, section 16).** **My recommendation:** the owner checks the Cloudflare dashboard once, before
   the second rule is added, and records the answer in `docs/deploy.md` section 5.

---

## 18. Next steps

1. The owner reviews this document and sets its status to "Approved for planning."
2. An implementation plan follows, under `docs/superpowers/plans/`.
3. After approval, #45 to #48 are updated where this document differs from their bodies, per
   section 15, and the `blocked` label and first line are removed from #45.
