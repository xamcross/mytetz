/**
 * The Cloudflare Turnstile script `SignInPanelComponent` loads when the backend reports a site
 * key.
 *
 * This file sits apart from the component. `loadTurnstileScript` can then run against a real
 * `Document`, with no need to mount the whole panel. `selection.ts` sits apart from
 * `focus-card.component.ts` for the same reason.
 */

/** Where Cloudflare hosts the widget script. Confirmed against Cloudflare's own Turnstile
 * client-side rendering guide (`developers.cloudflare.com/turnstile/get-started/client-side-rendering/`). */
export const TURNSTILE_SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js';

/**
 * The one method this codebase calls on the global object Cloudflare's own script defines.
 *
 * Nothing here ships that script. `window.turnstile` exists only after `loadTurnstileScript` has
 * appended it. It exists only once that script has then run.
 */
export interface TurnstileApi {
  render(
    container: HTMLElement,
    options: {
      sitekey: string;
      callback: (token: string) => void;
      'error-callback'?: () => void;
      'expired-callback'?: () => void;
    },
  ): string;
  reset(widgetId: string): void;
}

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

/**
 * Loads Cloudflare's Turnstile script, and resolves with the API it defines.
 *
 * A `window.turnstile` already present resolves at once, and appends nothing. That state means a
 * second panel instance on the same page, or an earlier call that already succeeded. The widget
 * script is meant to load once for each page. Cloudflare's own guide loads it with a plain
 * `<script src>`, and not a dynamic `import()`. The script is not a module. It defines its API as
 * a side effect on `window`, and not through an export.
 *
 * Never called from a render path. `SignInPanelComponent`'s own class doc explains why. This
 * function reads and writes `document`. The caller gates every call behind `afterRenderEffect`,
 * which Angular guarantees never runs on the server.
 */
export function loadTurnstileScript(): Promise<TurnstileApi> {
  const existing = window.turnstile;
  if (existing) return Promise.resolve(existing);

  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = TURNSTILE_SCRIPT_URL;
    script.async = true;
    script.defer = true;
    script.onload = () => {
      const api = window.turnstile;
      if (api) resolve(api);
      else reject(new Error('the Turnstile script loaded without defining window.turnstile'));
    };
    script.onerror = () => reject(new Error('the Turnstile script failed to load'));
    document.head.appendChild(script);
  });
}
