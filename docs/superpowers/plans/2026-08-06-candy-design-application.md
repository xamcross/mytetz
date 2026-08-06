# Candy Design Application Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the catalog page and the reader page the Candy look from `docs/mytetz-design-reference.html`, on a token layer that a later screen reuses.

**Architecture:** One global stylesheet holds the fonts, the custom properties and four appearance classes. Three new components hold the behaviour that a class cannot: the top bar, the health dot, and the verb picker. Every page component keeps its own layout only, and reads every colour from a token. No backend file changes.

**Tech Stack:** Angular 21.2 standalone components with signals. Vitest 4 with jsdom for unit tests. Playwright 1.62 for end-to-end tests. Plain CSS with custom properties. No new dependency.

**Spec:** `docs/superpowers/specs/2026-08-06-candy-design-application-design.md`

## Global Constraints

Every task's requirements include this section.

1. **Language.** Write every comment and every document in ASD-STE100 Simplified Technical English. Short sentences. Active voice. Present tense. Always an article. No `-ing` form as a noun. A code identifier keeps its own name. A commit subject line keeps the conventional-commit format.
2. **No backend change.** No file under `backend/` changes. No route changes. `frontend/src/app/core/models.ts` does not change.
3. **SSR-safe.** No component reads `window`, `document`, `matchMedia` or `localStorage` on the render path. A read inside an event handler, a host listener, `afterNextRender` or `afterRenderEffect` is allowed. This keeps spec C's server rendering possible.
4. **Unit tests run in jsdom.** jsdom has no layout engine. `getBoundingClientRect()` returns zeros there. No unit test may assert a pixel position. Position is proved by the manual pass of Task 8.
5. **Load-bearing selectors. Do not rename any of these.**
   - End-to-end: `.focus__streaming`, `.focus__caret`, `.crumb`, `.trail__item`, `.banner--error`, `.banner__retry-button`.
   - Unit: `.focus__body`, `data-testid="focus-body"`, `button[data-verb="…"]`, `button[data-slug="…"]`, `#topic-filter`.
6. **The `.focus__body` invariant.** `.focus__body` holds nothing but the body's own characters. `selectionToSpan` returns offsets into `root.textContent`, and the server checks `storedBody.substring(start, end) == text`. A label, an icon or a highlight wrapper inside that element shifts every offset, and every explain then fails with `SPAN_MISMATCH`.
7. **The colour rule.** Coral is the primary action, and one view has one coral control. Teal is the current position. Amber means "look at this" and never "click this". The red-pink family is a problem and is never coral.
8. **No raw hex in a component.** A component uses a `var(--mt-…)` token. `styles.css` is the only file that states a hex value.
9. **Component style budget.** `angular.json` errors at 8 kB per component stylesheet and warns at 4 kB. Shared appearance belongs in `styles.css`.
10. **Prettier.** CI runs `npm run format:check`. Run `npm run format` before every commit.
11. **Every command in this plan runs from `frontend/`.**

## Baseline, measured on 2026-08-06

| Suite | Command | Result |
|---|---|---|
| Frontend unit | `npm test -- --watch=false` | 103 passed, 8 files |
| One unit file | `npm test -- --watch=false --include <path>` | works |
| End-to-end | `npx playwright test` | 6 passed |
| Backend | `./gradlew build` from the repository root | 325 passed |

`ng test` takes no positional filter. `--include` is the file filter and `--filter` is the test-name filter.

## File structure

| Path | Responsibility |
|---|---|
| `public/fonts/*.woff2` | The four font subsets. |
| `public/fonts/OFL.txt` | The licence the OFL requires. |
| `src/styles.css` | The fonts, the tokens, the reset, the four appearance classes. The only file with a hex value. |
| `src/app/ui/palette.spec.ts` | The contrast proof. Never bundled into the app. |
| `src/app/ui/app-shell.component.ts` | The 64px bar. It projects the page. |
| `src/app/ui/status-dot.component.ts` | The health signal, in colour and in words. |
| `src/app/ui/verb-picker.component.ts` | The popover and the sheet. Position-agnostic. |
| `src/app/app.ts` | The shell around the router outlet. |
| `src/app/catalog/catalog-page.component.ts` | The catalog layout, the category filter, the catalog states. |
| `src/app/reader/reader-page.component.ts` | The reader grid and the reader states. |
| `src/app/reader/focus-card.component.ts` | The card, the selection, and the host of the picker. |
| `src/app/reader/trail-rail.component.ts` | The nested trail. |
| `src/app/reader/breadcrumb.component.ts` | The crumb chips. |

---

## Task 1: The token layer

**Files:**
- Create: `frontend/public/fonts/figtree-latin.woff2`, `figtree-latin-ext.woff2`, `fredoka-latin.woff2`, `fredoka-latin-ext.woff2`
- Create: `frontend/public/fonts/OFL.txt`
- Create: `frontend/tools/extract-fonts.mjs`
- Modify: `frontend/src/styles.css` (empty today, 2 lines)
- Test: `frontend/src/app/ui/palette.spec.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: the custom properties of Step 5, and the classes `.mt-card`, `.mt-card--amber`, `.mt-card--error`, `.mt-card--flat`, `.mt-card--dashed`, `.mt-pill`, `.mt-pill--coral`, `.mt-pill--teal`, `.mt-pill--ghost`, `.mt-pill--amber`, `.mt-chip`, `.mt-chip--teal`, `.mt-chip--amber`, `.mt-chip--error`, `.mt-eyebrow`, `.mt-eyebrow--coral`, `.mt-eyebrow--amber`, `.mt-skeleton`. Every later task uses them.

- [ ] **Step 1: Write the failing contrast test**

Create `frontend/src/app/ui/palette.spec.ts`. It states every colour pair that carries text, and proves each one reaches its WCAG AA threshold. The palette lives here and not in `src/`, because the app needs no runtime copy of it.

```ts
/**
 * The contrast proof for the Candy palette.
 *
 * The design file fails WCAG AA in three places. The spec records the three corrections, and this
 * file is where they stop being a claim. Every pair below carries text. Each one must reach its
 * threshold: 4.5:1 for normal text, and 3:1 for large text. Large text means 24px, or 18.66px at
 * weight 600 or more.
 *
 * `styles.css` holds the same values. Task 1 Step 6 checks that the two agree.
 */

const PALETTE = {
  page: '#effaf6',
  surface: '#fff',
  sunk: '#f4fbf8',
  border: '#cfe9e0',
  rule: '#d8efe8',
  chip: '#e4f2ed',
  skeleton: '#dcefe9',
  skeleton2: '#e6f4ef',
  ink: '#12312a',
  prose: '#1b3d36',
  muted: '#4c6b64',
  faint: '#7ba49b',
  coral: '#ff5d5d',
  coralDeep: '#d63f3f',
  coralPale: '#ffe0e0',
  coralText: '#cc3b3b',
  coralPress: '#c23636',
  teal: '#0f766e',
  tealDeep: '#0a544e',
  tealPale: '#a8d5cd',
  amber: '#ffd166',
  amberDeep: '#f0c256',
  amberBg: '#fff8e6',
  amberInk: '#6b4c00',
  amberInk2: '#8a6b23',
  errBg: '#fff1ef',
  errBorder: '#ffc4bf',
  errInk: '#b83232',
  errInk2: '#8a4b45',
} as const;

/** WCAG 2.2 relative luminance. Each channel is normalised, then linearised, then weighted. */
function luminance(hex: string): number {
  const h = hex.length === 4 ? `#${hex[1]}${hex[1]}${hex[2]}${hex[2]}${hex[3]}${hex[3]}` : hex;
  const channel = (offset: number): number => {
    const v = parseInt(h.slice(offset, offset + 2), 16) / 255;
    return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}

/** WCAG 2.2 contrast ratio. The result runs from 1 to 21. */
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const AA_NORMAL = 4.5;
const AA_LARGE = 3;

describe('the Candy palette', () => {
  it('computes a contrast ratio the way WCAG 2.2 defines it', () => {
    // Two anchors with an answer that does not depend on this palette. Without them a broken
    // formula would agree with itself and every assertion below would pass for the wrong reason.
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 2);
    expect(contrast('#777777', '#ffffff')).toBeCloseTo(4.48, 2);
  });

  const normal: ReadonlyArray<[string, string, string]> = [
    ['prose on the card', PALETTE.prose, PALETTE.surface],
    ['ink on the card', PALETTE.ink, PALETTE.surface],
    ['muted on the page', PALETTE.muted, PALETTE.page],
    ['muted on the card', PALETTE.muted, PALETTE.surface],
    ['muted on the sunk surface', PALETTE.muted, PALETTE.sunk],
    ['muted on a chip', PALETTE.muted, PALETTE.chip],
    ['small coral text on the card', PALETTE.coralText, PALETTE.surface],
    ['small coral text on the page', PALETTE.coralText, PALETTE.page],
    ['teal on the card', PALETTE.teal, PALETTE.surface],
    ['teal on the page', PALETTE.teal, PALETTE.page],
    ['white on a small coral control', PALETTE.surface, PALETTE.coralPress],
    ['white on a teal control', PALETTE.surface, PALETTE.teal],
    ['amber ink on the amber surface', PALETTE.amberInk, PALETTE.amberBg],
    ['amber ink 2 on the amber surface', PALETTE.amberInk2, PALETTE.amberBg],
    ['amber ink on the amber fill', PALETTE.amberInk, PALETTE.amber],
    ['error ink on the error surface', PALETTE.errInk, PALETTE.errBg],
    ['error ink 2 on the error surface', PALETTE.errInk2, PALETTE.errBg],
  ];

  for (const [name, fg, bg] of normal) {
    it(`reaches AA for normal text: ${name}`, () => {
      expect(contrast(fg, bg)).toBeGreaterThanOrEqual(AA_NORMAL);
    });
  }

  const large: ReadonlyArray<[string, string, string]> = [
    ['the 24px coral wordmark on the bar', PALETTE.coral, PALETTE.surface],
    ['white on the teal trail row', PALETTE.surface, PALETTE.teal],
    ['the teal-pale eyebrow on the teal trail row', PALETTE.tealPale, PALETTE.teal],
  ];

  for (const [name, fg, bg] of large) {
    it(`reaches AA for large text: ${name}`, () => {
      expect(contrast(fg, bg)).toBeGreaterThanOrEqual(AA_LARGE);
    });
  }

  it('records why the design file needed three corrections', () => {
    // These three are what the design file draws. Each one fails. If a later change makes one of
    // them pass, the correction it forced is no longer needed and the spec must be revisited.
    expect(contrast(PALETTE.faint, PALETTE.page)).toBeLessThan(AA_NORMAL);
    expect(contrast(PALETTE.coral, PALETTE.surface)).toBeLessThan(AA_NORMAL);
    expect(contrast(PALETTE.surface, PALETTE.coral)).toBeLessThan(AA_NORMAL);
  });
});
```

- [ ] **Step 2: Run the test to see it fail**

Run: `npm test -- --watch=false --include src/app/ui/palette.spec.ts`

Expected: the file is found and some assertions fail, because `--mt-faint` and the coral pairs are the values this step is about to prove. If every assertion passes at once, read the output — the anchor test of `contrast` must pass, and the three "records why" assertions must pass. Only the pairs in `normal` and `large` are at risk. Fix any that fail by correcting the palette entry, not by lowering a threshold.

- [ ] **Step 3: Write the font extraction tool**

Create `frontend/tools/extract-fonts.mjs`. The design file is a bundled canvas: its manifest maps a UUID to a gzip-compressed, base64-encoded payload. The four UUIDs below come from the `@font-face` rules inside the bundle's own template.

```js
// Pulls the four Latin font subsets out of the design bundle.
// Run once: node tools/extract-fonts.mjs
// The output files are committed. This tool exists so that the extraction is reproducible.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';

const BUNDLE = '../docs/mytetz-design-reference.html';
const OUT = 'public/fonts';

/** Each UUID names one payload in the bundle manifest. Each name is the file this tool writes. */
const WANTED = {
  '6dac9c78-6a37-4a2e-8601-1ef1def4fe3c': 'figtree-latin.woff2',
  '93c25b2b-8352-4fdb-abf3-efd57f357d58': 'figtree-latin-ext.woff2',
  'f55d5df1-39ca-4bf1-9254-7533812bc4d2': 'fredoka-latin.woff2',
  '7e6aa8e7-c790-4be7-a86f-921665c978f8': 'fredoka-latin-ext.woff2',
};

const html = readFileSync(BUNDLE, 'utf8');
const match = html.match(/<script type="__bundler\/manifest">\s*([\s\S]*?)\s*<\/script>/);
if (!match) throw new Error('the bundle holds no manifest');
const manifest = JSON.parse(match[1]);

mkdirSync(OUT, { recursive: true });
for (const [uuid, name] of Object.entries(WANTED)) {
  const entry = manifest[uuid];
  if (!entry) throw new Error(`the manifest holds no entry ${uuid}`);
  if (!entry.mime.startsWith('font/')) throw new Error(`${uuid} is ${entry.mime}, not a font`);
  let bytes = Buffer.from(entry.data, 'base64');
  if (entry.compressed) bytes = gunzipSync(bytes);
  // Every woff2 file starts with the signature "wOF2". A payload that does not is the wrong one.
  if (bytes.subarray(0, 4).toString('latin1') !== 'wOF2') {
    throw new Error(`${uuid} is not a woff2 file`);
  }
  writeFileSync(`${OUT}/${name}`, bytes);
  console.log(`${name} ${bytes.length} bytes`);
}
```

- [ ] **Step 4: Run the tool and check the four files**

Run: `node tools/extract-fonts.mjs`

Expected, exactly:

```
figtree-latin.woff2 20156 bytes
figtree-latin-ext.woff2 10280 bytes
fredoka-latin.woff2 29732 bytes
fredoka-latin-ext.woff2 4576 bytes
```

A different byte count means the bundle changed. Stop and report it. Do not continue with a font whose identity is not proved.

Then get the licence. Both families use the SIL Open Font License 1.1, and the licence requires the text to travel with the font.

Run:

```bash
curl -sL https://raw.githubusercontent.com/erikdkennedy/figtree/master/OFL.txt -o public/fonts/OFL-Figtree.txt
curl -sL https://raw.githubusercontent.com/hafontia-zz/Fredoka/master/OFL.txt -o public/fonts/OFL-Fredoka.txt
cat public/fonts/OFL-Figtree.txt public/fonts/OFL-Fredoka.txt > public/fonts/OFL.txt
rm public/fonts/OFL-Figtree.txt public/fonts/OFL-Fredoka.txt
grep -c "SIL OPEN FONT LICENSE Version 1.1" public/fonts/OFL.txt
```

Expected: the count is `2`. If either download fails, take the licence text from the font's own page on `fonts.google.com` and keep both copyright lines. The file must name Figtree and Fredoka, and must hold the licence body.

- [ ] **Step 5: Write `styles.css`**

Replace the whole of `frontend/src/styles.css`.

```css
/* The Candy design system.
   This is the only file in the project that states a colour. A component uses a token.
   Source: docs/mytetz-design-reference.html.
   Spec: docs/superpowers/specs/2026-08-06-candy-design-application-design.md. */

/* ── Fonts ─────────────────────────────────────────────────────────────────── */
/* Self-hosted, so no request leaves the origin and no third party sees a reader.
   The unicode-range values come from the design file, so a Latin reader takes 50 kB
   of the 64 kB and not all of it. */

@font-face {
  font-family: 'Figtree';
  font-style: normal;
  font-weight: 300 900;
  font-display: swap;
  src: url('/fonts/figtree-latin.woff2') format('woff2');
  unicode-range:
    U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308,
    U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD;
}
@font-face {
  font-family: 'Figtree';
  font-style: normal;
  font-weight: 300 900;
  font-display: swap;
  src: url('/fonts/figtree-latin-ext.woff2') format('woff2');
  unicode-range:
    U+0100-02BA, U+02BD-02C5, U+02C7-02CC, U+02CE-02D7, U+02DD-02FF, U+0304, U+0308, U+0329,
    U+1D00-1DBF, U+1E00-1E9F, U+1EF2-1EFF, U+2020, U+20A0-20AB, U+20AD-20C0, U+2113, U+2C60-2C7F,
    U+A720-A7FF;
}
@font-face {
  font-family: 'Fredoka';
  font-style: normal;
  font-weight: 300 600;
  font-stretch: 100%;
  font-display: swap;
  src: url('/fonts/fredoka-latin.woff2') format('woff2');
  unicode-range:
    U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308,
    U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD;
}
@font-face {
  font-family: 'Fredoka';
  font-style: normal;
  font-weight: 300 600;
  font-stretch: 100%;
  font-display: swap;
  src: url('/fonts/fredoka-latin-ext.woff2') format('woff2');
  unicode-range:
    U+0100-02BA, U+02BD-02C5, U+02C7-02CC, U+02CE-02D7, U+02DD-02FF, U+0304, U+0308, U+0329,
    U+1D00-1DBF, U+1E00-1E9F, U+1EF2-1EFF, U+2020, U+20A0-20AB, U+20AD-20C0, U+2113, U+2C60-2C7F,
    U+A720-A7FF;
}

/* ── Tokens ────────────────────────────────────────────────────────────────── */
/* src/app/ui/palette.spec.ts holds the same values and proves the contrast of each pair.
   Change a value here and change it there. */

:root {
  /* surface */
  --mt-page: #effaf6;
  --mt-surface: #fff;
  --mt-sunk: #f4fbf8;
  --mt-border: #cfe9e0;
  --mt-rule: #d8efe8;
  --mt-chip: #e4f2ed;
  --mt-skeleton: #dcefe9;
  --mt-skeleton-2: #e6f4ef;

  /* ink */
  --mt-ink: #12312a;
  --mt-prose: #1b3d36;
  --mt-muted: #4c6b64;
  /* Decoration only. It fails AA as text. Text uses --mt-muted. */
  --mt-faint: #7ba49b;

  /* coral — the primary action */
  --mt-coral: #ff5d5d;
  --mt-coral-deep: #d63f3f;
  --mt-coral-pale: #ffe0e0;
  /* Coral text below 18.66px. */
  --mt-coral-text: #cc3b3b;
  /* A coral fill under text below 18.66px. */
  --mt-coral-press: #c23636;

  /* teal — the current position */
  --mt-teal: #0f766e;
  --mt-teal-deep: #0a544e;
  --mt-teal-pale: #a8d5cd;

  /* amber — attention, never action */
  --mt-amber: #ffd166;
  --mt-amber-deep: #f0c256;
  --mt-amber-bg: #fff8e6;
  --mt-amber-ink: #6b4c00;
  --mt-amber-ink-2: #8a6b23;

  /* error */
  --mt-err-bg: #fff1ef;
  --mt-err-border: #ffc4bf;
  --mt-err-ink: #b83232;
  --mt-err-ink-2: #8a4b45;

  /* form */
  --mt-r-card: 26px;
  --mt-r-panel: 22px;
  --mt-r-tile: 20px;
  --mt-r-row: 16px;
  --mt-border-w: 2px;
  --mt-lift: 0 4px 0 var(--mt-border);
  --mt-lift-card: 0 5px 0 var(--mt-border);
  --mt-lift-coral: 0 4px 0 var(--mt-coral-deep);
  --mt-lift-teal: 0 4px 0 var(--mt-teal-deep);
  --mt-float: 0 16px 40px rgb(18 49 42 / 16%);

  /* type */
  --mt-display: 'Fredoka', 'Figtree', system-ui, sans-serif;
  --mt-body: 'Figtree', system-ui, sans-serif;
}

/* ── Reset ─────────────────────────────────────────────────────────────────── */

*,
*::before,
*::after {
  box-sizing: border-box;
}

body {
  margin: 0;
  background: var(--mt-page);
  color: var(--mt-ink);
  font-family: var(--mt-body);
  font-weight: 500;
  -webkit-font-smoothing: antialiased;
}

h1,
h2,
h3 {
  margin: 0;
  font-family: var(--mt-display);
  font-weight: 600;
  line-height: 1.2;
}

a {
  color: var(--mt-teal);
  text-decoration-thickness: 1px;
  text-underline-offset: 3px;
}
a:hover {
  text-decoration-thickness: 2px;
}

/* A control that loses its focus ring is a control a keyboard cannot find. No rule sets
   outline: none. */
:focus-visible {
  outline: 3px solid var(--mt-teal);
  outline-offset: 2px;
}

button {
  font: inherit;
  cursor: pointer;
}
button:disabled {
  cursor: not-allowed;
}

/* ── Appearance classes ────────────────────────────────────────────────────── */
/* The 0 4px 0 offset lift is written here once. The design uses it on eleven elements. */

.mt-card {
  background: var(--mt-surface);
  border: var(--mt-border-w) solid var(--mt-border);
  border-radius: var(--mt-r-panel);
  box-shadow: var(--mt-lift);
}
.mt-card--amber {
  background: var(--mt-amber-bg);
  border-color: var(--mt-amber);
  box-shadow: 0 4px 0 var(--mt-amber-deep);
}
.mt-card--error {
  background: var(--mt-err-bg);
  border-color: var(--mt-err-border);
  box-shadow: none;
  color: var(--mt-err-ink-2);
}
.mt-card--flat {
  box-shadow: none;
}
.mt-card--dashed {
  border-style: dashed;
  box-shadow: none;
}

.mt-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  border: var(--mt-border-w) solid var(--mt-border);
  border-radius: 999px;
  background: var(--mt-surface);
  color: var(--mt-muted);
  font-family: var(--mt-body);
  font-size: 14px;
  font-weight: 800;
  line-height: 1;
  box-shadow: var(--mt-lift);
  transition:
    transform 80ms ease-out,
    box-shadow 80ms ease-out;
}
.mt-pill:active:not(:disabled) {
  transform: translateY(2px);
  box-shadow: 0 2px 0 var(--mt-border);
}
/* Coral fills with --mt-coral-press, not --mt-coral: a pill's label is 14px, and white on
   --mt-coral measures 3.01:1 and fails AA. */
.mt-pill--coral {
  background: var(--mt-coral-press);
  border-color: var(--mt-coral-press);
  color: var(--mt-surface);
  box-shadow: var(--mt-lift-coral);
}
.mt-pill--coral:active:not(:disabled) {
  box-shadow: 0 2px 0 var(--mt-coral-deep);
}
.mt-pill--teal {
  background: var(--mt-teal);
  border-color: var(--mt-teal);
  color: var(--mt-surface);
  box-shadow: var(--mt-lift-teal);
}
.mt-pill--teal:active:not(:disabled) {
  box-shadow: 0 2px 0 var(--mt-teal-deep);
}
.mt-pill--amber {
  background: var(--mt-amber-bg);
  border-color: var(--mt-amber);
  color: var(--mt-amber-ink);
  box-shadow: 0 4px 0 var(--mt-amber-deep);
}
.mt-pill--ghost {
  box-shadow: none;
  color: var(--mt-teal);
}
.mt-pill:disabled {
  opacity: 0.55;
  box-shadow: none;
}

.mt-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 12px;
  border: var(--mt-border-w) solid var(--mt-border);
  border-radius: 999px;
  background: var(--mt-surface);
  color: var(--mt-muted);
  font-size: 13px;
  font-weight: 800;
  line-height: 1;
}
.mt-chip--teal {
  background: var(--mt-teal);
  border-color: var(--mt-teal);
  color: var(--mt-surface);
}
.mt-chip--amber {
  background: var(--mt-amber-bg);
  border-color: var(--mt-amber);
  color: var(--mt-amber-ink);
}
.mt-chip--error {
  background: var(--mt-err-bg);
  border-color: var(--mt-err-border);
  color: var(--mt-err-ink);
}

.mt-eyebrow {
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--mt-muted);
}
/* An eyebrow is 11px, so it takes the text-safe coral and not the fill coral. */
.mt-eyebrow--coral {
  color: var(--mt-coral-text);
}
.mt-eyebrow--amber {
  color: var(--mt-amber-ink-2);
}

.mt-skeleton {
  border-radius: 6px;
  background: var(--mt-skeleton);
  animation: mt-pulse 1.4s ease-in-out infinite;
}

@keyframes mt-pulse {
  0%,
  100% {
    background: var(--mt-skeleton);
  }
  50% {
    background: var(--mt-skeleton-2);
  }
}

/* A reader who asks for less motion still needs every change of state. Motion goes; state
   stays. */
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

- [ ] **Step 6: Check the tests pass and the CSS agrees with the palette**

Run: `npm test -- --watch=false --include src/app/ui/palette.spec.ts`
Expected: PASS.

Then prove that `styles.css` states the same values the test proves. Run from `frontend/`:

```bash
for c in effaf6 f4fbf8 cfe9e0 d8efe8 e4f2ed dcefe9 e6f4ef 12312a 1b3d36 4c6b64 7ba49b ff5d5d d63f3f ffe0e0 cc3b3b c23636 0f766e 0a544e a8d5cd ffd166 f0c256 fff8e6 6b4c00 8a6b23 fff1ef ffc4bf b83232 8a4b45; do
  grep -q "#$c" src/styles.css || echo "MISSING #$c"
done
```

Expected: no output. Any `MISSING` line means the two files disagree.

- [ ] **Step 7: Check the build**

Run: `npm run format && npm run build`
Expected: the build completes. The four font files appear under `dist/frontend/browser/fonts/`.

Run: `ls dist/frontend/browser/fonts/`
Expected: the four `.woff2` files and `OFL.txt`.

- [ ] **Step 8: Commit**

```bash
git add frontend/public/fonts frontend/tools/extract-fonts.mjs frontend/src/styles.css frontend/src/app/ui/palette.spec.ts
git commit -m "feat(ui): add the Candy token layer and self-hosted fonts

styles.css becomes the only file that states a colour. It holds the four
font faces, the custom properties, the reset and four appearance classes.

palette.spec.ts proves the contrast of every pair that carries text. It
also records the three pairs the design file draws that fail AA, so a
later change cannot quietly drop a correction."
```

---

## Task 2: The shell

**Files:**
- Create: `frontend/src/app/ui/status-dot.component.ts`
- Create: `frontend/src/app/ui/status-dot.component.spec.ts`
- Create: `frontend/src/app/ui/app-shell.component.ts`
- Modify: `frontend/src/app/app.ts` (whole file, 36 lines)
- Modify: `frontend/src/app/app.spec.ts`

**Interfaces:**
- Consumes: the classes and tokens of Task 1.
- Produces:
  - `StatusDotComponent`, selector `app-status-dot`, one required input `state: BackendState`.
  - `export type BackendState = 'checking' | 'ok' | 'degraded' | 'unreachable';` from `status-dot.component.ts`.
  - `AppShellComponent`, selector `app-shell`, one required input `backend: BackendState`. It projects its content below the bar.

- [ ] **Step 1: Write the failing status-dot test**

Create `frontend/src/app/ui/status-dot.component.spec.ts`.

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BackendState, StatusDotComponent } from './status-dot.component';

describe('StatusDotComponent', () => {
  let fixture: ComponentFixture<StatusDotComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [StatusDotComponent] });
    fixture = TestBed.createComponent(StatusDotComponent);
  });

  const dot = (state: BackendState): HTMLElement => {
    fixture.componentRef.setInput('state', state);
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('.dot');
  };

  it('states the result in words as well as in colour', () => {
    // The colour alone is not the signal. A reader who cannot tell teal from red still needs the
    // answer, so the words carry it and the colour repeats it.
    expect(dot('ok').getAttribute('aria-label')).toBe('Backend ok');
    expect(dot('degraded').getAttribute('aria-label')).toBe('Backend degraded');
    expect(dot('unreachable').getAttribute('aria-label')).toBe('Backend unreachable');
    expect(dot('checking').getAttribute('aria-label')).toBe('Backend: checking');
  });

  it('gives each result its own modifier class', () => {
    expect(dot('ok').classList.contains('dot--ok')).toBe(true);
    expect(dot('degraded').classList.contains('dot--degraded')).toBe(true);
    expect(dot('unreachable').classList.contains('dot--unreachable')).toBe(true);
    expect(dot('checking').classList.contains('dot--checking')).toBe(true);
  });

  it('repeats the label in the title, so a pointer reaches it too', () => {
    expect(dot('degraded').getAttribute('title')).toBe('Backend degraded');
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `npm test -- --watch=false --include src/app/ui/status-dot.component.spec.ts`
Expected: FAIL. The module `./status-dot.component` does not resolve.

- [ ] **Step 3: Write the status dot**

Create `frontend/src/app/ui/status-dot.component.ts`.

```ts
import { Component, computed, input } from '@angular/core';

/** What the health check found. `checking` is the state before the first answer arrives. */
export type BackendState = 'checking' | 'ok' | 'degraded' | 'unreachable';

const LABELS: Readonly<Record<BackendState, string>> = {
  checking: 'Backend: checking',
  ok: 'Backend ok',
  degraded: 'Backend degraded',
  unreachable: 'Backend unreachable',
};

/**
 * The backend health, as a dot on the right of the top bar.
 *
 * The design draws a streak pill in this place. No streak exists, so the health check that the
 * root component already runs takes the space instead.
 *
 * The colour is not the whole signal. `aria-label` states the result in words, so a reader who
 * cannot tell the colours apart gets the same answer.
 */
@Component({
  selector: 'app-status-dot',
  imports: [],
  template: `
    <span
      class="dot"
      [class]="'dot dot--' + state()"
      role="img"
      [attr.aria-label]="label()"
      [attr.title]="label()"
    ></span>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
      }
      .dot {
        width: 10px;
        height: 10px;
        border-radius: 999px;
        border: 2px solid var(--mt-surface);
        outline: 2px solid var(--mt-border);
      }
      .dot--checking {
        background: var(--mt-faint);
      }
      .dot--ok {
        background: var(--mt-teal);
      }
      .dot--degraded {
        background: var(--mt-amber);
      }
      .dot--unreachable {
        background: var(--mt-err-ink);
      }
    `,
  ],
})
export class StatusDotComponent {
  readonly state = input.required<BackendState>();
  protected readonly label = computed(() => LABELS[this.state()]);
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `npm test -- --watch=false --include src/app/ui/status-dot.component.spec.ts`
Expected: PASS, 3 tests.

- [ ] **Step 5: Write the shell**

Create `frontend/src/app/ui/app-shell.component.ts`. It has no test of its own: it holds no logic, and Step 7's `app.spec.ts` proves that it renders inside the root.

```ts
import { Component, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { BackendState, StatusDotComponent } from './status-dot.component';

/**
 * The 64px top bar, and the frame every page sits in.
 *
 * The design draws three nav items: Topics, Sessions and Glossary. Only Topics has a route. The
 * other two need a backend that does not exist, and a link to a dead end is worse than no link.
 * The spec's §12 lists what each one needs.
 *
 * Below 768px the nav item goes. The wordmark already routes to the catalog.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, StatusDotComponent],
  template: `
    <header class="bar">
      <div class="bar__left">
        <a class="bar__mark" routerLink="/">mytetz</a>
        <nav class="bar__nav" aria-label="Sections">
          <a
            class="bar__link"
            routerLink="/"
            routerLinkActive="bar__link--active"
            [routerLinkActiveOptions]="{ exact: true }"
            >Topics</a
          >
        </nav>
      </div>
      <app-status-dot [state]="backend()" />
    </header>
    <ng-content />
  `,
  styles: [
    `
      :host {
        display: block;
        min-height: 100vh;
      }
      .bar {
        height: 64px;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 16px;
        padding: 0 32px;
        background: var(--mt-surface);
        border-bottom: var(--mt-border-w) solid var(--mt-rule);
      }
      .bar__left {
        display: flex;
        align-items: center;
        gap: 28px;
        min-width: 0;
      }
      /* 24px at weight 600 is large text, so the fill coral is safe here. */
      .bar__mark {
        font-family: var(--mt-display);
        font-size: 24px;
        font-weight: 600;
        color: var(--mt-coral);
        text-decoration: none;
      }
      .bar__nav {
        display: flex;
        gap: 20px;
      }
      .bar__link {
        font-size: 15px;
        font-weight: 700;
        color: var(--mt-muted);
        text-decoration: none;
        padding-bottom: 3px;
        border-bottom: 3px solid transparent;
      }
      .bar__link--active {
        font-weight: 800;
        color: var(--mt-teal);
        border-bottom-color: var(--mt-teal);
      }
      @media (max-width: 767px) {
        .bar {
          padding: 0 20px;
        }
        .bar__nav {
          display: none;
        }
      }
    `,
  ],
})
export class AppShellComponent {
  readonly backend = input.required<BackendState>();
}
```

- [ ] **Step 6: Write the failing root test**

Replace the whole of `frontend/src/app/app.spec.ts`. It keeps the file's own pattern:
`provideHttpClientTesting` with `http.verify()`, and no change-detection provider. `app.config.ts`
provides `provideBrowserGlobalErrorListeners`, `provideRouter` and `provideHttpClient` and nothing
else, and `angular.json` declares no polyfill, so this application is zoneless by default. Read,
not assumed.

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

/**
 * The root's own guard.
 *
 * The root had almost no test until the Candy work. That gap once let the whole app ship with no
 * `<router-outlet>`: every component test passed, and the running site showed the scaffold and
 * nothing else. Playwright found it. These tests are the cheaper guard.
 */
describe('App', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** Creates the root, answers the health request the way `answer` says, and settles the view. */
  async function render(answer: (request: TestRequest) => void): Promise<ComponentFixture<App>> {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    answer(http.expectOne('/api/health'));
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const label = (fixture: ComponentFixture<App>): string | null =>
    (fixture.nativeElement.querySelector('.dot') as HTMLElement).getAttribute('aria-label');

  it('renders the shell and the router outlet', async () => {
    const fixture = await render((r) => r.flush({ status: 'ok', mongo: true }));

    expect(fixture.nativeElement.querySelector('app-shell')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('router-outlet')).not.toBeNull();
    // The scaffold said "backend: ok" in words. The dot says it now.
    expect(fixture.nativeElement.textContent).not.toContain('backend:');
  });

  it('reports a healthy backend', async () => {
    expect(label(await render((r) => r.flush({ status: 'ok', mongo: true })))).toBe('Backend ok');
  });

  it('reports a backend that answers without Mongo as degraded', async () => {
    expect(label(await render((r) => r.flush({ status: 'ok', mongo: false })))).toBe(
      'Backend degraded',
    );
  });

  it('reports a backend that does not answer as unreachable', async () => {
    // A transport failure, which is what a reader on a dead connection actually meets. A thrown
    // stub would prove the catch runs but not that it runs for the real reason.
    expect(label(await render((r) => r.error(new ProgressEvent('error'))))).toBe(
      'Backend unreachable',
    );
  });
});
```

- [ ] **Step 7: Run it to see it fail**

Run: `npm test -- --watch=false --include src/app/app.spec.ts`
Expected: FAIL. `app-shell` is not in the rendered output, `.dot` does not exist, and the text
still holds `backend:`.

- [ ] **Step 8: Rewrite the root**

Replace the whole of `frontend/src/app/app.ts`.

```ts
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ApiService } from './core/api.service';
import { AppShellComponent } from './ui/app-shell.component';
import { BackendState } from './ui/status-dot.component';

/**
 * The bootstrapped root. `app.routes.ts` wires `/` to the catalogue and `/learn/:sessionId` to the
 * reader.
 *
 * The `<router-outlet>` is load-bearing and it is tested. Without it the app compiles, every
 * component test passes, and the running site shows nothing.
 *
 * The health check is the same one the scaffold ran. Its result now reaches the dot in the top
 * bar rather than a line of text.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, AppShellComponent],
  template: `
    <app-shell [backend]="backend()">
      <router-outlet />
    </app-shell>
  `,
})
export class App implements OnInit {
  private readonly api = inject(ApiService);
  readonly backend = signal<BackendState>('checking');

  async ngOnInit(): Promise<void> {
    try {
      const health = await this.api.health();
      this.backend.set(health.mongo ? 'ok' : 'degraded');
    } catch {
      this.backend.set('unreachable');
    }
  }
}
```

- [ ] **Step 9: Run the whole unit suite**

Run: `npm test -- --watch=false`
Expected: PASS. The count rises from 103 to 110: three status-dot tests and four root tests replace the one the old `app.spec.ts` held. Read the actual number and record it.

- [ ] **Step 10: Run the end-to-end suite**

Run: `npx playwright test`
Expected: PASS, 6 tests. The shell adds a bar above every page and changes no selector the suite uses.

- [ ] **Step 11: Commit**

```bash
npm run format
git add frontend/src/app/ui frontend/src/app/app.ts frontend/src/app/app.spec.ts
git commit -m "feat(ui): put every page inside the Candy top bar

The root loses the scaffold heading and the 'backend: ok' line. The
health result reaches a dot on the right of the bar, and the dot states
the result in words as well as in colour.

The root had no test. It has four now. A missing router-outlet once
shipped because nothing here looked."
```

---

## Task 3: The catalog page

**Files:**
- Modify: `frontend/src/app/catalog/catalog-page.component.ts` (template and styles, and the filter members)
- Modify: `frontend/src/app/catalog/catalog-page.component.spec.ts` (add three tests; change none)

**Interfaces:**
- Consumes: the classes and tokens of Task 1. `TopicSummary` from `core/models`, unchanged.
- Produces: nothing that a later task uses.

- [ ] **Step 1: Write the three failing category tests**

Append these three tests to the `describe('CatalogPageComponent', …)` block in
`frontend/src/app/catalog/catalog-page.component.spec.ts`.

The file has no shared fixture: its `beforeEach` builds the TestBed and takes an
`HttpTestingController`, and each test then creates its own component and flushes its own request.
These three follow that. They use the file's own `quantumPhysics` (category `Physics`) and
`microbiology` (category `Biology`) fixtures, which are declared at the top of the file.

```ts
  it('offers one pill per distinct category, with All first', async () => {
    // The categories are derived from the topics already on screen. The API returns `category`
    // with every topic, so this costs no request and no backend change.
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    const labels = Array.from(fixture.nativeElement.querySelectorAll('button[data-category]')).map(
      (b) => (b as HTMLElement).textContent?.trim(),
    );

    expect(labels).toEqual(['All', 'Biology', 'Physics']);
  });

  it('shows only the topics of the selected category', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button[data-category="Biology"]').click();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Microbiology');
    expect(text).not.toContain('Quantum Physics');
  });

  it('combines the category pill with the text query', async () => {
    const fixture = TestBed.createComponent(CatalogPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/catalog/topics').flush([quantumPhysics, microbiology]);
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button[data-category="Biology"]').click();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('#topic-filter') as HTMLInputElement;
    input.value = 'quantum';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // Quantum Physics matches the query but not the category. Microbiology matches the category
    // but not the query. An AND leaves nothing. An OR would leave both, so this distinguishes the
    // two rather than merely showing that some filter runs.
    expect(fixture.nativeElement.querySelectorAll('.topic').length).toBe(0);
  });
```

- [ ] **Step 2: Run them to see them fail**

Run: `npm test -- --watch=false --include src/app/catalog/catalog-page.component.spec.ts`
Expected: FAIL on all three. No element carries `data-category`.

- [ ] **Step 3: Add the category filter to the component class**

In `frontend/src/app/catalog/catalog-page.component.ts`, add the signal and the computed list, and replace `filteredTopics`.

```ts
  /**
   * The chosen category, or `null` for every category.
   *
   * `null` rather than the string `'All'`, so a catalogue that one day publishes a category
   * actually named "All" does not collide with the control that clears the filter.
   */
  readonly category = signal<string | null>(null);

  /** `null` first, then each distinct category of the loaded topics, in alphabetical order. */
  readonly categories = computed<Array<string | null>>(() => {
    const distinct = [...new Set(this.topics().map((t) => t.category))];
    distinct.sort((a, b) => a.localeCompare(b));
    return [null, ...distinct];
  });

  /**
   * Client-side, deliberately. `?q=` exists on the backend (Task 1.3), but Slice 1's whole
   * catalogue is ~29 hand-curated topics — already fetched in full by `loadTopics()` — so
   * filtering it locally is instant and issues zero additional requests. The category filter is
   * free for the same reason: `TopicSummary.category` arrives with every topic.
   *
   * The two filters combine with AND. Revisit if Slice 5 grows the catalogue to the few hundred
   * topics the design spec anticipates.
   */
  readonly filteredTopics = computed(() => {
    const q = this.query().trim().toLowerCase();
    const cat = this.category();
    return this.topics().filter((t) => {
      if (cat !== null && t.category !== cat) return false;
      if (q === '') return true;
      return (
        t.title.toLowerCase().includes(q) ||
        t.category.toLowerCase().includes(q) ||
        t.summary.toLowerCase().includes(q)
      );
    });
  });

  categoryId(category: string | null): string {
    return category ?? '__all__';
  }

  categoryLabel(category: string | null): string {
    return category ?? 'All';
  }
```

- [ ] **Step 4: Replace the template**

Replace the `template` of `CatalogPageComponent` with this. Every selector the existing tests use is kept: `#topic-filter`, `button[data-slug]`, `.topic`, `.topic__category`, `.banner--error`, `.banner__retry-button`.

```html
    <main class="catalog">
      <div class="catalog__inner">
        <header class="catalog__header">
          <h1 class="catalog__title">What do you want to understand?</h1>
        </header>

        <div class="catalog__filter">
          <label class="catalog__label" for="topic-filter">Filter topics</label>
          <div class="catalog__row">
            <input
              id="topic-filter"
              class="catalog__search"
              type="search"
              placeholder="Search by title, category, or summary…"
              [value]="query()"
              (input)="onQueryInput($event)"
            />
            <div class="catalog__cats" role="group" aria-label="Filter by category">
              @for (c of categories(); track categoryId(c)) {
                <button
                  type="button"
                  class="mt-pill catalog__cat"
                  [class.mt-pill--teal]="category() === c"
                  [attr.data-category]="categoryId(c)"
                  [attr.aria-pressed]="category() === c"
                  (click)="category.set(c)"
                >
                  {{ categoryLabel(c) }}
                </button>
              }
            </div>
          </div>
        </div>

        @if (sessionError(); as err) {
          <div class="mt-card mt-card--error banner banner--error" role="alert">
            <p class="banner__message">{{ err.message }}</p>
            @if (err.retryLabel) {
              <p class="banner__retry">{{ err.retryLabel }}</p>
            }
            @if (err.reopenSessionId; as sessionId) {
              <button type="button" class="mt-pill mt-pill--coral banner__retry-button" (click)="reopen(sessionId)">
                Try again
              </button>
            }
          </div>
        }

        @if (topicsLoading()) {
          <p class="visually-hidden" role="status">Loading topics…</p>
          <ul class="topics" aria-hidden="true">
            @for (i of skeletons; track i) {
              <li class="topic topic--skeleton mt-card">
                <span class="mt-skeleton bar bar--eyebrow"></span>
                <span class="mt-skeleton bar bar--title"></span>
                <span class="mt-skeleton bar bar--line"></span>
                <span class="mt-skeleton bar bar--line bar--short"></span>
              </li>
            }
          </ul>
        } @else if (topicsError(); as loadError) {
          <div class="mt-card mt-card--error banner banner--error" role="alert">
            <p class="banner__message">{{ loadError }}</p>
            <button type="button" class="mt-pill mt-pill--coral banner__retry-button" (click)="loadTopics()">
              Retry
            </button>
          </div>
        } @else {
          <ul class="topics" [attr.aria-busy]="tilesLocked()">
            @for (t of filteredTopics(); track t.slug; let first = $first) {
              <li class="topic">
                <button
                  type="button"
                  class="mt-card topic__button"
                  [attr.data-slug]="t.slug"
                  [disabled]="tilesLocked()"
                  [attr.title]="tileLockedReason()"
                  (click)="open(t)"
                >
                  <span class="mt-eyebrow topic__category" [class.mt-eyebrow--coral]="first">{{
                    t.category
                  }}</span>
                  <h2 class="topic__title">{{ t.title }}</h2>
                  <p class="topic__summary">{{ t.summary }}</p>
                  @if (pendingSlug() === t.slug) {
                    <span class="mt-chip mt-chip--teal topic__pending" aria-live="polite"
                      >Starting…</span
                    >
                  }
                </button>
              </li>
            } @empty {
              <li class="topics__empty mt-card mt-card--dashed">
                @if (query() || category() !== null) {
                  <h2 class="topics__empty-title">Nothing under that name yet.</h2>
                  <p class="topics__empty-body">
                    The catalogue is {{ topics().length }} hand-written topics for now. Try a
                    shorter word, or clear the category.
                  </p>
                  <button type="button" class="mt-pill mt-pill--ghost" (click)="clearFilters()">
                    Clear the filters
                  </button>
                } @else {
                  <h2 class="topics__empty-title">No topics yet.</h2>
                  <p class="topics__empty-body">The catalogue is empty. Please come back later.</p>
                }
              </li>
            }
          </ul>
        }
      </div>
    </main>
```

Add the two members the template needs, next to the others in the class:

```ts
  /** Six placeholders, which is one full row of the widest grid. */
  readonly skeletons = [0, 1, 2, 3, 4, 5];

  clearFilters(): void {
    this.query.set('');
    this.category.set(null);
  }
```

- [ ] **Step 5: Replace the component styles**

Replace the `styles` array of `CatalogPageComponent`.

```css
      :host {
        display: block;
      }
      .catalog {
        padding: 36px 40px;
      }
      .catalog__inner {
        max-width: 1120px;
        margin: 0 auto;
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .catalog__title {
        font-size: 34px;
        line-height: 1.15;
      }
      .catalog__label {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
        white-space: nowrap;
      }
      .visually-hidden {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
        white-space: nowrap;
      }
      .catalog__row {
        display: flex;
        gap: 12px;
        align-items: center;
      }
      .catalog__search {
        flex: 1;
        min-width: 0;
        padding: 14px 18px;
        border: var(--mt-border-w) solid var(--mt-border);
        border-radius: var(--mt-r-row);
        background: var(--mt-surface);
        color: var(--mt-ink);
        font: inherit;
        font-size: 15px;
        font-weight: 600;
      }
      .catalog__search::placeholder {
        color: var(--mt-muted);
      }
      .catalog__search:focus-visible {
        border-color: var(--mt-teal);
      }
      .catalog__cats {
        display: flex;
        gap: 8px;
      }
      .topics {
        list-style: none;
        padding: 0;
        margin: 6px 0 0;
        display: grid;
        grid-template-columns: 1fr;
        gap: 16px;
      }
      .topic {
        display: flex;
        min-width: 0;
      }
      .topic__button {
        position: relative;
        width: 100%;
        text-align: left;
        padding: 20px;
        display: flex;
        flex-direction: column;
        gap: 6px;
        color: inherit;
        transition:
          transform 80ms ease-out,
          box-shadow 80ms ease-out;
      }
      .topic__button:hover:not(:disabled) {
        transform: translateY(-2px);
        box-shadow: 0 6px 0 var(--mt-border);
      }
      .topic__button:active:not(:disabled) {
        transform: translateY(2px);
        box-shadow: 0 2px 0 var(--mt-border);
      }
      .topic__button:disabled {
        opacity: 0.55;
        box-shadow: none;
      }
      .topic__title {
        font-size: 23px;
      }
      .topic__summary {
        margin: 0;
        font-size: 14px;
        line-height: 1.55;
        font-weight: 500;
        color: var(--mt-muted);
        text-wrap: pretty;
      }
      .topic__pending {
        position: absolute;
        top: 14px;
        right: 14px;
      }
      .topic--skeleton {
        flex-direction: column;
        gap: 10px;
        padding: 20px;
      }
      .bar {
        display: block;
        height: 14px;
      }
      .bar--eyebrow {
        width: 30%;
        height: 10px;
      }
      .bar--title {
        width: 60%;
        height: 22px;
      }
      .bar--line {
        width: 100%;
      }
      .bar--short {
        width: 72%;
      }
      .topics__empty {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 10px;
        padding: 24px;
      }
      .topics__empty-title {
        font-size: 22px;
      }
      .topics__empty-body {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
        color: var(--mt-muted);
        max-width: 52ch;
        text-wrap: pretty;
      }
      .banner {
        padding: 18px 20px;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 10px;
      }
      .banner__message,
      .banner__retry {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
      }
      .banner__retry {
        font-weight: 700;
      }
      @media (min-width: 768px) {
        .topics {
          grid-template-columns: repeat(2, 1fr);
        }
      }
      @media (min-width: 1120px) {
        .topics {
          grid-template-columns: repeat(3, 1fr);
        }
      }
      @media (max-width: 767px) {
        .catalog {
          padding: 24px 20px;
        }
        .catalog__title {
          font-size: 26px;
        }
        .catalog__row {
          flex-direction: column;
          align-items: stretch;
        }
        /* A wrapped pill list pushes the first tile below the fold. It scrolls sideways instead. */
        .catalog__cats {
          overflow-x: auto;
          padding-bottom: 4px;
        }
        .catalog__cat {
          flex: none;
        }
      }
```

Add `computed` and `signal` to the `@angular/core` import if either is missing.

- [ ] **Step 6: Run the catalog tests**

Run: `npm test -- --watch=false --include src/app/catalog/catalog-page.component.spec.ts`
Expected: PASS. The three new tests pass and every existing test in the file still passes.

If an existing test fails because the empty-state wording changed, read that test. The old copy said `No topics match "{{ query() }}"`. Update the assertion to the new copy — the message is deliberately different and the test states what a learner reads.

- [ ] **Step 7: Run the whole suite and the end-to-end suite**

Run: `npm test -- --watch=false`
Expected: PASS.

Run: `npx playwright test`
Expected: PASS, 6 tests. `openQuantumPhysicsSession` clicks `getByRole('button', { name: /Quantum Physics/ })`, and the tile is still a button whose text holds the title.

- [ ] **Step 8: Check the style budget**

Run: `npm run build`
Expected: the build completes with no `anyComponentStyle` warning. The limit is 4 kB warn, 8 kB error. If a warning appears, move the repeated rule into `styles.css`.

- [ ] **Step 9: Commit**

```bash
npm run format
git add frontend/src/app/catalog
git commit -m "feat(catalog): give the catalogue the Candy look and a category filter

The tiles become a responsive grid of Candy cards: one column, two from
768px, three from 1120px. The load state becomes six skeleton tiles, and
the empty state says how large the catalogue is.

The category pills are real. TopicSummary.category already arrives with
every topic, so the list is derived from what is on screen. The category
and the text query combine with AND. No request and no backend change."
```

---

## Task 4: The trail rail and the breadcrumb

**Files:**
- Modify: `frontend/src/app/reader/trail-rail.component.ts`
- Modify: `frontend/src/app/reader/breadcrumb.component.ts`

**Interfaces:**
- Consumes: the classes and tokens of Task 1. `NodeView` from `core/models`, unchanged.
- Produces: no API change. Both components keep every input and every output.

- [ ] **Step 1: Replace the trail rail template**

In `frontend/src/app/reader/trail-rail.component.ts`, replace the `template`. `.trail__item` and `data-node-id` stay: `learn.spec.ts` counts `.trail__item`.

```html
    <nav class="trail" aria-label="Session trail">
      <p class="mt-eyebrow trail__head">Your trail · {{ nodes().length }} steps</p>
      <button
        type="button"
        class="mt-pill mt-pill--ghost trail__toggle"
        [attr.aria-expanded]="!collapsed()"
        (click)="collapsed.set(!collapsed())"
      >
        {{ collapsed() ? 'Show' : 'Hide' }} trail ({{ nodes().length }})
      </button>
      <ol class="trail__list" [class.trail__list--collapsed]="collapsed()">
        @for (node of nodes(); track node.nodeId) {
          <li class="trail__row" [style.margin-left.px]="node.depth * 16">
            <button
              type="button"
              class="trail__item"
              [class.trail__item--current]="node.nodeId === currentNodeId()"
              [attr.data-node-id]="node.nodeId"
              [attr.aria-current]="node.nodeId === currentNodeId() ? 'true' : null"
              (click)="navigate.emit(node.nodeId)"
            >
              <span class="trail__dot" aria-hidden="true"></span>
              <span class="trail__text">
                <span class="mt-eyebrow trail__verb">{{
                  label(node.verb) + (node.nodeId === currentNodeId() ? ' · here' : '')
                }}</span>
                <span class="trail__span">{{ node.span || topicLabel() }}</span>
              </span>
            </button>
          </li>
        }
      </ol>
    </nav>
```

- [ ] **Step 2: Replace the trail rail styles**

```css
      :host {
        display: block;
      }
      .trail {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .trail__head {
        margin: 0;
      }
      .trail__list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 7px;
      }
      .trail__list--collapsed {
        display: none;
      }
      .trail__item {
        width: 100%;
        text-align: left;
        display: flex;
        align-items: center;
        gap: 9px;
        padding: 11px 13px;
        border: var(--mt-border-w) solid var(--mt-border);
        border-radius: var(--mt-r-row);
        background: var(--mt-surface);
        color: var(--mt-ink);
      }
      .trail__item--current {
        background: var(--mt-teal);
        border-color: var(--mt-teal);
        color: var(--mt-surface);
        box-shadow: var(--mt-lift-teal);
      }
      .trail__dot {
        width: 22px;
        height: 22px;
        flex: none;
        border-radius: 999px;
        background: var(--mt-amber);
      }
      .trail__text {
        display: flex;
        flex-direction: column;
        gap: 1px;
        min-width: 0;
      }
      .trail__verb {
        font-size: 10px;
      }
      .trail__item--current .trail__verb {
        color: var(--mt-teal-pale);
      }
      .trail__span {
        font-family: var(--mt-display);
        font-size: 15px;
        font-weight: 600;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      /* Wide screens never collapse: the rail is a permanent column there, so the initial
         collapsed state — chosen for the narrow case, without measuring anything — is overridden
         here rather than in TypeScript. 768px, so the drawer and the verb picker change mode
         together. */
      @media (min-width: 768px) {
        .trail__toggle {
          display: none;
        }
        .trail__list--collapsed {
          display: flex;
        }
      }
      @media (max-width: 767px) {
        .trail__head {
          display: none;
        }
      }
```

- [ ] **Step 3: Replace the breadcrumb template and styles**

In `frontend/src/app/reader/breadcrumb.component.ts`, replace both. `.crumb` stays: `learn.spec.ts` counts it and scopes a query to it.

The design at `4a` draws no breadcrumb, because the rail carries the same information there. The app keeps it: it is the only trail affordance below 768px until the drawer is opened.

Template:

```html
    <nav class="crumbs" aria-label="Explanation trail">
      <ol class="crumbs__list">
        @for (node of nodes(); track node.nodeId; let last = $last) {
          <li class="crumb">
            <button
              type="button"
              class="mt-chip crumb__button"
              [class.mt-chip--teal]="last"
              [attr.data-node-id]="node.nodeId"
              [attr.aria-current]="last ? 'page' : null"
              [disabled]="last"
              (click)="navigate.emit(node.nodeId)"
            >
              {{ node.parentNodeId === null ? topicLabel() : node.span }}
            </button>
            @if (!last) {
              <span class="crumb__separator" aria-hidden="true">›</span>
            }
          </li>
        }
      </ol>
    </nav>
```

Styles:

```css
      :host {
        display: block;
      }
      .crumbs__list {
        list-style: none;
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 6px;
        margin: 0 0 16px;
        padding: 0;
      }
      .crumb {
        display: flex;
        align-items: center;
        gap: 6px;
        min-width: 0;
      }
      .crumb__button {
        max-width: 22ch;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        display: block;
      }
      .crumb__button:disabled {
        cursor: default;
        opacity: 1;
      }
      .crumb__separator {
        color: var(--mt-faint);
        font-weight: 800;
      }
```

- [ ] **Step 4: Run the reader tests and the end-to-end suite**

Run: `npm test -- --watch=false`
Expected: PASS. No unit test reads either component's markup beyond what is kept.

Run: `npx playwright test`
Expected: PASS, 6 tests. The suite counts `.crumb` and `.trail__item` and clicks a crumb by its accessible name. All three still hold.

- [ ] **Step 5: Commit**

```bash
npm run format
git add frontend/src/app/reader/trail-rail.component.ts frontend/src/app/reader/breadcrumb.component.ts
git commit -m "feat(reader): give the trail and the breadcrumb the Candy look

The trail becomes a nested pill list. Each row indents by 16px per level
and shows the verb over the span. The current row inverts to teal and its
eyebrow reads 'here'.

The drawer breakpoint moves from 640px to 768px, so the drawer and the
verb picker change mode at the same width. No test depended on 640px.

The breadcrumb becomes chips. The design drops it on a wide screen,
because the rail says the same thing. It stays here: below 768px it is
the only trail a learner sees until the drawer is opened."
```

---

## Task 5: The verb picker

**Files:**
- Create: `frontend/src/app/ui/verb-picker.component.ts`
- Test: `frontend/src/app/ui/verb-picker.component.spec.ts`

**Interfaces:**
- Consumes: `SpanPayload` and `Verb` from `core/models`. The classes and tokens of Task 1.
- Produces, for Task 6:
  - `export interface PickerAnchor { top: number; left: number }`
  - `VerbPickerComponent`, selector `app-verb-picker`.
  - Inputs: `span: SpanPayload` (required), `anchor: PickerAnchor` (required).
  - Outputs: `chosen: Verb`, `dismissed: void`.
  - `export const PICKER_HEIGHT = 240;` — the CSS caps the picker at this height, and the host uses the same number to decide whether to place it above the phrase.
  - Each verb button keeps `data-verb="<Verb>"`.

The component is **position-agnostic**: it renders where `anchor` says. It never measures anything. jsdom has no layout, so a component that measured could not be tested at all.

- [ ] **Step 1: Write the failing picker test**

Create `frontend/src/app/ui/verb-picker.component.spec.ts`.

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Verb } from '../core/models';
import { VerbPickerComponent } from './verb-picker.component';

/**
 * The picker replaces a static row of four buttons. The row was always on screen and was disabled
 * until a phrase was highlighted. The picker is not on screen at all until then, so "the
 * affordance is not live" is now "the picker is absent" and no longer "the button is disabled".
 *
 * Nothing here asserts a position. Unit tests run in jsdom, which has no layout engine, so every
 * rect is zero. Task 8's manual pass is what proves the placement.
 */
describe('VerbPickerComponent', () => {
  let fixture: ComponentFixture<VerbPickerComponent>;
  let chosen: Verb[];
  let dismissed: number;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [VerbPickerComponent] });
    fixture = TestBed.createComponent(VerbPickerComponent);
    chosen = [];
    dismissed = 0;
    fixture.componentInstance.chosen.subscribe((v) => chosen.push(v));
    fixture.componentInstance.dismissed.subscribe(() => (dismissed += 1));
    fixture.componentRef.setInput('span', { text: 'escape velocity', start: 4, end: 19 });
    fixture.componentRef.setInput('anchor', { top: 0, left: 0 });
    fixture.detectChanges();
  });

  const button = (verb: Verb): HTMLButtonElement =>
    fixture.nativeElement.querySelector(`button[data-verb="${verb}"]`);
  const root = (): HTMLElement => fixture.nativeElement.querySelector('[role="dialog"]');

  it('offers the four text verbs and no other', () => {
    // SEED is the session's own root and VISUALIZE is slice 4. Neither belongs to a highlight.
    const verbs = Array.from(fixture.nativeElement.querySelectorAll('button[data-verb]')).map((b) =>
      (b as HTMLElement).getAttribute('data-verb'),
    );
    expect(verbs).toEqual(['EXPLAIN', 'DIG_DEEPER', 'BROADER_PICTURE', 'SIDE_VIEW']);
  });

  it('names each verb briefly, and describes it separately', () => {
    // The caption is a description and not part of the name. Joined into the name it would read
    // "Explain it Plain words, no jargon", and it would collide with the trail rail's own
    // "Explain" rows under a getByRole query.
    const explain = button('EXPLAIN');
    expect(explain.getAttribute('aria-label')).toBe('Explain it');
    const describedBy = explain.getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    expect(fixture.nativeElement.querySelector(`#${describedBy}`).textContent).toContain(
      'Plain words',
    );
  });

  it('quotes the phrase the learner highlighted', () => {
    expect(root().textContent).toContain('escape velocity');
  });

  it('emits the verb that was pressed', () => {
    button('SIDE_VIEW').click();
    expect(chosen).toEqual(['SIDE_VIEW']);
  });

  it('is a dialog with a name', () => {
    expect(root().getAttribute('role')).toBe('dialog');
    expect(root().getAttribute('aria-label')).toBe('Explain the highlighted phrase');
  });

  it('dismisses on Escape', () => {
    root().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(dismissed).toBe(1);
    expect(chosen).toEqual([]);
  });

  it('dismisses on a press outside itself', () => {
    document.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    fixture.detectChanges();
    expect(dismissed).toBe(1);
  });

  it('stays open on a press inside itself', () => {
    button('EXPLAIN').dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    fixture.detectChanges();
    expect(dismissed).toBe(0);
  });

  it('places itself where the anchor says', () => {
    // The value, not the resulting pixel. jsdom has no layout, so the assertion is that the
    // component passes the anchor through to CSS rather than that the browser honoured it.
    fixture.componentRef.setInput('anchor', { top: 120, left: 40 });
    fixture.detectChanges();
    expect(root().style.getPropertyValue('--picker-top')).toBe('120px');
    expect(root().style.getPropertyValue('--picker-left')).toBe('40px');
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `npm test -- --watch=false --include src/app/ui/verb-picker.component.spec.ts`
Expected: FAIL. The module does not resolve.

- [ ] **Step 3: Write the picker**

Create `frontend/src/app/ui/verb-picker.component.ts`.

```ts
import {
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  viewChildren,
} from '@angular/core';
import { SpanPayload, Verb } from '../core/models';

/** Where the picker sits, in the coordinates of the element that hosts it. */
export interface PickerAnchor {
  top: number;
  left: number;
}

/**
 * The picker's own height cap. The CSS below enforces it, and the host uses the same number to
 * decide whether the picker fits below the phrase or must go above it. One number, two readers.
 */
export const PICKER_HEIGHT = 240;

/** The four verbs a highlight can ask for.
 *
 * `SEED` is the session's own root and belongs to no highlight. `VISUALIZE` is slice 4: the
 * design draws a fifth row for it, and that row arrives with the feature. */
const VERBS: ReadonlyArray<{ verb: Verb; name: string; caption: string }> = [
  { verb: 'EXPLAIN', name: 'Explain it', caption: 'Plain words, no jargon' },
  { verb: 'DIG_DEEPER', name: 'Dig deeper', caption: 'One level more technical' },
  { verb: 'BROADER_PICTURE', name: 'Broader picture', caption: 'Where this sits in the whole' },
  { verb: 'SIDE_VIEW', name: 'Side view', caption: 'The same idea from elsewhere' },
];

/**
 * What a learner sees after they highlight a phrase.
 *
 * On a wide screen it is a card anchored under the highlight. Below 768px the same card rises from
 * the bottom edge, because a popover beside a thumb on a 390px screen covers the text it explains.
 * The two modes are one CSS media query, and no TypeScript measures a viewport — the reader stays
 * server-renderable.
 *
 * The component measures nothing at all. Its host computes the anchor inside an event handler and
 * passes it in. That split is what makes the picker testable: unit tests run in jsdom, which has
 * no layout engine and returns a zero rect for everything.
 */
@Component({
  selector: 'app-verb-picker',
  imports: [],
  host: {
    // A press anywhere else closes the picker. `document:mousedown` is a host listener, so Angular
    // attaches it only in a browser and the render path stays clean.
    '(document:mousedown)': 'onDocumentPress($event)',
  },
  template: `
    <div
      #root
      class="picker"
      role="dialog"
      aria-label="Explain the highlighted phrase"
      [style.--picker-top]="anchor().top + 'px'"
      [style.--picker-left]="anchor().left + 'px'"
      (keydown.escape)="dismissed.emit()"
      (keydown.tab)="onTab($event)"
    >
      <p class="picker__lead">“{{ span().text }}” — go on:</p>
      <div class="picker__grid">
        @for (v of verbs; track v.verb; let first = $first) {
          <button
            #verb
            type="button"
            class="picker__verb"
            [class.picker__verb--primary]="first"
            [attr.data-verb]="v.verb"
            [attr.aria-label]="v.name"
            [attr.aria-describedby]="'cap-' + v.verb"
            (click)="chosen.emit(v.verb)"
          >
            <span class="picker__name">{{ v.name }}</span>
            <span class="picker__caption" [id]="'cap-' + v.verb">{{ v.caption }}</span>
          </button>
        }
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: contents;
      }
      .picker {
        position: absolute;
        top: var(--picker-top, 0);
        left: var(--picker-left, 0);
        z-index: 20;
        width: min(520px, 100%);
        max-height: 240px;
        overflow: auto;
        padding: 16px;
        display: flex;
        flex-direction: column;
        gap: 10px;
        background: var(--mt-surface);
        border: var(--mt-border-w) solid var(--mt-border);
        border-radius: var(--mt-r-panel);
        box-shadow: var(--mt-float);
      }
      .picker__lead {
        margin: 0;
        font-size: 13px;
        font-weight: 700;
        color: var(--mt-muted);
      }
      .picker__grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 9px;
      }
      .picker__verb {
        text-align: left;
        padding: 11px 14px;
        display: flex;
        flex-direction: column;
        gap: 1px;
        border: var(--mt-border-w) solid var(--mt-border);
        border-radius: var(--mt-r-row);
        background: var(--mt-surface);
        color: var(--mt-ink);
      }
      /* The fill is --mt-coral-press and not --mt-coral. The name is 16px, so white on
         --mt-coral measures 3.01:1 and fails AA. Both lines are white; the face and the size
         keep them apart. */
      .picker__verb--primary {
        background: var(--mt-coral-press);
        border-color: var(--mt-coral-press);
        color: var(--mt-surface);
        box-shadow: var(--mt-lift-coral);
      }
      .picker__name {
        font-family: var(--mt-display);
        font-size: 16px;
        font-weight: 600;
      }
      .picker__caption {
        font-size: 12px;
        font-weight: 600;
        color: var(--mt-muted);
      }
      .picker__verb--primary .picker__caption {
        color: var(--mt-surface);
      }
      /* Below 768px the same card rises from the bottom edge. `top` and `left` are set again
         here, so the anchor the host passed in is simply unused — no !important, and no
         measurement in TypeScript. */
      @media (max-width: 767px) {
        .picker {
          position: fixed;
          top: auto;
          left: 0;
          right: 0;
          bottom: 0;
          width: auto;
          max-height: 60vh;
          border-radius: var(--mt-r-card) var(--mt-r-card) 0 0;
          animation: picker-rise 200ms ease-out;
        }
      }
      @keyframes picker-rise {
        from {
          transform: translateY(100%);
        }
        to {
          transform: translateY(0);
        }
      }
    `,
  ],
})
export class VerbPickerComponent {
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly span = input.required<SpanPayload>();
  readonly anchor = input.required<PickerAnchor>();

  readonly chosen = output<Verb>();
  readonly dismissed = output<void>();

  readonly verbs = VERBS;

  private readonly verbButtons = viewChildren<ElementRef<HTMLButtonElement>>('verb');

  constructor() {
    // The picker exists only while it is open, so "on creation" is "on open". `afterNextRender`
    // never runs on the server, which keeps this off the render path.
    afterNextRender(() => this.verbButtons()[0]?.nativeElement.focus());
  }

  /** A press outside the picker closes it. A press inside it does nothing. */
  onDocumentPress(event: Event): void {
    const target = event.target;
    if (target instanceof Node && this.host.nativeElement.contains(target)) return;
    this.dismissed.emit();
  }

  /** Keeps Tab inside the picker. Without this, Tab walks into the page behind an open dialog. */
  onTab(event: KeyboardEvent): void {
    const buttons = this.verbButtons().map((b) => b.nativeElement);
    if (buttons.length === 0) return;
    const first = buttons[0];
    const last = buttons[buttons.length - 1];
    const active = this.host.nativeElement.ownerDocument.activeElement;
    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `npm test -- --watch=false --include src/app/ui/verb-picker.component.spec.ts`
Expected: PASS, 9 tests.

If `dismisses on a press outside itself` fails, check that the host listener is on `document` and not on the host element. If `places itself where the anchor says` fails, check the binding form — a custom property takes the string form `[style.--picker-top]="anchor().top + 'px'"` and not the `.px` suffix form.

- [ ] **Step 5: Commit**

```bash
npm run format
git add frontend/src/app/ui/verb-picker.component.ts frontend/src/app/ui/verb-picker.component.spec.ts
git commit -m "feat(ui): add the verb picker, a popover above 768px and a sheet below

The picker measures nothing. Its host computes the anchor inside an event
handler and passes it in. That split keeps the reader server-renderable
and it keeps the component testable: unit tests run in jsdom, which has
no layout engine and returns a zero rect for everything.

Each verb button carries a short aria-label and points at its caption
with aria-describedby. A caption joined into the name would read
'Explain it Plain words, no jargon', and it would collide with the trail
rail's own Explain rows under a getByRole query."
```

---

## Task 6: The focus card hosts the picker

**Files:**
- Modify: `frontend/src/app/reader/focus-card.component.ts`
- Modify: `frontend/src/app/reader/focus-card.component.spec.ts`
- Modify: `frontend/e2e/learn.spec.ts`

**Interfaces:**
- Consumes: `VerbPickerComponent`, `PickerAnchor`, `PICKER_HEIGHT` from Task 5.
- Produces, for Task 7: `FocusCardComponent` keeps `body`, `streamingText`, `isStreaming` and
  `explainRequested` unchanged, and gains three optional inputs — `step: number | null` (default
  `null`), `verbLabel: string` (default `''`) and `topicLabel: string` (default `''`). Each one
  has a default, so the six tests that create the component with three inputs keep compiling.

This task ends green on all three suites. It changes the component and the two suites that read it in one commit, because the swap from a disabled button to an absent picker breaks them together.

- [ ] **Step 1: Update the focus-card spec to the new affordance**

In `frontend/src/app/reader/focus-card.component.spec.ts`, change the six assertions that read `.disabled`. The old row was always present and was disabled; the picker is absent until a phrase is highlighted. So "the affordance is not live" becomes "the picker is not there".

Replace the `verbButton` helper and add one more:

```ts
  const verbButton = (verb: Verb): HTMLButtonElement | null =>
    fixture.nativeElement.querySelector(`button[data-verb="${verb}"]`);
  /** True when the picker is on screen, which is the only time a verb can be pressed. */
  const pickerLive = (): boolean => verbButton('EXPLAIN') !== null;
```

Then make these exact replacements.

| Test | Was | Becomes |
|---|---|---|
| `emits the highlighted span with the verb of the button pressed` | `expect(verbButton('DIG_DEEPER').disabled).toBe(false);` | `expect(pickerLive()).toBe(true);` |
| same test, next line | `verbButton('DIG_DEEPER').click();` | `verbButton('DIG_DEEPER')!.click();` |
| `clears the span, rather than throwing…` | `expect(verbButton('EXPLAIN').disabled).toBe(false);` | `expect(pickerLive()).toBe(true);` |
| same test, later | `expect(verbButton('EXPLAIN').disabled).toBe(true);` | `expect(pickerLive()).toBe(false);` |
| `drops a selection that no longer indexes the body on screen` | `expect(verbButton('EXPLAIN').disabled).toBe(false);` | `expect(pickerLive()).toBe(true);` |
| same test, later | `expect(verbButton('EXPLAIN').disabled).toBe(true);` | `expect(pickerLive()).toBe(false);` |
| `disables every verb, and says why…` | the whole `for` loop over the four verbs | `expect(pickerLive()).toBe(false);` |
| `re-checks the body when a selection is made…` | `expect(verbButton('EXPLAIN').disabled).toBe(true);` | `expect(pickerLive()).toBe(false);` |
| `recovers on the next selection…` | `expect(verbButton('EXPLAIN').disabled).toBe(true);` | `expect(pickerLive()).toBe(false);` |
| same test, later | `expect(verbButton('EXPLAIN').disabled).toBe(false);` | `expect(pickerLive()).toBe(true);` |

Rename the test `disables every verb, and says why, when the rendered root stops matching the body` to `offers no verb, and says why, when the rendered root stops matching the body`.

Then add two tests at the end of the `describe`.

```ts
  it('keeps the picker out of the selectable root', () => {
    // The picker's own markup inside `.focus__body` would shift every offset by however many
    // characters it contributes, and every explain would come back SPAN_MISMATCH. The picker is a
    // sibling of the paragraph and never a child of it.
    select(4, 11);

    expect(pickerLive()).toBe(true);
    expect(bodyEl().querySelector('[role="dialog"]')).toBeNull();
    expect(bodyEl().textContent).toBe(BODY);
    expect(rootTextMatchesBody(bodyEl(), BODY)).toBe(true);
  });

  it('closes the picker when a stream starts', async () => {
    select(4, 11);
    expect(pickerLive()).toBe(true);

    // A stream in progress is prose that is in no stored body yet, so no selection over it can be
    // turned into a request. The affordance goes away rather than staying on screen and refusing.
    fixture.componentRef.setInput('isStreaming', true);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(pickerLive()).toBe(false);
  });
```

- [ ] **Step 2: Run the spec to see it fail**

Run: `npm test -- --watch=false --include src/app/reader/focus-card.component.spec.ts`
Expected: FAIL. `pickerLive()` is true whenever the old row is rendered, which is always, so the assertions that expect `false` fail.

- [ ] **Step 3: Rewrite the focus card**

Replace the `imports`, `template`, `styles` and the members named below in `frontend/src/app/reader/focus-card.component.ts`. **Keep the whole class doc comment**, and add the paragraph shown in Step 4. Delete the `VERBS` constant and the `verbs` member: the picker owns them now.

Imports:

```ts
import {
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { SpanPayload, Verb } from '../core/models';
import { PICKER_HEIGHT, PickerAnchor, VerbPickerComponent } from '../ui/verb-picker.component';
import { rootTextMatchesBody, selectionToSpan } from './selection';
```

Component metadata: `imports: [VerbPickerComponent]`.

Template. The `<!-- prettier-ignore -->` comment and the paragraph under it are copied unchanged, including the flush interpolation — read Step 4 before you touch them.

```html
    <article #cardEl class="focus mt-card">
      <div class="focus__head">
        <span class="mt-eyebrow mt-eyebrow--coral">{{ eyebrow() }}</span>
        @if (isStreaming()) {
          <span class="focus__track" role="presentation"><span class="focus__band"></span></span>
        }
      </div>

      <h1 class="focus__topic">{{ topicLabel() }}</h1>

      <!--
        Prettier is held off this element deliberately, and it is not cosmetic: run over it,
        Prettier moves the interpolation onto its own line, the Angular compiler keeps one space per
        whitespace run in a non-blank text node, and every offset measured against this element is
        then one character out - so every explain comes back SPAN_MISMATCH. Verified, not assumed:
        the reformatted version fails four tests in this component's spec. See the class comment
        above for the full invariant. The directive below applies to the very next node, so nothing
        may be inserted between it and the paragraph.
      -->
      <!-- prettier-ignore -->
      <p
        #bodyEl
        class="focus__body"
        data-testid="focus-body"
        (mouseup)="onSelectionChanged()"
        (touchend)="onSelectionChanged()"
      >{{ body() }}</p>

      @if (isStreaming() || streamingText().length > 0) {
        <p class="focus__streaming" role="status" aria-live="polite">
          {{ streamingText() }}
          @if (isStreaming()) {
            <span class="focus__caret" aria-hidden="true">▍</span>
          }
        </p>
      }

      <p class="focus__hint" [class.focus__hint--warning]="!bodyMatches()">{{ hint() }}</p>

      @if (pickerSpan(); as chosenSpan) {
        <app-verb-picker
          [span]="chosenSpan"
          [anchor]="anchor()!"
          (chosen)="request($event)"
          (dismissed)="close()"
        />
      }
    </article>
```

Styles:

```css
      :host {
        display: block;
      }
      .focus {
        position: relative;
        border-radius: var(--mt-r-card);
        box-shadow: var(--mt-lift-card);
        padding: 32px 36px;
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .focus__head {
        display: flex;
        align-items: center;
        gap: 12px;
      }
      /* The design draws a filled bar with a percentage. No bounded quantity exists to bind one
         to, and an invented percentage is a promise the app cannot keep. The track appears only
         while a stream runs, and it says "something is happening" and nothing more. */
      .focus__track {
        flex: 1;
        height: 8px;
        border-radius: 4px;
        background: var(--mt-rule);
        overflow: hidden;
      }
      .focus__band {
        display: block;
        width: 40%;
        height: 100%;
        border-radius: 4px;
        background: var(--mt-amber);
        animation: focus-travel 1.6s ease-in-out infinite;
      }
      @keyframes focus-travel {
        0% {
          transform: translateX(-100%);
        }
        100% {
          transform: translateX(250%);
        }
      }
      .focus__topic {
        font-size: 26px;
      }
      .focus__body {
        margin: 0;
        font-size: 19px;
        line-height: 1.65;
        font-weight: 500;
        color: var(--mt-prose);
        max-width: 62ch;
        white-space: pre-wrap;
        text-wrap: pretty;
      }
      /* The design draws the highlighted phrase in amber. A wrapper element inside this paragraph
         would shift every offset and break the invariant above, so the native selection carries
         the colour instead. No node is added. */
      .focus__body::selection {
        background: var(--mt-amber);
        color: var(--mt-amber-ink);
      }
      .focus__streaming {
        margin: 0;
        padding: 16px;
        font-size: 19px;
        line-height: 1.65;
        font-weight: 500;
        color: var(--mt-prose);
        max-width: 62ch;
        white-space: pre-wrap;
        background: var(--mt-sunk);
        border-radius: var(--mt-r-panel);
        border-left: 3px solid var(--mt-coral-press);
        user-select: none;
      }
      .focus__caret {
        color: var(--mt-coral-text);
        animation: focus-blink 1s step-end infinite;
      }
      @keyframes focus-blink {
        0%,
        49% {
          opacity: 1;
        }
        50%,
        100% {
          opacity: 0;
        }
      }
      .focus__hint {
        margin: 0;
        font-size: 13px;
        font-weight: 700;
        color: var(--mt-muted);
      }
      .focus__hint--warning {
        padding: 14px 16px;
        border-radius: var(--mt-r-panel);
        background: var(--mt-err-bg);
        border: var(--mt-border-w) solid var(--mt-err-border);
        color: var(--mt-err-ink);
      }
      @media (max-width: 767px) {
        .focus {
          padding: 20px;
        }
        .focus__topic {
          font-size: 22px;
        }
        .focus__body,
        .focus__streaming {
          font-size: 17px;
        }
      }
```

Members. Replace `hint`, add `eyebrow`, `anchor`, `pickerSpan`, `close` and `anchorFor`, and change `onSelectionChanged` and the `afterRenderEffect`. Add the new inputs the eyebrow needs.

```ts
  readonly body = input.required<string>();
  readonly streamingText = input.required<string>();
  readonly isStreaming = input.required<boolean>();
  /** The step number and the verb of the node in focus, for the eyebrow. The reader page supplies
   * both from `NodeView`. */
  readonly step = input<number | null>(null);
  readonly verbLabel = input<string>('');
  /** The topic's name, which the design draws as the card's own heading. It sits outside
   * `.focus__body`, so it contributes no character to the string the offsets index. */
  readonly topicLabel = input<string>('');

  readonly explainRequested = output<{ span: SpanPayload; verb: Verb }>();

  private readonly bodyRef = viewChild.required<ElementRef<HTMLElement>>('bodyEl');
  private readonly cardRef = viewChild.required<ElementRef<HTMLElement>>('cardEl');
  private readonly selectedSpan = signal<SpanPayload | null>(null);
  protected readonly anchor = signal<PickerAnchor | null>(null);
  protected readonly bodyMatches = signal(true);
  private checkedBody: string | null = null;

  readonly canExplain = computed(
    () => this.bodyMatches() && !this.isStreaming() && this.selectedSpan() !== null,
  );

  /** The span the picker should show, or `null` when the picker must not be on screen at all. */
  protected readonly pickerSpan = computed(() =>
    this.canExplain() && this.anchor() !== null ? this.selectedSpan() : null,
  );

  protected readonly eyebrow = computed(() => {
    const step = this.step();
    const verb = this.verbLabel();
    if (this.isStreaming()) return 'Writing the next explanation';
    if (step === null || verb === '') return 'Your explanation';
    return `Step ${step} · ${verb}`;
  });

  protected readonly hint = computed(() => {
    if (!this.bodyMatches()) {
      return 'This passage cannot be highlighted right now — what is on screen does not match the stored explanation. Reload the page to try again.';
    }
    if (this.isStreaming()) return 'Generating…';
    return 'Highlight a phrase, then choose how to go deeper.';
  });
```

The render effect gains one line — the anchor must go when the span goes:

```ts
  constructor() {
    afterRenderEffect({
      read: () => {
        const body = this.body();
        const matches = rootTextMatchesBody(this.bodyRef().nativeElement, body);
        this.bodyMatches.set(matches);
        if (body !== this.checkedBody) {
          this.checkedBody = body;
          // The offsets held here index the body that was on screen a moment ago. Against the new
          // one they name a phrase the learner never highlighted.
          this.selectedSpan.set(null);
          this.anchor.set(null);
        }
      },
    });
  }
```

`onSelectionChanged` keeps every guard it has and gains the anchor:

```ts
  onSelectionChanged(): void {
    const root = this.bodyRef().nativeElement;

    // Re-checked here as well as after render, because `textContent` is not a signal: something
    // that rewrites the text nodes *in place*, leaving `body()` untouched, never makes the
    // post-render check dirty and never re-runs it. Chrome's built-in page translation and
    // Grammarly both do exactly that, and on a learning site with international readers it is
    // ordinary rather than exotic — the affordance would stay live over text the server has never
    // seen, and every request would come back SPAN_MISMATCH. This is the instant the offsets are
    // computed, so it is the instant worth checking; the comparison is one paragraph long.
    const matches = rootTextMatchesBody(root, this.body());
    this.bodyMatches.set(matches);
    if (!matches) {
      this.selectedSpan.set(null);
      this.anchor.set(null);
      return;
    }

    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) {
      this.selectedSpan.set(null);
      this.anchor.set(null);
      return;
    }

    const range = selection.getRangeAt(0);
    const span = selectionToSpan(root, range);
    this.selectedSpan.set(span);
    this.anchor.set(span === null ? null : this.anchorFor(range));
  }

  /**
   * Where the picker goes, in the card's own coordinates.
   *
   * Every read here happens inside the `mouseup`/`touchend` handler that called this method, and
   * never on the render path. That is what keeps the reader server-renderable.
   *
   * jsdom has no layout engine and returns a zero rect for everything, so under a unit test this
   * returns `{top: 8, left: 0}` and the picker still renders. The placement itself is proved by
   * the manual pass, not by a test.
   */
  private anchorFor(range: Range): PickerAnchor {
    const card = this.cardRef().nativeElement.getBoundingClientRect();
    const rects = range.getClientRects();
    // The last rect, not the union: a selection that wraps over two lines should open the picker
    // under where it ended, which is where the learner's pointer is.
    const rect = rects.length > 0 ? rects[rects.length - 1] : range.getBoundingClientRect();

    const width = Math.min(520, card.width);
    const left = Math.max(0, Math.min(rect.left - card.left, card.width - width));

    const below = rect.bottom - card.top + 8;
    // If the picker would run past the bottom of the card, it goes above the phrase instead.
    const fits = below + PICKER_HEIGHT <= card.height;
    const top = fits ? below : Math.max(0, rect.top - card.top - PICKER_HEIGHT - 8);

    return { top, left };
  }

  /** The learner dismissed the picker. The affordance goes; the selection is theirs to remake. */
  close(): void {
    this.selectedSpan.set(null);
    this.anchor.set(null);
  }

  request(verb: Verb): void {
    const span = this.selectedSpan();
    if (span === null || !this.canExplain()) return;
    this.explainRequested.emit({ span, verb });
    this.close();
  }
```

- [ ] **Step 4: Extend the class doc comment**

Add this paragraph to the class doc comment of `FocusCardComponent`, after the two existing rules and before the `## SSR` heading. Keep every word that is already there.

```
 * **The picker is a sibling of the paragraph, never a child.** The design draws a highlighted
 * phrase with an amber background. A wrapper element inside `.focus__body` would break rule one,
 * so the colour comes from `.focus__body::selection` instead and no node is added. The picker
 * itself sits after the paragraph, inside the card. A test asserts both.
```

- [ ] **Step 5: Run the focus-card spec**

Run: `npm test -- --watch=false --include src/app/reader/focus-card.component.spec.ts`
Expected: PASS, 11 tests — the 9 that were there, plus the 2 new ones.

- [ ] **Step 6: Harden the reader spec's own helper, then run the whole unit suite**

`reader-page.component.spec.ts` presses a verb too. Its `highlightAndExplain()` helper, at lines
106 to 119, selects `pillars`, fires a real `mouseup`, calls `harness.detectChanges()`, and then
clicks `[data-verb="EXPLAIN"]`. The picker is open by that point, so the helper keeps working with
no change.

One line in it does need a change. It ends with `?.click()`, and the optional chain was harmless
while the button was always on screen. It is not harmless now: an absent picker would make the
click a silent no-op. Replace line 117 with a form that fails loudly.

```ts
    const explain = harness.routeNativeElement?.querySelector<HTMLButtonElement>(
      '[data-verb="EXPLAIN"]',
    );
    if (!explain) throw new Error('the verb picker did not open for the highlighted phrase');
    explain.click();
```

Run: `npm test -- --watch=false`
Expected: PASS.

`reader-page.component.spec.ts` reads `.focus__body`, `.trail__item`, `.crumb`, `.banner`,
`.banner__retry-button`, `.banner__dismiss`, `.banner__back`, `[data-node-id]` and `a[href="/"]`.
Task 7 keeps every one of them. It asserts on no `.status` class and on no loading copy.

- [ ] **Step 7: Update the end-to-end suite**

In `frontend/e2e/learn.spec.ts`, make these two changes.

First, add a helper near the top of the file, under the imports:

```ts
/**
 * A verb inside the picker, and only inside it.
 *
 * The scope is load-bearing. Playwright matches an accessible name as a case-insensitive
 * substring by default, and the trail rail renders a row whose text also holds "Explain". An
 * unscoped query is ambiguous the moment a session holds an EXPLAIN node.
 */
function verb(page: Page, name: string) {
  return page.locator('[role="dialog"]').getByRole('button', { name, exact: true });
}
```

Add `Page` to the `@playwright/test` import: `import { test, expect, type Page } from '@playwright/test';`

Second, the file holds **seven** occurrences of `page.getByRole('button', { name: 'Explain' })`, at
lines 41, 55, 106, 144, 171, 203 and 241. **Six** of them are a click and become
`verb(page, 'Explain it')`: lines 41, 106, 144, 171, 203 and 241. The seventh, at line 55, is the
disabled assertion and is handled next.

Confirm the count before you start and after you finish:

```bash
grep -c "getByRole('button', { name: 'Explain' })" e2e/learn.spec.ts
```

Expected before: `7`. Expected after: `0`.

Third, replace the assertion at line 55:

```ts
  await expect(page.getByRole('button', { name: 'Explain' })).toBeDisabled();
```

with:

```ts
  // The verbs used to be a row that was always on screen and went disabled. They are a picker
  // now, so "not offered" is "not there". A stream in progress is prose in no stored body, and a
  // selection over it indexes a string the server has never seen.
  await expect(page.locator('[role="dialog"]')).toHaveCount(0);
```

- [ ] **Step 8: Run the end-to-end suite**

Run: `npx playwright test`
Expected: PASS, 6 tests.

If a click on a verb times out, the picker did not open. Check that `selectPhrase` still ends with a real `page.mouse.up()` — it does — and that `onSelectionChanged` sets the anchor on the success path.

- [ ] **Step 9: Commit**

```bash
npm run format
git add frontend/src/app/reader/focus-card.component.ts frontend/src/app/reader/focus-card.component.spec.ts frontend/e2e/learn.spec.ts
git commit -m "feat(reader): replace the verb row with the anchored verb picker

The four buttons were always on screen and went disabled until a phrase
was highlighted. They are a picker now: absent until there is a phrase,
anchored under it above 768px, a sheet below.

The design draws the highlighted phrase with an amber background. A
wrapper element inside .focus__body would shift every offset and every
explain would come back SPAN_MISMATCH, so the colour comes from
::selection and no node is added. A test asserts the picker never enters
the selectable root.

Both suites follow the affordance: 'the button is disabled' becomes 'the
picker is not there'. Every e2e verb query is scoped to the picker,
because the trail rail also renders the word Explain and Playwright
matches an accessible name as a substring by default."
```

---

## Task 7: The reader page

**Files:**
- Modify: `frontend/src/app/reader/reader-page.component.ts`
- Modify: `frontend/src/app/reader/reader-page.component.spec.ts` if a selector it uses moved

**Interfaces:**
- Consumes: every component of Tasks 4 and 6, and the classes and tokens of Task 1.
- Produces: nothing that a later task uses.

- [ ] **Step 1: Add the two members the eyebrow needs**

`FocusCardComponent` gained `step` and `verbLabel` in Task 6. The reader supplies both from the node in focus. Add to `ReaderPageComponent`:

```ts
  /** Words for the verb of the node in focus. The same map the trail rail uses, kept here rather
   * than shared: two short maps are cheaper than a `core/` module that one more caller would
   * justify. Add the third caller and move it. */
  private static readonly VERB_WORDS: Readonly<Record<string, string>> = {
    SEED: 'Topic',
    EXPLAIN: 'Explain',
    DIG_DEEPER: 'Dig deeper',
    BROADER_PICTURE: 'Broader picture',
    SIDE_VIEW: 'Side view',
    VISUALIZE: 'Diagram',
  };

  /** The node in focus, or `null` while the session loads. */
  private readonly currentNode = computed(() => {
    const id = this.store.currentNodeId();
    return this.store.tree().find((n) => n.nodeId === id) ?? null;
  });

  /** The position of the node in focus in the trail, counted from one. */
  readonly step = computed(() => {
    const node = this.currentNode();
    return node === null ? null : node.depth + 1;
  });

  readonly verbLabel = computed(() => {
    const node = this.currentNode();
    if (node === null) return '';
    return ReaderPageComponent.VERB_WORDS[node.verb] ?? node.verb;
  });
```

- [ ] **Step 2: Replace the template**

Every load-bearing selector is kept: `.banner--error`, `.banner__retry-button`, and the child components' own.

```html
    <main class="reader">
      @if (store.loading()) {
        <p class="visually-hidden" role="status">Loading your session…</p>
        <div class="reader__grid">
          <div class="reader__rail" aria-hidden="true">
            <span class="mt-eyebrow">Your trail</span>
          </div>
          <div class="reader__main">
            <article class="focus-skeleton mt-card">
              <span class="mt-eyebrow mt-eyebrow--coral">Writing your first explanation</span>
              <span class="mt-skeleton line"></span>
              <span class="mt-skeleton line"></span>
              <span class="mt-skeleton line line--92"></span>
              <span class="mt-skeleton line line--78"></span>
              <span class="mt-skeleton line line--46"></span>
              <p class="focus-skeleton__note">
                A few seconds — it is written fresh for you, then kept, so a return here is
                instant. The highlight unlocks when the text lands.
              </p>
            </article>
          </div>
        </div>
      } @else if (loadError(); as failure) {
        <div class="reader__centre">
          <div class="mt-card mt-card--error banner banner--error" role="alert">
            <p class="banner__message">{{ failure.message }}</p>
            <div class="banner__actions">
              @if (failure.retryable) {
                <button
                  type="button"
                  class="mt-pill mt-pill--coral banner__retry-button"
                  (click)="store.retry()"
                >
                  Try again
                </button>
              }
              <a class="mt-pill mt-pill--ghost banner__back" routerLink="/">Back to topics</a>
            </div>
          </div>
        </div>
      } @else if (store.session()) {
        <div class="reader__grid">
          <app-trail-rail
            class="reader__rail"
            [nodes]="store.tree()"
            [currentNodeId]="store.currentNodeId()"
            [topicLabel]="topicLabel()"
            (navigate)="store.goTo($event)"
          />

          <div class="reader__main">
            @if (bannerError(); as failure) {
              <div class="mt-card mt-card--error banner banner--error" role="alert">
                <p class="banner__message">
                  {{ failure.message }}
                  @if (failure.discardedText) {
                    <span class="banner__detail">
                      The partial answer on screen was discarded — it was never saved to your
                      session.
                    </span>
                  }
                  @if (failure.retryAfter !== null) {
                    <span class="banner__detail">Try again in {{ wait(failure.retryAfter) }}.</span>
                  }
                </p>
                <div class="banner__actions">
                  @if (failure.retryable) {
                    <button
                      type="button"
                      class="mt-pill mt-pill--coral banner__retry-button"
                      (click)="store.retry()"
                    >
                      Try again
                    </button>
                  }
                  <button
                    type="button"
                    class="mt-pill mt-pill--ghost banner__dismiss"
                    (click)="store.dismissError()"
                  >
                    Dismiss
                  </button>
                </div>
              </div>
            }

            <app-breadcrumb
              [nodes]="store.breadcrumb()"
              [topicLabel]="topicLabel()"
              (navigate)="store.goTo($event)"
            />

            <app-focus-card
              [body]="store.currentBody()"
              [streamingText]="store.streamingText()"
              [isStreaming]="store.isStreaming()"
              [step]="step()"
              [verbLabel]="verbLabel()"
              [topicLabel]="topicLabel()"
              (explainRequested)="explain($event)"
            />
          </div>
        </div>
      }
    </main>
```

- [ ] **Step 3: Replace the styles**

```css
      :host {
        display: block;
      }
      .reader {
        padding: 28px 32px;
      }
      .visually-hidden {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
        white-space: nowrap;
      }
      /* One column below 768px. Two above it: the trail rail, then the card. The design's third
         column at 4a is dropped — every card in it needs a route that does not exist yet. It
         returns as a third track here and nowhere else. */
      .reader__grid {
        display: grid;
        grid-template-columns: 1fr;
        gap: 24px;
        max-width: 1004px;
        margin: 0 auto;
      }
      .reader__main {
        min-width: 0;
      }
      .reader__centre {
        max-width: 620px;
        margin: 48px auto 0;
      }
      .banner {
        padding: 20px 24px;
        margin: 0 0 16px;
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .banner__message {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
      }
      .banner__detail {
        display: block;
        font-weight: 700;
        margin-top: 6px;
      }
      .banner__actions {
        display: flex;
        gap: 10px;
        align-items: center;
        flex-wrap: wrap;
      }
      .banner__back {
        text-decoration: none;
      }
      .focus-skeleton {
        border-radius: var(--mt-r-card);
        box-shadow: var(--mt-lift-card);
        padding: 32px 36px;
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .line {
        display: block;
        height: 17px;
        width: 100%;
      }
      .line--92 {
        width: 92%;
      }
      .line--78 {
        width: 78%;
      }
      .line--46 {
        width: 46%;
      }
      .focus-skeleton__note {
        margin: 8px 0 0;
        font-size: 15px;
        line-height: 1.6;
        font-weight: 500;
        color: var(--mt-muted);
        max-width: 56ch;
        text-wrap: pretty;
      }
      @media (min-width: 768px) {
        .reader__grid {
          grid-template-columns: 260px minmax(0, 720px);
          align-items: start;
        }
      }
      @media (max-width: 767px) {
        .reader {
          padding: 20px;
        }
        .focus-skeleton {
          padding: 20px;
        }
      }
```

Add `computed` to the `@angular/core` import if it is missing — it is already there.

- [ ] **Step 4: Run the reader tests**

Run: `npm test -- --watch=false --include src/app/reader/reader-page.component.spec.ts`
Expected: PASS, with no change to the spec file.

The spec reads `.focus__body`, `.trail__item`, `.crumb`, `.banner`, `.banner__retry-button`,
`.banner__dismiss`, `.banner__back`, `[data-node-id]`, `[data-verb="EXPLAIN"]` and `a[href="/"]`.
The template above keeps every one. `.banner__back` stays on the full-page load-error branch only,
which is what the spec's two opposite assertions require: absent when a session is on screen
behind the banner, present when nothing is.

- [ ] **Step 5: Run every suite**

Run: `npm test -- --watch=false`
Expected: PASS.

Run: `npx playwright test`
Expected: PASS, 6 tests.

Run: `npm run build`
Expected: the build completes with no `anyComponentStyle` warning.

- [ ] **Step 6: Commit**

```bash
npm run format
git add frontend/src/app/reader/reader-page.component.ts frontend/src/app/reader/reader-page.component.spec.ts
git commit -m "feat(reader): give the reader the Candy two-column grid

The trail rail becomes a permanent left column at 768px and stays a
drawer below it. The card caps at 720px and the pair centres.

The design's third column at 4a is dropped. Every card in it — saved
terms, the quick check, the quota meter — needs a route that does not
exist. An empty column is worse than none, and the grid takes a third
track the day the routes arrive.

The session load state becomes the framed skeleton the design draws, so
the page does not jump when the first explanation lands."
```

---

## Task 8: The manual pass and the final check

**Files:** none. This task changes no code. It proves what no test can.

A re-skin is a visual change and the suites read the DOM. jsdom has no layout, so no unit test has ever seen a position, a wrap or a colour.

- [ ] **Step 1: Run every suite from a clean state**

From `frontend/`:

```bash
npm test -- --watch=false
npx playwright test
npm run build
npm run format:check
```

Expected: all four pass. Record the unit-test count.

From the repository root:

```bash
./gradlew build
```

Expected: 325 tests pass. No backend file changed, so this is a check and not a risk.

- [ ] **Step 2: Check that no component holds a raw colour**

From `frontend/`:

```bash
grep -rn "#[0-9a-fA-F]\{3,8\}\b" src/app --include=*.ts | grep -v "\.spec\.ts"
```

Expected: no output. `src/app/ui/palette.spec.ts` is the only file with a hex value, and it is excluded because it is a spec.

Any hit is a token that was missed. Replace it with the matching `var(--mt-…)`.

- [ ] **Step 3: Start the app**

Run: `npm start`

Open `http://localhost:4200`. The backend does not need to run: the catalog shows its load failure, which is one of the states to check.

To see the full reader, run the backend as `docs/deploy.md` describes, or drive the app through the Playwright stubs with `npx playwright test --headed --debug`.

- [ ] **Step 4: Check every state at three widths**

Use the browser's device toolbar at **390px**, **768px** and **1360px**. Check each row at each width.

| # | Screen | What to confirm |
|---|---|---|
| 1 | Catalog, loaded | Tiles are 1 / 2 / 3 across. The first tile's category is coral; the rest are muted. |
| 2 | Catalog, a tile hovered | It rises 2px and its shadow grows. |
| 3 | Catalog, a tile focused by Tab | A teal ring is visible on every tile. |
| 4 | Catalog, load in progress | Six skeleton tiles pulse. |
| 5 | Catalog, load failed | The red-pink card and a coral Retry pill. |
| 6 | Catalog, a category chosen | The pill fills teal. Only that category shows. At 390px the pill list scrolls sideways and does not wrap. |
| 7 | Catalog, a query matching nothing | The dashed card, the count of the catalogue, and a working "Clear the filters". |
| 8 | Catalog, a tile pressed | Every tile disables and the teal "Starting…" chip shows on the one pressed. |
| 9 | Reader, session load | The framed skeleton. The page does not jump when the text lands. |
| 10 | Reader, settled | The rail is a column at 768px and 1360px, and a drawer at 390px. The card caps at 720px and centres. |
| 11 | Reader, a phrase highlighted | The selection is amber. The picker opens under the phrase at 768px and above, and rises from the bottom at 390px. |
| 12 | Reader, a phrase near the card's bottom edge | The picker flips above the phrase and stays inside the card. |
| 13 | Reader, a phrase near the right edge | The picker's right edge stays inside the card. |
| 14 | Reader, picker open, Escape pressed | It closes and focus returns to the paragraph. |
| 15 | Reader, picker open, Tab held | Focus cycles through the four verbs and does not leave. |
| 16 | Reader, a stream in progress | The amber band travels. The picker is not on screen. The caret blinks. |
| 17 | Reader, explain failed | The red-pink card above the breadcrumb, with the coral retry and the ghost dismiss. |
| 18 | Top bar | The wordmark is coral. "Topics" is teal and underlined on the catalog, and muted on the reader. At 390px the nav item is gone. |
| 19 | Top bar, backend down | The dot is red and its title reads "Backend unreachable". |

- [ ] **Step 5: Check the reduced-motion path**

In the device toolbar, set **Emulate CSS prefers-reduced-motion: reduce**. Confirm:

- The skeleton bars stop pulsing but stay visible.
- The picker appears at once and does not slide.
- The stream band stops travelling; the track and the band stay visible.
- The caret stops blinking but stays visible.
- A tile still moves on hover — that is a transform under a transition, and the reduce rule shortens the transition rather than removing the change of state.

- [ ] **Step 6: Check the fonts loaded from the origin**

Open the browser's network panel and reload. Confirm:

- Four requests to `/fonts/*.woff2`, each `200`, each from `localhost`.
- No request to `fonts.googleapis.com` or `fonts.gstatic.com`.

- [ ] **Step 7: Record the result**

Add a short section to `docs/superpowers/specs/2026-08-06-candy-design-application-design.md` under §11, listing the date, the browser and its version, and any row of Step 4 that needed a fix.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/specs/2026-08-06-candy-design-application-design.md
git commit -m "docs: record the Candy manual pass

The suites read the DOM and unit tests run in jsdom, which has no layout
engine. Nineteen states at three widths are what proves the placement,
the wrap and the colour."
```

---

## Definition of done

1. `npm test -- --watch=false` passes.
2. `npx playwright test` passes, 6 tests.
3. `npm run build` passes with no `anyComponentStyle` warning.
4. `npm run format:check` passes.
5. `./gradlew build` passes from the repository root, 325 tests. No backend file changed.
6. Step 2 of Task 8 gives no output: no component holds a raw hex value.
7. The nineteen rows of Task 8 Step 4 are checked at 390px, 768px and 1360px.
8. `frontend/public/fonts/OFL.txt` exists and names both families.
