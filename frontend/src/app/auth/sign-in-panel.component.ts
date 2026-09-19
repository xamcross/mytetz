import {
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { TurnstileApi, loadTurnstileScript } from './turnstile';

/**
 * The panel a signed-out learner sees in place of an explanation.
 *
 * Two ways in. An email address asks the backend for a magic link. The button below is a plain
 * same-origin `<a href>` to the Google route — a real navigation, not a `fetch` or a
 * `window.location` assignment — so this stays clear of `window` and `document` on its render
 * path, which matters here because this panel is shown inside the reader (Specification A's SSR
 * requirement for that page).
 *
 * The email path never states whether the address is one the backend knows.
 * `POST /api/auth/magic-link` answers `204` for a known address and an unknown one alike, on
 * purpose, and this panel shows the identical "check your email" message for both outcomes.
 * Anything that varied the message by outcome would let a caller learn who has an account by
 * trying addresses one at a time.
 *
 * ## A sign-in method this deployment has not configured
 *
 * [googleEnabled] and [magicLinkEnabled] each hide their own control. Each hides its control when
 * `GET /api/auth/config` reports the matching backend factory as not built. A learner then sees
 * only the control that works, and not one that always answers `503`.
 *
 * The config read and a later submit happen at two different moments. A control can still fail at
 * submit time, even after the config read reported it as enabled. [describeRequestFailure]'s own
 * `SIGN_IN_UNAVAILABLE` branch covers that case for the email form.
 *
 * ## The Turnstile widget
 *
 * `ApiService.authConfig` names the site key. It answers `null` when this deployment holds no
 * Turnstile secret. See `TurnstileConfig`'s own KDoc on the backend. A `null` site key renders no
 * widget, and loads no script at all. [turnstileSiteKey] gates both the container in the template
 * and the render effect below.
 *
 * One widget covers both ways in. Its token travels two ways: as `turnstileToken` on the
 * magic-link request, and appended to [googleHref] as a query parameter on the Google link.
 * `AuthRoutes.kt` already reads it in both places. Loading the script, and rendering the widget,
 * both happen inside `afterRenderEffect`. Angular guarantees that hook never runs on the server,
 * so the SSR requirement above holds for the widget too.
 *
 * A `403 TURNSTILE_FAILED` answer resets the widget. [resetTurnstile] clears the spent token, and
 * asks Cloudflare's own script for a fresh one. A learner who solved the challenge once then need
 * not load a whole new page to solve it again.
 */
@Component({
  selector: 'app-sign-in-panel',
  imports: [RouterLink],
  template: `
    <div class="sign-in-panel mt-card mt-card--raised">
      @if (sent()) {
        <p class="sign-in-panel__message" role="status">
          If that address is on the site, a sign-in link is on its way. Check your email.
        </p>
      } @else {
        <p class="sign-in-panel__lead">Sign in to keep going.</p>
        <p class="sign-in-panel__consent">
          Signing in accepts the <a routerLink="/terms">terms</a> and the
          <a routerLink="/privacy">privacy policy</a>.
        </p>

        @if (magicLinkEnabled()) {
          <form class="sign-in-panel__form" (submit)="submit($event)">
            <label class="sign-in-panel__label" for="sign-in-email">Email address</label>
            <input
              id="sign-in-email"
              class="sign-in-panel__input"
              type="email"
              autocomplete="email"
              [value]="email()"
              (input)="onInput($event)"
            />
            @if (validationError(); as message) {
              <p class="sign-in-panel__error" role="alert">{{ message }}</p>
            }
            <button type="submit" class="mt-pill mt-pill--coral" [disabled]="submitting()">
              Email me a sign-in link
            </button>
          </form>

          @if (turnstileSiteKey(); as key) {
            <div
              class="sign-in-panel__turnstile"
              #turnstileContainer
              data-testid="turnstile-container"
            ></div>
          }
        }

        @if (googleEnabled()) {
          <a class="mt-pill mt-pill--ghost sign-in-panel__google" [attr.href]="googleHref()">
            Continue with Google
          </a>
        }
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .sign-in-panel {
        padding: 32px 36px;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 16px;
      }
      .sign-in-panel__lead {
        margin: 0;
        font-size: 20px;
        font-weight: 600;
      }
      .sign-in-panel__consent {
        margin: 0;
        font-size: 13px;
        color: var(--mt-muted);
      }
      .sign-in-panel__form {
        display: flex;
        flex-direction: column;
        gap: 8px;
        width: 100%;
        max-width: 360px;
      }
      .sign-in-panel__label {
        font-size: 13px;
        font-weight: 700;
        color: var(--mt-muted);
      }
      .sign-in-panel__input {
        padding: 12px 14px;
        /* --mt-edge, not --mt-border: this is a control edge, and SC 1.4.11 needs 3:1. */
        border: var(--mt-border-w) solid var(--mt-edge);
        border-radius: var(--mt-r-row);
        background: var(--mt-surface);
        color: var(--mt-ink);
        font: inherit;
      }
      .sign-in-panel__input:focus-visible {
        border-color: var(--mt-teal);
      }
      .sign-in-panel__error {
        margin: 0;
        font-size: 13px;
        font-weight: 700;
        color: var(--mt-err-ink);
      }
      .sign-in-panel__message {
        margin: 0;
        font-size: 16px;
        line-height: 1.55;
        font-weight: 500;
        max-width: 48ch;
      }
    `,
  ],
})
export class SignInPanelComponent {
  private readonly api = inject(ApiService);

  readonly email = signal('');
  readonly sent = signal(false);
  readonly submitting = signal(false);
  readonly validationError = signal<string | null>(null);

  /** `null` until `GET /api/auth/config` answers. Stays `null` for good on a deployment with no
   * Turnstile secret — see this class's own KDoc. Either way, no widget renders. */
  readonly turnstileSiteKey = signal<string | null>(null);
  private readonly turnstileToken = signal<string | null>(null);

  /** True until `GET /api/auth/config` answers otherwise. A learner sees both controls while the
   * config read is in flight, or when it fails outright — a config read never disables a control
   * this way, only an explicit `false` in the answer does. */
  readonly googleEnabled = signal(true);
  readonly magicLinkEnabled = signal(true);

  private readonly turnstileContainer = viewChild<ElementRef<HTMLElement>>('turnstileContainer');
  private turnstileApi: TurnstileApi | null = null;
  private turnstileWidgetId: string | null = null;

  /** `/api/auth/google`, with `turnstileToken` appended once the widget has produced one. This is
   * a plain `<a href>`, so the token has to sit in the URL itself. See this class's own KDoc on
   * why this is a real navigation, and not a `fetch`. */
  readonly googleHref = computed(() => {
    const token = this.turnstileToken();
    return token === null
      ? '/api/auth/google'
      : `/api/auth/google?turnstileToken=${encodeURIComponent(token)}`;
  });

  constructor() {
    void this.loadConfig();

    afterRenderEffect({
      read: () => {
        const key = this.turnstileSiteKey();
        const container = this.turnstileContainer()?.nativeElement;
        // `turnstileApi !== null` stops a second render call once the tracked signals settle to a
        // value this effect has already acted on. A repeat `render()` on the same container is
        // not idempotent. Cloudflare's own API expects exactly one call for each widget.
        if (key === null || container === undefined || this.turnstileApi !== null) return;

        loadTurnstileScript()
          .then((api) => {
            this.turnstileApi = api;
            this.turnstileWidgetId = api.render(container, {
              sitekey: key,
              callback: (token) => this.turnstileToken.set(token),
            });
          })
          .catch(() => {
            // The widget failed to load. A learner can still submit. The token stays null, and
            // the server then answers `403 TURNSTILE_FAILED`. That is the same outcome as a
            // learner who never solved a rendered challenge, and not a dead end of its own.
          });
      },
    });
  }

  private async loadConfig(): Promise<void> {
    try {
      const config = await this.api.authConfig();
      this.turnstileSiteKey.set(config.turnstileSiteKey);
      this.googleEnabled.set(config.googleEnabled);
      this.magicLinkEnabled.set(config.magicLinkEnabled);
    } catch {
      // No widget without a working config read. Both controls stay shown, and sign-in degrades
      // by way of the 503 each one answers on its own, rather than breaking outright here.
    }
  }

  onInput(event: Event): void {
    this.email.set((event.target as HTMLInputElement).value);
    this.validationError.set(null);
  }

  async submit(event: Event): Promise<void> {
    event.preventDefault();
    const address = this.email().trim();
    if (address.length === 0) {
      this.validationError.set('Enter an email address.');
      return;
    }

    this.submitting.set(true);
    try {
      await this.api.requestMagicLink(address, this.turnstileToken());
      this.sent.set(true);
    } catch (err) {
      const body = err instanceof HttpErrorResponse ? asApiErrorBody(err.error) : null;
      if (body?.code === 'TURNSTILE_FAILED') this.resetTurnstile();
      this.validationError.set(describeRequestFailure(body));
    } finally {
      this.submitting.set(false);
    }
  }

  /** Clears the spent token, and asks Cloudflare's own widget for a fresh one. A `403
   * TURNSTILE_FAILED` answer then does not strand the learner behind a challenge nothing but a
   * full reload can solve a second time. */
  private resetTurnstile(): void {
    this.turnstileToken.set(null);
    const widgetId = this.turnstileWidgetId;
    if (this.turnstileApi && widgetId !== null) this.turnstileApi.reset(widgetId);
  }
}

/**
 * What the learner reads when `requestMagicLink` itself fails.
 *
 * `AuthRoutes.kt` refuses this route four ways: `429 RATE_LIMITED`
 * (`MAGIC_LINK_PER_IP`/`MAGIC_LINK_PER_ADDRESS`), `403 TURNSTILE_FAILED`,
 * `413 PAYLOAD_TOO_LARGE` (`MAX_AUTH_BODY_BYTES`, which an ordinary address never reaches), and
 * `503 SIGN_IN_UNAVAILABLE` (the mail configuration is missing). `RATE_LIMITED` gets its own
 * message. "Check your connection" is wrong advice for a learner who is not offline, and fixes
 * nothing by retrying at once. `TURNSTILE_FAILED` shows the server's own message, and not a
 * generic one. `submit()` has already reset the widget by the time this runs. The learner needs
 * to know a fresh attempt is what comes next. `SIGN_IN_UNAVAILABLE` points the learner at the
 * other control on this same panel, in case the config route reported it enabled before it
 * stopped working. Every other failure — a dropped connection, `PAYLOAD_TOO_LARGE`, a 500 —
 * reduces to one generic message. No branch names the address itself. The address's known or
 * unknown status stays unrevealed either way.
 */
function describeRequestFailure(body: ApiErrorBody | null): string {
  if (body?.code === 'RATE_LIMITED') {
    return 'Too many requests have been made. Try again shortly.';
  }
  if (body?.code === 'TURNSTILE_FAILED') {
    return body.message;
  }
  if (body?.code === 'SIGN_IN_UNAVAILABLE') {
    return 'Email sign-in is not available right now. Use Google.';
  }
  return 'Could not send the link. Check your connection and try again.';
}

/** The same `{code, message, retryAfter}` shape every backend refusal uses. Kept private here for
 * the same reason `sse.client.ts`, `catalog-page.component.ts` and `session.store.ts` each keep
 * their own copy: it is a wire shape, not a domain model. */
interface ApiErrorBody {
  code: string;
  message: string;
  retryAfter?: number | null;
}

function asApiErrorBody(value: unknown): ApiErrorBody | null {
  if (
    value !== null &&
    typeof value === 'object' &&
    typeof (value as Record<string, unknown>)['code'] === 'string' &&
    typeof (value as Record<string, unknown>)['message'] === 'string'
  ) {
    return value as ApiErrorBody;
  }
  return null;
}
