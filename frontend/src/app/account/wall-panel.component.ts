import { Component, computed, input } from '@angular/core';

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
 * The button is present and inert. `POST /api/billing/checkout` does not exist yet; task 13 wires
 * this button to it.
 */
@Component({
  selector: 'app-wall-panel',
  imports: [],
  template: `
    <div class="wall-panel mt-card mt-card--raised">
      <p class="wall-panel__lead">{{ headline() }}</p>
      <p class="wall-panel__body">A subscription opens the reader and keeps it open.</p>
      <button type="button" class="mt-pill mt-pill--coral wall-panel__subscribe">Subscribe</button>
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
    `,
  ],
})
export class WallPanelComponent {
  readonly code = input.required<WallCode>();
  readonly headline = computed(() => HEADLINES[this.code()]);
}
