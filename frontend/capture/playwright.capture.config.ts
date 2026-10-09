import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/**
 * Issue #182: the config of the listing capture. The owner runs it by hand:
 *
 *   CAPTURE_BASE_URL=https://mytetz.com npx playwright test -c capture/playwright.capture.config.ts
 *
 * The CI e2e run uses `playwright.config.ts`, whose `testDir` is `./e2e`. It never reads this
 * folder. This config starts no dev server, because the capture runs against a live site.
 *
 * It turns off the trace, the screenshot, and the video of the test runner. A trace holds cookies
 * and requests, and the sign-in link must never reach a file. The capture script takes the images
 * and the video itself.
 */
export default defineConfig({
  testDir: '.',
  testMatch: 'listing-capture.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 10 * 60 * 1000,
  reporter: 'list',
  outputDir: path.resolve(__dirname, '..', '..', 'build', 'capture', 'test-results'),
  use: {
    viewport: { width: 1270, height: 760 },
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    actionTimeout: 60_000,
    navigationTimeout: 60_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1270, height: 760 } },
    },
  ],
});
