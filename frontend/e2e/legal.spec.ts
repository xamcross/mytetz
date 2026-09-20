import { test, expect, type Page } from '@playwright/test';
import {
  mockExplainRefusal,
  openQuantumPhysicsSession,
  selectPhrase,
  stubCatalogueAndSession,
} from './support';

/**
 * Task issue #5's own acceptance criteria: the three legal routes render, the footer reaches
 * every surface a learner visits, the account page links to the terms, and the privacy page
 * states the technical facts §5.7's spec lists. The legal wording itself is owner work — see
 * `PrivacyPageComponent`'s own class doc comment — so this suite checks structure and the named
 * facts, and never the `[owner text]` placeholders' prose.
 */

const ROUTES = ['/privacy', '/terms', '/imprint'];

/**
 * Every href the footer carries, in order. Issue #63 added the guide hub, issue #47 added
 * `/how-it-works`, and issue #121 added `/faq` — all three are static or Ktor-rendered pages and
 * not a legal route, so none of them stays in ROUTES: that constant drives the legal-page checks
 * below. The assertions stay an exact match, and not a subset, so that a legal link cannot
 * disappear without a failure here.
 */
const FOOTER_LINKS = ['/guides', '/how-it-works', '/faq', ...ROUTES];

/** Issue #35's own acceptance criteria: the tab names the page, not the generic title
 * `index.html` sets for every route. */
const TITLES: Record<string, string> = {
  '/privacy': 'Privacy policy | mytetz',
  '/terms': 'Terms of service | mytetz',
  '/imprint': 'Imprint | mytetz',
};

for (const route of ROUTES) {
  test(`${route} renders with HTTP 200`, async ({ page }) => {
    const response = await page.goto(route);
    expect(response?.status()).toBe(200);
    await expect(page.locator('h1')).toBeVisible();
  });

  test(`${route} sets its own tab title`, async ({ page }) => {
    await page.goto(route);
    // `toHaveTitle` polls until the title matches or the timeout ends. A one-time `page.title()`
    // read can run before the lazy route finishes activating and sets the title, which is a race,
    // not a flake to paper over with a retry or a sleep — see this file's own history.
    await expect(page).toHaveTitle(TITLES[route]);
  });

  test(`${route} carries no owner-text marker`, async ({ page }) => {
    await page.goto(route);
    const body = await page.locator('main').innerText();
    expect(body).not.toContain('[owner text]');
  });
}

/** Reaches the reader's sign-in wall, the surface acceptance criterion 2 names "the reader". */
async function openReaderWall(page: Page): Promise<void> {
  await stubCatalogueAndSession(page);
  await mockExplainRefusal(page, 's1', {
    status: 401,
    body: { code: 'SIGN_IN_REQUIRED', message: 'sign in to keep going' },
  });
  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await page
    .locator('[role="dialog"]')
    .getByRole('button', { name: 'Explain it', exact: true })
    .click();
  await expect(page.getByText('Sign in to keep going.')).toBeVisible();
}

async function footerLinks(page: Page): Promise<string[]> {
  return page.locator('footer a').evaluateAll((els) => els.map((el) => el.getAttribute('href')));
}

/** `GET /api/account`'s answer for a plain, active subscriber — every test below that only needs
 * the account page to render a signed-in learner, and does not care about the meter's numbers. */
async function stubActiveAccount(page: Page): Promise<void> {
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
}

test('the footer carries the legal links on the catalogue, the reader, the auth page and the account page', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await page.goto('/');
  await page.locator('.topic__tile').first().waitFor();
  expect(await footerLinks(page)).toEqual(FOOTER_LINKS);

  await openReaderWall(page);
  expect(await footerLinks(page)).toEqual(FOOTER_LINKS);

  await page.goto('/auth?auth=failed');
  await expect(page.getByText('Sign-in did not complete.')).toBeVisible();
  expect(await footerLinks(page)).toEqual(FOOTER_LINKS);

  await stubActiveAccount(page);
  await page.goto('/account');
  await page.getByText('learner@example.com').waitFor();
  expect(await footerLinks(page)).toEqual(FOOTER_LINKS);
});

test('the account and auth tabs carry their own title', async ({ page }) => {
  await stubActiveAccount(page);
  await page.goto('/account');
  await page.getByText('learner@example.com').waitFor();
  expect(await page.title()).toBe('Your account | mytetz');

  await page.goto('/auth?auth=failed');
  await expect(page.getByText('Sign-in did not complete.')).toBeVisible();
  expect(await page.title()).toBe('Sign-in | mytetz');
});

test('each footer link navigates to its own legal page', async ({ page }) => {
  await stubCatalogueAndSession(page);

  for (const [label, path] of [
    ['Privacy', '/privacy'],
    ['Terms', '/terms'],
    ['Imprint', '/imprint'],
  ] as const) {
    await page.goto('/');
    await page.locator('.topic__tile').first().waitFor();
    await page.locator('footer a', { hasText: label }).click();
    await expect(page).toHaveURL(new RegExp(`${path}$`));
  }
});

test('the privacy page states the technical facts the spec requires', async ({ page }) => {
  await page.goto('/privacy');

  const body = await page.locator('main').innerText();
  for (const fact of [
    'mytetz_pid',
    'mytetz_sid',
    'mytetz_g_state',
    'mytetz_g_verifier',
    'fly.io',
    'Frankfurt',
    'MongoDB Atlas',
    'eu-central-1',
    'Cloudflare',
    'Anthropic',
    'Resend',
    'Google',
    'Freemius',
    '30 days',
    '90 days',
    'Turnstile',
    'Wikimedia',
  ]) {
    expect(body, `the privacy page names "${fact}"`).toContain(fact);
  }

  await expect(page.locator('a[href="/account"]')).toBeVisible();
});

test('the terms page states the price, the trial and the daily allowance', async ({ page }) => {
  await page.goto('/terms');

  const body = await page.locator('main').innerText();
  for (const fact of ['$12', '40 tokens', '25 tokens', '14 days']) {
    expect(body, `the terms page names "${fact}"`).toContain(fact);
  }
});

test('the account page links to the terms next to the subscribe control', async ({ page }) => {
  // ACTIVE: issue #177 changes nothing about this row. "Manage subscription" stays here for
  // every status that already showed it.
  await stubActiveAccount(page);
  await page.goto('/account');
  await page.getByText('learner@example.com').waitFor();

  // Scoped to the actions row: the footer also carries a "Terms" link, and a bare
  // `getByRole('link', { name: 'Terms' })` would match both.
  const actions = page.locator('.account-page__actions');
  await expect(actions.locator('[data-action="manage-subscription"]')).toBeVisible();
  await actions.getByRole('link', { name: 'Terms' }).click();
  await expect(page).toHaveURL(/\/terms$/);
});
