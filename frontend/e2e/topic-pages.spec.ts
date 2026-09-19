import { test, expect } from '@playwright/test';
import { SEED, stubCatalogueAndSession } from './support';

/**
 * Issue #45's own acceptance criterion asks for one Playwright path: "tile link > topic page >
 * start > reader". This file states exactly how far this suite can carry that path, and why, and
 * then carries it as far as that limit allows.
 *
 * `playwright.config.ts`'s own doc comment states the shape of this suite: it runs against a bare
 * `ng serve`, with no Ktor process and no Mongo alongside it. `GET /topics/{slug}` is a Ktor route
 * (`TopicPageRoutes.kt`), so `ng serve` cannot answer it for real. `topicPageFixture` below stands
 * in for that page, fulfilled through `page.route` exactly like every `/api/**` stub in this
 * suite — this suite proves the *client* path (a real browser click, a real network request, a
 * real navigation), and `TopicPageRoutesTest.kt` (backend, `:backend:api:test`) proves the
 * *server* path: that Ktor really renders this markup for a real, published topic.
 *
 * `/topic-start.js` is not stubbed. `ng serve` serves `frontend/public/*` for real, so this file
 * is the one real, unstubbed piece of behaviour this suite drives — the same file
 * `TopicPageRoutesTest.kt` and `TopicPageHtmlTest.kt` prove is linked and escaped correctly, now
 * proved to actually work in a real browser against a real (stubbed) `POST /api/sessions`.
 */

/**
 * The control markup `TopicPageHtmlTest.kt`'s own
 * `the start control holds the button with the escaped slug, the alert paragraph and the noscript
 * text` test pins, with `special-relativity` swapped for [slug]. Kept identical on purpose: a
 * change to the real button, the alert paragraph or the noscript text must change in both places,
 * or this suite tests a fixture the backend no longer builds.
 */
function topicPageFixture(slug: string, title: string): string {
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <title>${title} explained simply | mytetz</title>
    <script src="/topic-start.js" defer></script>
  </head>
  <body>
    <h1>${title}</h1>
    <section class="start">
      <h2>Start with this topic</h2>
      <button type="button" id="topic-start-button" class="start__cta" data-topic-slug="${slug}">Start with this topic</button>
      <p id="topic-start-error" class="start__error" role="alert"></p>
      <noscript><p>The Start with this topic button needs JavaScript.</p></noscript>
    </section>
  </body>
</html>`;
}

test('the full path: tile link, topic page, start, reader', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await page.route('**/topics/quantum-physics', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: topicPageFixture('quantum-physics', 'Quantum Physics'),
    }),
  );

  // Tile link: a real click on the real, Angular-rendered catalogue tile.
  await page.goto('/');
  await page.locator('a.topic__tile').first().click();

  // Topic page: the fixture answered the navigation, standing in for the real Ktor route.
  await expect(page.locator('h1')).toHaveText('Quantum Physics');

  // Start: a real click, running the real, unstubbed topic-start.js against the stubbed
  // POST /api/sessions stubCatalogueAndSession already installed.
  await page.getByRole('button', { name: 'Start with this topic' }).click();

  // Reader: the real Angular route at /learn/:sessionId, rendering the stubbed session's seed.
  await page.getByText(SEED).waitFor();
});

test('a second click while the request is in flight sends no second request', async ({ page }) => {
  let sessionRequests = 0;
  await page.route('**/api/sessions', (route) => {
    sessionRequests += 1;
    route.fulfill({
      json: {
        sessionId: 's1',
        topicSlug: 'quantum-physics',
        rootNodeId: 'n0',
        currentNodeId: 'n0',
        nodes: [],
        status: 'ACTIVE',
        explanations: { k0: SEED },
      },
    });
  });
  await page.route('**/api/sessions/s1', (route) =>
    route.fulfill({
      json: {
        sessionId: 's1',
        topicSlug: 'quantum-physics',
        rootNodeId: 'n0',
        currentNodeId: 'n0',
        nodes: [
          {
            nodeId: 'n0',
            parentNodeId: null,
            explanationKey: 'k0',
            span: '',
            verb: 'SEED',
            variant: 0,
            depth: 0,
          },
        ],
        status: 'ACTIVE',
        explanations: { k0: SEED },
      },
    }),
  );
  await page.route('**/topics/quantum-physics', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: topicPageFixture('quantum-physics', 'Quantum Physics'),
    }),
  );

  await page.goto('/topics/quantum-physics');

  // Two clicks in the same task, through the DOM's own click() method rather than Playwright's
  // action-checked click(): once the first click's synchronous handler code disables the button,
  // Playwright's own click() would wait for it to become enabled again, which never happens on
  // this success path — it navigates away instead. Calling click() twice in one page.evaluate is
  // what a genuine double-click or a stuck key produces: both events land before the fetch the
  // first one starts has any chance to resolve.
  await page.evaluate(() => {
    const button = document.getElementById('topic-start-button') as HTMLButtonElement;
    button.click();
    button.click();
  });

  await page.getByText(SEED).waitFor();
  expect(sessionRequests).toBe(1);
});

test('a 429 refusal shows the backend message and how long to wait', async ({ page }) => {
  await page.route('**/topics/quantum-physics', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: topicPageFixture('quantum-physics', 'Quantum Physics'),
    }),
  );
  await page.route('**/api/sessions', (route) =>
    route.fulfill({
      status: 429,
      json: {
        code: 'RATE_LIMITED',
        message: 'too many sessions started; try again later',
        retryAfter: 3600,
      },
    }),
  );

  await page.goto('/topics/quantum-physics');
  await page.getByRole('button', { name: 'Start with this topic' }).click();

  await expect(page.getByRole('alert')).toHaveText(
    'too many sessions started; try again later Try again in 1 hour.',
  );
  await expect(page.getByRole('button', { name: 'Start with this topic' })).toBeEnabled();
});

test('a failure with no usable body shows the general message, and re-enables the button', async ({
  page,
}) => {
  await page.route('**/topics/quantum-physics', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: topicPageFixture('quantum-physics', 'Quantum Physics'),
    }),
  );
  await page.route('**/api/sessions', (route) =>
    route.fulfill({ status: 500, contentType: 'text/plain', body: 'internal server error' }),
  );

  await page.goto('/topics/quantum-physics');
  const button = page.getByRole('button', { name: 'Start with this topic' });
  await button.click();

  await expect(page.getByRole('alert')).toHaveText('Could not start that topic. Please try again.');
  await expect(button).toBeEnabled();
  await expect(button).toHaveText('Start with this topic');
});

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
  // client-side route change.
});
