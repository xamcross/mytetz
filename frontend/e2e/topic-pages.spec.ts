import { test, expect } from '@playwright/test';
import { stubCatalogueAndSession } from './support';

/**
 * Issue #45's own acceptance criterion asks for one Playwright path: "tile link > topic page >
 * start > reader". This file states exactly how far this suite can carry that path, and why.
 *
 * `playwright.config.ts`'s own doc comment states the shape of this suite: it runs against a bare
 * `ng serve`, with no Ktor process and no Mongo alongside it, and every `/api/**` call a spec makes
 * is stubbed with `page.route`. `GET /topics/{slug}` is a Ktor route (`TopicPageRoutes.kt`), so
 * this suite never runs the code under test for that page at all — `ng serve` has no such route,
 * and its own dev-server fallback answers with the Angular shell instead, which is not what
 * production serves at that URL.
 *
 * So this file proves the one thing it can prove with a real browser and no stub standing in for
 * the destination: the catalogue tile is a genuine `<a href="/topics/<slug>">`, so a browser
 * navigates to it as a normal document load rather than an Angular route change. It does not, and
 * cannot, prove that the destination renders the topic page, that "Start with this topic" exists
 * there, or that starting a session from it reaches the reader — `TopicPageRoutesTest.kt` (backend,
 * `:backend:api:test`) is where the destination itself is proved, against the real Ktor route.
 */

test('the catalogue tile is a real link to its topic page, not an Angular route change', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  await page.goto('/');

  const tile = page.locator('a.topic__tile').first();
  await tile.waitFor();

  await expect(tile).toHaveAttribute('href', '/topics/quantum-physics');
  // A real <a href> next to no [routerLink] usage in the component's own template (checked in
  // catalog-page.component.ts) is what makes this a full document navigation rather than a
  // client-side route change — Playwright has no direct way to assert "this click will not be
  // intercepted by the Angular router" other than the href itself resolving to a path
  // app.routes.ts does not own.
});
