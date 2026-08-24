import { test, expect, type Page } from '@playwright/test';
import type { SessionView, TopicSummary } from '../src/app/core/models';
import { SEED, selectPhrase, stubCatalogueAndSession } from './support';

/**
 * What only a real browser can check about the Candy design.
 *
 * The unit suite runs in jsdom. jsdom has no layout engine and no media queries, so no unit test
 * can see a position, a breakpoint or a computed colour: every rect there is zero and every
 * `getComputedStyle` returns the declared string. Every claim below is about where an element
 * lands, which media query wins, or what a real font resolves to. So each one needs a real
 * Chromium, and each one lives here.
 *
 * This file asserts and it never compares an image. A screenshot baseline breaks on a font
 * rebuild, on a driver update and on the machine it runs on, and it says only "something moved".
 * An assertion says which number changed, and it says it in the failure line.
 *
 * `./support.ts` supplies every stub. Two fixtures below are local, and each one says why.
 *
 * Task 8 measured all of this by hand once. Two of its findings — a 19px jump at 390px, and
 * Escape sending focus to `<body>` — are now product fixes, and both have an assertion here.
 */

const WIDTHS = {
  narrow: { width: 390, height: 844 },
  mid: { width: 768, height: 1024 },
  wide: { width: 1360, height: 900 },
} as const;

/**
 * Five categories, so the pill row genuinely overflows a 390px screen. `stubCatalogueAndSession`
 * registers one topic in one category, which gives two pills and nothing to scroll.
 */
const MANY_TOPICS: TopicSummary[] = [
  {
    slug: 'quantum-physics',
    title: 'Quantum Physics',
    category: 'Physics',
    summary: 'Small things.',
  },
  {
    slug: 'cell-biology',
    title: 'Cell Biology',
    category: 'Biology',
    summary: 'The unit of life.',
  },
  {
    slug: 'supply-and-demand',
    title: 'Supply and Demand',
    category: 'Money',
    summary: 'Price and quantity.',
  },
  {
    slug: 'ancient-rome',
    title: 'Ancient Rome',
    category: 'History',
    summary: 'Republic to empire.',
  },
  {
    slug: 'grammar-basics',
    title: 'Grammar Basics',
    category: 'Language',
    summary: 'Parts of speech.',
  },
];

/**
 * The twelve categories the published catalogue actually returns, in its own alphabetical order.
 *
 * The design's sample data has four, and a filter row built for four overflowed the page at every
 * width once real data arrived. A fixture that mirrors production is what stops that returning.
 * Read from the live API on 2026-08-07; grow it when the catalogue grows.
 */
const LIVE_CATEGORIES = [
  'Astronomy',
  'Biology',
  'Chemistry',
  'Computer Science',
  'Earth Science',
  'Economics',
  'History',
  'Linguistics',
  'Mathematics',
  'Philosophy',
  'Physics',
  'Psychology',
] as const;

const EVERY_CATEGORY: TopicSummary[] = LIVE_CATEGORIES.map((category, i) => ({
  slug: `topic-${i}`,
  title: `Topic ${i}`,
  category,
  summary: 'A summary.',
}));

/**
 * A multi-sentence explanation, for the "the picker opens below the phrase" claim alone.
 *
 * `SEED` is one sentence. Its whole card renders under 260px tall, which is less than the
 * picker's own 240px height cap plus the offset down to the first line — so with that fixture the
 * picker flips above the phrase at every width, whichever phrase is chosen. That is arithmetic
 * for a short card, and it is not evidence about a claim that describes an ordinary explanation.
 */
const LONG_BODY =
  'Quantum mechanics is the fundamental physical theory that describes matter and light ' +
  'at the smallest scales. It replaces the deterministic laws of classical mechanics with ' +
  'probabilities, superpositions, and measurement-dependent outcomes. Physicists developed ' +
  'the theory in the early twentieth century to explain phenomena classical physics could ' +
  'not, such as the photoelectric effect and atomic spectra.';

function sessionWithBody(body: string): SessionView {
  return {
    sessionId: 's1',
    topicSlug: 'quantum-physics',
    rootNodeId: 'n0',
    currentNodeId: 'n0',
    nodes: [
      {
        nodeId: 'n0',
        parentNodeId: null,
        explanationKey: 'k0',
        span: '',
        verb: 'SEED',
        variant: 0,
        depth: 0,
      },
    ],
    explanations: { k0: body },
  };
}

/** The one stubbed session, addressed directly. The catalogue click adds nothing to a layout
 * claim, and it costs a second page load. */
async function gotoReader(page: Page, body: string = SEED): Promise<void> {
  await page.goto('/learn/s1');
  await page.getByText(body).waitFor();
}

/** A gate a test holds open, so a loading state stays on screen until it has been measured.
 * `route.fulfill` cannot otherwise be held. */
function gate(): { wait: Promise<void>; open: () => void } {
  let open!: () => void;
  const wait = new Promise<void>((resolve) => {
    open = resolve;
  });
  return { wait, open };
}

const picker = (page: Page) => page.locator('[role="dialog"]');

test('the tile grid takes one column at 390px, two at 768px and three at 1360px', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await page.goto('/');
  await page.locator('.topic__button').first().waitFor();

  for (const size of Object.values(WIDTHS)) {
    await page.setViewportSize(size);
    // The track count, not the tile count: the grid declares its columns, and one stubbed topic
    // still resolves `repeat(3, 1fr)` to three tracks.
    const columns = await page
      .locator('.topics')
      .evaluate((el) => getComputedStyle(el).gridTemplateColumns.trim().split(/\s+/).length);
    const expected = size.width >= 1120 ? 3 : size.width >= 768 ? 2 : 1;
    expect(columns, `column count at ${size.width}px`).toBe(expected);
  }
});

test('the category pill row scrolls sideways at 390px rather than wrapping', async ({ page }) => {
  await stubCatalogueAndSession(page);
  // Added after the shared stub, so this handler wins: Playwright runs the most recent route
  // first. Five categories give six pills, which is more than 390px holds.
  await page.route('**/api/catalog/topics*', (route) => route.fulfill({ json: MANY_TOPICS }));
  await page.setViewportSize(WIDTHS.narrow);
  await page.goto('/');
  await page.locator('.topic__button').first().waitFor();

  const cats = page.locator('.catalog__cats');
  expect(await cats.evaluate((el) => getComputedStyle(el).overflowX)).toBe('auto');
  const box = await cats.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }));
  expect(
    box.scroll,
    'the pill row is wider than its box, so there is something to scroll',
  ).toBeGreaterThan(box.client);
  // A wrapped row would push the first tile below the fold. Every pill on one line proves it did
  // not wrap, which a computed `overflow-x` alone does not.
  const tops = await page
    .locator('.catalog__cat')
    .evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().top)));
  expect(new Set(tops).size, 'every pill sits on one row').toBe(1);
});

test('nothing overflows the page sideways at any width, with every real category', async ({
  page,
}) => {
  // The guard that was missing. The filter row was built for the design's four sample categories
  // and shipped against the catalogue's twelve: the pills ran off the right of the screen at
  // 1360px, and the search field — the only item that could shrink — collapsed to nothing.
  // Neither fault was visible to a suite that only measured 390px.
  await stubCatalogueAndSession(page);
  await page.route('**/api/catalog/topics*', (route) => route.fulfill({ json: EVERY_CATEGORY }));

  for (const size of Object.values(WIDTHS)) {
    await page.setViewportSize(size);
    await page.goto('/');
    await page.locator('.topic__button').first().waitFor();

    const doc = await page.evaluate(() => ({
      scroll: document.documentElement.scrollWidth,
      client: document.documentElement.clientWidth,
    }));
    expect(doc.scroll, `the page does not scroll sideways at ${size.width}px`).toBeLessThanOrEqual(
      doc.client,
    );
  }
});

test('the search field stays readable at every width, with every real category', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await page.route('**/api/catalog/topics*', (route) => route.fulfill({ json: EVERY_CATEGORY }));

  for (const size of Object.values(WIDTHS)) {
    await page.setViewportSize(size);
    await page.goto('/');
    await page.locator('.topic__button').first().waitFor();

    const box = await page.locator('#topic-filter').boundingBox();
    // 260px holds roughly thirty characters at 15px, so a learner sees what they typed. The
    // field measured about 40px before the fix, which showed no character at all.
    expect(box!.width, `the search field's width at ${size.width}px`).toBeGreaterThan(260);
  }
});

test('the category pills wrap at 1360px, so every category stays reachable', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.route('**/api/catalog/topics*', (route) => route.fulfill({ json: EVERY_CATEGORY }));
  await page.setViewportSize(WIDTHS.wide);
  await page.goto('/');
  await page.locator('.topic__button').first().waitFor();

  const cats = page.locator('.catalog__cats');
  const box = await cats.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }));
  expect(
    box.scroll,
    'the pill row fits its box, so nothing hides off the right',
  ).toBeLessThanOrEqual(box.client);

  // Wrapped, not squeezed: thirteen pills over more than one row, and every pill still full width.
  const tops = await page
    .locator('.catalog__cat')
    .evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().top)));
  expect(tops.length, 'All plus twelve categories').toBe(13);
  expect(new Set(tops).size, 'the pills use more than one row').toBeGreaterThan(1);
});

test('the wordmark is Fredoka at 24px and weight 600', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await page.goto('/');
  await page.locator('.topic__button').first().waitFor();

  const mark = await page.locator('.bar__mark').evaluate((el) => {
    const s = getComputedStyle(el);
    return { size: s.fontSize, weight: s.fontWeight, family: s.fontFamily.split(',')[0] };
  });
  // 24px at weight 600 is large text, which is the whole reason the wordmark may use the fill
  // coral. Shrink it and the colour stops clearing AA — see §3.4 rule 2.
  expect(mark.size).toBe('24px');
  expect(mark.weight).toBe('600');
  expect(mark.family.replace(/["']/g, '')).toBe('Fredoka');
});

test('the trail rail is a permanent column at 768px and a drawer at 390px', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await gotoReader(page);

  for (const size of Object.values(WIDTHS)) {
    await page.setViewportSize(size);
    const toggle = await page
      .locator('.trail__toggle')
      .evaluate((el) => getComputedStyle(el).display);
    const head = await page.locator('.trail__head').evaluate((el) => getComputedStyle(el).display);
    if (size.width < 768) {
      expect(toggle, `the drawer toggle is on screen at ${size.width}px`).not.toBe('none');
      expect(head, `the rail heading is hidden at ${size.width}px`).toBe('none');
    } else {
      expect(toggle, `the rail is a column at ${size.width}px, so no toggle`).toBe('none');
      expect(head, `the rail heading is on screen at ${size.width}px`).not.toBe('none');
    }
  }
});

test('the reader does not move down when the loaded session replaces the skeleton', async ({
  page,
}) => {
  // Task 8 measured a 19px drop at 390px. Below 768px the rail stacks over the card, and the
  // loaded rail was taller than the placeholder that stood in for it.
  const seedView = sessionWithBody(SEED);
  const jumps: Record<number, number> = {};

  for (const size of Object.values(WIDTHS)) {
    const held = gate();
    await page.unroute('**/api/sessions/s1').catch(() => undefined);
    await page.route('**/api/sessions/s1', async (route) => {
      await held.wait;
      route.fulfill({ json: seedView });
    });
    await page.setViewportSize(size);
    await page.goto('/learn/s1');
    await page.locator('.focus-skeleton').waitFor();

    const before = await page.locator('.reader__main').boundingBox();
    held.open();
    await page.getByText(SEED).waitFor();
    const after = await page.locator('.reader__main').boundingBox();
    jumps[size.width] = Math.abs((after?.y ?? 0) - (before?.y ?? 0));
  }

  for (const [width, jump] of Object.entries(jumps)) {
    expect(jump, `the main column stays put at ${width}px`).toBeLessThanOrEqual(1);
  }
});

test('the picker opens below a phrase near the top of the card', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.route('**/api/sessions/s1', (route) =>
    route.fulfill({ json: sessionWithBody(LONG_BODY) }),
  );
  await page.setViewportSize(WIDTHS.mid);
  await gotoReader(page, LONG_BODY);

  await selectPhrase(page, 'focus-body', 'Quantum mechanics');
  await picker(page).waitFor();
  const body = await page.locator('.focus__body').boundingBox();
  const box = await picker(page).boundingBox();
  expect(box!.y, 'the picker sits under the phrase when there is room below it').toBeGreaterThan(
    body!.y,
  );
});

test('the picker flips above a phrase near the bottom, and stays inside the card', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);

  for (const size of [WIDTHS.mid, WIDTHS.wide]) {
    await page.setViewportSize(size);
    await gotoReader(page);
    // The last words of the seed sit on the paragraph's last line, over the hint and the card's
    // own bottom padding — the least room below of anywhere in the card.
    await selectPhrase(page, 'focus-body', 'matter and light.');
    await picker(page).waitFor();
    const body = await page.locator('.focus__body').boundingBox();
    const box = await picker(page).boundingBox();
    const card = await page.locator('.focus').boundingBox();

    expect(box!.y, `the picker opens above the phrase at ${size.width}px`).toBeLessThan(body!.y);
    expect(
      box!.y,
      `the picker's top stays inside the card at ${size.width}px`,
    ).toBeGreaterThanOrEqual(card!.y - 1);
    expect(
      box!.y + box!.height,
      `the picker's bottom stays inside the card at ${size.width}px`,
    ).toBeLessThanOrEqual(card!.y + card!.height + 1);
    // A 3px tolerance and not 0: the card and the picker each carry a 2px border of their own,
    // and the clamp measures the card's content width. A sub-border difference is not an overflow.
    expect(
      box!.x + box!.width - (card!.x + card!.width),
      `the picker's right edge stays inside the card at ${size.width}px`,
    ).toBeLessThanOrEqual(3);
    expect(
      box!.x,
      `the picker's left edge stays inside the card at ${size.width}px`,
    ).toBeGreaterThanOrEqual(card!.x - 3);

    await page.keyboard.press('Escape');
    await expect(picker(page)).toHaveCount(0);
  }
});

test('the picker is a bottom sheet at 390px and an anchored popover at 1360px', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);

  await page.setViewportSize(WIDTHS.narrow);
  await gotoReader(page);
  await selectPhrase(page, 'focus-body', 'Quantum mechanics');
  await picker(page).waitFor();
  const narrow = await picker(page).evaluate((el) => {
    const s = getComputedStyle(el);
    return { position: s.position, bottom: s.bottom };
  });
  expect(narrow.position, 'a popover beside a thumb covers the text it explains').toBe('fixed');
  expect(narrow.bottom, 'the sheet sits flush with the bottom edge').toBe('0px');
  await page.keyboard.press('Escape');
  await expect(picker(page)).toHaveCount(0);

  // A fresh load rather than a resize: a resize followed at once by a drag was flaky, most likely
  // over leftover selection state from the sheet's own dismissal.
  await page.setViewportSize(WIDTHS.wide);
  await gotoReader(page);
  await selectPhrase(page, 'focus-body', 'Quantum mechanics');
  await picker(page).waitFor();
  expect(
    await picker(page).evaluate((el) => getComputedStyle(el).position),
    'on a wide screen the picker is anchored to the phrase inside the card',
  ).toBe('absolute');
});

test('Escape closes the picker and returns focus to the body paragraph', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await gotoReader(page);
  await selectPhrase(page, 'focus-body', 'Quantum mechanics');
  await picker(page).waitFor();

  await page.keyboard.press('Escape');
  await expect(picker(page)).toHaveCount(0);

  const landed = await page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    return {
      testId: el?.getAttribute('data-testid') ?? null,
      isBody: el === document.body,
      // Measured, not assumed. Escape is a key press, so Chromium does treat this return as
      // focus-visible — the rule of thumb that programmatic focus never matches is wrong here.
      // The ring is suppressed for this one element, and this reads the rendered result.
      focusVisible: el?.matches(':focus-visible') ?? false,
      outlineStyle: el ? getComputedStyle(el).outlineStyle : null,
    };
  });
  expect(landed.isBody, 'focus must not fall back to <body> — the learner loses their place').toBe(
    false,
  );
  expect(landed.testId, 'focus lands on the paragraph the learner was reading').toBe('focus-body');
  expect(landed.focusVisible, 'Chromium does count this return as focus-visible').toBe(true);
  expect(landed.outlineStyle, 'no ring is drawn around 62ch of prose').toBe('none');
});

test('every control still draws its focus ring', async ({ page }) => {
  // The paragraph is the one element whose ring is suppressed. This is the guard that the
  // suppression did not spread: a control that loses its ring is a control a keyboard cannot find.
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await page.goto('/');
  await page.locator('.topic__button').first().waitFor();

  await page.locator('#topic-filter').focus();
  await page.keyboard.press('Tab');
  const ring = await page.evaluate(() => {
    const s = getComputedStyle(document.activeElement as HTMLElement);
    return { color: s.outlineColor, width: s.outlineWidth, offset: s.outlineOffset };
  });
  expect(ring.color, 'the ring is teal').toBe('rgb(15, 118, 110)');
  expect(ring.width).toBe('3px');
  expect(ring.offset).toBe('2px');
});

test('Tab and Shift+Tab cycle inside the picker and never leave it', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await gotoReader(page);
  await selectPhrase(page, 'focus-body', 'Quantum mechanics');
  await picker(page).waitFor();

  const verbNow = () => page.evaluate(() => document.activeElement?.getAttribute('data-verb'));
  const insideNow = () =>
    page.evaluate(
      () => document.querySelector('[role="dialog"]')?.contains(document.activeElement) ?? false,
    );

  const forward: (string | null)[] = [];
  for (let i = 0; i < 5; i++) {
    expect(await insideNow(), `focus stays inside the picker on Tab press ${i}`).toBe(true);
    forward.push(await verbNow());
    await page.keyboard.press('Tab');
  }
  expect(new Set(forward.slice(0, 4)).size, 'four distinct verbs are reachable').toBe(4);
  expect(forward[4], 'the fifth Tab wraps back to the first verb').toBe(forward[0]);

  // The fifth press left focus on the second verb, so one Shift+Tab walks back to the first.
  await page.keyboard.press('Shift+Tab');
  expect(await verbNow(), 'Shift+Tab walks back one verb').toBe(forward[0]);

  // And the backward wrap. Angular builds a full key name from the modifiers held, so
  // `keydown.tab` alone never fires while Shift is down and this half needs its own binding.
  await page.keyboard.press('Shift+Tab');
  expect(await insideNow(), 'Shift+Tab keeps focus inside the picker').toBe(true);
  expect(await verbNow(), 'Shift+Tab from the first verb wraps to the last').toBe(forward[3]);
});

test('every font comes from this origin, and none from a Google Fonts host', async ({ page }) => {
  await stubCatalogueAndSession(page);
  const requests: string[] = [];
  const statuses: Record<string, number> = {};
  page.on('request', (req) => requests.push(req.url()));
  page.on('response', (res) => {
    if (res.url().endsWith('.woff2')) statuses[res.url()] = res.status();
  });

  await page.goto('/');
  await page.locator('.topic__button').first().waitFor();
  // A face is fetched by the layout that first needs its weight, so the page is given a moment.
  await page.waitForTimeout(500);

  const google = requests.filter((u) => /fonts\.(googleapis|gstatic)\.com/.test(new URL(u).host));
  expect(google, 'no reader is exposed to a third-party font host').toEqual([]);

  const faces = [...new Set(requests.filter((u) => u.includes('/fonts/') && u.endsWith('.woff2')))];
  for (const url of faces) {
    expect(new URL(url).hostname, `${url} is same-origin`).toBe('localhost');
    expect(statuses[url], `${url} answered 200`).toBe(200);
  }

  // Two faces load and not four, and that is the design working. Each `@font-face` keeps the
  // design's own `unicode-range`, and the two `-latin-ext` files cover glyphs — Central European
  // and Vietnamese diacritics among them — that English content never needs. A correct browser
  // skips them. That is the saving §3.1 describes, and not a missing file.
  const names = faces.map((u) => u.split('/').pop());
  expect(names, 'the base Figtree face loads').toContain('figtree-latin.woff2');
  expect(names, 'the base Fredoka face loads').toContain('fredoka-latin.woff2');
  expect(names, 'English content skips the extended Latin faces').not.toContain(
    'figtree-latin-ext.woff2',
  );
});

test('the mark draws at 28px, left of the wordmark', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await page.goto('/');
  await page.locator('.topic__button').first().waitFor();

  const box = await page.locator('.bar__mark app-logo-mark').boundingBox();

  expect(box?.width).toBe(28);
  expect(box?.height).toBe(28);
});

test('the mark resolves its palette tokens inside the inline SVG', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await page.goto('/');
  await page.locator('.topic__button').first().waitFor();

  // `fill="var(--mt-coral)"` is a presentation attribute, and a presentation attribute maps to a
  // CSS declaration, so `var()` inside one has to resolve. jsdom cannot prove that — it returns
  // the declared string — and a mark whose fill resolves to nothing draws as black. This is the
  // one assertion that catches it.
  const fills = await page
    .locator('.bar__mark app-logo-mark rect')
    .evaluateAll((els) => els.map((el) => getComputedStyle(el).fill));

  expect(fills).toEqual(['rgb(228, 242, 237)', 'rgb(255, 93, 93)', 'rgb(15, 118, 110)']);
});

test('the browser can fetch every icon the page declares', async ({ page, request }) => {
  await stubCatalogueAndSession(page);
  await page.goto('/');

  const hrefs = await page
    .locator('head link[rel~="icon"], head link[rel~="apple-touch-icon"]')
    .evaluateAll((els) => els.map((el) => (el as HTMLLinkElement).href));

  // A declared icon that 404s is invisible in development, because the browser falls back to
  // `/favicon.ico` without a word. Only a fetch of each declared href finds it.
  expect(hrefs.length).toBe(3);
  for (const href of hrefs) {
    expect((await request.get(href)).status(), href).toBe(200);
  }
});

test('the tab carries the product name, not the generator default', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.goto('/');

  expect(await page.title()).toBe('mytetz');
});
