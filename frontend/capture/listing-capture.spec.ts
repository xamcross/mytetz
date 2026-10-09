import { test, type BrowserContext } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import {
  VIEWPORT,
  assertNoChallenge,
  deleteTestLearner,
  explainPhrase,
  guardModelCalls,
  openSignInLink,
  openTestMe,
  openTopic,
  showDashboard,
  startReader,
  type ModelCallCounter,
} from './steps';

/**
 * Issue #182: the listing capture. It takes four screenshots and one video of a test learner.
 *
 * Run it only after `./gradlew :backend:graph:makeCaptureSignInLink --args="--write"`, within
 * 15 minutes. The runbook is `docs/deploy.md`, section "Listing capture (issue #182)".
 *
 *   CAPTURE_BASE_URL=https://mytetz.com npx playwright test -c capture/playwright.capture.config.ts
 *
 * - `CAPTURE_BASE_URL` is the site. The script replaces the host of the link with this value.
 * - `CAPTURE_LINK_FILE` names the link file. The default is `build/capture/sign-in-link.txt`.
 * - `CAPTURE_TOPIC` names the topic slug. The default is `quantum-physics`.
 *
 * The script never prints the link or a cookie. It keeps the cookies in memory. It makes at most
 * eight model calls. At the end it deletes the test learner.
 */

type StorageState = Awaited<ReturnType<BrowserContext['storageState']>>;

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMAGES = path.join(REPO_ROOT, 'docs', 'marketing', 'images');
const TMP = path.join(REPO_ROOT, 'build', 'capture', 'video-tmp');
const MIN_VIDEO_MS = 32_000;
const MAX_VIDEO_MS = 58_000;
const PAUSE_MS = 2_500;

test('capture the screenshots and the demo video', async ({ browser }) => {
  const baseUrl = process.env['CAPTURE_BASE_URL'];
  if (!baseUrl) throw new Error('set CAPTURE_BASE_URL, for example https://mytetz.com');
  const origin = new URL(baseUrl).origin;
  const linkFile =
    process.env['CAPTURE_LINK_FILE'] ??
    path.join(REPO_ROOT, 'build', 'capture', 'sign-in-link.txt');
  if (!fs.existsSync(linkFile)) {
    throw new Error('the link file is missing: run the makeCaptureSignInLink command first');
  }
  const slug = process.env['CAPTURE_TOPIC'] ?? 'quantum-physics';

  // Replace the host of the link. The path and the token stay.
  const link = new URL(fs.readFileSync(linkFile, 'utf8').trim());
  const target = new URL(origin);
  link.protocol = target.protocol;
  link.host = target.host;

  fs.mkdirSync(IMAGES, { recursive: true });
  fs.rmSync(TMP, { recursive: true, force: true });
  fs.mkdirSync(TMP, { recursive: true });
  const shot = (name: string) => path.join(IMAGES, name);

  const counter: ModelCallCounter = { count: 0 };
  const openContexts: BrowserContext[] = [];
  const newContext = async (state?: StorageState, video = false) => {
    const context = await browser.newContext({
      baseURL: origin,
      viewport: VIEWPORT,
      storageState: state,
      ...(video ? { recordVideo: { dir: TMP, size: VIEWPORT } } : {}),
    });
    await guardModelCalls(context, counter);
    openContexts.push(context);
    return context;
  };

  let signedInState: StorageState | undefined;
  try {
    // Steps 1 and 2: sign in and show the dashboard.
    const first = await newContext();
    const page1 = await first.newPage();
    await openSignInLink(page1, link.toString());
    signedInState = await first.storageState();
    await showDashboard(page1);
    await page1.screenshot({ path: shot('dashboard.png') });
    await first.close();

    // Steps 3 to 5 and 7: one context, one video.
    const second = await newContext(signedInState, true);
    const page2 = await second.newPage();
    const startedAt = Date.now();
    await page2.goto('/');
    await assertNoChallenge(page2);
    await showDashboard(page2);
    await page2.waitForTimeout(PAUSE_MS);
    await openTopic(page2, slug);
    await page2.waitForTimeout(PAUSE_MS);
    await page2.screenshot({ path: shot('topic.png') });
    await startReader(page2);
    await page2.waitForTimeout(PAUSE_MS);
    await explainPhrase(page2);
    await page2.waitForTimeout(PAUSE_MS);
    await page2.screenshot({ path: shot('explanation.png') });
    await explainPhrase(page2);
    await page2.waitForTimeout(PAUSE_MS);
    await page2.screenshot({ path: shot('breadcrumb.png') });
    const remaining = MIN_VIDEO_MS - (Date.now() - startedAt);
    if (remaining > 0) await page2.waitForTimeout(remaining);
    const videoMs = Date.now() - startedAt;
    const readerUrl = page2.url();
    signedInState = await second.storageState();
    const video = page2.video();
    await second.close();
    await video?.saveAs(path.join(IMAGES, 'demo.webm'));
    console.log(`Video length: about ${Math.round(videoMs / 1000)} s.`);
    if (videoMs > MAX_VIDEO_MS) {
      console.warn('The video is longer than 60 s. Trim it before you commit it.');
    }

    // Step 6: Test Me, on the same session.
    const third = await newContext(signedInState);
    const page3 = await third.newPage();
    await page3.goto(readerUrl);
    await openTestMe(page3);
    await page3.waitForTimeout(PAUSE_MS);
    await page3.screenshot({ path: shot('test-me.png') });
    await third.close();
  } finally {
    console.log(`Model calls made: ${counter.count} (limit 8).`);
    // Step 8: delete the test learner, also after a failed step.
    if (signedInState) {
      const last = await newContext(signedInState);
      const page4 = await last.newPage();
      try {
        await deleteTestLearner(page4);
        console.log('The test learner is deleted.');
      } catch {
        console.error('The test learner is NOT deleted. Delete it at /account by hand.');
        throw new Error('the delete step failed');
      } finally {
        await last.close();
      }
    }
    for (const context of openContexts) await context.close().catch(() => undefined);
    fs.rmSync(TMP, { recursive: true, force: true });
  }
});
