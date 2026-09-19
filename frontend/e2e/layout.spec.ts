import { test, expect, type Page } from '@playwright/test';
import type {
  QuizResultView,
  QuizTemplateView,
  SessionView,
  TopicSummary,
} from '../src/app/core/models';
import {
  CHILD,
  SEED,
  accountView,
  explainedView,
  mockExplainRefusal,
  mockExplainStream,
  mockQuiz,
  openQuantumPhysicsSession,
  selectPhrase,
  sseFrame,
  stubAccount,
  stubCatalogueAndSession,
} from './support';

/** A verb inside the picker, and only inside it. See `learn.spec.ts`'s own copy of this helper.
 * That file states why the scope is load-bearing. */
function verb(page: Page, name: string) {
  return page.locator('[role="dialog"]').getByRole('button', { name, exact: true });
}

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
    status: 'ACTIVE',
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
  await page.locator('.topic__tile').first().waitFor();

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
  await page.locator('.topic__tile').first().waitFor();

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
    await page.locator('.topic__tile').first().waitFor();

    const doc = await page.evaluate(() => ({
      scroll: document.documentElement.scrollWidth,
      client: document.documentElement.clientWidth,
    }));
    expect(doc.scroll, `the page does not scroll sideways at ${size.width}px`).toBeLessThanOrEqual(
      doc.client,
    );
  }
});

test('the introduction, the filter row and the first tile all fit at 412px', async ({ page }) => {
  // Issue #38: the catalogue gained an introduction paragraph above the filter row. This checks
  // that the addition does not push the filter row or the first tile off a common phone width.
  await stubCatalogueAndSession(page);
  await page.setViewportSize({ width: 412, height: 915 });
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();

  await expect(page.locator('.catalog__intro')).toBeVisible();

  const boxes = {
    'the introduction': await page.locator('.catalog__intro').boundingBox(),
    'the filter row': await page.locator('.catalog__filter').boundingBox(),
    'the first tile': await page.locator('.topic__tile').first().boundingBox(),
  };
  for (const [name, box] of Object.entries(boxes)) {
    expect(box, `${name} is on screen`).toBeTruthy();
    expect(box!.x, `${name} starts inside the 412px viewport`).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width, `${name} fits inside the 412px viewport`).toBeLessThanOrEqual(412);
  }

  const doc = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(doc.scroll, 'the page does not scroll sideways at 412px').toBeLessThanOrEqual(doc.client);
});

test('the search field stays readable at every width, with every real category', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await page.route('**/api/catalog/topics*', (route) => route.fulfill({ json: EVERY_CATEGORY }));

  for (const size of Object.values(WIDTHS)) {
    await page.setViewportSize(size);
    await page.goto('/');
    await page.locator('.topic__tile').first().waitFor();

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
  await page.locator('.topic__tile').first().waitFor();

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
  await page.locator('.topic__tile').first().waitFor();

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

/**
 * Round 2 of issue #104. `animate.leave` keeps the stream box mounted for the whole close
 * animation, after the review's own trigger for animation A already runs: the session refreshes,
 * the body lands, and `streamingText` clears. The three tests below confirm the moment stays
 * safe with the animation on.
 */

test("the card's height changes once, not twice, when an answer lands", async ({ page }) => {
  // A short streamed preview and a much longer landed body, on purpose: before `onStreamingLeave`
  // took the box out of flow, the settled body landed at its own new height in one reflow, and
  // the box leaving 160ms later shrank the card in a second, separate reflow — measured at
  // roughly 402px, then 497px, then 417px for this exact fixture, before the fix. A short and a
  // long text this different cannot hide that behind a coincidentally equal line count.
  const landedBody =
    'Quantum mechanics is the fundamental physical theory that describes matter and light ' +
    'at the smallest scales. It replaces the deterministic laws of classical mechanics with ' +
    'probabilities, superpositions, and measurement-dependent outcomes. Physicists developed ' +
    'the theory in the early twentieth century to explain phenomena classical physics could ' +
    'not, such as the photoelectric effect and atomic spectra.';
  const shortPreview = 'A short answer.';

  await stubCatalogueAndSession(page, explainedView(landedBody));
  const stream = await mockExplainStream(page, 's1');
  await page.setViewportSize(WIDTHS.wide);
  await openQuantumPhysicsSession(page);

  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await verb(page, 'Explain it').click();
  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: shortPreview }));
  await page.locator('.focus__streaming').waitFor();
  const midStream = await page.locator('.focus').boundingBox();

  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: true }));
  await stream.close();

  // The instant the new text is on screen — the streaming box is still in the DOM here, per
  // animate.leave, so this is the moment a second, separate reflow would show if the box still
  // counted as a normal flex item.
  await page.getByText(/photoelectric effect/).waitFor();
  const justLanded = await page.locator('.focus').boundingBox();

  await expect(page.locator('.focus__streaming')).toHaveCount(0);
  const settled = await page.locator('.focus').boundingBox();

  expect(
    Math.abs((justLanded?.height ?? 0) - (settled?.height ?? 0)),
    'the card is already at its settled height the instant the new body is on screen',
  ).toBeLessThanOrEqual(1);
  expect(
    justLanded?.height,
    'the one real change in height happens here, between mid-stream and landed',
  ).not.toBeCloseTo(midStream?.height ?? 0, 0);
});

test('the reader main column does not move when an answer lands', async ({ page }) => {
  await stubCatalogueAndSession(page, explainedView(LONG_BODY));
  const stream = await mockExplainStream(page, 's1');
  await page.setViewportSize(WIDTHS.wide);
  await openQuantumPhysicsSession(page);

  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await verb(page, 'Explain it').click();
  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: 'A short answer.' }));
  await page.locator('.focus__streaming').waitFor();
  const before = await page.locator('.reader__main').boundingBox();

  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: true }));
  await stream.close();
  await expect(page.locator('.focus__streaming')).toHaveCount(0);

  const after = await page.locator('.reader__main').boundingBox();
  expect(
    Math.abs((after?.y ?? 0) - (before?.y ?? 0)),
    'the main column stays put while an answer lands, the same claim criterion 1 makes for the skeleton swap',
  ).toBeLessThanOrEqual(1);
});

test('the leaving stream box cannot be selected, and the status paragraph changes exactly twice', async ({
  page,
}) => {
  await stubCatalogueAndSession(page, explainedView(CHILD));
  const stream = await mockExplainStream(page, 's1');
  await openQuantumPhysicsSession(page);

  // Collects every distinct text the status paragraph shows, from before the stream starts to
  // well after the answer lands — the same claim `focus-card.component.spec.ts`'s own "announces
  // one full stream exactly two times" makes in jsdom, checked here with the leave animation
  // actually running.
  await page.evaluate(() => {
    const w = window as unknown as { __statusChanges: string[] };
    w.__statusChanges = [];
    const el = document.querySelector('.focus__stream-status')!;
    let previous = el.textContent?.trim() ?? '';
    new MutationObserver(() => {
      const text = el.textContent?.trim() ?? '';
      if (text !== previous) {
        previous = text;
        w.__statusChanges.push(text);
      }
    }).observe(el, { characterData: true, childList: true, subtree: true });
  });

  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await verb(page, 'Explain it').click();
  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: CHILD }));
  await page.locator('.focus__streaming').waitFor();

  // The leaving box keeps `user-select: none` — the same rule that already stops any selection
  // over streamed prose today — so a drag that reaches it still cannot select it, animation or
  // not. `onSelectionChanged` is bound to `.focus__body` alone besides, so a mouseup that lands
  // on the streaming box could not reach it even if a selection did form there.
  const userSelect = await page
    .locator('.focus__streaming')
    .evaluate((el) => getComputedStyle(el).userSelect);
  expect(userSelect, 'the streamed box stays unselectable while it streams').toBe('none');

  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: true }));
  await stream.close();
  // `page.getByTestId('focus-body')`, and not `page.getByText(...)`: this claim is about the
  // landed answer, and the leaving stream box holds the same words for the whole close
  // animation, so a page-wide text search can match both and fail with a strict mode violation.
  await expect(page.getByTestId('focus-body')).toContainText('subatomic scale');

  // Still frozen and still unselectable while it fades — the moment this issue's round 2 adds.
  const userSelectWhileLeaving = await page
    .locator('.focus__streaming')
    .evaluate((el) => getComputedStyle(el).userSelect);
  expect(userSelectWhileLeaving, 'the leaving box stays unselectable while it fades').toBe('none');

  await expect(page.locator('.focus__streaming')).toHaveCount(0);

  const changes = await page.evaluate(
    () => (window as unknown as { __statusChanges: string[] }).__statusChanges,
  );
  expect(changes, 'exactly two announcements for one stream, with the animation on').toEqual([
    'The explanation is on its way.',
    'The explanation is ready.',
  ]);
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
  // Animation D moves the picker with `transform` while it opens. Waiting for its entrance
  // animation to finish keeps this a claim about the settled layout, and not about a box that is
  // still sliding into place.
  await expect(picker(page)).toHaveCSS('opacity', '1');
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
    // Same wait as the test above, for the same reason: the entrance animation moves the box
    // with `transform` while it plays, and this claim is about where the box settles.
    await expect(picker(page)).toHaveCSS('opacity', '1');
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

/**
 * Round 2 of issue #104. `animate.leave` keeps the picker in the DOM for the whole close
 * animation, and before this issue it left at once. The three tests below check that a learner
 * cannot act on a picker that is on its way out.
 */

test('clicking a verb twice in fast succession sends exactly one explain request', async ({
  page,
}) => {
  await stubCatalogueAndSession(page, explainedView(CHILD));
  const stream = await mockExplainStream(page, 's1');
  // Counts every attempt to reach the explain endpoint. Registered after `mockExplainStream`'s
  // own `addInitScript`, so it wraps that shim rather than being short-circuited by it — the
  // shim answers the explain path itself and never calls a fetch registered before it.
  await page.addInitScript(
    ({ sessionId }) => {
      const w = window as unknown as { __explainCalls: number };
      w.__explainCalls = 0;
      const path = `/api/sessions/${sessionId}/explain`;
      const inner = window.fetch.bind(window);
      window.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.endsWith(path)) w.__explainCalls += 1;
        return inner(input, init);
      };
    },
    { sessionId: 's1' },
  );

  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  // Two click events in fast succession on the same, still-present button — the button the
  // learner pressed is still in the DOM and still bound while its host's close animation runs.
  await verb(page, 'Explain it').click({ clickCount: 2 });

  const calls = await page.evaluate(
    () => (window as unknown as { __explainCalls: number }).__explainCalls,
  );
  expect(calls, 'exactly one explain request, although the button was clicked twice').toBe(1);

  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: true }));
  await stream.close();
});

test('after Escape closes the picker, a second Escape and a Tab do not reach the leaving picker', async ({
  page,
}) => {
  const pageErrors: string[] = [];
  page.on('pageerror', (e) => pageErrors.push(String(e)));

  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await gotoReader(page);
  await selectPhrase(page, 'focus-body', 'Quantum mechanics');
  await picker(page).waitFor();

  await page.keyboard.press('Escape');

  // No wait for the close animation before this read: `close()` moves focus in the same handler
  // that `dismissed` runs, well before `animate.leave` finishes removing the element.
  const active = await page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    return {
      testId: el?.getAttribute('data-testid') ?? null,
      insidePicker: el?.closest('app-verb-picker') !== null,
    };
  });
  expect(active.testId, 'focus already left the picker before its leave animation ends').toBe(
    'focus-body',
  );
  expect(active.insidePicker, 'the active element is not inside the leaving picker').toBe(false);

  // The leaving picker is still in the DOM and its own key handlers are still bound, but focus
  // has already moved away from it, so a second Escape and a Tab must not reach them: no picker
  // reappears, and no handler of the leaving picker throws.
  await page.keyboard.press('Escape');
  await page.keyboard.press('Tab');
  await expect(picker(page)).toHaveCount(0);
  expect(pageErrors, 'no handler of the leaving picker throws').toEqual([]);
});

test('closing the picker with an outside click, then selecting a new phrase at once, leaves one picker for the new phrase', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await page.route('**/api/sessions/s1', (route) =>
    route.fulfill({ json: sessionWithBody(LONG_BODY) }),
  );
  await page.setViewportSize(WIDTHS.wide);
  await gotoReader(page, LONG_BODY);
  await selectPhrase(page, 'focus-body', 'Quantum mechanics');
  await picker(page).waitFor();

  // A press elsewhere on the page, outside the picker, is the outside-press path. The trail
  // rail's own heading, and not `.focus__hint`: the open picker's dropdown covers the hint
  // paragraph at this width, so a click there would hit the picker itself rather than land
  // outside it.
  await page.locator('.trail__head').click();
  // At once: no wait for the close animation before the next selection starts, while the old
  // picker is still in the DOM, still leaving.
  await selectPhrase(page, 'focus-body', 'at the smallest scales');

  // `toHaveCount`/`toContainText` retry until the old picker's leave animation actually finishes
  // and Angular removes it, so this is a claim about the settled state.
  await expect(picker(page)).toHaveCount(1);
  await expect(picker(page)).toContainText('at the smallest scales');
});

test('every control still draws its focus ring', async ({ page }) => {
  // The paragraph is the one element whose ring is suppressed. This is the guard that the
  // suppression did not spread: a control that loses its ring is a control a keyboard cannot find.
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();

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

test('a coral pill keeps its lift and gains a second ring while it has the keyboard focus', async ({
  page,
}) => {
  // Section 3.3 of the design review: the teal ring measures 1.01:1 against a coral fill, so
  // styles.css joins the ring and the lift in one box-shadow list. A plain rule that set its own
  // box-shadow on :focus-visible would replace the lift instead of adding a ring next to it.
  await page.route('**/api/auth/config', (route) =>
    route.fulfill({
      json: { turnstileSiteKey: null, googleEnabled: true, magicLinkEnabled: true },
    }),
  );
  await page.goto('/auth');

  await page.getByLabel('Email address').focus();
  await page.keyboard.press('Tab');
  // `.mt-pill`'s transition covers box-shadow, and the ring adds a second shadow layer to the
  // one the pill already draws. This polls the settled value, and not a mid-transition frame.
  await expect
    .poll(
      () => page.evaluate(() => getComputedStyle(document.activeElement as HTMLElement).boxShadow),
      { message: 'the coral lift and the white ring both draw' },
    )
    .toBe('rgb(214, 63, 63) 0px 4px 0px 0px, rgb(255, 255, 255) 0px 0px 0px 2px');
});

test('a link with the class .mt-pill shows no underline', async ({ page }) => {
  await page.route('**/api/auth/config', (route) =>
    route.fulfill({
      json: { turnstileSiteKey: null, googleEnabled: true, magicLinkEnabled: true },
    }),
  );
  await page.goto('/auth');

  const google = page.getByRole('link', { name: 'Continue with Google' });
  await google.waitFor();
  expect(await google.evaluate((el) => getComputedStyle(el).textDecorationLine)).toBe('none');
});

test('a pill lifts and gains a hover shadow while a pointer rests on it', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.goto('/');
  // The "Physics" pill, and not "All": "All" is the selected teal pill by default, and this test
  // reads the plain grey hover lift every .mt-pill answers with.
  const pill = page.locator('.catalog__cat').last();
  await pill.waitFor();

  const box = await pill.boundingBox();
  if (box === null) throw new Error('the category pill has no box to hover');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  // The transition takes --mt-dur-press (90ms), so this polls the computed value instead of
  // reading it right after the mouse moves, and it does not wait with a fixed sleep.
  await expect
    .poll(() => pill.evaluate((el) => getComputedStyle(el).boxShadow), {
      message: 'a pill gains the grey hover lift while a pointer rests on it',
    })
    .toBe('rgb(207, 233, 224) 0px 5px 0px 0px');
});

test.describe('with a reduced-motion preference', () => {
  test('the motion tokens compute to a 1ms duration and a 0px distance', async ({ page }) => {
    // `page.emulateMedia` and not `test.use({ reducedMotion: 'reduce' })`: the context option did
    // not reach `window.matchMedia` in this project's Chromium, confirmed with a standalone check
    // against `window.matchMedia('(prefers-reduced-motion: reduce)').matches`. The imperative call
    // does reach it, on the very same browser.
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await stubCatalogueAndSession(page);
    await page.goto('/');
    await page.locator('.topic__tile').first().waitFor();

    const tokens = await page.evaluate(() => {
      const root = getComputedStyle(document.documentElement);
      return {
        moveNear: root.getPropertyValue('--mt-move-near').trim(),
        durState: root.getPropertyValue('--mt-dur-state').trim(),
      };
    });
    expect(tokens.moveNear).toBe('0px');
    expect(tokens.durState).toBe('1ms');
  });

  test('the caret stops blinking and stays visible, and the band stops moving, while a stream runs', async ({
    page,
  }) => {
    // The caret and the band carry a meaning: a stream is running. Reduced motion stops the
    // animation, and the design keeps the caret visible and the band in place, rather than
    // removing either one.
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await stubCatalogueAndSession(page);
    const stream = await mockExplainStream(page, 's1');
    await openQuantumPhysicsSession(page);

    await selectPhrase(page, 'focus-body', 'fundamental physical theory');
    await verb(page, 'Explain it').click();
    await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
    await page.locator('.focus__caret').waitFor();

    const caret = await page.locator('.focus__caret').evaluate((el) => {
      const s = getComputedStyle(el);
      return { animationName: s.animationName, opacity: s.opacity };
    });
    const bandAnimation = await page
      .locator('.focus__band')
      .evaluate((el) => getComputedStyle(el).animationName);

    expect(caret.animationName, 'the caret animation stops').toBe('none');
    expect(caret.opacity, 'the caret stays visible').toBe('1');
    expect(bandAnimation, 'the band animation stops').toBe('none');

    await stream.close();
  });

  test('the skeleton stops pulsing while the catalogue loads', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const held = gate();
    await page.route('**/api/catalog/topics*', async (route) => {
      await held.wait;
      route.fulfill({ json: [] });
    });
    await page.goto('/');
    await page.locator('.mt-skeleton').first().waitFor();

    const animationName = await page
      .locator('.mt-skeleton')
      .first()
      .evaluate((el) => getComputedStyle(el).animationName);
    expect(animationName).toBe('none');

    held.open();
  });

  test('the picker opens and closes with a 1ms fade, and leaves no element behind', async ({
    page,
  }) => {
    // Animation D. The phone sheet's 100% travel is not a distance any --mt-move-* token
    // covers, so its reduced-motion form is an explicit rule rather than a token substitution —
    // this is the one animation of this issue that needs its own duration and name asserted,
    // rather than relying on the generic token test above.
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await stubCatalogueAndSession(page);
    await page.setViewportSize(WIDTHS.wide);
    await gotoReader(page);
    await selectPhrase(page, 'focus-body', 'Quantum mechanics');
    await picker(page).waitFor();

    const opened = await picker(page).evaluate((el) => {
      const s = getComputedStyle(el);
      return { animationName: s.animationName, animationDuration: s.animationDuration };
    });
    expect(opened.animationName, 'the picker fades in rather than sliding').toBe('picker-fade');
    expect(opened.animationDuration).toBe('0.001s');

    await page.keyboard.press('Escape');
    // `animate.leave` keeps the picker in the DOM only until its animation ends. A duration of
    // 1ms and not 0ms is what makes that `animationend` fire at all — see styles.css's own
    // comment on the token block for why 0ms is unsafe here — so this is also the proof that the
    // choice works for a real element, and not only for the tokens in isolation.
    await expect(picker(page)).toHaveCount(0);
  });

  test('the answer replaces the stream box with no leftover element', async ({ page }) => {
    // Animation A. The stream box leaves through `animate.leave`, and the reduced-motion tokens
    // take its exit to 1ms. This is the proof that the element is actually gone afterwards, and
    // not merely invisible.
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await stubCatalogueAndSession(page, explainedView(CHILD));
    const stream = await mockExplainStream(page, 's1');
    await openQuantumPhysicsSession(page);

    await selectPhrase(page, 'focus-body', 'fundamental physical theory');
    await verb(page, 'Explain it').click();
    await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
    await stream.send(sseFrame('delta', { t: CHILD }));
    await page.locator('.focus__streaming').waitFor();

    await stream.send(sseFrame('done', { contentKey: 'k1', grounded: true }));
    await stream.close();

    // The body, not a page-wide text search: the leaving box holds the same words while it
    // fades, even at 1ms, so a page-wide search could resolve to both and fail with a strict
    // mode violation.
    await expect(page.getByTestId('focus-body')).toContainText('subatomic scale');
    await expect(page.locator('.focus__streaming')).toHaveCount(0);
    // Animation A's other half: the body settles at full opacity, and not stuck at the 0.25 the
    // keyframe's `from` step declares.
    await expect(page.locator('.focus__body')).toHaveCSS('opacity', '1');

    // Animation E and F. A new trail row and a new crumb both land, and the current row still
    // changes colour to say where the learner is — the duration tokens take 1ms, proved
    // generically above, and this is the proof that the elements themselves still arrive and
    // still carry the right state under that duration.
    await expect(page.locator('.trail__item--current')).toContainText('fundamental physical');
    await expect(page.locator('.trail__item--current')).toHaveCSS(
      'background-color',
      'rgb(15, 118, 110)',
    );
    await expect(page.locator('.crumb')).toHaveCount(2);
  });

  test('the sign-in panel replaces the focus card and settles at full opacity', async ({
    page,
  }) => {
    // Animation M. The panel's own entrance is token-driven, so this is the proof that it still
    // renders, in the slot the focus card would occupy, and ends up fully visible.
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await stubCatalogueAndSession(page);
    await mockExplainRefusal(page, 's1', {
      status: 401,
      body: { code: 'SIGN_IN_REQUIRED', message: 'sign in to keep going' },
    });
    await openQuantumPhysicsSession(page);
    await selectPhrase(page, 'focus-body', 'fundamental physical theory');
    await verb(page, 'Explain it').click();

    // The host, app-sign-in-panel, and not the .sign-in-panel div inside it: animate.enter's
    // class and its opacity animation sit on the host, and a child's own computed opacity stays
    // "1" regardless of what its ancestor's opacity is doing.
    const host = page.locator('app-sign-in-panel');
    await expect(host).toBeVisible();
    await expect(host).toHaveCSS('opacity', '1');
    await expect(page.locator('app-focus-card')).toHaveCount(0);
  });
});

test.describe('on a touch screen', () => {
  test.use({ hasTouch: true, isMobile: true, viewport: WIDTHS.narrow });

  test('a ghost pill keeps its rest background after a tap, and does not stay hovered', async ({
    page,
  }) => {
    // "Exam" and not a category filter pill: tapping a category filter pill selects it, which
    // changes its own colour on purpose (it becomes the current pill) — a fact about the
    // catalogue, and not about hover. "Exam" answers no such state.
    // Background, and not box-shadow: a ghost pill draws no shadow either at rest or on hover, so
    // box-shadow could not tell the two states apart. The hover rule changes the background from
    // --mt-surface (white) to --mt-sunk, so that property is the one a stuck hover would show on.
    await stubCatalogueAndSession(page);
    await gotoReader(page);

    // The standard method this issue uses is `@media (hover: hover)`. This confirms the emulated
    // touch context actually reports it as false, so a pass here is evidence about that method
    // and not an accident of a rule that never runs.
    const supportsHover = await page.evaluate(() => window.matchMedia('(hover: hover)').matches);
    test.skip(supportsHover, 'this browser reports (hover: hover) as true in a touch context');

    const exam = page.getByTestId('exam');
    await exam.waitFor();
    await exam.tap();

    // A tap ends the touch at once, so any :active state is already gone. This polls rather than
    // reading right away, because the transition itself still takes --mt-dur-press to settle.
    await expect
      .poll(() => exam.evaluate((el) => getComputedStyle(el).backgroundColor), {
        message: 'a tapped ghost pill settles back to its white rest background',
      })
      .toBe('rgb(255, 255, 255)');
  });
});

/** One question, so the quiz reaches its coral pill in one click. The score is not read here. */
const PRESS_TEMPLATE: QuizTemplateView = {
  attemptId: 'attempt-98',
  kind: 'TEST_ME',
  questions: [
    {
      questionId: 'q1',
      stem: 'What does quantum mechanics describe?',
      options: ['Matter and light', 'Only sound'],
    },
  ],
};

const PRESS_RESULT: QuizResultView = {
  score: 1,
  total: 1,
  correctIndices: { q1: 0 },
  rationales: { q1: 'Quantum mechanics describes matter and light.' },
};

test('a ghost pill draws no shadow while a learner presses it', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await gotoReader(page);

  const exam = page.getByTestId('exam');
  const box = await exam.boundingBox();
  if (box === null) throw new Error('the Exam pill has no box to press');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  // `.mt-pill`'s transition takes 80ms, so this polls the computed value. It does not read the
  // value right after `mouse.down()`, and it does not wait with a fixed sleep.
  await expect
    .poll(() => exam.evaluate((el) => getComputedStyle(el).boxShadow), {
      message: 'a ghost pill must draw no shadow while a learner presses it',
    })
    .toBe('none');
  await page.mouse.up();
});

test('a coral pill keeps its smaller shadow while a learner presses it', async ({ page }) => {
  // The ghost fix above must change no other pill. This presses the coral pill of the Test Me
  // quiz and checks that its press shadow still only shrinks, from a 4px lift to a 2px lift.
  await stubCatalogueAndSession(page);
  await mockQuiz(page, 's1', PRESS_TEMPLATE, PRESS_RESULT);
  await gotoReader(page);

  await page.getByTestId('test-me').click();
  const quiz = page.locator('[role="dialog"]');
  await quiz.getByText(PRESS_TEMPLATE.questions[0].stem).waitFor();
  await quiz
    .getByRole('button', { name: PRESS_TEMPLATE.questions[0].options[0], exact: true })
    .click();

  const seeResults = quiz.getByRole('button', { name: 'See results', exact: true });
  const box = await seeResults.boundingBox();
  if (box === null) throw new Error('the "See results" pill has no box to press');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await expect
    .poll(() => seeResults.evaluate((el) => getComputedStyle(el).boxShadow), {
      message: 'a coral pill keeps a smaller shadow, not no shadow, while a learner presses it',
    })
    .toBe('rgb(214, 63, 63) 0px 2px 0px 0px');
  await page.mouse.up();
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

  // Five verbs since slice 4 added VISUALIZE. One Tab press per verb, plus one more to see the
  // wrap back to the first.
  const verbCount = 5;
  const forward: (string | null)[] = [];
  for (let i = 0; i < verbCount + 1; i++) {
    expect(await insideNow(), `focus stays inside the picker on Tab press ${i}`).toBe(true);
    forward.push(await verbNow());
    await page.keyboard.press('Tab');
  }
  expect(new Set(forward.slice(0, verbCount)).size, 'five distinct verbs are reachable').toBe(
    verbCount,
  );
  expect(forward[verbCount], 'the wrapping Tab press returns to the first verb').toBe(forward[0]);

  // The wrapping press left focus on the second verb, so one Shift+Tab walks back to the first.
  await page.keyboard.press('Shift+Tab');
  expect(await verbNow(), 'Shift+Tab walks back one verb').toBe(forward[0]);

  // And the backward wrap. Angular builds a full key name from the modifiers held, so
  // `keydown.tab` alone never fires while Shift is down and this half needs its own binding.
  await page.keyboard.press('Shift+Tab');
  expect(await insideNow(), 'Shift+Tab keeps focus inside the picker').toBe(true);
  expect(await verbNow(), 'Shift+Tab from the first verb wraps to the last').toBe(
    forward[verbCount - 1],
  );
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
  await page.locator('.topic__tile').first().waitFor();
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

test('the header fits on one line at 400px while signed out, with the Sign in link and the dot', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await page.setViewportSize({ width: 400, height: 900 });
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();

  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();

  const bar = await page.locator('.bar').boundingBox();
  expect(bar!.height, 'the bar stays 64px tall, not wrapped to a second line').toBe(64);

  const doc = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(doc.scroll, 'the page does not scroll sideways at 400px').toBeLessThanOrEqual(doc.client);
});

/**
 * Issue #100. The design review calculated an overflow for a signed-in learner at a phone width,
 * from the meter's declared widths and font size, but the review states plainly that no person
 * measured it in a browser. This test is the measurement, at both widths the review names.
 *
 * The reset date is fixed at "September 20, 2026 at 3:00 PM" — the exact example the review
 * itself gives for a long detail string — so the measured widths answer the review's own claim.
 */
test('the header fits on one line for a signed-in learner at 390px and 400px, with a long reset date', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await stubAccount(page, accountView());

  for (const width of [390, 400]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await page.locator('.topic__tile').first().waitFor();

    const meter = page.locator('app-allowance-meter');
    await expect(meter, `the meter is visible at ${width}px`).toBeVisible();
    await expect(
      meter,
      `the meter states the count and the reset date at ${width}px`,
    ).toContainText('12 of 40 left today');
    await expect(meter).toContainText('Resets September 20, 2026 at 3:00 PM.');

    const bar = await page.locator('.bar').boundingBox();
    const meterBox = await meter.boundingBox();
    const doc = await page.evaluate(() => ({
      scroll: document.documentElement.scrollWidth,
      client: document.documentElement.clientWidth,
    }));

    // Printed so the issue's own record of the measurement quotes a real run, not an estimate.
    console.log(
      `[issue-100] width=${width} scrollWidth=${doc.scroll} clientWidth=${doc.client} ` +
        `barHeight=${bar!.height} meterWidth=${meterBox!.width}`,
    );

    expect(bar!.height, `the bar stays 64px tall at ${width}px, not wrapped to a second line`).toBe(
      64,
    );
    expect(doc.scroll, `the page does not scroll sideways at ${width}px`).toBeLessThanOrEqual(
      doc.client,
    );
  }
});

/**
 * Every new learner starts in a trial, and the trial row prints a different pair of strings —
 * "left in your trial" instead of "left today", and "Trial ends" instead of "Resets" — so it
 * needs its own measurement and cannot lean on the ACTIVE case above.
 */
test('the header fits on one line for a learner in trial at 390px, with a long trial end date', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await stubAccount(
    page,
    accountView({
      status: 'TRIALING',
      trialEndsAtEpochMillis: Date.UTC(2026, 8, 20),
      resetsAtEpochMillis: null,
    }),
  );
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();

  const meter = page.locator('app-allowance-meter');
  await expect(meter).toContainText('12 of 40 left in your trial');
  await expect(meter).toContainText('Trial ends September 20, 2026.');

  const bar = await page.locator('.bar').boundingBox();
  expect(bar!.height, 'the bar stays 64px tall for a learner in trial').toBe(64);

  const doc = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(
    doc.scroll,
    'the page does not scroll sideways for a learner in trial',
  ).toBeLessThanOrEqual(doc.client);
});

/** True when box `a` and box `b` share any area. Used below: a bar height of 64px and no
 * sideways scroll are both still true if a tall child overflows the bar and sits on top of the
 * wordmark or the Account link instead — this is the check that a coordinator review of issue
 * #100 asked for, because those two facts alone do not rule that out. */
function overlaps(
  a: { x: number; y: number; width: number; height: number },
  b: { x: number; y: number; width: number; height: number },
): boolean {
  return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;
}

/**
 * Issue #91 adds a checkout error to the meter, for an account with no active allowance. That
 * text also renders in the header bar, so a failed checkout at a phone width needs its own proof
 * that the bar stays one line and the page stays inside its own width.
 *
 * A bar that stays 64px tall and a page that does not scroll sideways are not, by themselves,
 * proof that the error text sits inside the header row: `.bar` has a fixed height, so a tall
 * child does not grow it — the child simply overflows, over the wordmark or over the page below.
 * A first, failing run of this test measured exactly that: the error box ran from y=-16 to y=89,
 * well outside the bar's own 0-to-64 range, even though the bar height and the scroll width both
 * read as correct. The fix takes the error out of the row and fixes it just below the bar. This
 * test now also reads the error's own box, and the boxes of the wordmark and the Account link, so
 * that kind of overflow fails the test even when the bar height and the scroll width do not
 * catch it.
 */
test('the header stays inside the page width when Subscribe fails for an expired learner at 390px', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await stubAccount(page, accountView({ status: 'EXPIRED', remaining: 0 }));
  await page.route('**/api/billing/checkout', (route) => route.fulfill({ status: 500, body: '' }));
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();

  await page.getByRole('button', { name: 'Subscribe' }).click();

  const error = page.locator('.allowance-meter__error');
  await expect(error).toBeVisible();
  await expect(error).toContainText(
    'Could not start checkout. Check your connection and try again.',
  );
  await expect(error).toHaveAttribute('role', 'alert');

  const bar = (await page.locator('.bar').boundingBox())!;
  const errorBox = (await error.boundingBox())!;
  const mark = (await page.locator('.bar__mark').boundingBox())!;
  const account = (await page.locator('a.bar__account').boundingBox())!;
  const doc = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));

  // Printed so the issue's own record of the measurement quotes a real run, not an estimate.
  console.log(
    `[issue-100] checkout-error width=390 bar=${JSON.stringify(bar)} error=${JSON.stringify(errorBox)} ` +
      `mark=${JSON.stringify(mark)} account=${JSON.stringify(account)} doc=${JSON.stringify(doc)}`,
  );

  expect(bar.height, 'the bar stays 64px tall while the error shows').toBe(64);
  // The error is now a small card below the bar, not a row item — its top sits at or past
  // the bar's own bottom edge, and never inside the bar's 0-to-64 range.
  expect(errorBox.y, 'the error card sits below the bar, not on top of it').toBeGreaterThanOrEqual(
    bar.y + bar.height,
  );
  expect(errorBox.x, 'the error card starts inside the window').toBeGreaterThanOrEqual(0);
  expect(errorBox.x + errorBox.width, 'the error card ends inside the window').toBeLessThanOrEqual(
    doc.client,
  );
  expect(overlaps(errorBox, mark), 'the error does not cover the wordmark').toBe(false);
  expect(overlaps(errorBox, account), 'the error does not cover the Account link').toBe(false);
  expect(doc.scroll, 'the page does not scroll sideways while the error shows').toBeLessThanOrEqual(
    doc.client,
  );
});

/**
 * The fix above only applies below 768px. This proves the desktop header did not change for the
 * worse: at 1360px there is room for the error on one line inside the row, exactly as before
 * issue #100's second round.
 */
test('the header still fits at 1360px when Subscribe fails, with the error inside the row', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await stubAccount(page, accountView({ status: 'EXPIRED', remaining: 0 }));
  await page.route('**/api/billing/checkout', (route) => route.fulfill({ status: 500, body: '' }));
  await page.setViewportSize(WIDTHS.wide);
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();

  await page.getByRole('button', { name: 'Subscribe' }).click();

  const error = page.locator('.allowance-meter__error');
  await expect(error).toBeVisible();
  await expect(error).toContainText(
    'Could not start checkout. Check your connection and try again.',
  );

  const bar = (await page.locator('.bar').boundingBox())!;
  const errorBox = (await error.boundingBox())!;
  const doc = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));

  console.log(
    `[issue-100] checkout-error width=1360 bar=${JSON.stringify(bar)} error=${JSON.stringify(errorBox)} doc=${JSON.stringify(doc)}`,
  );

  expect(bar.height, 'the bar stays 64px tall on desktop').toBe(64);
  expect(errorBox.y, 'the error box stays inside the bar on desktop').toBeGreaterThanOrEqual(bar.y);
  expect(
    errorBox.y + errorBox.height,
    'the error box stays inside the bar on desktop',
  ).toBeLessThanOrEqual(bar.y + bar.height);
  expect(doc.scroll, 'the page does not scroll sideways on desktop').toBeLessThanOrEqual(
    doc.client,
  );
});

/**
 * The EXPIRED state without a failed checkout call: only the Subscribe button shows. The button
 * alone must not push the header past the phone width either, and it must not cover the wordmark
 * or the Account link.
 */
test('the header stays inside the page width for an expired learner with no error at 390px', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await stubAccount(page, accountView({ status: 'EXPIRED', remaining: 0 }));
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();

  const button = page.getByRole('button', { name: 'Subscribe' });
  await expect(button).toBeVisible();

  const bar = (await page.locator('.bar').boundingBox())!;
  const buttonBox = (await button.boundingBox())!;
  const mark = (await page.locator('.bar__mark').boundingBox())!;
  const account = (await page.locator('a.bar__account').boundingBox())!;
  const doc = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));

  console.log(
    `[issue-100] expired-no-error width=390 bar=${JSON.stringify(bar)} button=${JSON.stringify(buttonBox)} ` +
      `mark=${JSON.stringify(mark)} account=${JSON.stringify(account)} doc=${JSON.stringify(doc)}`,
  );

  expect(bar.height, 'the bar stays 64px tall with only the Subscribe button').toBe(64);
  expect(buttonBox.y, 'the Subscribe button does not start above the bar').toBeGreaterThanOrEqual(
    bar.y,
  );
  expect(
    buttonBox.y + buttonBox.height,
    'the Subscribe button does not extend below the bar',
  ).toBeLessThanOrEqual(bar.y + bar.height);
  expect(overlaps(buttonBox, mark), 'the Subscribe button does not cover the wordmark').toBe(false);
  expect(overlaps(buttonBox, account), 'the Subscribe button does not cover the Account link').toBe(
    false,
  );
  expect(
    doc.scroll,
    'the page does not scroll sideways with only the Subscribe button',
  ).toBeLessThanOrEqual(doc.client);
});

/**
 * The account page renders the same `AllowanceMeterComponent` inside its own card, and the fix
 * for the header must not reach it: the learner reads the full detail text there, on any width.
 *
 * The shell wraps every route, so a visit to `/account` renders two meters at once: the header's
 * own, and the account page's. This test reads the account page's card, and not the header, by
 * scoping to `.account-page__card` — the class `AccountPageComponent` gives its own card.
 */
test('the account page still shows the full detail text of the meter at 390px', async ({
  page,
}) => {
  await stubAccount(page, accountView());
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/account');

  const detail = page.locator('.account-page__card .allowance-meter__detail');
  await expect(
    detail,
    'the detail stays visible in the account card, unlike in the header',
  ).toBeVisible();
  await expect(detail).toContainText('Resets September 20, 2026 at 3:00 PM.');

  // The header's own meter, on the same page, still hides its detail — proof that the rule scopes
  // to the header and did not simply stop applying below 768px.
  const headerDetail = page.locator('header.bar .allowance-meter__detail');
  await expect(
    headerDetail,
    'the header keeps hiding its own detail on every other page too',
  ).toBeHidden();
});

test('the mark draws at 28px, left of the wordmark', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();

  const box = await page.locator('.bar__mark app-logo-mark').boundingBox();

  expect(box?.width).toBe(28);
  expect(box?.height).toBe(28);
});

test('the mark resolves its palette tokens inside the inline SVG', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.setViewportSize(WIDTHS.wide);
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();

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

  // Issue #34 replaced the bare "mytetz" title with one line that also states the product, so a
  // crawler and a shared link read it before any JavaScript runs.
  expect(await page.title()).toBe('mytetz: understand hard topics one sentence at a time');
});

test('the reader tab carries the topic once a session opens', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await openQuantumPhysicsSession(page);

  // Issue #35: `ReaderPageComponent` sets this itself, once the session names its topic — no
  // static `title` in `app.routes.ts` could, since the topic is not known until then.
  expect(await page.title()).toBe('Quantum Physics | mytetz');
});
