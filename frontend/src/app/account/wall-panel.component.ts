import { Component, computed, inject, input, signal } from '@angular/core';
import { ApiService } from '../core/api.service';

/** The two refusals that share this wall. Every other refusal — a bad span, a quota, a broken
 * stream — keeps its own banner and never reaches this component; see
 * `ReaderPageComponent.subscribeRequired`. */
export type WallCode = 'TRIAL_EXHAUSTED' | 'SUBSCRIPTION_REQUIRED';

/** The one line that names why the wall is up. `TRIAL_EXHAUSTED` addresses a learner whose trial
 * worked and ran out. `SUBSCRIPTION_REQUIRED` addresses a learner whose access has lapsed or never
 * started, so it must not name a trial that learner may never have had. */
const HEADLINES: Readonly<Record<WallCode, string>> = {
  TRIAL_EXHAUSTED: 'Your trial ran out of explanations.',
  SUBSCRIPTION_REQUIRED: 'You need a subscription to keep reading.',
};

/**
 * The panel a learner sees in place of an explanation once paying is the only way forward.
 *
 * One panel for two codes. `TRIAL_EXHAUSTED` and `SUBSCRIPTION_REQUIRED` both mean the same thing
 * to a learner — a subscription is what reopens the reader — so both open this component and not
 * two near-duplicates.
 *
 * This is deliberately a different component from the reader's wait banner. Paying and waiting are
 * different advice, and a trial pool does not roll over, so a trial learner told to wait would be
 * waiting for a reset that never comes. See `ReaderPageComponent.bannerError`, which keeps both
 * codes here out of the banner for the same reason.
 *
 * The Subscribe button asks the backend for a checkout URL. The button then sends the browser to
 * that URL. The server builds the URL and adds the learner's email. The browser never builds the
 * URL, and the browser never holds a checkout secret.
 */
@Component({
  selector: 'app-wall-panel',
  imports: [],
  template: `
    <div class="wall-panel mt-card mt-card--raised">
      <p class="wall-panel__lead">{{ headline() }}</p>
      <p class="wall-panel__body">A subscription opens the reader and keeps it open.</p>
      @if (subscribeError(); as message) {
        <p class="wall-panel__error" role="alert">{{ message }}</p>
      }
      <button
        type="button"
        class="mt-pill mt-pill--coral wall-panel__subscribe"
        [disabled]="subscribing()"
        (click)="subscribe()"
      >
        Subscribe
      </button>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .wall-panel {
        padding: 32px 36px;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 16px;
      }
      .wall-panel__lead {
        margin: 0;
        font-size: 20px;
        font-weight: 600;
      }
      .wall-panel__body {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
        color: var(--mt-muted);
        max-width: 48ch;
      }
      .wall-panel__error {
        margin: 0;
        font-size: 13px;
        font-weight: 700;
        color: var(--mt-err-ink);
      }
    `,
  ],
})
export class WallPanelComponent {
  private readonly api = inject(ApiService);

  readonly code = input.required<WallCode>();
  readonly headline = computed(() => HEADLINES[this.code()]);

  /** True while a checkout request is in flight. The button stays disabled during this time.
   * This stops a second click from sending a second request before the redirect happens. */
  readonly subscribing = signal(false);
  /** The message for a failed checkout request, or `null` when there is no failure. */
  readonly subscribeError = signal<string | null>(null);

  async subscribe(): Promise<void> {
    if (this.subscribing()) return;
    this.subscribing.set(true);
    this.subscribeError.set(null);
    try {
      const { url } = await this.api.checkout();
      this.redirect(url);
      // This method leaves `subscribing` set to true after success. The browser is about to
      // leave this page, so there is nothing left to re-enable. See
      // `CatalogPageComponent.goToSession` for the same reason.
    } catch {
      this.subscribeError.set('Could not start checkout. Check your connection and try again.');
      this.subscribing.set(false);
    }
  }

  /** Sends the browser to [url]. This method stays separate so a test can replace it. jsdom does
   * not implement real navigation, so a test cannot check `window.location` directly. */
  redirect(url: string): void {
    window.location.href = url;
  }
}
