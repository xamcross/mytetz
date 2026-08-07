# Candy design — application to the Angular reader

This document uses ASD-STE100 Simplified Technical English.

**Status.** Approved design. Ready for an implementation plan.

**Source.** `docs/mytetz-design-reference.html`. The file is a bundled Claude Design canvas. It
holds five sections: `2b` and `3a` show mobile, `4a` shows desktop at 1360, `5a` and `5b` show the
quiz and the Visualize verb. All five use one visual direction. The design calls it **Candy**.

The committed file is stripped: 3,783,458 bytes became 1,359,456 bytes, a saving of 64%. Removed:
42 of the 52 embedded woff2 subsets. Six families are gone whole — Baloo 2, Bricolage Grotesque,
Literata, Newsreader, Nunito and Sora — because no section uses one. The devanagari, hebrew,
vietnamese, cyrillic and greek subsets of the four families the design does use are gone too. Kept:
the latin and latin-ext subsets of Fredoka, Figtree, Space Grotesk and IBM Plex Mono.

**The vendored Babel is kept, and that was measured and not assumed.** The device frame and the
browser frame are JSX modules that the runtime transpiles in the page. Drop the bundled Babel and
the runtime falls back to a fetch from `unpkg.com`; with no network both frames fail to load and the
rendered DOM loses 60 KB. Babel is 880 KB of the original file, which is 23% of it and not the 3.1
MB an earlier note claimed — that number is Babel's size after it is decompressed, not in the file.
The fonts were 2.62 MB, which is 69%.

Verified in a real Chromium with every non-file request blocked: the stripped file renders a DOM
byte-identical to the original, with no console error, all four sections present, and all six
loading font faces still loading.

**Parent spec.** `docs/superpowers/specs/2026-08-01-assisted-learning-engine-design.md`. This
document changes no decision in that spec. It changes how the product looks.

---

## 1. Purpose

Give the catalog and the reader the Candy look. Build a token layer that a later screen can reuse.
Change no backend route and add no product feature.

---

## 2. Scope

### In scope

- A token layer in `frontend/src/styles.css`: the fonts, the custom properties, four global
  classes.
- A shell component: the 64px top bar plus a backend health indicator.
- The catalog page at `/`.
- The reader page at `/learn/:sessionId`, and its four child components.
- A verb picker: a popover on a wide screen, a bottom sheet on a narrow screen.
- Every state the two pages already produce: the load, an error, an empty result, a stream in
  progress.

### Out of scope, and why

The design draws a product that is three slices ahead of the code. Each item below has no backend
route. Each one needs its own spec.

| Item in the design | Why it is out |
|---|---|
| Sessions list at `/sessions` | No `GET /api/sessions`. |
| Glossary at `/glossary`, saved terms | No collection, no route. |
| Quick check quiz | Parent spec §8, slice 3. Not built. |
| Visualize diagram | Parent spec §9, slice 4. Not built. |
| Streak counter, account avatar | No user model. |
| Quota meter, "16 of 20 left" | The backend knows the quota. No route reports the balance. |
| "Popular this week" | No demand data. |
| Nearest-match card on an empty search | No match API. A client-side match is a feature. |
| "Suggest a topic instead" | `POST /api/topic-requests` exists. The UI for it is a feature. |

The token layer and the shell are built so that each item above drops in later without a rewrite.

---

## 3. The design system

### 3.1 Fonts

The design uses two families. Both are variable fonts under the SIL Open Font License. The project
self-hosts them.

| File in `frontend/public/fonts/` | Family | Axis | Size |
|---|---|---|---|
| `figtree-latin.woff2` | Figtree | weight 300–900 | 20 KB |
| `figtree-latin-ext.woff2` | Figtree | weight 300–900 | 10 KB |
| `fredoka-latin.woff2` | Fredoka | weight 300–600 | 29 KB |
| `fredoka-latin-ext.woff2` | Fredoka | weight 300–600 | 5 KB |

The four files come from the design bundle. Their manifest identifiers are `6dac9c78`, `93c25b2b`,
`f55d5df1` and `7e6aa8e7`.

Rules:

- Each `@font-face` keeps the `unicode-range` value from the design, so a Latin reader downloads
  49 KB and not 64 KB. The two `-latin` files are 20 KB and 29 KB, and a browser skips the two
  `-latin-ext` files for English content. An earlier draft of this line said 30 KB, which is not
  what the four files in the table add up to.
- Each `@font-face` uses `font-display: swap`.
- `frontend/public/fonts/OFL.txt` holds the licence text. The OFL requires it.
- No request goes to a third-party host. Cloudflare caches the files with the rest of the origin.

**Fredoka is the display face.** It carries a heading, a card title, a trail label and a verb name.
**Figtree is the body face.** It carries prose, a caption, a control and an eyebrow.

### 3.2 Custom properties

Every value comes from the design file. `styles.css` declares them on `:root`.

```css
/* surface */
--mt-page:#effaf6;   --mt-surface:#fff;  --mt-sunk:#f4fbf8;
--mt-border:#cfe9e0; --mt-rule:#d8efe8;  --mt-chip:#e4f2ed;
--mt-skeleton:#dcefe9; --mt-skeleton-2:#e6f4ef;
/* ink */
--mt-ink:#12312a;    --mt-prose:#1b3d36;
--mt-muted:#4c6b64;  --mt-faint:#7ba49b;
/* coral — the primary action */
--mt-coral:#ff5d5d;      --mt-coral-deep:#d63f3f;
--mt-coral-pale:#ffe0e0; --mt-coral-text:#cc3b3b; --mt-coral-press:#c23636;
/* teal — the current position */
--mt-teal:#0f766e; --mt-teal-deep:#0a544e; --mt-teal-pale:#a8d5cd;
/* amber — attention */
--mt-amber:#ffd166;    --mt-amber-deep:#f0c256; --mt-amber-bg:#fff8e6;
--mt-amber-ink:#6b4c00; --mt-amber-ink-2:#8a6b23;
/* error */
--mt-err-bg:#fff1ef; --mt-err-border:#ffc4bf; --mt-err-ink:#b83232; --mt-err-ink-2:#8a4b45;
/* form */
--mt-r-card:26px; --mt-r-panel:22px; --mt-r-row:16px;
--mt-border-w:2px;
--mt-lift:0 4px 0 var(--mt-border);
--mt-lift-card:0 5px 0 var(--mt-border);
--mt-lift-coral:0 4px 0 var(--mt-coral-deep);
--mt-lift-teal:0 4px 0 var(--mt-teal-deep);
--mt-float:0 16px 40px rgba(18,49,42,0.16);
/* type */
--mt-display:'Fredoka','Figtree',system-ui,sans-serif;
--mt-body:'Figtree',system-ui,sans-serif;
```

### 3.3 The colour rule

The design keeps one rule. The code must keep it too.

- **Coral is the primary action.** One coral control per view.
- **Teal is the current position.** The active nav item, the current trail row, a secondary
  action.
- **Amber means "look at this".** It never means "click this".
- **An error is told from a coral control by its form, and never by its hue.** `--mt-coral-press`
  and `--mt-err-ink` measure 1.09:1 against each other. As colours they are the same, and no
  palette change makes them different. What separates them is the shape they take. A coral primary
  is a **filled pill with white text**. An error is **dark red text inside a pale pink card with a
  pink border**. Fill against text, and the pale card, carry the whole distinction. That works for
  every reader, including a reader with red-green colour blindness, because neither cue is a hue.
  `palette.spec.ts` asserts all of it.

A component that breaks the rule looks correct and reads wrong. A review must check it.

### 3.4 Contrast — three deviations from the design file

The design file fails WCAG AA in three places. Each measurement below is a contrast ratio against
the stated background. AA needs 4.5:1 for normal text and 3:1 for large text. Large text means
24px, or 18.66px at weight 600 or more.

| Design use | Measured | Verdict |
|---|---|---|
| `#7ba49b` text on `#effaf6` | 2.55:1 | Fails |
| `#7ba49b` text on `#fff` | 2.74:1 | Fails |
| `#ff5d5d` 11px eyebrow on `#fff` | 3.01:1 | Fails |
| White 13px/800 on `#ff5d5d` | 3.01:1 | Fails |
| White 19px/600 on `#ff5d5d` | 3.01:1 | Passes as large text |
| `#8a6b23` on `#fff8e6` | 4.66:1 | Passes |
| `#b83232` on `#fff1ef` | 5.46:1 | Passes |

The code applies three corrections. Each one keeps the design's hue.

1. **`--mt-faint` is decoration only.** It draws a divider, a dot and an inactive track. Text that
   the design draws in `#7ba49b` uses `--mt-muted` (`#4c6b64`, 5.37:1 on the page). This is a
   colour the design already uses.
2. **Small coral text uses `--mt-coral-text`** (`#cc3b3b`, 4.92:1 on white). The 24px wordmark
   keeps `--mt-coral`, because it is large text.
3. **A coral control below 18.66px uses `--mt-coral-press`** as its background (`#c23636`, 5.42:1
   against white). A coral control at 19px or more keeps `--mt-coral`.

The corrections change the look by a small amount. They are deliberate. A reader with low vision
is a reader.

### 3.5 The global classes

Four classes, for appearance with no behaviour. Each has tone modifiers.

| Class | Draws | Modifiers |
|---|---|---|
| `.mt-card` | Surface, 2px border, radius, offset lift | `--amber`, `--error`, `--flat`, `--dashed`, `--raised` |
| `.mt-pill` | A control with radius 999px | `--coral`, `--teal`, `--ghost` |
| `.mt-chip` | A small label with radius 999px | `--teal`, `--amber`, `--error` |
| `.mt-eyebrow` | 11px, weight 800, uppercase, 0.1em tracking | `--coral`, `--amber` |

`.mt-eyebrow--coral` resolves to `--mt-coral-text` and not to `--mt-coral`. An eyebrow is 11px, so
§3.4 rule 2 applies to it. `.mt-pill--coral` fills with `--mt-coral-press` when its label is below
18.66px, and with `--mt-coral` at 19px or more. §3.4 rule 3.

`.mt-card--raised` is the design's big card: radius `--mt-r-card` with the deeper `--mt-lift-card`.
The reader's focus card and the skeleton that stands in for it are one card in two states, so the
pair lives here and not in each component.

There is no `.mt-pill--amber`. A pill is a control, and §3.3 says amber never means "click this".
The amber tone stays on `.mt-card--amber` and `.mt-chip--amber`.

The offset lift `0 4px 0 <colour>` is written once, here. The design uses it on eleven elements.
A change to the look is then one edit and not eleven.

A control that uses `.mt-pill` presses down on `:active`: it moves 2px down and loses 2px of
shadow. `@media (prefers-reduced-motion: reduce)` removes the transition and keeps the change of
state.

### 3.7 The reset

`styles.css` also sets the page defaults, which no component then repeats.

- `*, *::before, *::after { box-sizing: border-box }`.
- `body`: `background: var(--mt-page)`, `color: var(--mt-ink)`,
  `font-family: var(--mt-body)`, `margin: 0`, `-webkit-font-smoothing: antialiased`.
- `h1`–`h3`: `font-family: var(--mt-display)`, `font-weight: 600`, `margin: 0`.
- `:focus-visible`: a 3px `--mt-teal` outline with a 2px offset. One rule in the project sets
  `outline: none`, and it names one element that is not a control: `.focus__body`. See §7.6.
- `a`: `--mt-teal`, with an underline that thickens on hover.

Each page component then holds its own layout only. It holds no colour that a token names.

### 3.6 Type scale

| Role | Face | Size | Weight | Line height |
|---|---|---|---|---|
| Page title | Fredoka | 34px | 600 | 1.15 |
| Card title | Fredoka | 26px | 600 | 1.2 |
| Tile title | Fredoka | 23px | 600 | 1.2 |
| Verb name | Fredoka | 16px | 600 | 1.2 |
| Prose | Figtree | 19px | 500 | 1.65 |
| Tile summary | Figtree | 14px | 500 | 1.55 |
| Control | Figtree | 13–15px | 700–800 | 1 |
| Eyebrow | Figtree | 11px | 800 | 1 |

Below 768px the page title drops to 26px and prose drops to 17px. Prose caps at 62ch.

---

## 4. Layout and breakpoints

One breakpoint: **768px**.

| Width | Shell | Catalog | Reader | Verb picker |
|---|---|---|---|---|
| < 768px | Wordmark, health dot | One column | One column, trail as a drawer | Bottom sheet |
| ≥ 768px | Wordmark, Topics, health dot | Two columns, then three at 1120px | Two columns: 260px rail, then the card | Anchored popover |

`trail-rail.component.ts` moves its media query from 640px to 768px, so the drawer and the picker
change mode together. No test depends on 640px. This was checked, not assumed.

The reader grid is `260px minmax(0, 720px)`, centred, with a 24px gap. The right column of the
design at `4a` is dropped, because every card in it needs a route that does not exist. The grid
returns to three columns with a one-line change when the routes arrive.

---

## 5. The shell

### 5.1 `ui/app-shell.component.ts` — new

A 64px bar with a white surface and a 2px `--mt-rule` bottom border. It projects the page below
itself with `<ng-content>`.

- Left: the wordmark `mytetz` in Fredoka 24px/600, `--mt-coral`. It routes to `/`.
- Left, next: one nav link, `Topics`, Figtree 15px. Active it takes weight 800, `--mt-teal` and a
  3px `--mt-teal` bottom border. Inactive it takes weight 700 and `--mt-muted`. `routerLinkActive`
  supplies the state.
- Right: the health dot.
- Below 768px the nav link is hidden. The wordmark already routes to the catalog.

### 5.2 `ui/status-dot.component.ts` — new

The design draws a streak pill on the right of the bar. No streak exists. The health check that
`app.ts` runs today takes that place instead.

A 10px circle with a 2px ring.

| Health result | Colour | `title` and `aria-label` |
|---|---|---|
| `mongo: true` | `--mt-teal` | `Backend ok` |
| `mongo: false` | `--mt-amber` | `Backend degraded` |
| request failed | `--mt-err-ink` | `Backend unreachable` |
| before the answer | `--mt-faint` | `Backend: checking` |

The dot is not the only signal. Its `aria-label` states the result in words, so a reader who
cannot tell the colours apart still gets it.

### 5.3 `app.ts` — changed

It loses the scaffold `<h1>mytetz</h1>` and `<p>backend: {{ status() }}</p>`. It becomes the shell
around `<router-outlet />`. It keeps the health request and passes the result to the dot.

`app.ts` has no test today. This work adds one.

---

## 6. The catalog page

### 6.1 Layout

A page title, a filter row, then a tile grid. The content caps at 1120px and centres. Padding is
36px by 40px, and 24px by 20px below 768px.

The grid is one column below 768px, two columns from 768px, three columns from 1120px.

### 6.2 The filter row

- A search field takes the remaining width. It is `--mt-surface` with a 2px `--mt-border` and
  radius `--mt-r-row`. On focus the border becomes `--mt-teal` and a 3px `--mt-teal-pale` ring
  appears. The field keeps `id="topic-filter"`, which the unit test uses.
- A category pill list follows. The design draws `All`, `Physics`, `Biology`, `Money`.

**The pills are real, not decoration.** `TopicSummary.category` already arrives with every topic.
The component derives the distinct categories from the loaded list, sorts them, and puts `All`
first. A selected pill is `--mt-pill--teal`. The category filter and the text query combine with
AND.

The pill list scrolls sideways below 768px. It does not wrap, because a wrapped pill list pushes
the first tile below the fold.

### 6.3 A topic tile

`.mt-card` with 20px padding and radius `--mt-r-panel`. It holds three things:

1. The category, as `.mt-eyebrow`, in `--mt-muted`. The design gives the first tile a coral
   eyebrow, and the code drops it. A coral retry pill is reachable in this same view, and §3.3
   allows one coral element. The eyebrow is decoration and the pill is the action, so the pill
   keeps the accent.
2. The title, Fredoka 23px/600.
3. The summary, Figtree 14px/500, `--mt-muted`.

The tile stays a `<button>` with `data-slug`, which four unit tests use.

- Hover: the lift grows to `0 6px 0` and the tile moves 2px up.
- Focus: a 3px `--mt-teal` outline with a 2px offset. Never `outline: none`.
- Disabled: opacity 0.55, no lift, `cursor: not-allowed`. The `title` explains why. Both already
  work, and both keep their behaviour.
- Pending: the `Starting…` label becomes a `.mt-chip--teal` in the corner of the tile.

### 6.4 Catalog states

| State | Design |
|---|---|
| Load | Six skeleton tiles. Each is `.mt-card` with three bars in `--mt-skeleton`. The bars pulse. `prefers-reduced-motion` stops the pulse and keeps the bars. |
| Load failed | `.mt-card--error` full width, with a `.mt-pill--coral` retry. |
| Session failed | The same card. It keeps `.banner--error` and `.banner__retry-button`, which the e2e suite uses. |
| No match | A `.mt-card--dashed`: "Nothing under that name yet." and the count of the catalogue. No nearest-match card, because no match API exists. |
| Empty catalogue | The same card with different words. |

---

## 7. The reader page

### 7.1 Layout

The two-column grid of §4. The trail rail is on the left. The breadcrumb, any banner, and the
focus card are on the right.

The design at `4a` draws no breadcrumb, because the trail rail carries the same information there.
The app keeps the breadcrumb: it is built, it is tested, and it is the only trail affordance below
768px until the drawer is opened. It becomes a row of `.mt-chip` items, and the current crumb is
`.mt-chip--teal` and stays disabled.

### 7.2 The trail rail

The design draws a nested pill list. Each row indents by 16px per level of depth. The rail keeps
`.trail__item` and `data-node-id`, which the e2e suite uses.

A row is `.mt-card--flat` with radius `--mt-r-row`, 11px by 13px padding. It holds:

- A 22px `--mt-amber` circle on the left.
- The verb name, as `.mt-eyebrow` in `--mt-muted`.
- The span, Fredoka 15px/600.

The current row inverts: `--mt-teal` background, `--mt-lift-teal`, white span text, and the
eyebrow in `--mt-teal-pale` reads `<Verb> · here`. `aria-current` stays as it is.

Below 768px the rail is a drawer. The toggle becomes `.mt-pill--ghost` and states the count.

### 7.3 The focus card

`.mt-card` with radius `--mt-r-card`, `--mt-lift-card`, and 32px by 36px padding. It holds, in
order:

1. An eyebrow row: `Step N · <Verb>` in `--mt-coral-text`, then a progress track.
2. The topic title, Fredoka 26px/600.
3. The body prose.
4. The stream element, when a stream runs.
5. The hint.

**`Step N`** is the depth of the current node plus one. The value already exists in `NodeView`.

**The progress track.** The design draws a filled amber bar. The percentage in the design is
arbitrary, and no bounded quantity exists to bind it to. So the track appears **only while a
stream runs**, as an indeterminate amber band that travels along a `--mt-rule` track. A finished
card shows no track. This is honest: an invented percentage is a promise the app cannot keep.
`prefers-reduced-motion` replaces the travel with a static half-width band.

### 7.4 The body, and one invariant this design must not break

`focus-card.component.ts` holds a load-bearing rule, and it is documented and tested there:
**`.focus__body` may contain nothing but the body's own characters.** `selectionToSpan` returns
offsets into `root.textContent`, and the server checks `storedBody.substring(start, end) == text`.
A label, an icon or a highlight wrapper inside that element shifts every offset. Every explain
then fails with `SPAN_MISMATCH`.

The design draws a highlighted phrase with an amber background:
`<span style="background:#ffd166">…</span>` inside the prose. **A DOM wrapper inside
`.focus__body` breaks the invariant.**

The design's look is reached without a DOM change:

```css
.focus__body::selection { background: var(--mt-amber); color: var(--mt-amber-ink); }
```

The native selection highlight takes the design's amber. No node is added, no offset moves, and
the invariant holds. `rootTextMatchesBody` continues to guard it.

The prose is Figtree 19px/500, `--mt-prose`, line height 1.65, `max-width: 62ch`,
`text-wrap: pretty`, and `white-space: pre-wrap` as it is today.

### 7.5 The stream element

It keeps `.focus__streaming` and `.focus__caret`, which six e2e assertions use. It stays outside
the selectable root. It keeps `user-select: none`.

The design gives it: the same prose type, `--mt-sunk` background, radius `--mt-r-panel`, 16px
padding, and a 3px `--mt-coral` left border. The caret is `--mt-coral` and blinks at 1s.
`prefers-reduced-motion` holds it steady.

### 7.6 The verb picker

`ui/verb-picker.component.ts` — new. It replaces the static row of four buttons.

#### Contract

| Member | Type | Meaning |
|---|---|---|
| `span` (input) | `SpanPayload \| null` | The phrase. `null` closes the picker. |
| `anchor` (input) | `{ top: number; left: number } \| null` | The position, in the host card's coordinates. |
| `disabled` (input) | `boolean` | True while a stream runs, or when the body does not match. |
| `chosen` (output) | `Verb` | The learner picked a verb. |
| `dismissed` (output) | `PickerDismissal` | `'escape'` or `'outside-press'`. |

The reason is on the output, and there is no second output for the keyboard path. One output keeps
every dismissal in one place, so a host cannot subscribe to one path and forget the other. The host
acts on the reason: see the focus rule below.

#### The four verbs

The design's words, with the design's captions.

| `Verb` | Name | Caption |
|---|---|---|
| `EXPLAIN` | Explain it | Plain words, no jargon |
| `DIG_DEEPER` | Dig deeper | One level more technical |
| `BROADER_PICTURE` | Broader picture | Where this sits in the whole |
| `SIDE_VIEW` | Side view | The same idea from elsewhere |

`SEED` and `VISUALIZE` stay out, for the reasons the current component already records.

`EXPLAIN` is the primary. Its fill is `--mt-coral-press` and not `--mt-coral`, because its name is
Fredoka 16px and §3.4 rule 3 applies. The name and the caption are both white: white on
`--mt-coral-press` measures 5.42:1, and white on `--mt-coral` measures 3.01:1 and fails. The two
lines stay apart by face and size, and not by opacity. The button keeps `--mt-lift-coral`.

The other three verbs are `--mt-surface` with a 2px `--mt-border`. Their name is `--mt-ink` and
their caption is `--mt-muted`.

The design's fifth row, "Show me a diagram", is not rendered: `VISUALIZE` is slice 4.

Each button keeps `data-verb`, which the unit test uses.

#### Accessible names

A caption inside a button joins the accessible name. `Explain it Plain words, no jargon` is a poor
name, and it collides with the trail rail's own `Explain` rows under `getByRole`. So:

- The button carries `aria-label` with the name alone, for example `Explain it`.
- The caption carries an `id`, and the button points at it with `aria-describedby`.

The name is then short and the caption still reaches a screen reader.

#### Position and behaviour

- The picker opens on `mouseup` or `touchend` when a span exists. This is the same handler that
  reads the selection today, so no new code runs on the render path and the reader stays
  server-renderable.
- The anchor comes from `range.getBoundingClientRect()` minus the card's own rect. Both reads
  happen inside the event handler.
- Wide screen: `position: absolute` inside a `position: relative` wrapper on the card, 8px below
  the phrase, with the design's 14px arrow. The left edge clamps to the card, so the picker never
  overflows. If less than 240px remains below the phrase, the picker flips above it.
- Narrow screen: `position: fixed`, full width, at the bottom edge, radius 26px on the top corners
  only. It rises 200ms. `prefers-reduced-motion` shows it at once.
- It closes on: a verb chosen, `Escape`, a click outside, a change of `body()`, and the start of a
  stream.
- `role="dialog"` and `aria-label="Explain the highlighted phrase"`. Focus moves to the first
  verb when it opens. `Tab` cycles inside it. `Escape` closes it and returns focus to the body
  paragraph.

**The Tab trap needs two bindings.** Angular builds a full key name from the modifiers a reader
holds, so `keydown.tab` never fires while Shift is down. The picker binds `keydown.tab` and
`keydown.shift.tab` to the same handler. With one binding the backward wrap is dead code.

**Only Escape returns focus.** The paragraph carries `tabindex="-1"`, so it can hold focus at all.
An attribute contributes no character to `textContent`, so §7.4's invariant is untouched. The
outside-press path returns nothing: it runs inside a `mousedown` listener, and a focus move there
cancels the drag a learner starts when they reselect a phrase.

**The paragraph draws no focus ring.** Measured in a real Chromium: Escape is a key press, so the
return does match `:focus-visible`, and a 3px teal ring would draw around 62ch of prose. One rule,
`.focus__body:focus-visible { outline: none }`, suppresses it for that element alone. The paragraph
is not a control, and `tabindex="-1"` keeps it out of the Tab order, so no reader can arrive there
by tabbing and then be lost. Every control keeps its ring, and an end-to-end test asserts that.

#### The hint

`.focus__hint` stays. It is the resting instruction: "Highlight a phrase, then choose how to go
deeper." When the body does not match the stored explanation it keeps its warning words and takes
`.mt-card--error`. It no longer needs to state the selection, because the picker states it.

### 7.7 Reader states

| State | Design |
|---|---|
| Session load | The rail and the card are framed and empty. The card shows five skeleton bars in `--mt-skeleton`, with the eyebrow "Writing your first explanation" and the indeterminate track. This is the design at `4a`. The rail placeholder mirrors the loaded rail state for state: an eyebrow at 768px and above, a `.mt-pill--ghost` below it. Below 768px the rail stacks over the card, so a placeholder of a different height moves the card down when the text lands. The height comes from `.mt-pill` and never from a `min-height`. |
| Load failed, nothing behind | A full-page `.mt-card--error`, centred, with a retry pill and a "Back to topics" link. |
| Explain failed, reader behind | A `.mt-card--error` above the breadcrumb. It keeps `.banner--error`, `.banner__message`, `.banner__detail`, `.banner__retry-button` and `.banner__dismiss`. The retry is `.mt-pill--teal` and not coral: the reader stays on screen behind this banner, so the picker's coral "Explain it" is still reachable, and §3.3 allows one coral control per view. The picker is a dialog that traps Tab, so while it is open it is the whole view and its primary must be unmistakable. The retry is then the secondary action, which is what teal names. The full-page failure below keeps its coral retry, because no picker exists there. |
| Quota reached | The same error card. The design's "Quiz me instead" action is not rendered, because no quiz exists. The wait, which the server supplies, is stated as it is today. |

---

## 8. Files

| Path | Change |
|---|---|
| `frontend/public/fonts/*.woff2` | New. Four files. |
| `frontend/public/fonts/OFL.txt` | New. The licence. |
| `frontend/src/styles.css` | Empty today. It gains the fonts, the properties, the reset, the four classes. |
| `frontend/src/app/ui/app-shell.component.ts` | New. |
| `frontend/src/app/ui/status-dot.component.ts` | New. |
| `frontend/src/app/ui/verb-picker.component.ts` | New. |
| `frontend/src/app/app.ts` | The shell replaces the scaffold. |
| `frontend/src/app/catalog/catalog-page.component.ts` | Styles, the category pills, the states. |
| `frontend/src/app/reader/reader-page.component.ts` | The grid, the styles, the states. |
| `frontend/src/app/reader/focus-card.component.ts` | It hosts the picker. It keeps the invariant. |
| `frontend/src/app/reader/trail-rail.component.ts` | Nested pills. The breakpoint moves to 768px. |
| `frontend/src/app/reader/breadcrumb.component.ts` | Chips. |
| `frontend/e2e/layout.spec.ts` | New. Every claim that needs a real layout engine. §9.5. |
| `frontend/angular.json` | Confirm `styles.css` and `public/` are in the build. Both already are. |

No file in `backend/` changes. No route changes. No model changes.

---

## 9. Test impact

The suites today: 325 backend, 103 frontend unit, 6 end-to-end.

### 9.1 What must keep working, unchanged

These selectors are load-bearing. The re-skin must not rename them.

- e2e: `.focus__streaming`, `.focus__caret`, `.crumb`, `.trail__item`, `.banner--error`,
  `.banner__retry-button`.
- unit: `.focus__body`, `button[data-verb="…"]`, `button[data-slug="…"]`, `#topic-filter`,
  `data-testid="focus-body"`.

### 9.2 What must change, and why

1. **`learn.spec.ts` line 55.** It asserts
   `expect(page.getByRole('button', { name: 'Explain' })).toBeDisabled()` while a stream runs. A
   popover has no disabled state; it is closed. The assertion becomes "the picker is not on
   screen", against the picker's own root.
2. **Every `getByRole('button', { name: 'Explain' })` scopes to the picker.** The trail rail
   renders rows whose text also holds `Explain`. Playwright matches an accessible name as a
   case-insensitive substring by default, so an unscoped query becomes ambiguous the moment a
   trail holds an `EXPLAIN` node. The `aria-label` of §7.6 keeps the name short; the scope removes
   the ambiguity.
3. **A click on a verb needs the picker open.** `selectPhrase` drags a real selection and fires a
   real `mouseup`, so the picker opens without a change to the helper. This was read, not assumed.
4. **`focus-card.component.spec.ts`** keeps its selection helper. Its verb queries move to the
   picker's root.

### 9.3 New tests

| Subject | Test |
|---|---|
| `app.ts` | The outlet renders. The dot shows each of the three health results. |
| `status-dot` | Each result gives the right colour and the right `aria-label`. |
| `verb-picker` | It opens on a span. It closes on Escape, on an outside click, on a chosen verb, and on a body change. It emits the right `Verb`. Focus moves in and returns. |
| `catalog` | The category pills come from the loaded topics. A pill and the query combine with AND. |
| `focus-card` | The invariant test of §7.4 gains a case: the picker's DOM never enters `.focus__body`. |
| Contrast | A unit test asserts the ratio of each text-on-surface pair in §3.4 against its background. It also asserts §3.3's real discriminator: the coral fill and the error ink are the same colour, and the two surfaces plus both text pairs are what tell them apart. |
| Layout | `e2e/layout.spec.ts` asserts every claim that needs a real layout engine. See §9.5. |

### 9.5 The layout suite

The unit suite runs in jsdom, which has no layout engine and no media queries. No unit test there
can see a position, a breakpoint or a resolved font. `frontend/e2e/layout.spec.ts` holds the claims
that need a real browser, as assertions and never as an image comparison:

- The tile grid's column count at 390px, 768px and 1360px.
- The category pill row scrolls sideways at 390px and does not wrap.
- The wordmark's computed size, weight and family.
- The trail rail is a column at 768px and a drawer at 390px.
- The reader does not move when the loaded session replaces the skeleton.
- The picker opens below a phrase near the top and above one near the bottom.
- The picker's four edges stay inside the card.
- The picker is `fixed` at 390px and `absolute` at 1360px.
- Escape returns focus to the paragraph, and draws no ring there.
- Every control still draws its 3px teal ring.
- `Tab` and `Shift+Tab` cycle inside the picker.
- Every font comes from this origin, and no request reaches a Google Fonts host.

A screenshot baseline is deliberately absent. It breaks on a font rebuild, on a driver update and
on the machine it runs on, and it reports only "something moved". An assertion names the number.

### 9.4 What no test can catch

A re-skin is a visual change, and the suite reads the DOM. The plan must include one manual pass
at three widths — 390px, 768px and 1360px — over: the catalog, a tile pending, a load failure, an
empty search, the reader, the picker on both modes, a stream in progress, and an error banner.

---

## 10. Risks

| Risk | Effect | Control |
|---|---|---|
| The picker's DOM lands inside `.focus__body` | Every explain fails with `SPAN_MISMATCH` | The picker is a sibling of the paragraph. `rootTextMatchesBody` runs after every render. A test asserts it. §7.4. |
| A `getByRole` query becomes ambiguous | The e2e suite fails, or passes against the wrong button | Short `aria-label` values, and a scope to the picker root. §9.2. |
| The design's own contrast failures ship | A reader with low vision cannot read a label | Three corrections, and a contrast test. §3.4. |
| A font swap moves the text | The reader loses their place mid-read | `font-display: swap`, and the metric-adjacent fallback `system-ui` on both stacks. |
| The reader stops being server-renderable | Spec C loses its ground | The picker reads the DOM only inside an event handler. No `matchMedia` and no `window` read on the render path. Both are stated in §7.6 and were the existing rule. |
| A hard-coded value creeps back in | The token layer stops being the source | Review checks each component for a raw hex value. |

---

## 11. Definition of done

1. `npm test -- --watch=false` passes, with the new tests of §9.3.
2. `npx playwright test` passes, with the changes of §9.2.
3. `npm run build` passes. No component holds a raw hex value that a token already names.
4. `./gradlew build` still passes. No backend file changed.
5. The manual pass of §9.4 is complete at all three widths.
6. Every contrast pair of §3.4 measures at or above its AA threshold.
7. `frontend/public/fonts/OFL.txt` exists.

### 11.1 Verification record — Task 8

Date: 2026-08-07. Browser: Chromium 151.0.7922.34, run through Playwright 1.62.1.

Counts: MEASURED 19 / SCREENSHOT ONLY 0 / NOT REACHABLE 0. A real Chromium checked every row of
§9.4's manual pass, at each width the row applies to, with a real assertion — not only a look.

Two rows needed a fix. **Both are fixed**, and each now has a permanent assertion in
`e2e/layout.spec.ts`:

- Row 9, reader session load: at 390px the page moved down 19px when the loaded session replaced
  the skeleton. The loaded trail rail was taller than its own skeleton placeholder. The
  placeholder now mirrors the loaded rail state for state. See §7.7.
- Row 14, Escape: focus returned to `<body>`, not to the body paragraph. `.focus__body` now
  carries `tabindex="-1"`, and only the Escape path restores focus. See §7.6.

One expectation in Task 8's own brief was stale, and is not a product defect: Step 6 expects four
font requests, but the design's own unicode-range choice correctly loads only two fonts for
English text.

Full detail: `.superpowers/sdd/2026-08-06-candy-design-application/task-8-report.md`, and the fix
wave in `final-fix-report.md` beside it.

---

## 12. What comes next

The token layer and the shell exist so that each deferred screen is a page and not a rewrite.

| Order | Work | It needs |
|---|---|---|
| 1 | Sessions list | `GET /api/sessions` for the principal |
| 2 | Glossary | A saved-terms collection, and its routes |
| 3 | Quota meter and streak | A route that reports the balance |
| 4 | Quick check | Parent spec §8, slice 3 |
| 5 | Visualize | Parent spec §9, slice 4. The picker gains its fifth row. |
