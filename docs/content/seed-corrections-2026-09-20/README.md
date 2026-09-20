# Seed text corrections, 2026-09-20 (issue #162)

This note is the step 1 investigation. It answers the stop rule before the command exists. Every
statement below names a file and a line.

## The three corrections

| Slug | Old statement | New statement | Old chars | New chars |
|---|---|---|---|---|
| `special-relativity` | "how space and time behave for objects moving at constant speeds, especially near the speed of light" | "how space and time behave for objects moving at a constant velocity, especially near the speed of light" | 541 | 545 |
| `microbiology` | "the scientific study of living things too small to see with the naked eye, such as bacteria, viruses, fungi, and single-celled organisms called protozoa" | "the scientific study of organisms and agents too small to see with the naked eye, such as bacteria, fungi, single-celled organisms called protozoa, and viruses" | 518 | 525 |
| `historical-linguistics` | "Linguists compare related languages, like French, Spanish, and Italian, to reconstruct earlier "parent" languages, such as Latin, that are no longer spoken." | "Linguists compare related languages to reconstruct a lost "parent" language, such as Proto-Indo-European, and test the method on Latin, the known parent of French, Spanish, and Italian." | 564 | 593 |

The old text and the char counts come from a read-only `GET` of the live page on 2026-09-20:
`https://mytetz.com/topics/special-relativity`, `/topics/microbiology`,
`/topics/historical-linguistics`. Every other sentence of each seed stays exactly as it is on the
live page, with its own punctuation. `docs/content/seed-corrections-2026-09-20/<slug>.txt` holds
each full new text, byte for byte, with no trailing newline.

## The reason for each correction

- **`special-relativity`.** Special relativity is about a constant velocity — a constant speed
  *and* a constant direction — not a constant speed alone. This is the ordinary statement of the
  theory's first postulate (inertial frames, i.e. frames moving at constant velocity relative to
  one another).
- **`microbiology`.** Most biologists do not class a virus as a living thing: a virus cannot
  metabolise or reproduce on its own, outside a host cell. The corrected sentence lists it as an
  "agent" studied by microbiology, alongside the living organisms, rather than as one of the
  "living things."
- **`historical-linguistics`.** Latin is attested by a large body of written text, so it is not an
  example of an unattested reconstructed parent. The standard example of a reconstructed parent
  that left no texts is Proto-Indo-European. Latin's real role in the field is as a check on the
  comparative method: linguists can compare French, Spanish and Italian, reconstruct what their
  parent must have looked like, and then confirm the reconstruction against the real, attested
  Latin — which is why the corrected text keeps Latin, French, Spanish and Italian, but changes
  what they are said to demonstrate.

None of the three corrected statements was found to be itself in error.

## How a session node stores its phrase

- A `SessionNode` stores `explanationKey`, `span`, `verb`, `variant`, `depth` and
  `createdAtEpochMillis` — never the offsets of the highlighted phrase.
  (`backend/session/src/main/kotlin/com/mytetz/session/LearningSession.kt:61-70`)
- `span` is the **child's own** highlighted phrase (the text that produced this node from its
  parent), stored as plain text, not as an offset pair into the parent body.
  (Same file, `SessionNode.span`, line 65; `SessionService.explain` writes it at
  `backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt:592`.)
- Offsets exist only for one request, in memory: `SpanSelection(text, start, end)`
  (`SessionService.kt:48`), checked against the **live, current** parent body by `validateSpan`
  (`SessionService.kt:788-804`) and never written to the database.
- The sentence around a span (`spanSentence`) is computed fresh from the live parent body at
  request time, by `sentenceAround` (`SessionService.kt:806-881`), and it is stored only on the
  **child** `Explanation` document that request produces
  (`backend/graph/src/main/kotlin/com/mytetz/graph/Explanation.kt:15`) — never on the parent, and
  never on the `SessionNode`.

## What the reader does when a stored phrase is not at its offsets any more

- The reader never re-locates a historical `SessionNode.span` inside a live body by offset. The
  trail rail and the breadcrumb both render `node.span` as a plain label string:
  `frontend/src/app/reader/trail-rail.component.ts:65` and
  `frontend/src/app/reader/breadcrumb.component.ts:30`. Changing a parent's stored text cannot
  break either rendering, because neither one searches the parent body for the label.
- `FocusCardComponent.bodyMatches` (`frontend/src/app/reader/focus-card.component.ts:439`) checks
  only that the DOM's `textContent` for the node **currently in focus** equals the `body` input
  that same render was given (`focus-card.component.ts:493-514`, using
  `rootTextMatchesBody` at `frontend/src/app/reader/selection.ts:124-126`). Both sides of that
  check come from the same page load, so a seed text correction cannot make it fail on a fresh
  load — the corrected text loads on both sides of the comparison. The warning state
  (`focus__hint--warning`, `focus-card.component.ts:165`) exists for a different, pre-existing
  case: a page-translation or grammar-checking extension rewriting the live DOM in place after
  render (documented at `focus-card.component.ts:609-615`).
- **The one real interaction:** a browser tab that already loaded the seed's *old* text, if the
  learner highlights a phrase and submits it *after* the correction lands. The client sends
  offsets computed against the old text. `validateSpan` then compares those offsets against the
  now-corrected live body (`SessionService.kt:801-803`) and raises `SpanMismatchException`. The
  API layer maps this to an ordinary `400 SPAN_MISMATCH`
  (`backend/api/src/main/kotlin/com/mytetz/api/ErrorMapping.kt:131-132`), the same response class
  a stale translation-mutated DOM already produces today. No node is appended, no data is written,
  and reloading the page (which re-fetches the corrected text) fully recovers the session. This is
  not a state a learner cannot recover from.

## Which stored fields and which public pages depend on the seed text

- `TopicPageRoutes.kt` reads the seed fresh from the database on every request —
  `explanations.findByKey(seedKey)` at
  `backend/api/src/main/kotlin/com/mytetz/api/TopicPageRoutes.kt:54-55`, no cache in this process —
  and passes it to `TopicPageView.seedBody` (line 74).
- `TopicPageHtml.kt:279` renders `view.seedBody` as the page's one visible paragraph. This is the
  only place the seed's exact wording reaches a public page.
- `TopicPageHtml.kt`'s JSON-LD (`learningResourceJsonLd`, lines 353-360) uses `view.summary` (the
  catalogue's own topic summary) and `view.reviewedAt`, never the seed body — so a seed text
  correction does not touch the JSON-LD `description`.
- `SitemapRoutes.kt` reads the same seed, fresh, once per request
  (`backend/api/src/main/kotlin/com/mytetz/api/SitemapRoutes.kt:66-72`) and feeds
  `seed.createdAtEpochMillis` into `lastModifiedFor`, which takes the **later** of that value and
  `topic.reviewedAt` (`SitemapRoutes.kt:41-49, 181-185`). A text-only correction does not, by
  itself, change `createdAtEpochMillis`, so it does not move `<lastmod>` forward on its own — the
  design already expects a separate review-date step (issue #47's own command, chained after this
  one in the runbook) to record that a person confirmed the corrected text.
- `ExplanationPageRoutes.kt:86` filters to `verb == Verb.EXPLAIN` only, so a `SEED` document is
  never reachable through `/topics/{slug}/explain/{shortKey}`. A seed correction never touches an
  explanation page.

## Whether the pre-warm or a migration can write the old text back

- **Pre-warm never overwrites an existing seed.** `SessionService.prewarmSeed`
  (`backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt:368-385`) checks
  `explanations.findByKey(graph.keyFor(request)) != null` first and returns `false`, generating
  nothing, the instant a document already exists under that key. `ContentKey.seed` derives the key
  from `(topicSlug, promptVersion, modelFamily)` only
  (`backend/graph/src/main/kotlin/com/mytetz/graph/ContentKey.kt:59-68`) — never from the body text
  — so the key is unchanged by this command, and `Components.prewarm()`
  (`backend/api/src/main/kotlin/com/mytetz/api/Components.kt:456-493`), which runs on every boot
  with no flag, finds the corrected document already there and leaves it alone.
- **The migration is a real, but pre-existing and flag-gated, risk — not specific to this
  command.** `Components.migrate()` (`Components.kt:414-423`) runs only when the operator sets
  `MYTETZ_MIGRATE_ON_BOOT=true` for one deploy (`Components.kt:145, 688, 694-703`), and it deletes
  every explanation — seed or not, corrected or not — whose `modelFamily` differs from the
  deploy's own model family (`ExplanationRepository.deleteWhereModelFamilyIsNot`,
  `backend/graph/src/main/kotlin/com/mytetz/graph/ExplanationRepository.kt:57-73`). A correction
  written by this issue's command does not change `modelFamily`, so it deletes on exactly the same
  terms as every other explanation: only on a genuine model-family change, never on an ordinary
  deploy. When that migration does run, `prewarm()` then generates a **brand-new** seed from the
  live model under the new family — not literally "the old text put back", since nothing here
  restores a prior value, but a fresh, non-deterministic generation that carries no guarantee of
  keeping the correction. This is an existing hazard for every explanation in the store, stated
  already in `ExplanationRepository.kt:65-70` ("a mistake here costs a regeneration and not a
  corruption"); this issue's command does not create it or make it worse. It is called out in the
  runbook (`docs/deploy.md`, "Seed text correction" section) as a reason to re-check the three
  corrected pages after any deploy that turns `MYTETZ_MIGRATE_ON_BOOT` on.

## Stop-rule verdict

Neither trigger applies:

1. A replace of the text cannot leave an existing session in a state a learner cannot recover
   from. The one failure mode found — a stale browser tab's offsets mismatching a just-corrected
   body — is an ordinary, already-existing `400 SPAN_MISMATCH`, and a page reload fully recovers
   it. No node is appended and no document is corrupted.
2. Neither the pre-warm nor an ordinary migration run writes the old text back over a correction.
   `prewarm()` never touches an existing document. `migrate()` only ever deletes-and-regenerates on
   a deliberate, operator-flagged model-family change, exactly as it already does for every other
   explanation in the store — a documented, pre-existing operational property of the system, not a
   defect this command introduces.

The command is built on this base. This finding does not change: an owner who runs
`MYTETZ_MIGRATE_ON_BOOT=true` for a real model-family switch should re-run the check step of the
runbook afterwards, for these three topics, the same as for every other seed.
