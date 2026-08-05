import { defineConfig, devices } from '@playwright/test';

/**
 * Task 1.17's end-to-end guard on the click path: catalogue → session → selection → streamed
 * explanation → breadcrumb.
 *
 * Runs against a real `ng serve` dev server (Problem B in the task brief: the brief that introduced
 * this suite described running "against the dev server" but never wired a `webServer`, so
 * `npx playwright test` had nothing to hit). No backend process runs alongside it: every `/api/**`
 * call the app makes is stubbed from inside each spec — `page.route` for the catalogue and session
 * endpoints, and the in-page `fetch` shim in `e2e/support.ts` for the one endpoint `route.fulfill`
 * cannot deliver progressively (see that file's comment on Problem A). CI therefore needs neither a
 * Mongo/Atlas connection nor an `ANTHROPIC_API_KEY`.
 *
 * `reuseExistingServer: !process.env.CI` is Playwright's own documented pattern
 * (https://playwright.dev/docs/test-webserver#configuring-webserver-in-github-actions-and-others):
 * a developer iterating locally can leave `ng serve` running on this port and every subsequent
 * `npx playwright test` reuses it, but a CI runner never has one listening yet, so it always starts a
 * clean `ng serve` process built from exactly the commit under test — never a warm dev server left
 * over from an earlier, possibly stale, run. A dedicated port (4300, not Angular's default 4200)
 * keeps this from colliding with — or silently reusing — a developer's own everyday `ng serve`.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',

  use: {
    baseURL: 'http://localhost:4300',
    trace: 'on-first-retry',
  },

  webServer: {
    command: 'npm run start -- --port 4300',
    url: 'http://localhost:4300',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
