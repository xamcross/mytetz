import { Component, inject, signal } from '@angular/core';
import { ApiService } from '../core/api.service';

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
 */
@Component({
  selector: 'app-sign-in-panel',
  imports: [],
  template: `
    <div class="sign-in-panel mt-card mt-card--raised">
      @if (sent()) {
        <p class="sign-in-panel__message" role="status">
          If that address is on the site, a sign-in link is on its way. Check your email.
        </p>
      } @else {
        <p class="sign-in-panel__lead">Sign in to keep going.</p>

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

        <a class="mt-pill mt-pill--ghost sign-in-panel__google" href="/api/auth/google">
          Continue with Google
        </a>
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
        border: var(--mt-border-w) solid var(--mt-border);
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
      await this.api.requestMagicLink(address);
      this.sent.set(true);
    } catch {
      // The request itself did not reach the server — a connectivity problem, not a refusal; the
      // backend never refuses this endpoint. The address's known/unknown status stays unrevealed,
      // because no answer about it was ever given.
      this.validationError.set('Could not send the link. Check your connection and try again.');
    } finally {
      this.submitting.set(false);
    }
  }
}
