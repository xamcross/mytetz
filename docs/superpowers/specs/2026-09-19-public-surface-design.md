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
with real text and no Angular. Section 4 uses this precedent as one data point for the rendering
method, together with the audit's measurements.

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
  the list that #36's route list (`SpaRoutes.kt:18-27`) must keep matching.

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
separate from a build-time static export
(https://angular.dev/guide/ssr). This app already runs on one fly.io machine with 512 MB of memory.

- `fly.toml:64-66` sets `size = "shared-cpu-1x"` and `memory = "512mb"`. One machine, not two.
- `Dockerfile:97-98` sets `JAVA_OPTS="-XX:MaxRAMPercentage=75.0"`. The JVM alone claims 75% of the
  machine's memory. This is the same figure the audit comment gives.

A Node.js SSR process would share the remaining memory with the JVM, on a machine already close to
its ceiling. The learning-engine design already warns about this at
`docs/superpowers/specs/2026-08-01-assisted-learning-engine-design.md:72`: "When spec C requires
server-side rendering, an Angular SSR service joins as a second fly process." This document
overrides that expectation. A second process is not affordable on the current machine, and the
reader's own code has kept it optional: `frontend/src/app/catalog/catalog-page.component.ts:11-14`
states, in a comment, that the page "does not read `window`, `document` or `localStorage` on its
render path... kept that way on purpose... for when spec C adds server rendering." Server rendering
of the Angular reader is not ruled out for ever. It is ruled out for this machine, today.

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
  (`backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt:317-321`,
  `prewarmSeed`).

A build-time prerender would therefore ship a topic page with no seed text, which defeats the
purpose of the page.

### 4.4 Why option 3 wins

Ktor already holds every piece the public pages need, in modules that already exist:

- `catalog` resolves a slug to a published topic
  (`backend/catalog/src/main/kotlin/com/mytetz/catalog/CatalogService.kt:28`,
  `backend/catalog/src/main/kotlin/com/mytetz/catalog/TopicRepository.kt:63`).
- `graph` resolves a content key to a stored explanation, as a pure read with no generation
  (`ExplanationRepository.kt:35-36`, `findByKey`).
- `SpaRoutes.kt` and `GuidePages.kt` already prove the pattern: a Ktor route that emits real HTML,
  served from inside the same jar, at no extra memory cost.

No second process, no second template language for the interactive reader, and no data-availability
problem. The cost is a topic card that exists in two templates: one in Kotlin for the public page,
one in Angular for the catalogue tile. That duplication is accepted, because it is far cheaper than
a second fly machine.

### 4.5 What does not change

The Angular reader stays exactly as it is. It still avoids `window`, `document`, and
`localStorage` on its render path, per the learning-engine design's own rule
(`assisted-learning-engine-design.md:72`). That rule keeps a future move to Angular SSR possible, if
the machine ever grows. This document does not ask for that move now.

---

## 5. Decision — topic pages

**Decision.** `GET /topics/{slug}` is a Ktor route. It renders a full HTML page for a published
topic, with the seed text, and it triggers no generation.

### 5.1 What the page contains

- `<title>`: `<Topic title> explained simply | mytetz`, under 60 characters. A long title uses a
  shorter pattern.
- `<meta name="description">`: the topic's `summary` field
  (`backend/catalog/src/main/kotlin/com/mytetz/catalog/Topic.kt:13`).
- `<link rel="canonical" href="https://mytetz.com/topics/<slug>">`, plus `og:url`, `og:title`,
  `og:description`, and `og:image`.
- An `<h1>` with the title, the category, and the seed text in a `<p>`.
- `LearningResource` JSON-LD (https://schema.org/LearningResource) and `BreadcrumbList` JSON-LD.
- A "Start with this topic" control that calls `POST /api/sessions` and opens `/learn/:sessionId`.

### 5.2 How the page reads the seed, and why it never generates one

The route calls, in order:

1. `CatalogService.findBySlug(slug)`, and it answers `404` unless the result exists and
   `status == PUBLISHED` — the same rule `CatalogRoutes.kt:108-109` already enforces for
   `GET /api/catalog/topics/{slug}`.
2. `ContentKey.seed(topic.slug, promptVersion, modelFamily)`
   (`backend/graph/src/main/kotlin/com/mytetz/graph/ContentKey.kt:59-68`), where `promptVersion`
   comes from `GraphConfig.promptVersion` (`backend/graph/src/main/kotlin/com/mytetz/graph/GraphConfig.kt:14`).
3. `ExplanationRepository.findByKey(key)` (`ExplanationRepository.kt:35-36`), which only reads. It
   never calls the model.

A topic whose seed is not in the store renders the summary and no seed paragraph. This case should
not happen for a published topic once #18's pre-warm step runs, per the invariant the monetization
design already states: "A published topic must have a seed explanation in the store"
(`2026-08-07-monetization-design.md:441-442`). The topic page does not re-check that invariant. It
trusts it, and it degrades gracefully if the invariant is ever violated.

### 5.3 The list under the seed — this document changes #45

This is a point where this document changes the page structure that #45 proposes, and the reason
is a mismatch inside the tracker itself.

- Issue #19's own requirements state: "Topic pages at `/topics/{slug}` with the seed and the
  most-requested explanations under it, ordered by `requestCount`."
- Issue #45's implementation step 2 instead asks for "a list of links to the other published
  topics in the same category."

Both lists are useful, and they serve two different jobs. This document keeps both, in this order:

1. **Popular questions about this topic** — the top `EXPLAIN` nodes under this topic, ordered by
   `Explanation.requestCount` descending (`backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt:28`),
   each linking to its public explanation page (section 6). This is the list issue #19 asks for.
2. **Related topics** — the other published topics in the same category, as #45 proposes. This
   keeps the page useful for site navigation even before any explanation is published.

List 1 has no content until an explanation is `published` (section 6), which #48 delivers after
#45. #45 therefore ships list 2 alone. #48 must add list 1 to the same template once a published
explanation exists for that topic. **This document directs #45 and #48 to update their own bodies
to reflect this two-list structure**, per step 11 of issue #19 — a step this spec does not carry
out itself.

### 5.4 The catalogue tile

#45 asks to change each catalogue tile from a `<button>` into an `<a href="/topics/<slug>">`. This
document confirms that change. Two corrections to #45's own citation:

- #45 cites the button at `catalog-page.component.ts:99-113` and an `<h2>` inside it at line 112.
  On 2026-09-19 the button is at lines 123-143 and the `<h2>` is at line 136. #38 added the
  paragraph at lines 26-35 after #45 was written, which shifted every later line number. #45's
  implementer must re-locate the button by its class name, `topic__button`, and not by the line
  numbers in the issue body.
- The defect #45 names is real: an `<h2>` element inside a `<button>` element is not valid HTML.
  `catalog-page.component.ts:136` confirms it.

---

## 6. Decision — public explanation pages and the glossary

**Decision.** A published `EXPLAIN` node gets a stable URL at
`/topics/<slug>/explain/<short-key>`, where `<short-key>` is the first 12 characters of the node's
64-character hexadecimal content key.

### 6.1 Why this shape, and what lost

| Option | Reason it lost |
|---|---|
| The full 64-character key in the URL | Needlessly long. A crawler and a person both handle a shorter, still-unique path better. |
| An 8-character prefix | `ContentKey.derive` (`ContentKey.kt:34-51`) returns a SHA-256 digest, rendered as 64 hex
characters (line 50). Section 7 caps published pages at 100. At 100 items, an 8-character (32-bit) prefix carries a
collision probability high enough to matter over the life of the site; 12 characters (48 bits) does not. |
| Drop the verb segment, since today only `EXPLAIN` gets published | Rejected for the same reason #47 keeps `grounded` and `sources` on every `Explanation`, published or not: the design spec's verb table (`assisted-learning-engine-design.md:355-361`) defines five verbs, and a later decision to publish a `DIG_DEEPER` or `BROADER_PICTURE` page should not force a URL migration. The segment costs nothing today. |

This confirms the shape #48 already proposes as an example ("for example
`/topics/<slug>/<verb>/<short-key>`") and fixes its two open points: the verb segment stays, and
the key length is 12 hexadecimal characters.

### 6.2 The `published` flag and `noindex`

- The explanation document gains a `published` field, default `false`, per #48 step 2.
- An unpublished page answers with `X-Robots-Tag: noindex`, using the same header and constant
  `NoIndexPlugin.kt` already defines (`X_ROBOTS_TAG`, `NOINDEX`, lines 11 and 14).
- A published page carries the canonical tag and no `noindex` header.
- The page shows the breadcrumb trail from the topic to this node as `BreadcrumbList` JSON-LD, the
  span as the `<h1>`, the explanation body, and a link back to the topic page.

### 6.3 The glossary and the cap

`/glossary` lists every published `EXPLAIN` node by its span, each with `DefinedTerm` JSON-LD
(https://schema.org/DefinedTerm), per #48 step 4. This document confirms #48 step 7's cap of 100
indexable explanation pages until #50 shows they earn traffic. The cap keeps the site inside
Google's scaled-content-abuse policy, which applies "when many pages are generated for the primary
purpose of manipulating search rankings and not helping users"
(https://developers.google.com/search/docs/essentials/spam-policies#scaled-content-abuse). A
capped, human-reviewed set of 100 pages is a curated set, not a scaled one.

### 6.4 One item this document leaves to #48

#48 step 5 asks the owner to choose between a script run against the database and a route guarded
by an owner allow-list, for setting `published`. This document does not choose between them. It is
listed as an owner decision in section 11.

---

## 7. Decision — `sitemap.xml` and `robots.txt`

**Decision.** `GET /sitemap.xml` becomes a Ktor route. `robots.txt` stays a static file.

### 7.1 The route

This document confirms #46 as written, with one fact checked and confirmed:

- #46 step 2 asks to set `lastmod` "from the `createdAt` of the seed explanation, or from a topic
  timestamp if `Topic` holds one," and to "confirm the field before you use it."
- **Confirmed:** `Topic` (`backend/catalog/src/main/kotlin/com/mytetz/catalog/Topic.kt:9-17`) holds
  no timestamp field. `Explanation` (`backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt:29`)
  holds `createdAtEpochMillis`. #46 must use the seed explanation's `createdAtEpochMillis`.

The route writes one `<url>` for the home page, one for each published topic, one for each path in
`GuidePages.paths` (`GuidePages.kt:22-30`), and, after #48, one for each published explanation page.
It follows the sitemap protocol: `<loc>` is required, `<lastmod>` is optional, and one file may hold
up to 50,000 URLs (https://www.sitemaps.org/protocol.html). The catalogue is far below that limit
even at the 100-topic scale #18 targets.

### 7.2 `robots.txt` and `llms.txt` stay static, with one open point

`robots.txt` needs no change: `Allow: /` already covers every new public path, and the `noindex`
pages are excluded through the `X-Robots-Tag` header, not through `robots.txt`
(`NoIndexPlugin.kt:39-43`).

`llms.txt` is a static file today (#39, `frontend/public/llms.txt`). #45 step 6 asks to add every
topic URL to it by hand. This does not scale to the 100-topic catalogue #18 targets, for the same
reason #46 replaces the static sitemap. This document raises it as an open point in section 10,
rather than deciding it, because no issue in 45-48 owns `llms.txt`'s long-term shape.

---

## 8. Decision — structured data and Open Graph

**Decision.** Each page type gets the JSON-LD listed below. All of it is valid schema.org markup.
Not all of it is a Google Search rich-result type, and this document says which is which so nobody
expects a result that will not appear.

| Page | JSON-LD | Google rich-result type? |
|---|---|---|
| Topic page | `LearningResource`, `BreadcrumbList` | `BreadcrumbList`: yes. `LearningResource`: not confirmed — see below. |
| Explanation page | `BreadcrumbList` | Yes |
| Glossary entry | `DefinedTerm` | Not confirmed — see below |
| `/` and `/how-it-works` | `Organization` | Yes, for the Knowledge Panel |

**Confirmed against Google's own gallery
(https://developers.google.com/search/docs/appearance/structured-data/search-gallery):**
`BreadcrumbList` and `Organization` are both listed as Google Search rich-result types.
`LearningResource` and `DefinedTerm` are not listed there. This does not make either type wrong to
use — schema.org defines both, and an answer engine other than Google Search may still read them —
but it means neither gives a Google Search rich-result appearance today. This document adds them
for their machine-readable value, and not on a promise of a rich result.

`BreadcrumbList` needs at least two `ListItem` entries, each with `position`, `name`, and usually
`item` (https://developers.google.com/search/docs/appearance/structured-data/breadcrumb). A topic
page's trail (home, topic) meets this. An explanation page's trail (home, topic, explanation) meets
it too.

Every page keeps its Open Graph tags, per the pattern #34 and #35 already ship for the Angular
routes: `og:url`, `og:title`, `og:description`, `og:image`.

---

## 9. Decision — the wall and the public pages

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

This is not a new decision. It is a confirmation that the public pages this document adds do not
need a new row in the gate table.

---

## 10. Decision — Cloudflare cache rules

**Decision.** Add one Cloudflare Cache Rule that matches `/topics/*`, `/topics/*/explain/*`, and
`/glossary*`, and sets a long edge cache lifetime. This rule is separate from the existing
`/api/*` bypass rule from `docs/deploy.md` section 5, step 4.

### 10.1 Why one more rule fits inside the Free plan

`docs/deploy.md` section 5 already records that the zone is on Cloudflare's Free plan, and that
Free allows only one rate-limiting rule
(https://developers.cloudflare.com/waf/rate-limiting-rules/). Cache Rules are a separate feature
with a separate limit. Cloudflare's own documentation on Cache Rules states that a Free plan zone
may have 10 Cache Rules
(https://developers.cloudflare.com/cache/how-to/cache-rules/). The existing `/api/*` bypass rule is
one. This document's new rule is a second. Both fit well inside the limit of 10.

### 10.2 Why a separate rule, and not one shared rule

A shared rule cannot express "cache this, bypass that" at once. The `/api/*` rule must keep
bypassing the cache, per the existing note that a cached or buffered response breaks the
server-sent-events stream. The new rule caches only the new static-shaped HTML paths. Two rules,
two intents.

### 10.3 The cache lifetime

Follow the same reasoning `SpaRoutes.kt:56-65` already uses for a static content page: a public
topic or explanation page changes only when a person edits or republishes it, which is rare, so an
edge cache lifetime of one hour is enough to cut repeat load without serving a stale page for long.
This matches the `public, max-age=3600` value `cacheControlFor` already gives any other
`index.html` (`SpaRoutes.kt:69`).

---

## 11. Decision — measurement

**Decision.** #42 owns Google Search Console ownership, and this document does not repeat that
step. This document names the first queries the owner should watch once #45 and #48 are live,
drawn from the audit's own evidence: `<topic> explained simply` (per issue #45's citation of issue
#44's finding, quoted on #19: "Small sites rank for '<topic> explained simply'"), the bare topic
name, and `what is <topic>`.

`#50` is the review of traffic against this list, after `#42` and this document's pages are live.
This document does not move that boundary.

---

## 12. Where this document confirms or changes #45 to #48

| Issue | Confirms | Changes |
|---|---|---|
| #45 | The route shape `/topics/{slug}`, the `404` rule, the seed-only read with no generation, the tile-to-`<a>` change, the JSON-LD types. | Adds the "popular questions" list ordered by `requestCount` (section 5.3), which #45's own step 2 omits. Corrects the line numbers #45 cites for the catalogue tile (section 5.4). |
| #46 | The route shape, the protocol reference, the deletion of the static file, the inclusion of `GuidePages.paths`. | Confirms `Topic` holds no timestamp (section 7.1), which resolves #46 step 2's own "confirm the field" instruction: use the seed explanation's `createdAtEpochMillis`. |
| #47 | The `/how-it-works` page, the `Organization` JSON-LD, the review-date field on `Topic`, the web-tool sourcing rule. | None. |
| #48 | The `published` flag, the `noindex` header, the glossary, the 100-page cap. | Fixes the URL shape's two open points: keep the verb segment, and set the key length at 12 hexadecimal characters (section 6.1). |

---

## 13. Facts this document could not confirm

- **Whether Google Search or another answer engine gives any ranking or citation credit to
  `LearningResource` or `DefinedTerm` JSON-LD.** Google's own rich-result gallery does not list
  either type as of this document's review
  (https://developers.google.com/search/docs/appearance/structured-data/search-gallery). No
  official source states what, if anything, an AI answer engine such as an AI Overview does with
  either type. **Not confirmed.**
- **Whether an AI answer engine (outside Google Search) publishes any official crawling or
  indexing specification at all.** This document found no equivalent to Google's Search Central
  documentation for another named answer engine. **Not confirmed.** Section 11's list of queries to
  watch is therefore built on Google Search Console data only.
- **The exact Cloudflare feature name that implements the existing `/api/*` bypass** (Cache Rules,
  or an older Page Rule). `docs/deploy.md` section 5 step 4 names the effect and not the feature.
  This document assumes Cache Rules, because Cloudflare's newer accounts default to that feature,
  but it does not carry a citation confirming which feature this zone actually uses. **Not
  confirmed; the owner should check the dashboard before adding the second rule in section 10.**

---

## 14. Questions for the owner

Only the owner answers these. Each one blocks a small, named part of implementation, and none of
them blocks approving this document as a whole.

1. **The review path for setting `published` on an explanation (#48 step 5).** A script run
   against the database with `MONGODB_URI`, or a route guarded by an owner-email allow-list? **My
   recommendation:** the script. It needs no new authentication code, and the review step is
   already an infrequent, deliberate action.
2. **Any public profile beyond GitHub for the `Organization` JSON-LD's `sameAs` field (#47 step
   3).** #47 already asks this. This document repeats it because section 8 depends on the answer.
   **My recommendation:** ship with `https://github.com/xamcross/mytetz` alone, and add more later;
   `sameAs` accepts a list and a later addition is not a breaking change.
3. **Whether `llms.txt` should become a Ktor route, generated the same way as `sitemap.xml`
   (section 7.2).** No issue in 45-48 owns this, and it will drift once the catalogue passes a
   handful of topics. **My recommendation:** yes, as a small follow-up after #46, reusing the same
   `CatalogService.listPublished()` call.
4. **Confirmation of the Cloudflare feature used for the existing `/api/*` bypass rule (section
   10, section 13).** **My recommendation:** the owner checks the Cloudflare dashboard once, before
   the second rule is added, and records the answer in `docs/deploy.md` section 5.

---

## 15. Next steps

1. The owner reviews this document and sets its status to "Approved for planning."
2. An implementation plan follows, under `docs/superpowers/plans/`.
3. After approval, #45 to #48 are updated where this document differs from their bodies, per
   section 12, and the `blocked` label and first line are removed from #45.
