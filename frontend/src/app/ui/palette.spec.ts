import { readFileSync } from 'node:fs';

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

/**
 * Relative luminance of a colour in the sRGB colour space. Each channel is normalised, then
 * linearised, then weighted.
 *
 * The branch threshold is 0.04045, the value in the current sRGB specification. WCAG
 * publishes 0.03928, a value from an older sRGB draft. No channel in this palette falls
 * between the two values, so both thresholds give the same answer here.
 */
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

/**
 * Reads the `:root` token block of a stylesheet and returns each `--token: value` pair.
 *
 * `guides.css` cannot import `styles.css` (see its header comment), so a change to a coral
 * token there can silently drift from this palette. This reader lets a test resolve the real
 * `var(--…)` value from the shipped file, instead of a hex value copied into the test.
 */
function readRootTokens(css: string): Record<string, string> {
  const root = css.match(/:root\s*\{([^}]*)\}/);
  if (!root) throw new Error('the stylesheet must declare a :root block');
  const tokens: Record<string, string> = {};
  for (const [, name, value] of root[1].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    tokens[name] = value.trim();
  }
  return tokens;
}

/** Returns the body of the first CSS rule for `selector`, for example `.bar__cta`. */
function readRule(css: string, selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const rule = css.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`));
  if (!rule) throw new Error(`the stylesheet must declare a rule for ${selector}`);
  return rule[1];
}

/** Returns the value of one declaration, for example `background`, inside a rule body. */
function readDeclaration(rule: string, property: string): string {
  const escaped = property.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const declaration = rule.match(new RegExp(`(?:^|;|\\{)\\s*${escaped}\\s*:\\s*([^;]+);`));
  if (!declaration) throw new Error(`the rule must declare ${property}`);
  return declaration[1].trim();
}

/** Resolves a declaration value to a hex colour, following one `var(--token)` reference. */
function resolveColor(value: string, tokens: Record<string, string>): string {
  const ref = value.match(/^var\((--[\w-]+)\)$/);
  if (!ref) return value;
  const resolved = tokens[ref[1]];
  if (!resolved) throw new Error(`:root must declare ${ref[1]}`);
  return resolved;
}

describe('the Candy palette', () => {
  it('computes a contrast ratio the way WCAG 2.2 defines it', () => {
    // Two anchors with an answer that does not depend on this palette. Without them a broken
    // formula would agree with itself and every assertion below would pass for the wrong reason.
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 2);
    expect(contrast('#777777', '#ffffff')).toBeCloseTo(4.48, 2);
  });

  const normal: ReadonlyArray<[string, string, string]> = [
    ['prose on the card', PALETTE.prose, PALETTE.surface],
    ['prose on the sunk surface', PALETTE.prose, PALETTE.sunk],
    ['ink on the card', PALETTE.ink, PALETTE.surface],
    ['ink on the page', PALETTE.ink, PALETTE.page],
    ['muted on the page', PALETTE.muted, PALETTE.page],
    ['muted on the card', PALETTE.muted, PALETTE.surface],
    ['muted on the sunk surface', PALETTE.muted, PALETTE.sunk],
    ['muted on a chip', PALETTE.muted, PALETTE.chip],
    ['small coral text on the card', PALETTE.coralText, PALETTE.surface],
    ['small coral text on the page', PALETTE.coralText, PALETTE.page],
    ['small coral text on the sunk surface', PALETTE.coralText, PALETTE.sunk],
    ['teal on the card', PALETTE.teal, PALETTE.surface],
    ['teal on the page', PALETTE.teal, PALETTE.page],
    ['white on a small coral control', PALETTE.surface, PALETTE.coralPress],
    ['white on a teal control', PALETTE.surface, PALETTE.teal],
    ['amber ink on the amber surface', PALETTE.amberInk, PALETTE.amberBg],
    ['amber ink 2 on the amber surface', PALETTE.amberInk2, PALETTE.amberBg],
    ['amber ink on the amber fill', PALETTE.amberInk, PALETTE.amber],
    ['error ink on the error surface', PALETTE.errInk, PALETTE.errBg],
    ['error ink 2 on the error surface', PALETTE.errInk2, PALETTE.errBg],
    ['the eyebrow on the teal trail row', PALETTE.chip, PALETTE.teal],
  ];

  for (const [name, fg, bg] of normal) {
    it(`reaches AA for normal text: ${name}`, () => {
      expect(contrast(fg, bg)).toBeGreaterThanOrEqual(AA_NORMAL);
    });
  }

  const large: ReadonlyArray<[string, string, string]> = [
    ['the 24px coral wordmark on the bar', PALETTE.coral, PALETTE.surface],
    ['white on the teal trail row', PALETTE.surface, PALETTE.teal],
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
    // teal-pale on teal fails AA for normal text, which is why the eyebrow uses chip instead.
    expect(contrast(PALETTE.tealPale, PALETTE.teal)).toBeLessThan(AA_NORMAL);
  });

  it('separates a coral control from an error by fill and by surface, not by hue', () => {
    // The spec once said the error family "is never coral". That wording was wrong. The coral
    // fill and the error ink measure about 1.09:1 against each other — as colours they are the
    // same. Hue was never the discriminator, and no palette change can make it one.
    expect(contrast(PALETTE.coralPress, PALETTE.errInk)).toBeLessThan(1.5);

    // What genuinely holds. A coral primary is a filled pill with white text. An error is dark
    // red text on a pale pink card with a pink border. The surfaces are far apart, and both text
    // pairs clear AA, so the two read apart for every reader — including a reader with
    // red-green colour blindness, because fill against text carries the difference and not hue.
    expect(contrast(PALETTE.errBg, PALETTE.coralPress)).toBeGreaterThanOrEqual(AA_NORMAL);
    expect(contrast(PALETTE.surface, PALETTE.coralPress)).toBeGreaterThanOrEqual(AA_NORMAL);
    expect(contrast(PALETTE.errInk, PALETTE.errBg)).toBeGreaterThanOrEqual(AA_NORMAL);
  });

  it('draws the browser icon from this palette, because a file cannot read a token', () => {
    const icon = readFileSync('public/icon.svg', 'utf8');

    // `styles.css` says only it and this file state a colour. `public/icon.svg` is the one
    // exception the rule cannot cover: the browser loads it as a document of its own, with no
    // stylesheet, so `var(--mt-coral)` resolves to nothing and the shape vanishes. The file must
    // therefore hold the hex. This test is what keeps the two from drifting apart.
    const hexes = new Set(
      (icon.match(/#[0-9a-f]{3,6}/gi) ?? []).map((h: string) => h.toLowerCase()),
    );

    expect(hexes).toEqual(new Set([PALETTE.chip, PALETTE.coral, PALETTE.teal]));
    expect(icon).not.toContain('var(--mt-');
  });
});

describe('the non-text contrast of the controls (issue #103)', () => {
  // `src/styles.css` and `focus-card.component.ts` are read directly, and their values are never
  // copied into this file. A change to the real token, or to the real rule, is what makes each
  // test below pass or fail — not a value someone remembered to update here.
  const css = readFileSync('src/styles.css', 'utf8');
  const tokens = readRootTokens(css);

  it('gives --mt-edge a boundary of 3:1 or more against --mt-surface', () => {
    if (!('--mt-edge' in tokens)) throw new Error(':root must declare --mt-edge');
    expect(contrast(tokens['--mt-edge'], tokens['--mt-surface'])).toBeGreaterThanOrEqual(AA_LARGE);
  });

  it('gives the indeterminate progress band a boundary of 3:1 or more against its track', () => {
    // The band and its track live in focus-card.component.ts, next to the animation they belong
    // to, and not in styles.css. Both declarations still name a global token, so the values here
    // resolve against the same :root block as every other pair in this file.
    const componentCss = readFileSync('src/app/reader/focus-card.component.ts', 'utf8');
    const track = resolveColor(
      readDeclaration(readRule(componentCss, '.focus__track'), 'background'),
      tokens,
    );
    const band = resolveColor(
      readDeclaration(readRule(componentCss, '.focus__band'), 'background'),
      tokens,
    );
    expect(contrast(track, band)).toBeGreaterThanOrEqual(AA_LARGE);
  });

  /**
   * Round 2 of issue #103. The chosen and the unchosen quiz option share one edge, --mt-edge on
   * --mt-surface, at 3.26:1. Their difference is the check glyph and the heavier edge, not a
   * colour, so the glyph itself is the one graphic that must still clear 3:1 against the fill it
   * sits on.
   *
   * No pair of edge colours reaches 3:1 between the two states themselves. The unchosen edge is
   * #4a9d84. An amber dark enough to reach 3:1 against that value has to go past #573d00, a
   * near-black brown that no longer reads as amber at all — computed at 3.11:1, only just over
   * the line, and already unrecognisable as the system's amber. The glyph-against-fill pair below
   * is the one comparison this design can actually keep in the amber family.
   */
  it('gives the check glyph a boundary of 3:1 or more against the chosen option it sits on', () => {
    const componentCss = readFileSync('src/app/assess/quiz-panel.component.ts', 'utf8');
    const glyph = resolveColor(
      readDeclaration(readRule(componentCss, '.quiz-panel__check'), 'color'),
      tokens,
    );
    const fill = resolveColor(
      readDeclaration(readRule(componentCss, '.quiz-panel__option--chosen'), 'background'),
      tokens,
    );
    expect(contrast(glyph, fill)).toBeGreaterThanOrEqual(AA_LARGE);
  });
});

describe('the guide page buttons', () => {
  // `guides.css` cannot import `styles.css` (see its header comment), so this suite reads the
  // real shipped file and resolves each `var(--…)` value from its own `:root` block. A test that
  // copies the hex values into the test would prove nothing about the file that ships.
  const css = readFileSync('public/guides/guides.css', 'utf8');
  const tokens = readRootTokens(css);

  const ctas: ReadonlyArray<string> = ['.bar__cta', '.start__cta'];

  for (const selector of ctas) {
    it(`gives ${selector} a text contrast of 4.5:1 or more against its fill`, () => {
      const rule = readRule(css, selector);
      const fill = resolveColor(readDeclaration(rule, 'background'), tokens);
      const text = resolveColor(readDeclaration(rule, 'color'), tokens);

      expect(contrast(text, fill)).toBeGreaterThanOrEqual(AA_NORMAL);
    });
  }
});
