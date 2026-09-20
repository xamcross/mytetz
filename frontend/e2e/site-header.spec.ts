import { test, expect, type Page } from '@playwright/test';

/**
 * Issue #143's own account-control script, `frontend/public/site-header.js`.
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
 * copies. This suite proves the script that reads it.
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
        <a class="bar__mark" href="/">mytetz</a>
        <nav class="bar__nav" aria-label="Main">
          <a class="bar__link" href="/glossary">Glossary</a>
          <a class="bar__link" href="/guides">Guides</a>
        </nav>
      </div>
      <div class="bar__right">
        <a class="bar__link bar__account" id="site-header-account" href="/auth">Sign in</a>
        <span class="bar__count" id="site-header-count"></span>
      </div>
    </header>
  </body>
</html>`;
}

async function gotoFixture(page: Page): Promise<void> {
  await page.route('**/fixture', (route) =>
    route.fulfill({ contentType: 'text/html', body: headerFixture() }),
  );
  await page.goto('/fixture');
}

test('a 200 answer turns Sign in into Account, and shows the allowance count', async ({ page }) => {
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
