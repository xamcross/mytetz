import { test, expect } from '@playwright/test';
import { accountView, billingPlansView, stubAccount, stubBillingPlans } from './support';

/**
 * The plan screen at `/subscribe` (issue #137), end to end: the account page's own Subscribe
 * control opens it, it shows the stubbed plan numbers, Subscribe starts exactly one checkout
 * call, and Stay on the free plan sends none and leaves the page. `stubAccount` and
 * `stubBillingPlans`, from `./support`, are the same two stubs every unit spec of this issue
 * exercises against a real HTTP layer instead.
 */

test('a learner in trial opens the plan screen from the account page, and Subscribe starts one checkout', async ({
  page,
}) => {
  await stubAccount(page, accountView({ status: 'TRIALING', remaining: 17 }));
  await stubBillingPlans(page, billingPlansView({ subscriberDailyExplains: 25 }));
  await page.goto('/account');

  const subscribe = page.locator('[data-action="subscribe"]');
  await expect(subscribe).toBeVisible();
  await subscribe.click();

  await expect(page).toHaveURL('/subscribe');
  await expect(page.locator('h1')).toHaveText('Choose your plan');
  await expect(page.getByText('25 tokens each day.')).toBeVisible();
  await expect(page.getByText('40 tokens over 7 days.')).toBeVisible();

  let checkoutCalls = 0;
  await page.route('**/api/billing/checkout', (route) => {
    checkoutCalls += 1;
    route.fulfill({ json: { url: 'https://checkout.freemius.com/product/1/plan/2/' } });
  });
  // A click sends the real browser to the returned link with `window.location.href`, which the
  // rule against starting a real checkout forbids letting reach the actual vendor. This stub
  // answers that one address locally, entirely inside Playwright's own network layer, so the
  // click's real navigation has somewhere safe to land.
  await page.route('https://checkout.freemius.com/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/html', body: '<p>stub checkout page</p>' }),
  );

  await page.locator('[data-action="subscribe"]').click();

  await expect(page).toHaveURL('https://checkout.freemius.com/product/1/plan/2/');
  expect(checkoutCalls, 'the click sends exactly one checkout request').toBe(1);
});

test('Stay on the free plan sends no request, and leaves the plan screen', async ({ page }) => {
  await stubAccount(page, accountView({ status: 'TRIALING' }));
  await stubBillingPlans(page);
  await page.goto('/account');
  await page.locator('[data-action="subscribe"]').click();
  await expect(page).toHaveURL('/subscribe');

  let checkoutCalls = 0;
  await page.route('**/api/billing/checkout', (route) => {
    checkoutCalls += 1;
    route.fulfill({ json: { url: 'https://example.com/checkout' } });
  });

  await page.locator('[data-action="stay-on-free-plan"]').click();

  await expect(page).toHaveURL('/account');
  expect(checkoutCalls, 'Stay on the free plan must send no checkout request').toBe(0);
});

test('an active subscriber sees no Subscribe control on the plan screen', async ({ page }) => {
  await stubAccount(page, accountView({ status: 'ACTIVE' }));
  await stubBillingPlans(page);
  await page.goto('/subscribe');

  await expect(page.locator('h1')).toHaveText('Choose your plan');
  await expect(page.locator('[data-action="subscribe"]')).toHaveCount(0);
  await expect(page.getByText('You already have a subscription.')).toBeVisible();
  const manage = page.getByRole('link', { name: 'Manage subscription' });
  await expect(manage).toHaveAttribute('href', '/account');
});

test('a visitor with no account sees the two plans and a sign-in link', async ({ page }) => {
  // No stubAccount call: GET /api/account answers with whatever a bare dev server gives a
  // request it never registered a route for, and AccountStore.load() reads that as "signed out"
  // the same way every other spec in this suite already relies on — see support.ts's own
  // `stubAccount` doc comment.
  await stubBillingPlans(page);
  await page.goto('/subscribe');

  await expect(page.locator('h1')).toHaveText('Choose your plan');
  const signIn = page.getByRole('link', { name: 'Sign in to subscribe' });
  await expect(signIn).toHaveAttribute('href', '/auth');
  await expect(page.getByText('Stay on the free plan')).toBeVisible();
});

test('at 390px the plan screen does not scroll sideways, and the two cards stand one below the other', async ({
  page,
}) => {
  await stubAccount(page, accountView({ status: 'TRIALING' }));
  await stubBillingPlans(page);
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/subscribe');

  const premium = page.locator('.subscribe-page__card').first();
  const free = page.locator('.subscribe-page__card').last();
  await expect(premium).toBeVisible();
  await expect(free).toBeVisible();

  const premiumBox = (await premium.boundingBox())!;
  const freeBox = (await free.boundingBox())!;
  expect(freeBox.y, 'the free card sits below the premium card at 390px').toBeGreaterThanOrEqual(
    premiumBox.y + premiumBox.height,
  );

  const doc = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(doc.scroll, 'the page does not scroll sideways at 390px').toBeLessThanOrEqual(doc.client);
});
