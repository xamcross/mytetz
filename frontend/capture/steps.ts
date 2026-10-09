import { expect, type BrowserContext, type Page } from '@playwright/test';
import { selectPhrase } from '../e2e/support';

/**
 * Issue #182: the steps of the listing capture. `listing-capture.spec.ts` calls them in order.
 * A step takes a `Page` and nothing else, so a local proof can call a step against the stubbed
 * dev server of the e2e suite.
 *
 * No step prints a link or a cookie.
 */

/** The one account that the capture may sign in and delete. */
export const CAPTURE_EMAIL = 'listing-capture@mytetz.com';

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
  // The delete step removes whichever account this link signs in. Check the address first.
  // The server normalises an address with trim and lower case. The message never names the address.
  const body = (await account.json()) as { email?: unknown };
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  if (email !== CAPTURE_EMAIL) {
    throw new Error(
      `the link signs in an account other than ${CAPTURE_EMAIL}. The capture stops and deletes nothing.`,
    );
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

/** Words that join a phrase badly. A phrase never runs across one of them. */
const FILLER_WORDS = new Set(
  (
    'that this these those with from into than then them they their there what when where which while ' +
    'have has had been were was will would could should about also each such some more most very ' +
    'only other over under your yours does did not but and the for are can its our out any all too ' +
    'like just because between through'
  ).split(' '),
);

/**
 * Chooses a drill phrase from `text`: two or three whole words with no punctuation inside, which
 * occur one time in the text. It prefers the phrase nearest to the middle of the text. It gives
 * back `null` when the text holds no such phrase.
 *
 * The model writes a new text for each request, so a fixed phrase is not always in the text
 * (issue #191). This function reads the phrase from the text itself.
 */
export function choosePhrase(text: string): string | null {
  // A run is a series of plain words that only single spaces separate. A comma, a full stop, a
  // hyphen, or a filler word ends the run.
  const runs: Array<{ words: string[]; end: number }> = [];
  const wordPattern = /[A-Za-z]{4,}/g;
  let current: { words: string[]; end: number } | null = null;
  for (const match of text.matchAll(wordPattern)) {
    const start = match.index ?? 0;
    const word = match[0];
    const end = start + word.length;
    const before = start > 0 ? text[start - 1] : ' ';
    const after = end < text.length ? text[end] : ' ';
    const plain = !/[A-Za-z�'-]/.test(before) && !/[A-Za-z�'-]/.test(after);
    if (!plain || FILLER_WORDS.has(word.toLowerCase())) {
      if (current) runs.push(current);
      current = null;
      continue;
    }
    if (current && text.slice(current.end, start) === ' ') {
      current.words.push(word);
      current.end = end;
    } else {
      if (current) runs.push(current);
      current = { words: [word], end };
    }
  }
  if (current) runs.push(current);

  const middle = text.length / 2;
  let best: { phrase: string; distance: number } | null = null;
  for (const run of runs) {
    for (let size = 2; size <= 3; size += 1) {
      for (let i = 0; i + size <= run.words.length; i += 1) {
        const phrase = run.words.slice(i, i + size).join(' ');
        if (text.split(phrase).length !== 2) continue;
        const distance = Math.abs(text.indexOf(phrase) - middle);
        if (best === null || distance < best.distance) best = { phrase, distance };
      }
    }
  }
  return best === null ? null : best.phrase;
}

/** Picks a drill phrase from the text that the reader shows now. */
async function pickPhrase(page: Page): Promise<string> {
  const text = (await page.getByTestId('focus-body').textContent()) ?? '';
  const phrase = choosePhrase(text);
  if (phrase === null) throw new Error('the text holds no phrase of two or three plain words');
  return phrase;
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
