import { test, expect } from '@playwright/test';

/**
 * Issue #32 and issue #33's own acceptance criteria: a crawler reads a real `sitemap.xml` and a
 * real `robots.txt`, not the Angular shell that `default("index.html")` used to answer with. Both
 * files live under `frontend/public/`, so `ng serve` and the production Ktor build both serve them
 * from the site root without a route of their own.
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

test('sitemap.xml lists the home page and every guide, in a valid urlset', async ({ request }) => {
  const response = await request.get('/sitemap.xml');
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toContain('xml');

  const body = await response.text();
  expect(body).toContain('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">');
  expect(body).toContain('<loc>https://mytetz.com/</loc>');
  // Issue #63 adds the guide pages. Issue #46 replaces this static file with a Ktor route, and
  // that route must keep every URL below — see step 6 of #46.
  for (const slug of [
    '/guides',
    '/guides/how-to-study-on-your-own',
    '/guides/what-to-use-instead-of-a-highlighter',
    '/guides/how-to-test-yourself-while-you-read',
    '/guides/when-to-review-what-you-read',
    '/guides/how-students-study-now',
    '/guides/why-a-person-stops-an-online-course',
  ]) {
    expect(body).toContain(`<loc>https://mytetz.com${slug}</loc>`);
  }
  // The reader, the account page and the auth page all sit behind the sign-in wall or the
  // magic-link flow — see issue #32, implementation step 2 — so none of them belongs here.
  for (const path of ['/learn/', '/account', '/auth']) {
    expect(body).not.toContain(path);
  }
});
