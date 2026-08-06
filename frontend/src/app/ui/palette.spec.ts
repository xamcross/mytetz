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
