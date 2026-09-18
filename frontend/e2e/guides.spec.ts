import { test, expect } from '@playwright/test';
import { stubCatalogueAndSession } from './support';

/**
 * Issue #63's own acceptance criterion on the click path: the footer link opens the hub, and a hub
 * link opens a guide.
 *
 * These pages are hand-written HTML under `frontend/public/guides`, not Angular routes, so every
 * link below leaves the application and makes a full document load. That is deliberate:
 * `app.routes.ts` has no `guides` path, so a `routerLink` would open `NotFoundPageComponent`.
 *
 * The assertions read the heading and never the URL. The two servers disagree on the URL, and both
 * are correct:
 *
 * - `ng serve`, which this suite runs against, answers `/guides` with a 301 to `/guides/`.
 * - Ktor answers `/guides` with 200 and no redirect — see `spaRoutes()` and issue #62.
 *
 * Each page's own canonical tag names the one URL a search engine should keep, so the difference
 * costs nothing.
 */

test('the footer link opens the hub, and a hub link opens a guide', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.goto('/');
  await page.locator('.topic__button').first().waitFor();

  await page.locator('footer a', { hasText: 'Guides' }).click();
  await expect(page.locator('h1')).toHaveText('Study guides');

  await page.locator('.index a', { hasText: 'How to study on your own' }).click();
  await expect(page.locator('h1')).toHaveText('How to study on your own');
});

test('a guide page carries its own canonical tag and needs no JavaScript', async ({ page }) => {
  await page.goto('/guides/what-to-use-instead-of-a-highlighter');

  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    'href',
    'https://mytetz.com/guides/what-to-use-instead-of-a-highlighter',
  );
  // No `app-root`: a crawler that runs no JavaScript still reads the whole page.
  await expect(page.locator('app-root')).toHaveCount(0);
  await expect(page.locator('h1')).toHaveText('What to use instead of a highlighter');
});

test('every guide in the hub index answers with its own heading', async ({ page }) => {
  await page.goto('/guides');

  const links = page.locator('.index a');
  const count = await links.count();
  expect(count).toBe(6);

  for (let i = 0; i < count; i++) {
    const heading = (await links.nth(i).locator('h2').textContent())?.trim();
    const href = await links.nth(i).getAttribute('href');
    expect(href, 'every hub link points under /guides/').toMatch(/^\/guides\//);

    await page.goto(href!);
    await expect(page.locator('h1'), `${href} names itself in its h1`).toHaveText(heading!);
    await page.goBack();
  }
});
