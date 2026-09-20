import { test, expect } from '@playwright/test';
import {
  SEED,
  mockExplainStream,
  openQuantumPhysicsSession,
  selectByOffsets,
  stubCatalogueAndSession,
} from './support';

/**
 * Issue #169. A learner's drag rarely lands exactly on a word's own edge. This suite drags a real
 * selection that starts inside one word and ends inside another, through a real browser against a
 * real `ng serve` — the same technique `learn.spec.ts` and `layout.spec.ts` use for a phrase drag,
 * applied here to a drag that `selectPhrase`'s own `full.indexOf(phrase)` cannot express, since
 * both edges of the phrase it finds already stand on a word's own edge. `selectByOffsets` in
 * `./support.ts` is the drag helper this issue adds for that.
 *
 * `SEED` (`./support.ts`) is 'Quantum mechanics is the fundamental physical theory that describes
 * matter and light.'. "fundamental" spans characters 25..36, and "theory" spans 46..52 — verified
 * against `SEED`'s own text, not assumed. The drag below runs from offset 28 (three characters
 * into "fundamental") to offset 50 (four characters into "theory", before its own "ry"), so both
 * ends of the drag stand inside a word, not on either word's own edge.
 */
test('a drag that starts and ends inside two different words grows to whole words everywhere', async ({
  page,
}) => {
  await stubCatalogueAndSession(page);
  const stream = await mockExplainStream(page, 's1');

  await openQuantumPhysicsSession(page);

  await selectByOffsets(page, 'focus-body', 28, 50);

  const picker = page.locator('[role="dialog"]');
  await picker.waitFor();
  // The picker's lead line ("'fundamental physical theory' — go on:") carries the grown phrase,
  // not the three-word fragment the drag itself covered.
  await expect(picker.locator('.picker__lead')).toContainText('fundamental physical theory');

  // The highlight on the page — the actual browser Selection — shows the same grown phrase, so
  // the learner sees what the question is about.
  const highlighted = await page.evaluate(() => window.getSelection()?.toString());
  expect(highlighted).toBe('fundamental physical theory');

  await picker.getByRole('button', { name: 'Explain it', exact: true }).click();

  const body = (await stream.requestBody()) as {
    span: { text: string; start: number; end: number };
  } | null;
  expect(body).not.toBeNull();
  expect(body!.span).toEqual({ text: 'fundamental physical theory', start: 25, end: 52 });
  // The invariant the server itself checks: the phrase stands at its own offsets in the body text.
  expect(SEED.slice(body!.span.start, body!.span.end)).toBe(body!.span.text);

  await stream.close();
});
