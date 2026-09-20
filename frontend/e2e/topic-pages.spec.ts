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
    <link rel="stylesheet" href="/guides/guides.css" />
    <script src="/topic-start.js" defer></script>
  </head>
  <body>
    <h1>${title}</h1>
    <section class="start">
      <button type="button" id="topic-start-button" class="start__cta" data-topic-slug="${slug}" disabled>Start with this topic</button>
      <p id="topic-start-error" class="start__error" role="alert"></p>
      <p id="topic-start-script-hint" class="start__script-hint" role="status">This button needs a script that did not load. Load the page again.</p>
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
  // POST /api/sessions stubCatalogueAndSession already installed. The button ships disabled
  // (issue #161), and Playwright's own click() already waits for an element to become enabled
  // before it acts — so this click waits out the same gap the removed domcontentloaded wait
  // once covered, with no guess about how long the deferred script's own fetch takes.
  await page.getByRole('button', { name: 'Start with this topic' }).click();

  // Reader: the real Angular route at /learn/:sessionId, rendering the stubbed session's seed.
  await page.getByText(SEED).waitFor();
});

test('a click before a delayed script attaches its handler starts nothing; the button works once it does', async ({
  page,
}) => {
  let sessionRequests = 0;
  await page.route('**/topics/quantum-physics', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: topicPageFixture('quantum-physics', 'Quantum Physics'),
    }),
  );
  // Issue #161's own evidence: a real script request can finish over a second after a learner's
  // click. This delays the real /topic-start.js request by 1500ms and then serves the real file,
  // so the button has no click handler yet for that whole time.
  await page.route('**/topic-start.js', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 1500));
    await route.continue();
  });
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

  // `waitUntil: 'commit'`, and not the default `'load'`: the `load` event fires only once every
  // deferred script has run, so a plain `goto()` here would sit through the whole 1500ms delay
  // before returning, and the button this test wants to see disabled would already be enabled.
  // `'commit'` returns as soon as the navigation itself lands, well before the deferred script's
  // own, separate, delayed request even starts.
  await page.goto('/topics/quantum-physics', { waitUntil: 'commit' });

  const button = page.locator('#topic-start-button');
  await expect(button).toBeDisabled();

  // A forced click bypasses Playwright's own actionability wait, dispatching a real mouse click
  // straight at the button while it is still disabled. A genuinely disabled control does not
  // dispatch a click event to any handler, attached or not, so this proves the server's own
  // disabled attribute, and not only topic-start.js's own busy flag, is what blocks an early
  // click — the absence of a request below is not a timing race, since a click with no effect
  // never queues one, whenever it is checked.
  await button.click({ force: true });
  expect(sessionRequests).toBe(0);

  // Once the delayed script finishes, it attaches its handler and enables the button.
  await expect(button).toBeEnabled();

  await button.click();
  await page.getByText(SEED).waitFor();
  expect(sessionRequests).toBe(1);
});

test('a 404 for the script leaves the button disabled and shows a reason', async ({ page }) => {
  await page.route('**/topics/quantum-physics', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: topicPageFixture('quantum-physics', 'Quantum Physics'),
    }),
  );
  // The script request fails outright: topic-start.js never runs, so nothing ever attaches a
  // click handler or hides the script hint paragraph.
  await page.route('**/topic-start.js', (route) =>
    route.fulfill({ status: 404, contentType: 'text/plain', body: 'not found' }),
  );

  await page.goto('/topics/quantum-physics');

  const button = page.locator('#topic-start-button');
  await expect(button).toBeDisabled();

  // guides.css's own timer reveals the hint on its own, with no script of any kind involved —
  // this is a plain wait for a real, running CSS animation, not a spy on a timer function.
  const hint = page.locator('#topic-start-script-hint');
  await expect(hint).toBeVisible();
  await expect(hint).toHaveText(
    'This button needs a script that did not load. Load the page again.',
  );
  await expect(button).toBeDisabled();
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

test('a 503 refusal shows the backend message with no invented wait', async ({ page }) => {
  await page.route('**/topics/quantum-physics', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: topicPageFixture('quantum-physics', 'Quantum Physics'),
    }),
  );
  await page.route('**/api/sessions', (route) =>
    route.fulfill({
      status: 503,
      json: {
        code: 'SPEND_LIMIT',
        message: 'new explanations are paused for today; cached ones still work',
        retryAfter: null,
      },
    }),
  );

  await page.goto('/topics/quantum-physics');
  await page.getByRole('button', { name: 'Start with this topic' }).click();

  // A rate limit says "try later"; a spend limit says the service is degraded — no invented wait
  // is shown for the latter, since the server gave none. Same rule the removed catalogue-tile
  // test pinned, now pinned here instead.
  await expect(page.getByRole('alert')).toHaveText(
    'new explanations are paused for today; cached ones still work',
  );
  await expect(page.getByRole('button', { name: 'Start with this topic' })).toBeEnabled();
});

test('shows a starting indicator while the request is in flight', async ({ page }) => {
  await page.route('**/topics/quantum-physics', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: topicPageFixture('quantum-physics', 'Quantum Physics'),
    }),
  );

  let releaseResponse!: () => void;
  const held = new Promise<void>((resolve) => {
    releaseResponse = resolve;
  });
  await page.route('**/api/sessions', async (route) => {
    await held;
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

  await page.goto('/topics/quantum-physics');
  // A plain id locator, and not getByRole with a name filter: the button's own accessible name
  // changes to "Starting…" the instant the click handler runs, so a name-filtered locator built
  // before the click stops matching anything right after it.
  const button = page.locator('#topic-start-button');
  await button.click();

  await expect(button).toHaveText('Starting…');
  await expect(button).toBeDisabled();

  releaseResponse();
  await page.getByText(SEED).waitFor();
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
