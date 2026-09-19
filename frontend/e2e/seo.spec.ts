import { test, expect } from '@playwright/test';

/**
 * Issue #32 and issue #33's own acceptance criteria: a crawler reads a real `robots.txt`, not the
 * Angular shell that `default("index.html")` used to answer with. `robots.txt` lives under
 * `frontend/public/`, so `ng serve` and the production Ktor build both serve it from the site root
 * with no route of its own.
 *
 * Issue #46 replaces `frontend/public/sitemap.xml` with `GET /sitemap.xml`, a Ktor route.
 * `SitemapRoutesTest.kt` proves that route's own content, so this file no longer does.
 */

test('robots.txt is a plain-text file that allows every crawler and names the sitemap', async ({
  request,
}) => {
  const response = await request.get('/robots.txt');
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toContain('text/plain');

  const body = await response.text();
  expect(body).toContain('User-agent: *');
  expect(body).toContain('Allow: /');
  expect(body).toContain('Sitemap: https://mytetz.com/sitemap.xml');
  // The catalogue page reads `/api/catalog/topics` at render time, so a disallow on `/api/` would
  // leave every crawler's render of `/` empty. See issue #33, implementation step 2.
  expect(body).not.toContain('Disallow: /api');
});

test('this dev server holds no static sitemap.xml file', async ({ request }) => {
  // Issue #46 deletes frontend/public/sitemap.xml. This suite runs `ng serve` alone, with no Ktor
  // backend beside it (see this file's own webServer command), so a request for /sitemap.xml here
  // must answer 404 and not the deleted file's old body. A 200 here would mean a stray copy of the
  // static file came back, silently out of step with the real route SitemapRoutesTest.kt covers.
  const response = await request.get('/sitemap.xml');
  expect(response.status()).toBe(404);
});

/**
 * Issue #47 step 3 and spec section 11: the home page names one `Organization`, the same node
 * `/how-it-works` carries, so an answer engine reads one `Organization` for the whole site and
 * not two that disagree. Issue #34 already gives `/` a `WebSite` node; this test proves the page
 * still carries both, in one `@graph`, and not a second `<script>` block.
 */
test('the home page carries one WebSite node and one Organization node, in one JSON-LD graph', async ({
  page,
}) => {
  await page.goto('/');

  const scripts = await page.locator('script[type="application/ld+json"]').all();
  expect(scripts).toHaveLength(1);

  const parsed = JSON.parse((await scripts[0].textContent()) ?? '');
  expect(parsed['@context']).toBe('https://schema.org');
  const graph = parsed['@graph'] as Array<Record<string, unknown>>;

  const website = graph.find((node) => node['@type'] === 'WebSite');
  expect(website?.name).toBe('mytetz');
  expect(website?.url).toBe('https://mytetz.com/');

  const organization = graph.find((node) => node['@type'] === 'Organization');
  expect(organization?.['@id']).toBe('https://mytetz.com/#organization');
  expect(organization?.name).toBe('mytetz');
  expect(organization?.url).toBe('https://mytetz.com');
  expect(organization?.logo).toBe('https://mytetz.com/icon.svg');
  expect(organization?.sameAs).toEqual(['https://github.com/xamcross/mytetz']);
});
