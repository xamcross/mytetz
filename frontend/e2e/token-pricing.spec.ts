import { test, expect, type Page } from '@playwright/test';
import {
  CHILD,
  accountView,
  explainedView,
  mockExplainStream,
  openQuantumPhysicsSession,
  selectPhrase,
  sseFrame,
  stubAccount,
  stubCatalogueAndSession,
} from './support';

/**
 * Issue #139. The picker shows "1 token" on each verb for a signed-in learner with a live count,
 * and none for a visitor with no account. After an explanation the learner reads the true result —
 * "1 token used" or "no token used" — from the account's own count before and after, never a guess
 * (a cache hit spends nothing; see `SessionRoutes.kt`'s own comment on `recordSpend`).
 *
 * `stubAccount` answers every `GET /api/account` with one fixed view. `page.route` keeps only the
 * most recently registered handler for a pattern, so a second `stubAccount` call for the same
 * route replaces the first from that point on — used below to change the count only once the
 * explanation has actually finished, and not for the reader's own earlier reads on page load.
 */
function verb(page: Page, name: string) {
  return page.locator('[role="dialog"]').getByRole('button', { name, exact: true });
}

test('the picker shows "1 token" on each verb for a signed-in learner with a live count', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await stubAccount(page, accountView({ status: 'TRIALING', allowance: 40, remaining: 36 }));
  await openQuantumPhysicsSession(page);

  await selectPhrase(page, 'focus-body', 'fundamental physical theory');

  for (const name of [
    'Explain it',
    'Dig deeper',
    'Broader picture',
    'Side view',
    'Show me a diagram',
  ]) {
    await expect(verb(page, name)).toContainText('1 token');
  }
});

test('the picker shows no price for a visitor with no account', async ({ page }) => {
  await stubCatalogueAndSession(page);
  // No `stubAccount` call: `GET /api/account` reaches the dev server with no backend behind it,
  // answers with something other than 401, and `AccountStore.load` leaves `view` at `null` — the
  // same signed-out default every other spec in this suite relies on before issue #100.
  await openQuantumPhysicsSession(page);

  await selectPhrase(page, 'focus-body', 'fundamental physical theory');

  await expect(verb(page, 'Explain it')).not.toContainText('1 token');
});

test('after an explanation that spends a token, the learner reads "1 token used" with the new count', async ({
  page,
}) => {
  await stubCatalogueAndSession(page, explainedView(CHILD));
  await stubAccount(page, accountView({ status: 'TRIALING', allowance: 40, remaining: 36 }));
  const stream = await mockExplainStream(page, 's1');
  await openQuantumPhysicsSession(page);

  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await verb(page, 'Explain it').click();
  // Switches the stub only now, after every read the page made on its own way here — the reader's
  // initial load among them — has already happened. The one read `SessionStore.explain` makes
  // after `done` is the only one this new stub answers, so it alone sees the count go down.
  await stubAccount(page, accountView({ status: 'TRIALING', allowance: 40, remaining: 35 }));
  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: CHILD }));
  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: true }));
  await stream.close();

  await expect(page.getByTestId('focus-body')).toContainText('subatomic scale');
  await expect(page.locator('.focus__token-result')).toHaveText('1 token used. 35 tokens left.');
});

test('after an explanation that reuses an existing answer, the learner reads that no token was used', async ({
  page,
}) => {
  await stubCatalogueAndSession(page, explainedView(CHILD));
  // The same count on both reads: a cache hit records nothing, so `remaining` does not move.
  await stubAccount(page, accountView({ status: 'TRIALING', allowance: 40, remaining: 36 }));
  const stream = await mockExplainStream(page, 's1');
  await openQuantumPhysicsSession(page);

  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await verb(page, 'Explain it').click();
  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: true }));
  await stream.send(sseFrame('delta', { t: CHILD }));
  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: true }));
  await stream.close();

  await expect(page.getByTestId('focus-body')).toContainText('subatomic scale');
  await expect(page.locator('.focus__token-result')).toHaveText(
    'No token used. This text existed already.',
  );
});

test('the picker and the control row do not scroll sideways at 390px, with a price on every verb', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await stubAccount(page, accountView({ status: 'TRIALING', allowance: 40, remaining: 36 }));
  await page.setViewportSize({ width: 390, height: 900 });
  await openQuantumPhysicsSession(page);

  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await expect(verb(page, 'Explain it')).toContainText('1 token');

  const doc = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(doc.scroll, 'the picker does not scroll the page sideways at 390px').toBeLessThanOrEqual(
    doc.client,
  );

  await page.keyboard.press('Escape');
  await expect(page.locator('[role="dialog"]')).toHaveCount(0);

  const controlsDoc = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(
    controlsDoc.scroll,
    'the control row does not scroll the page sideways at 390px',
  ).toBeLessThanOrEqual(controlsDoc.client);
});
