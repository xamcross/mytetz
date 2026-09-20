import { test, expect } from '@playwright/test';

/**
 * Issue #143's own account-control script, `frontend/public/site-header.js`.
 *
 * `playwright.config.ts`'s own doc comment states the shape of this suite: it runs against a bare
 * `ng serve`, with no Ktor process alongside it. The real `siteHeaderBar()` markup
 * (`backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt`) is not a route this suite can
 * reach, so `headerFixture` below stands in for it, fulfilled through `page.route` — the same
 * pattern `frontend/e2e/topic-pages.spec.ts`'s own `topicPageFixture` uses for `topic-start.js`.
 * `/site-header.js` itself is not stubbed: `ng serve` serves `frontend/public/*` for real, so this
 * suite drives the one real, unstubbed script the shared header depends on.
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

test('a 200 answer turns Sign in into Account, and shows the allowance count', async ({ page }) => {
  await page.route('**/fixture', (route) =>
    route.fulfill({ contentType: 'text/html', body: headerFixture() }),
  );
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

  await page.goto('/fixture');

  const link = page.locator('#site-header-account');
  await expect(link).toHaveText('Account');
  await expect(link).toHaveAttribute('href', '/account');
  await expect(page.locator('#site-header-count')).toHaveText('20 of 25 left today');

  // The learner's own email never reaches the page: only textContent writes ever touch it, and
  // none of them ever receive account.email.
  await expect(page.locator('body')).not.toContainText('learner@example.com');
});

test('a trialing account shows the trial words, not "today"', async ({ page }) => {
  await page.route('**/fixture', (route) =>
    route.fulfill({ contentType: 'text/html', body: headerFixture() }),
  );
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

  await page.goto('/fixture');

  await expect(page.locator('#site-header-count')).toHaveText('5 of 8 left in your trial');
});

test('a 401 answer changes nothing: the header keeps Sign in', async ({ page }) => {
  await page.route('**/fixture', (route) =>
    route.fulfill({ contentType: 'text/html', body: headerFixture() }),
  );
  let accountRequests = 0;
  await page.route('**/api/account', (route) => {
    accountRequests += 1;
    route.fulfill({ status: 401, json: { code: 'SIGN_IN_REQUIRED', message: 'sign in' } });
  });

  await page.goto('/fixture');
  // No selector to wait on for "nothing changed": wait for the one request this page sends
  // instead, then assert the header is still the signed-out header.
  await expect.poll(() => accountRequests).toBe(1);

  const link = page.locator('#site-header-account');
  await expect(link).toHaveText('Sign in');
  await expect(link).toHaveAttribute('href', '/auth');
  await expect(page.locator('#site-header-count')).toBeEmpty();
});

test('sends exactly one GET /api/account request for the page view', async ({ page }) => {
  await page.route('**/fixture', (route) =>
    route.fulfill({ contentType: 'text/html', body: headerFixture() }),
  );
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

  await page.goto('/fixture');
  await expect(page.locator('#site-header-account')).toHaveText('Account');

  expect(requests).toBe(1);
});

test('a network failure changes nothing: the header keeps Sign in', async ({ page }) => {
  await page.route('**/fixture', (route) =>
    route.fulfill({ contentType: 'text/html', body: headerFixture() }),
  );
  await page.route('**/api/account', (route) => route.abort('failed'));

  await page.goto('/fixture');
  // No response ever arrives; give the failed fetch a turn to run its .catch before asserting.
  await page.waitForTimeout(200);

  await expect(page.locator('#site-header-account')).toHaveText('Sign in');
});
