import { test, expect } from '@playwright/test';
import {
  CHILD,
  SEED,
  explainedView,
  mockExplainRefusal,
  mockExplainStream,
  openQuantumPhysicsSession,
  selectPhrase,
  sseFrame,
  stubCatalogueAndSession,
} from './support';

/**
 * The end-to-end guard on the whole click path Task 1.17 exists to build: catalogue, session
 * creation, selection, streaming, breadcrumb — through a real browser against a real `ng serve`,
 * with every backend call stubbed (see `playwright.config.ts` and `./support.ts`).
 *
 * Each test installs its own stubs rather than sharing a `beforeEach`, because `stubCatalogueAndSession`
 * needs to know — up front — what a *successful* explain should hand back on re-read, and that view
 * differs per test.
 *
 * Streaming tests drive `ExplainStream.send()`/`close()` explicitly rather than baking real-time
 * delays into a mock (see `mockExplainStream`'s doc comment in `./support.ts`): every "is this on
 * screen yet" assertion is checked against a connection that provably does not have the rest of the
 * data yet, because the test has not sent it, rather than against a timing bet that can lose under
 * CI's CPU pressure.
 */

test('pick a topic, highlight a phrase, and watch the explanation stream in progressively', async ({
  page,
}) => {
  const { sessionGetCount } = await stubCatalogueAndSession(page, explainedView(CHILD));
  const stream = await mockExplainStream(page, 's1');

  await openQuantumPhysicsSession(page);

  // Problem C: a real mouse drag over "fundamental physical theory", not a fabricated Range plus a
  // synthetic mouseup — see `selectPhrase`'s doc comment.
  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await page.getByRole('button', { name: 'Explain' }).click();

  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  // Split where the second half's distinctive phrase begins, so "is this on screen yet" has an
  // unambiguous answer.
  const splitAt = CHILD.indexOf('subatomic');
  await stream.send(sseFrame('delta', { t: CHILD.slice(0, splitAt) }));

  // The assertion this whole slice's streaming design earns: partial prose is on screen, and the
  // stream has not finished, before the rest of it arrives. This is not a timing bet — the second
  // half genuinely has not been sent yet.
  await expect(page.locator('.focus__streaming')).toContainText('microscopic realm');
  await expect(page.locator('.focus__streaming')).not.toContainText('subatomic');
  await expect(page.locator('.focus__caret')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Explain' })).toBeDisabled();

  await stream.send(sseFrame('delta', { t: CHILD.slice(splitAt) }));
  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: false }));
  await stream.close();

  await expect(page.getByText(/subatomic scale/)).toBeVisible();
  // `exact: true` on its own already disambiguates from the trail rail's row for this node — that
  // button's accessible name is "Explain fundamental physical theory" (verb prefixed onto the span),
  // which an exact match on "fundamental physical theory" alone does not match. Scoping to `.crumb`
  // besides is not load-bearing for that; it is here so the assertion reads as "the breadcrumb has
  // this crumb" rather than "some button somewhere has this exact name", and so it stays correct if
  // a future change ever makes the two accessible names collide. (An earlier, regex-based version of
  // this query — `getByRole('button', { name: /fundamental physical theory/ })`, unscoped — did match
  // both and failed with a real strict-mode violation; switching to `exact: true` was the actual fix.)
  await expect(
    page.locator('.crumb').getByRole('button', { name: 'fundamental physical theory', exact: true }),
  ).toBeVisible();

  // Acceptance criterion 4: the breadcrumb and trail rail grow with each drill-down.
  await expect(page.locator('.crumb')).toHaveCount(2);
  await expect(page.locator('.trail__item')).toHaveCount(2);
  // Exactly the reader's own initial load, plus its one re-read after `done` — not a third,
  // unaccounted-for request. The same technique acceptance criterion 5 asks for against the live
  // deployment's `requestCount`, applied here to this test's own claim about the request shape.
  expect(sessionGetCount()).toBe(2);

  // Acceptance criterion 4's other half: "crumbs navigate". `goTo()` is a pure client-side focus
  // change — no network request — so this also re-proves that clicking the root crumb does not
  // silently re-issue a GET (`sessionGetCount` stays at 2).
  await page.locator('.crumb').getByRole('button', { name: 'Quantum Physics' }).click();
  await expect(page.getByTestId('focus-body')).toHaveText(SEED);
  await expect(page.locator('.crumb')).toHaveCount(1);
  expect(sessionGetCount()).toBe(2);
});

test('replaces streamed text with the winning body on a superseded race, not the local draft', async ({
  page,
}) => {
  const winner = 'The authoritative winning explanation, persisted by another instance first.';
  await stubCatalogueAndSession(page, explainedView(winner));
  const stream = await mockExplainStream(page, 's1');

  // Problem D: the reader's contract for `superseded` — content-race replacement, not append — has
  // no coverage above the unit level anywhere else. Getting this wrong is not cosmetic: quiz and
  // exam generation read the *stored* body, so a learner left looking at their own instance's
  // discarded draft would be examined on prose they never actually saw.
  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await page.getByRole('button', { name: 'Explain' }).click();

  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: 'A losing draft that must not survive the race. ' }));
  await expect(page.locator('.focus__streaming')).toContainText('losing draft');

  await stream.send(sseFrame('superseded', { body: winner }));
  // The replacement, caught in the act: the winning body is on screen and the losing draft is gone
  // from the same element, which only holds if `superseded` replaced rather than appended (an
  // append would leave both present at once).
  await expect(page.locator('.focus__streaming')).toContainText(winner);
  await expect(page.locator('.focus__streaming')).not.toContainText('losing draft');

  await stream.send(sseFrame('done', { contentKey: 'k1', grounded: false }));
  await stream.close();

  // And what the session settles on afterwards is the authoritative stored body.
  await expect(page.getByTestId('focus-body')).toHaveText(winner);
});

test('refuses with nothing rendered when the daily quota is exceeded', async ({ page }) => {
  const { sessionGetCount } = await stubCatalogueAndSession(page);

  // Acceptance criterion 6 names QUOTA_EXCEEDED by code. This is the "refusal with nothing
  // rendered" shape `ReaderError.discardedText` exists to distinguish from a mid-stream failure —
  // see the next test for the other shape. A pre-stream refusal never opens the response body, so
  // it is mocked as a non-200 JSON error rather than a stream.
  await mockExplainRefusal(page, 's1', {
    status: 429,
    body: {
      code: 'QUOTA_EXCEEDED',
      message: "You've reached today's explanation limit.",
      retryAfter: 3600,
    },
  });

  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await page.getByRole('button', { name: 'Explain' }).click();

  const banner = page.locator('.banner--error');
  await expect(banner).toContainText("You've reached today's explanation limit.");
  await expect(banner).toContainText('Try again in 1 hour.');
  // Nothing was streamed, so nothing was withdrawn — the discarded-text sentence must not appear.
  await expect(banner).not.toContainText('was discarded');
  // QUOTA_EXCEEDED is a wait, not a retry: SessionStore's RETRYABLE_CODES deliberately excludes it.
  await expect(banner.locator('.banner__retry-button')).toHaveCount(0);

  // The session itself is untouched — the seed is still exactly what it was, and no re-read of the
  // session was ever issued (a refusal is decided before any generation exists to read back).
  await expect(page.getByTestId('focus-body')).toHaveText(SEED);
  expect(sessionGetCount()).toBe(1);
});

test('discards partial prose and offers a retry when generation fails mid-stream', async ({ page }) => {
  await stubCatalogueAndSession(page);
  const stream = await mockExplainStream(page, 's1');

  // The other error shape acceptance criterion 6 implies but never names: a request that *did*
  // start streaming and then failed. `discardedText` must be true here (prose really was on
  // screen), unlike the quota test above.
  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await page.getByRole('button', { name: 'Explain' }).click();

  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: 'Because ' }));
  await expect(page.locator('.focus__streaming')).toContainText('Because');

  await stream.send(
    sseFrame('error', {
      code: 'GENERATION_FAILED',
      message: 'The model could not produce a valid explanation.',
      retryAfter: null,
    }),
  );

  const banner = page.locator('.banner--error');
  await expect(banner).toContainText('The model could not produce a valid explanation.');
  await expect(banner).toContainText('was discarded');
  await expect(banner.locator('.banner__retry-button')).toBeVisible();
  // The withdrawn draft does not linger once the failure is reported.
  await expect(page.locator('.focus__streaming')).toHaveCount(0);
});

test('treats a stream that closes without its terminal event as truncated, not successful', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  const stream = await mockExplainStream(page, 's1');

  // Task 1.16's own distinct case: the connection ends cleanly (the mock closes it) but no `done`
  // frame was ever sent. SessionStore's `!sawDone` branch is what this test is for.
  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await page.getByRole('button', { name: 'Explain' }).click();

  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: 'Because ' }));
  await expect(page.locator('.focus__streaming')).toContainText('Because');
  await stream.close(); // No `done` ever sent.

  const banner = page.locator('.banner--error');
  await expect(banner).toContainText('the connection closed before the explanation finished');
  await expect(banner).toContainText('was discarded');
  // STREAM_TRUNCATED is one of SessionStore's RETRYABLE_CODES.
  await expect(banner.locator('.banner__retry-button')).toBeVisible();
});

test('aborts the in-flight fetch when the reader navigates away mid-stream', async ({ page }) => {
  await stubCatalogueAndSession(page);
  const stream = await mockExplainStream(page, 's1');

  // Task 1.16 named this its own deferred item: its abort tests used fakes that complete normally,
  // so the real `fetch`-abort path — AbortController firing, the browser's own fetch rejecting with
  // AbortError — was only verified by symmetry with the code, never actually run. A real navigation
  // away, driven by the browser's own back button, is what makes it real: it destroys
  // `ReaderPageComponent` through Angular's router (not a full document reload, so this is a soft
  // SPA navigation — `DestroyRef.onDestroy` fires, `SessionStore.abandon()` runs, and it calls
  // `AbortController.abort()` on the in-flight generation), and the mock's `ReadableStream` reacts to
  // that abort signal exactly as a real network stream would.
  //
  // Critical, from review: asserting only that the page survived (no thrown error, the catalogue
  // still usable) does not distinguish the abort hook actually running from `DestroyRef.onDestroy`
  // being deleted outright — remove that hook and `abort()` never fires, this mock's `pull()` just
  // hangs on an unresolved promise forever, and every DOM-visible or `pageerror` assertion below
  // still passes identically. `stream.aborted()` reads the mock's own flag, set only inside the
  // `AbortSignal` listener itself, so it can only be true if the abort actually happened.
  const pageErrors: Error[] = [];
  page.on('pageerror', (err) => pageErrors.push(err));

  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await page.getByRole('button', { name: 'Explain' }).click();

  await stream.send(sseFrame('meta', { contentKey: 'k1', cached: false }));
  await stream.send(sseFrame('delta', { t: 'Some prose that never gets to finish streaming.' }));
  await expect(page.locator('.focus__streaming')).toContainText('never gets to finish');
  // The generation is still "in progress" from the server's point of view when the learner leaves —
  // `stream.close()` is deliberately never called.
  expect(await stream.aborted()).toBe(false);

  await page.goBack();

  // The proof this test exists for: the abort path actually ran, not merely that nothing crashed.
  await expect.poll(() => stream.aborted()).toBe(true);

  // Back on the catalogue, with the topic tile live again — the navigation itself completed cleanly.
  await expect(page.getByRole('button', { name: /Quantum Physics/ })).toBeEnabled();
  // And nothing thrown by the aborted generation escaped as an uncaught exception or unhandled
  // rejection. `SessionStore.explain`'s `catch` checks `controller.signal.aborted` and returns
  // silently for exactly this reason; this is the assertion that it actually does.
  expect(pageErrors).toEqual([]);
});
