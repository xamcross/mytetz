import { expect, type BrowserContext, type Page } from '@playwright/test';
import { selectPhrase } from '../e2e/support';

/**
 * Issue #182: the steps of the listing capture. `listing-capture.spec.ts` calls them in order.
 * A step takes a `Page` and nothing else, so a local proof can call a step against the stubbed
 * dev server of the e2e suite.
 *
 * No step prints a link or a cookie.
 */

export const VIEWPORT = { width: 1270, height: 760 } as const;

/** The most model calls that one run may make. The capture stops before it passes this number. */
export const MAX_MODEL_CALLS = 8;

const STREAM_TIMEOUT_MS = 120_000;

/** The model calls that one run has made. All contexts of the run share one counter. */
export interface ModelCallCounter {
  count: number;
}

/**
 * Lets at most `MAX_MODEL_CALLS` requests that can start a model call leave the browser.
 * The route aborts each further request, so the run cannot pass the limit.
 */
export async function guardModelCalls(
  context: BrowserContext,
  counter: ModelCallCounter,
): Promise<void> {
  await context.route(/\/api\/sessions\/[^/]+\/(explain|quizzes)$/, (route) => {
    if (route.request().method() !== 'POST') return route.continue();
    if (counter.count >= MAX_MODEL_CALLS) return route.abort();
    counter.count += 1;
    return route.continue();
  });
}

/** Stops the run with a clear message when the page shows a Cloudflare challenge. */
export async function assertNoChallenge(page: Page): Promise<void> {
  const title = await page.title();
  const challenge =
    /just a moment|attention required|verify you are human/i.test(title) ||
    (await page.locator('#challenge-form, #cf-challenge-running, .cf-turnstile').count()) > 0;
  if (challenge) {
    throw new Error(
      'Cloudflare shows a challenge. The capture stops here and does not try to pass it. ' +
        'Open the site in a browser, then ask the maintainer how to proceed.',
    );
  }
}

/** Opens the sign-in link. The server signs the test learner in and redirects to `/`. */
export async function openSignInLink(page: Page, link: string): Promise<void> {
  try {
    await page.goto(link, { waitUntil: 'domcontentloaded' });
  } catch {
    // The Playwright message holds the URL, and the URL holds the token.
    throw new Error('the sign-in link did not open');
  }
  await assertNoChallenge(page);
  // A failed sign-in leaves the learner anonymous, and `/api/account` then answers 401.
  const account = await page.request.get('/api/account');
  if (!account.ok()) {
    throw new Error(`the sign-in failed: /api/account answered ${account.status()}`);
  }
}

/** Step 2: the dashboard, with the topic tiles on screen. */
export async function showDashboard(page: Page): Promise<void> {
  await expect(page.locator('a[data-slug]').first()).toBeVisible({ timeout: 30_000 });
  await page.waitForLoadState('networkidle');
  // The tiles drop in with an animation. Wait until it ends.
  await page.waitForTimeout(2_000);
}

/** Step 3: opens a topic from the dashboard. Ends on the topic text. */
export async function openTopic(page: Page, slug: string): Promise<void> {
  await page.locator(`a[data-slug="${slug}"]`).click();
  await expect(page.locator('h1')).toBeVisible();
  await assertNoChallenge(page);
}

/** Presses "Start with this topic" and waits for the reader. */
export async function startReader(page: Page): Promise<void> {
  const start = page.locator('#topic-start-button');
  await expect(start).toBeEnabled({ timeout: 30_000 });
  await start.click();
  await page.waitForURL(/\/learn\//, { timeout: 60_000 });
  await expect(page.getByTestId('focus-body')).toBeVisible({ timeout: 60_000 });
}

/** Picks two plain words from the middle of the text that the reader shows. */
async function pickPhrase(page: Page): Promise<string> {
  const text = (await page.getByTestId('focus-body').textContent()) ?? '';
  const words = text.split(/\s+/).filter((word) => /^[A-Za-z]{4,}$/.test(word));
  if (words.length < 6) throw new Error('the text has too few plain words to pick a phrase');
  const start = Math.floor(words.length / 3);
  return words.slice(start, start + 2).join(' ');
}

/** Highlights a phrase with a real mouse drag, presses "Explain it", and waits for the stream end. */
export async function explainPhrase(page: Page): Promise<void> {
  const before = await page.locator('.crumb').count();
  const phrase = await pickPhrase(page);
  await selectPhrase(page, 'focus-body', phrase);
  await page
    .locator('[role="dialog"]')
    .getByRole('button', { name: 'Explain it', exact: true })
    .click();
  await expect(page.locator('.crumb')).toHaveCount(before + 1, { timeout: STREAM_TIMEOUT_MS });
  await expect(page.locator('.focus__caret')).toHaveCount(0, { timeout: STREAM_TIMEOUT_MS });
  await expect(page.locator('.focus__streaming')).toHaveCount(0, { timeout: STREAM_TIMEOUT_MS });
}

/** Step 6: opens Test Me and waits for the first question. */
export async function openTestMe(page: Page): Promise<void> {
  await page.getByTestId('test-me').click();
  await expect(page.locator('[role="region"]').getByRole('button').first()).toBeVisible({
    timeout: STREAM_TIMEOUT_MS,
  });
}

/** Step 8: deletes the test learner through the delete control on `/account`. */
export async function deleteTestLearner(page: Page): Promise<void> {
  await page.goto('/account');
  await page.locator('[data-action="delete-account"]').click();
  await page.locator('[data-action="delete-account-confirm"]').click();
  await expect(page.locator('[data-action="delete-account-confirm"]')).toHaveCount(0, {
    timeout: 30_000,
  });
}
