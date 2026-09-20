import { test, expect, type Page } from '@playwright/test';
import { stubCatalogueAndSession } from './support';

/**
 * Issue #143's own account-control and status-dot script, `frontend/public/site-header.js`.
 *
 * `playwright.config.ts`'s own doc comment states the shape of this suite: it runs against a bare
 * `ng serve`, with no Ktor process alongside it. The real `siteHeaderBar()` markup
 * (`backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt`) is not a route this suite can
 * reach, so `headerFixture` below stands in for it, fulfilled through `page.route` — the same
 * pattern `frontend/e2e/topic-pages.spec.ts`'s own `topicPageFixture` uses for `topic-start.js`.
 * `/site-header.js` and `/guides/guides.css` are not stubbed: `ng serve` serves `frontend/public/*`
 * for real, so this suite drives the one real, unstubbed script the shared header depends on, laid
 * out with the one real stylesheet every public page links.
 *
 * `HeaderFooterParityTest.kt` (backend, `:backend:api:test`) proves the real markup this fixture
 * copies, including the dot's own server-rendered "checking" state. This suite proves the script
 * that reads it.
 */
function headerFixture(): string {
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <title>Fixture | mytetz</title>
    <link rel="stylesheet" href="/guides/guides.css" />
    <script src="/site-header.js" defer></script>
  </head>
  <body>
    <header class="bar">
      <div class="bar__left">
        <a class="bar__mark" href="/" aria-label="mytetz">
          <svg class="bar__mark-icon" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
            <rect x="0" y="0" width="32" height="32" rx="10" fill="#e4f2ed" />
          </svg>
          <span class="bar__mark-text">mytetz</span>
        </a>
        <nav class="bar__nav" aria-label="Main">
          <a class="bar__link" href="/glossary">Glossary</a>
          <a class="bar__link" href="/guides">Guides</a>
        </nav>
      </div>
      <div class="bar__right">
        <a class="bar__link bar__account" id="site-header-account" href="/auth">Sign in</a>
        <span class="bar__count" id="site-header-count"></span>
        <span
          class="dot dot--checking"
          id="site-header-dot"
          role="img"
          aria-label="Backend: checking"
          title="Backend: checking"
        ></span>
      </div>
    </header>
  </body>
</html>`;
}

/** `GET /api/health`'s answer for a plain, healthy backend — every test below that needs the
 * dot settled and does not care about its own state. */
async function stubHealthyBackend(page: Page): Promise<void> {
  await page.route('**/api/health', (route) =>
    route.fulfill({ json: { status: 'ok', mongo: true, ready: true } }),
  );
}

async function gotoFixture(page: Page): Promise<void> {
  await page.route('**/fixture', (route) =>
    route.fulfill({ contentType: 'text/html', body: headerFixture() }),
  );
  await page.goto('/fixture');
}

test('a 200 answer turns Sign in into Account, and shows the allowance count', async ({ page }) => {
  await stubHealthyBackend(page);
  await page.route('**/api/account', (route) =>
    route.fulfill({
      json: {
        email: 'learner@example.com',
        status: 'ACTIVE',
        trialEndsAtEpochMillis: null,
        currentPeriodEndsAtEpochMillis: null,
        allowance: 25,
        remaining: 20,
        resetsAtEpochMillis: null,
      },
    }),
  );

  await gotoFixture(page);

  const link = page.locator('#site-header-account');
  await expect(link).toHaveText('Account');
  await expect(link).toHaveAttribute('href', '/account');
  await expect(page.locator('.bar__count-digits')).toHaveText('20 of 25 left');
  await expect(page.locator('.sr-only')).toHaveText('20 of 25 left today');

  // The learner's own email never reaches the page: only textContent writes ever touch it, and
  // none of them ever receive account.email.
  await expect(page.locator('body')).not.toContainText('learner@example.com');
});

test('a trialing account shows the trial words, not "today"', async ({ page }) => {
  await stubHealthyBackend(page);
  await page.route('**/api/account', (route) =>
    route.fulfill({
      json: {
        email: 'learner@example.com',
        status: 'TRIALING',
        trialEndsAtEpochMillis: 1234,
        currentPeriodEndsAtEpochMillis: null,
        allowance: 8,
        remaining: 5,
        resetsAtEpochMillis: null,
      },
    }),
  );

  await gotoFixture(page);

  await expect(page.locator('.bar__count-digits')).toHaveText('5 of 8 left');
  await expect(page.locator('.sr-only')).toHaveText('5 of 8 left in your trial');
});

test('a 401 answer changes nothing: the header keeps Sign in', async ({ page }) => {
  await stubHealthyBackend(page);
  let accountRequests = 0;
  await page.route('**/api/account', (route) => {
    accountRequests += 1;
    route.fulfill({ status: 401, json: { code: 'SIGN_IN_REQUIRED', message: 'sign in' } });
  });

  await gotoFixture(page);
  // No selector to wait on for "nothing changed": wait for the one request this page sends
  // instead, then assert the header is still the signed-out header.
  await expect.poll(() => accountRequests).toBe(1);

  const link = page.locator('#site-header-account');
  await expect(link).toHaveText('Sign in');
  await expect(link).toHaveAttribute('href', '/auth');
  await expect(page.locator('#site-header-count')).toBeEmpty();
});

test('sends exactly one GET /api/account request for the page view', async ({ page }) => {
  await stubHealthyBackend(page);
  let requests = 0;
  await page.route('**/api/account', (route) => {
    requests += 1;
    expect(route.request().method()).toBe('GET');
    route.fulfill({
      json: {
        email: 'learner@example.com',
        status: 'ACTIVE',
        trialEndsAtEpochMillis: null,
        currentPeriodEndsAtEpochMillis: null,
        allowance: 25,
        remaining: 20,
        resetsAtEpochMillis: null,
      },
    });
  });

  await gotoFixture(page);
  await expect(page.locator('#site-header-account')).toHaveText('Account');

  expect(requests).toBe(1);
});

test('a network failure changes nothing: the header keeps Sign in', async ({ page }) => {
  await stubHealthyBackend(page);
  await page.route('**/api/account', (route) => route.abort('failed'));

  await gotoFixture(page);
  // No response ever arrives; give the failed fetch a turn to run its .catch before asserting.
  await page.waitForTimeout(200);

  await expect(page.locator('#site-header-account')).toHaveText('Sign in');
});

/**
 * Issue #143, corrected on review: the count brought back the overlap issue #133 fixed once,
 * because it always wrote the full sentence. allowance-meter.component.ts's own fix hides the
 * period word below 768px and keeps the full sentence for a screen reader only, at every width.
 * This block proves site-header.js copies that same structure.
 */
test.describe('the count follows the same width rule as the application header', () => {
  async function stubTrialingAccount(page: Page): Promise<void> {
    await stubHealthyBackend(page);
    await page.route('**/api/account', (route) =>
      route.fulfill({
        json: {
          email: 'learner@example.com',
          status: 'TRIALING',
          trialEndsAtEpochMillis: 1234,
          currentPeriodEndsAtEpochMillis: null,
          allowance: 40,
          remaining: 12,
          resetsAtEpochMillis: null,
        },
      }),
    );
  }

  test('at 390px the digits show, the period word hides, and the accessible text keeps the full sentence', async ({
    page,
  }) => {
    await stubTrialingAccount(page);
    await page.setViewportSize({ width: 390, height: 800 });
    await gotoFixture(page);

    await expect(page.locator('.bar__count-digits')).toHaveText('12 of 40 left');
    await expect(page.locator('.bar__count-digits')).toBeVisible();
    await expect(page.locator('.bar__count-period')).toBeHidden();
    // The accessible text exists and holds the full sentence whether or not it is visible: a
    // screen reader reads it either way.
    await expect(page.locator('.sr-only')).toHaveText('12 of 40 left in your trial');
  });

  test('at 1360px the full sentence is visible', async ({ page }) => {
    await stubTrialingAccount(page);
    await page.setViewportSize({ width: 1360, height: 900 });
    await gotoFixture(page);

    await expect(page.locator('.bar__count-digits')).toHaveText('12 of 40 left');
    await expect(page.locator('.bar__count-period')).toBeVisible();
    await expect(page.locator('.bar__count-period')).toHaveText('in your trial');
  });
});

/**
 * Issue #143, corrected on review: the status dot. GET /api/health needs no sign-in and sets no
 * cookie (see HealthRoutesTest's own cookie test), so the same request app.ts sends once at
 * start-up also works from a public page.
 */
test.describe('the status dot', () => {
  function dot(page: Page) {
    return page.locator('#site-header-dot');
  }

  test('starts in the checking state, and stays there while the request is in flight', async ({
    page,
  }) => {
    await page.route('**/api/account', (route) =>
      route.fulfill({ status: 401, json: { code: 'SIGN_IN_REQUIRED', message: 'sign in' } }),
    );
    let releaseResponse!: () => void;
    const held = new Promise<void>((resolve) => {
      releaseResponse = resolve;
    });
    await page.route('**/api/health', async (route) => {
      await held;
      route.fulfill({ json: { status: 'ok', mongo: true, ready: true } });
    });

    await gotoFixture(page);

    // The server ships "checking" — see siteHeaderBar()'s own KDoc — and a page with no
    // JavaScript, or one whose health request is still in flight, keeps that honest state: it
    // never claims a health this page has not yet confirmed.
    await expect(dot(page)).toHaveClass('dot dot--checking');
    await expect(dot(page)).toHaveAttribute('aria-label', 'Backend: checking');
    await expect(dot(page)).toHaveAttribute('title', 'Backend: checking');

    releaseResponse();
    await expect(dot(page)).toHaveClass('dot dot--ok');
  });

  test('shows Backend ok when the answer carries mongo true', async ({ page }) => {
    await page.route('**/api/account', (route) => route.fulfill({ status: 401, json: {} }));
    await page.route('**/api/health', (route) =>
      route.fulfill({ json: { status: 'ok', mongo: true, ready: true } }),
    );

    await gotoFixture(page);

    await expect(dot(page)).toHaveClass('dot dot--ok');
    await expect(dot(page)).toHaveAttribute('aria-label', 'Backend ok');
    await expect(dot(page)).toHaveAttribute('title', 'Backend ok');
  });

  test('shows Backend degraded when the answer carries mongo false', async ({ page }) => {
    await page.route('**/api/account', (route) => route.fulfill({ status: 401, json: {} }));
    // The same shape app.spec.ts's own "reports a backend that answers without Mongo as
    // degraded" test flushes: a successful answer whose body names mongo: false.
    await page.route('**/api/health', (route) =>
      route.fulfill({ json: { status: 'degraded', mongo: false, ready: true } }),
    );

    await gotoFixture(page);

    await expect(dot(page)).toHaveClass('dot dot--degraded');
    await expect(dot(page)).toHaveAttribute('aria-label', 'Backend degraded');
  });

  test('shows Backend unreachable when the request fails', async ({ page }) => {
    await page.route('**/api/account', (route) => route.fulfill({ status: 401, json: {} }));
    await page.route('**/api/health', (route) => route.abort('failed'));

    await gotoFixture(page);

    await expect(dot(page)).toHaveClass('dot dot--unreachable');
    await expect(dot(page)).toHaveAttribute('aria-label', 'Backend unreachable');
  });

  test('shows Backend unreachable when the real backend answers 503, the same as app.ts', async ({
    page,
  }) => {
    // The real /api/health route answers 503, never 200, when Mongo does not ping — see
    // HealthRoutesTest's own `health reports 503 when mongo is unreachable`. app.ts's own
    // ApiService.health() throws on a non-2xx answer, and the catch sets 'unreachable'; this
    // script follows the same rule through response.ok.
    await page.route('**/api/account', (route) => route.fulfill({ status: 401, json: {} }));
    await page.route('**/api/health', (route) =>
      route.fulfill({ status: 503, json: { status: 'degraded', mongo: false, ready: true } }),
    );

    await gotoFixture(page);

    await expect(dot(page)).toHaveClass('dot dot--unreachable');
  });

  test('a health failure does not stop the account control from updating', async ({ page }) => {
    await page.route('**/api/health', (route) => route.abort('failed'));
    await page.route('**/api/account', (route) =>
      route.fulfill({
        json: {
          email: 'learner@example.com',
          status: 'ACTIVE',
          trialEndsAtEpochMillis: null,
          currentPeriodEndsAtEpochMillis: null,
          allowance: 25,
          remaining: 20,
          resetsAtEpochMillis: null,
        },
      }),
    );

    await gotoFixture(page);

    await expect(page.locator('#site-header-account')).toHaveText('Account');
    await expect(dot(page)).toHaveClass('dot dot--unreachable');
  });

  test('an account failure does not stop the dot from updating', async ({ page }) => {
    await page.route('**/api/account', (route) => route.abort('failed'));
    await stubHealthyBackend(page);

    await gotoFixture(page);

    await expect(dot(page)).toHaveClass('dot dot--ok');
    await expect(page.locator('#site-header-account')).toHaveText('Sign in');
  });
});

/**
 * Issue #133's own gap rule, measured on the public header. `HEADER_GAP_WIDTHS` and the two
 * account states copy `layout.spec.ts`'s own `HEADER_GAP_CASES` (minus "an expired learner": the
 * public header shows no Subscribe control yet — issue #137's own /subscribe screen does not
 * exist — so an EXPIRED account is out of scope here, and named as a known difference in the
 * report instead).
 */
type BoxByLabel = { label: string; box: { x: number; y: number; width: number; height: number } };

async function namedHeaderLocators(page: Page) {
  const navLinks = await page.locator('.bar__nav .bar__link').all();
  return [
    { label: 'wordmark', locator: page.locator('a.bar__mark') },
    ...navLinks.map((locator, index) => ({ label: `nav-link-${index}`, locator })),
    { label: 'account-link', locator: page.locator('#site-header-account') },
    { label: 'count', locator: page.locator('#site-header-count') },
    { label: 'status-dot', locator: page.locator('#site-header-dot') },
  ];
}

async function visibleHeaderBoxes(page: Page): Promise<BoxByLabel[]> {
  const boxes: BoxByLabel[] = [];
  for (const { label, locator } of await namedHeaderLocators(page)) {
    if (!(await locator.isVisible())) continue;
    const box = await locator.boundingBox();
    if (box) boxes.push({ label, box });
  }
  return boxes;
}

function sameRow(a: { y: number; height: number }, b: { y: number; height: number }): boolean {
  return a.y < b.y + b.height && b.y < a.y + a.height;
}

function overlaps(
  a: { x: number; y: number; width: number; height: number },
  b: { x: number; y: number; width: number; height: number },
): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

/** The same four checks `layout.spec.ts`'s own `assertHeaderGapInvariants` states for the
 * application header, read here against the public header instead. */
function assertHeaderGapInvariants(
  boxes: BoxByLabel[],
  doc: { scroll: number; client: number },
  width: number,
): void {
  for (const { label, box } of boxes) {
    expect(box.x, `${label} starts inside the viewport at ${width}px`).toBeGreaterThanOrEqual(0);
    expect(
      box.x + box.width,
      `${label} ends inside the viewport at ${width}px`,
    ).toBeLessThanOrEqual(doc.client);
  }

  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      expect(
        overlaps(boxes[i].box, boxes[j].box),
        `${boxes[i].label} does not overlap ${boxes[j].label} at ${width}px`,
      ).toBe(false);
    }
  }

  const sortedByX = [...boxes].sort((a, b) => a.box.x - b.box.x);
  for (let i = 0; i + 1 < sortedByX.length; i++) {
    const current = sortedByX[i];
    const next = sortedByX[i + 1];
    if (!sameRow(current.box, next.box)) continue;
    const gap = next.box.x - (current.box.x + current.box.width);
    expect(
      gap,
      `${current.label} and ${next.label} keep an 8px gap at ${width}px`,
    ).toBeGreaterThanOrEqual(8);
  }

  expect(doc.scroll, `the page does not scroll sideways at ${width}px`).toBeLessThanOrEqual(
    doc.client,
  );
}

/** The same rule `layout.spec.ts`'s own `assertHeaderEdgeMargins` states: at a phone width the
 * first element starts 20px from the left edge. */
function assertHeaderEdgeMargin(boxes: BoxByLabel[], width: number): void {
  const first = [...boxes].sort((a, b) => a.box.x - b.box.x)[0];
  expect(
    first.box.x,
    `${first.label} starts 20px from the left edge at ${width}px`,
  ).toBeGreaterThanOrEqual(19);
  expect(
    first.box.x,
    `${first.label} starts 20px from the left edge at ${width}px`,
  ).toBeLessThanOrEqual(21);
}

const HEADER_GAP_WIDTHS = [320, 360, 390, 412, 768];
const PHONE_EDGE_WIDTHS = [320, 360, 390, 412];

const HEADER_GAP_CASES: Array<{
  label: string;
  status: string;
  remaining: number;
  allowance: number;
}> = [
  { label: 'a learner in trial', status: 'TRIALING', remaining: 12, allowance: 40 },
  { label: 'an active learner', status: 'ACTIVE', remaining: 20, allowance: 25 },
];

for (const { label, status, remaining, allowance } of HEADER_GAP_CASES) {
  for (const width of HEADER_GAP_WIDTHS) {
    test(`no two public header elements overlap or sit closer than 8px, for ${label}, at ${width}px`, async ({
      page,
    }) => {
      await stubHealthyBackend(page);
      await page.route('**/api/account', (route) =>
        route.fulfill({
          json: {
            email: 'learner@example.com',
            status,
            trialEndsAtEpochMillis: status === 'TRIALING' ? Date.UTC(2026, 8, 20) : null,
            currentPeriodEndsAtEpochMillis: null,
            allowance,
            remaining,
            resetsAtEpochMillis: null,
          },
        }),
      );
      await page.setViewportSize({ width, height: 900 });
      await gotoFixture(page);
      await expect(page.locator('#site-header-account')).toHaveText('Account');
      await expect(page.locator('#site-header-dot')).toHaveClass('dot dot--ok');

      const boxes = await visibleHeaderBoxes(page);
      const doc = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        client: document.documentElement.clientWidth,
      }));

      // Printed so a report of this issue quotes a real run, and not an estimate.
      console.log(
        `[issue-143] label=${label} width=${width} boxes=${JSON.stringify(boxes)} doc=${JSON.stringify(doc)}`,
      );

      assertHeaderGapInvariants(boxes, doc, width);
      if (PHONE_EDGE_WIDTHS.includes(width)) assertHeaderEdgeMargin(boxes, width);
    });
  }
}

/**
 * Issue #143, corrected on review: the owner's own complaint was that the account control moves
 * between pages. This test opens the real application (stubbed to a visitor with no account) and
 * the public header fixture side by side, and compares the account link's and the dot's own left
 * edge, in pixels — the two boxes issue #133's own review measured a 42px and a 34px gap for.
 */
test('the account control and the dot start at the same x as the application header, for a visitor with no account', async ({
  context,
}) => {
  for (const width of [390, 1360]) {
    const appPage = await context.newPage();
    await stubCatalogueAndSession(appPage);
    await appPage.route('**/api/account', (route) =>
      route.fulfill({ status: 401, json: { code: 'SIGN_IN_REQUIRED', message: 'sign in' } }),
    );
    await appPage.route('**/api/health', (route) =>
      route.fulfill({ json: { status: 'ok', mongo: true, ready: true } }),
    );
    await appPage.setViewportSize({ width, height: 900 });
    await appPage.goto('/');
    await appPage.locator('.topic__tile').first().waitFor();
    await expect(appPage.locator('.bar app-status-dot .dot')).toHaveClass(/dot--ok/);
    const appAccountBox = await appPage.locator('a.bar__account').boundingBox();
    const appDotBox = await appPage.locator('.bar app-status-dot .dot').boundingBox();

    const pubPage = await context.newPage();
    await pubPage.route('**/api/account', (route) =>
      route.fulfill({ status: 401, json: { code: 'SIGN_IN_REQUIRED', message: 'sign in' } }),
    );
    await stubHealthyBackend(pubPage);
    await pubPage.setViewportSize({ width, height: 900 });
    await gotoFixture(pubPage);
    await expect(pubPage.locator('#site-header-dot')).toHaveClass('dot dot--ok');
    const pubAccountBox = await pubPage.locator('#site-header-account').boundingBox();
    const pubDotBox = await pubPage.locator('#site-header-dot').boundingBox();

    console.log(
      `[issue-143] position width=${width} app.account.x=${appAccountBox!.x} public.account.x=${pubAccountBox!.x} app.dot.x=${appDotBox!.x} public.dot.x=${pubDotBox!.x}`,
    );

    expect(
      Math.abs(appAccountBox!.x - pubAccountBox!.x),
      `the account link starts at the same x at ${width}px`,
    ).toBeLessThanOrEqual(1);
    expect(
      Math.abs(appDotBox!.x - pubDotBox!.x),
      `the dot starts at the same x at ${width}px`,
    ).toBeLessThanOrEqual(1);

    await appPage.close();
    await pubPage.close();
  }
});
