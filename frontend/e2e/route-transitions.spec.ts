import { test, expect, type Page } from '@playwright/test';
import { accountView, stubAccount } from './support';

/**
 * Animation N, defect 1 of the design review's second round. `AccountPageComponent.
 * removeQueryString` calls `router.navigate([], { queryParams: {}, replaceUrl: true })` after
 * the Freemius return. Before the fix, that call started a real view transition: the whole
 * account page faded out and in for 320ms, and took no input during that time, for a navigation
 * that never changed the route.
 *
 * `document.startViewTransition` wraps itself here, before the application loads, so this test
 * sees every call the router makes and every call to the returned `ViewTransition`'s own
 * `skipTransition()`. A `record` is pushed for each call to the native function, and its
 * `skipped` flag flips true only once `skipTransition()` runs on the transition it belongs to.
 */
async function trackViewTransitions(page: Page): Promise<void> {
  await page.addInitScript(() => {
    (window as unknown as { __vt: { skipped: boolean }[] }).__vt = [];
    const native = document.startViewTransition?.bind(document);
    if (!native) return;
    document.startViewTransition = ((callback?: () => void | Promise<void>) => {
      const record = { skipped: false };
      (window as unknown as { __vt: { skipped: boolean }[] }).__vt.push(record);
      const transition = native(callback);
      const originalSkip = transition.skipTransition.bind(transition);
      transition.skipTransition = () => {
        record.skipped = true;
        originalSkip();
      };
      return transition;
    }) as typeof document.startViewTransition;
  });
}

async function viewTransitionCalls(page: Page): Promise<{ skipped: boolean }[]> {
  return page.evaluate(() => (window as unknown as { __vt: { skipped: boolean }[] }).__vt);
}

test('a query-string-only navigation starts no real view transition', async ({ page }) => {
  await trackViewTransitions(page);
  await stubAccount(page, accountView());

  await page.goto('/account?action=subscribe&plan_id=1');
  await page.waitForFunction(() => location.search === '');

  const calls = await viewTransitionCalls(page);
  // The router still calls `startViewTransition` for the navigation that clears the query
  // string — see the class comment above. The fix is that every such call ends up skipped, not
  // that the router never calls the native function at all.
  expect(
    calls.length,
    'the router calls startViewTransition for the query-clearing navigation',
  ).toBeGreaterThan(0);
  expect(
    calls.every((call) => call.skipped),
    'every call for a query-string-only change is skipped',
  ).toBe(true);
});

test('a real change of route still starts one view transition, and it is not skipped', async ({
  page,
}) => {
  await trackViewTransitions(page);
  await stubAccount(page, accountView());

  await page.goto('/');
  await page.locator('a.bar__account').click();
  await page.waitForURL('**/account');

  const calls = await viewTransitionCalls(page);
  expect(calls.length, 'a real navigation calls startViewTransition').toBeGreaterThan(0);
  expect(
    calls.some((call) => !call.skipped),
    'a real navigation is not skipped',
  ).toBe(true);
});
