import { test, expect, type Page } from '@playwright/test';
import {
  mockExplainRefusal,
  openQuantumPhysicsSession,
  selectPhrase,
  stubCatalogueAndSession,
} from './support';

/** A verb inside the picker, and only inside it. See `learn.spec.ts`'s own copy of this helper.
 * That file states why the scope is load-bearing. */
function verb(page: Page, name: string) {
  return page.locator('[role="dialog"]').getByRole('button', { name, exact: true });
}

/**
 * Reaches the sign-in wall the way a real learner does.
 *
 * A learner reads the seed anonymously, highlights a phrase, and asks for an explanation. The
 * backend then refuses the request with `SIGN_IN_REQUIRED`.
 */
async function openSignInWall(page: Page): Promise<void> {
  await stubCatalogueAndSession(page);
  await mockExplainRefusal(page, 's1', {
    status: 401,
    body: { code: 'SIGN_IN_REQUIRED', message: 'sign in to keep going' },
  });

  await openQuantumPhysicsSession(page);
  await selectPhrase(page, 'focus-body', 'fundamental physical theory');
  await verb(page, 'Explain it').click();

  await expect(page.getByText('Sign in to keep going.')).toBeVisible();
}

/**
 * A fake `window.turnstile`. This suite delivers it as the body of a stubbed
 * `https://challenges.cloudflare.com/turnstile/v0/api.js`. That is the real script's own URL,
 * confirmed against `turnstile.ts`'s own KDoc. `render` calls its `callback` at once with a fixed
 * token. That is the token a learner who has already solved the visible challenge would produce.
 * A real widget's own visual challenge is Cloudflare's concern, and not this suite's.
 */
const FAKE_TURNSTILE_SCRIPT = `
  window.turnstile = {
    render: (container, options) => {
      options.callback('e2e-fake-token');
      return 'widget-1';
    },
    reset: () => {},
  };
`;

test('the sign-in panel loads the widget and sends its token on both ways in', async ({ page }) => {
  let magicLinkBody: unknown = null;

  await page.route('**/api/auth/config', (route) =>
    route.fulfill({
      json: { turnstileSiteKey: 'e2e-site-key', googleEnabled: true, magicLinkEnabled: true },
    }),
  );
  await page.route('https://challenges.cloudflare.com/turnstile/v0/api.js', (route) =>
    route.fulfill({ contentType: 'application/javascript', body: FAKE_TURNSTILE_SCRIPT }),
  );
  await page.route('**/api/auth/magic-link', async (route) => {
    magicLinkBody = route.request().postDataJSON();
    await route.fulfill({ status: 204 });
  });

  await openSignInWall(page);

  // The widget renders once `GET /api/auth/config` and the stubbed script have both resolved.
  // Both happen asynchronously, after the panel's own construction. The google link is the one
  // piece of UI whose state directly proves the token arrived. `toHaveAttribute` auto-retries.
  const googleLink = page.getByRole('link', { name: 'Continue with Google' });
  await expect(googleLink).toHaveAttribute(
    'href',
    '/api/auth/google?turnstileToken=e2e-fake-token',
  );

  await page.getByLabel('Email address').fill('learner@example.com');
  await page.getByRole('button', { name: 'Email me a sign-in link' }).click();

  await expect(page.getByText('Check your email')).toBeVisible();
  expect(magicLinkBody).toEqual({ email: 'learner@example.com', turnstileToken: 'e2e-fake-token' });
});

test('the panel renders no widget when the deployment holds no turnstile secret', async ({
  page,
}) => {
  let scriptRequested = false;

  await page.route('**/api/auth/config', (route) =>
    route.fulfill({
      json: { turnstileSiteKey: null, googleEnabled: true, magicLinkEnabled: true },
    }),
  );
  await page.route('https://challenges.cloudflare.com/turnstile/v0/api.js', (route) => {
    scriptRequested = true;
    return route.fulfill({ contentType: 'application/javascript', body: FAKE_TURNSTILE_SCRIPT });
  });

  await openSignInWall(page);

  const googleLink = page.getByRole('link', { name: 'Continue with Google' });
  await expect(googleLink).toHaveAttribute('href', '/api/auth/google');
  await expect(page.getByTestId('turnstile-container')).toHaveCount(0);
  expect(scriptRequested).toBe(false);
});

test('the panel states above the controls that signing in accepts the terms, with a working link', async ({
  page,
}) => {
  await openSignInWall(page);

  // Scoped to the consent sentence: the footer also carries a "Terms" link, and a bare
  // `getByRole('link', { name: 'terms' })` would match both.
  const consent = page.locator('.sign-in-panel__consent');
  await expect(consent).toHaveText('Signing in accepts the terms and the privacy policy.');

  await consent.getByRole('link', { name: 'terms' }).click();
  await expect(page).toHaveURL(/\/terms$/);
});

test('the panel states above the controls that signing in accepts the privacy policy, with a working link', async ({
  page,
}) => {
  await openSignInWall(page);

  const consent = page.locator('.sign-in-panel__consent');
  await consent.getByRole('link', { name: 'privacy policy' }).click();
  await expect(page).toHaveURL(/\/privacy$/);
});
