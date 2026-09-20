import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AccountStore } from '../core/account.store';
import { ApiService } from '../core/api.service';
import { BillingPlansView } from '../core/models';
import { NavigationHistoryService } from '../core/navigation-history.service';

/**
 * The three statuses a learner can already manage at the Freemius customer portal — the same set
 * `AccountPageComponent.manageVisible` reads. A learner with one of these already pays, so this
 * page shows no second way to pay: one subscription, one checkout.
 */
const SUBSCRIBED_STATUSES: ReadonlySet<string> = new Set(['ACTIVE', 'PAST_DUE', 'CANCELLED']);

/** The message every failed checkout attempt shows. The same sentence `WallPanelComponent` and
 * `AllowanceMeterComponent` showed before this page took over the checkout call. */
const CHECKOUT_ERROR = 'Could not start checkout. Check your connection and try again.';

/**
 * `/subscribe` — the plan screen (issue #137).
 *
 * A signed-in learner with no paid subscription always needs a path to a checkout. Before this
 * page, the "Subscribe" button in the header and on the wall started the checkout by itself, with
 * no plan, no price and no free option on screen first. Every "Subscribe" control in the app now
 * opens this page instead, and the checkout starts from here, and only from here.
 *
 * The page reads `GET /api/billing/plans` for the price and the trial and subscriber numbers, and
 * shows a skeleton while that request runs, and an error with a retry control when it fails. It
 * reads `AccountStore.view` for the signed-in learner's own status, to decide which control the
 * Premium card shows: "Subscribe" for a learner who may still pay, "Sign in to subscribe" for a
 * visitor with no account, and, for a learner who already pays, a sentence that says so with a
 * link to the account page instead of the two-card comparison.
 *
 * The page states only what the product does: a free trial, and a free reading surface with no
 * ongoing allowance of its own. It makes no claim about a result of reading, and no statement
 * about a refund — see `docs/superpowers/specs/2026-08-07-monetization-design.md` section 7.4 for
 * what a lapsed learner keeps, which is exactly what the free card states.
 */
@Component({
  selector: 'app-subscribe-page',
  imports: [RouterLink],
  template: `
    <main class="subscribe-page">
      <h1>Choose your plan</h1>

      @if (loading() && plans() === null) {
        <p class="mt-sr-only" role="status">Loading the plans…</p>
        <div class="mt-card mt-card--raised subscribe-page__skeleton" aria-hidden="true">
          <span class="mt-skeleton subscribe-page__skeleton-row"></span>
          <span
            class="mt-skeleton subscribe-page__skeleton-row subscribe-page__skeleton-row--76"
          ></span>
          <span
            class="mt-skeleton subscribe-page__skeleton-row subscribe-page__skeleton-row--60"
          ></span>
        </div>
      }

      @if (loadError(); as message) {
        <div class="mt-card mt-card--error subscribe-page__banner" role="alert">
          <p class="subscribe-page__banner-text">{{ message }}</p>
          <button type="button" class="mt-pill mt-pill--ghost" (click)="load()">Try again</button>
        </div>
      }

      @if (plans(); as plan) {
        @if (alreadySubscribed()) {
          <div class="mt-card mt-card--raised subscribe-page__card">
            <p class="subscribe-page__lead">You already have a subscription.</p>
            <a class="mt-pill mt-pill--ghost" routerLink="/account">Manage subscription</a>
          </div>
        } @else {
          <div class="subscribe-page__grid">
            <div class="mt-card mt-card--raised subscribe-page__card">
              <p class="mt-eyebrow subscribe-page__eyebrow">Premium</p>
              <p class="subscribe-page__price">\${{ plan.priceUsdPerMonth }} each month</p>
              <p class="subscribe-page__line">
                {{ plan.subscriberDailyExplains }} tokens each day.
              </p>
              <p class="subscribe-page__note">
                One token pays for one new explanation or one quiz.
              </p>
              @if (subscribeError(); as message) {
                <p class="subscribe-page__error" role="alert">{{ message }}</p>
              }
              @if (signedIn()) {
                <button
                  type="button"
                  data-action="subscribe"
                  class="mt-pill mt-pill--coral subscribe-page__action"
                  [disabled]="subscribing()"
                  [attr.aria-busy]="subscribing() ? 'true' : null"
                  (click)="subscribe()"
                >
                  {{ subscribing() ? 'Opening checkout…' : 'Subscribe' }}
                </button>
              } @else {
                <a
                  class="mt-pill mt-pill--coral subscribe-page__action"
                  data-action="sign-in-to-subscribe"
                  routerLink="/auth"
                  >Sign in to subscribe</a
                >
              }
            </div>

            <div class="mt-card mt-card--raised subscribe-page__card">
              <p class="mt-eyebrow subscribe-page__eyebrow">Free</p>
              <p class="subscribe-page__line">
                Read the dashboard and every topic page. No account is necessary.
              </p>
              <p class="subscribe-page__line">
                A new learner gets one trial: {{ plan.trialGenerations }} tokens over
                {{ plan.trialDays }} days. No card is necessary.
              </p>
              <p class="subscribe-page__line">
                After the trial, a new explanation needs Premium. Each session and each explanation
                that you have stays open.
              </p>
              <a
                class="mt-pill mt-pill--ghost subscribe-page__action"
                data-action="stay-on-free-plan"
                [routerLink]="backHref()"
                >Stay on the free plan</a
              >
            </div>
          </div>
        }

        <p class="subscribe-page__terms"><a routerLink="/terms">Terms</a></p>
      }
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .subscribe-page {
        max-width: 880px;
        margin: 0 auto;
        padding: 36px 20px 64px;
        display: flex;
        flex-direction: column;
        gap: 20px;
      }
      .subscribe-page h1 {
        margin: 0;
        font-size: 28px;
      }
      .subscribe-page__skeleton {
        width: 100%;
        padding: 32px 36px;
        display: flex;
        flex-direction: column;
        gap: 16px;
        box-sizing: border-box;
      }
      .subscribe-page__skeleton-row {
        display: block;
        height: 18px;
        width: 90%;
      }
      .subscribe-page__skeleton-row--76 {
        width: 76%;
      }
      .subscribe-page__skeleton-row--60 {
        width: 60%;
      }
      .subscribe-page__banner {
        padding: 20px 24px;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 12px;
      }
      .subscribe-page__banner-text {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
      }
      /* Two equal-width columns from 640px up, one column below it. stretch keeps both cards the
         same height, so the two action buttons at the bottom of each card, held down with
         margin-top: auto, align on the same row. */
      .subscribe-page__grid {
        display: grid;
        grid-template-columns: 1fr;
        align-items: stretch;
        gap: 20px;
      }
      @media (min-width: 640px) {
        .subscribe-page__grid {
          grid-template-columns: 1fr 1fr;
        }
      }
      .subscribe-page__card {
        padding: 32px 28px;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 12px;
      }
      .subscribe-page__eyebrow {
        margin: 0;
      }
      .subscribe-page__price {
        margin: 0;
        font-size: 28px;
        font-weight: 800;
        color: var(--mt-ink);
      }
      .subscribe-page__lead {
        margin: 0;
        font-size: 17px;
        font-weight: 600;
      }
      .subscribe-page__line {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
        color: var(--mt-muted);
      }
      .subscribe-page__note {
        margin: 0;
        font-size: 13px;
        line-height: 1.5;
        font-weight: 600;
        color: var(--mt-muted);
      }
      .subscribe-page__error {
        margin: 0;
        font-size: 13px;
        font-weight: 700;
        color: var(--mt-err-ink);
      }
      /* Pushes the action to the bottom of the card, so the Premium and the Free button share one
         row even when the two cards' own text takes a different amount of space. */
      .subscribe-page__action {
        margin-top: auto;
      }
      .subscribe-page__terms {
        margin: 0;
        font-size: 14px;
        font-weight: 600;
      }
    `,
  ],
})
export class SubscribePageComponent {
  private readonly api = inject(ApiService);
  private readonly account = inject(AccountStore);
  private readonly history = inject(NavigationHistoryService);

  /** The numbers `GET /api/billing/plans` answers, or `null` before the first answer lands. */
  readonly plans = signal<BillingPlansView | null>(null);
  readonly loading = signal(false);
  /** The message for a failed plans request, or `null` when there is no failure. */
  readonly loadError = signal<string | null>(null);

  /** True once a sign-in fills `AccountStore.view`. A visitor with no account sees a sign-in link
   * in place of the Subscribe button. */
  readonly signedIn = computed(() => this.account.view() !== null);

  /** True for a learner who already pays — see [SUBSCRIBED_STATUSES]. Such a learner sees no
   * second way to pay, so the page skips the two-card comparison entirely. */
  readonly alreadySubscribed = computed(() => {
    const status = this.account.view()?.status;
    return status !== undefined && SUBSCRIBED_STATUSES.has(status);
  });

  /** The in-app page "Stay on the free plan" returns to. Falls back to `/` both when the learner
   * opened this page directly (no earlier in-app page exists) and when the previous page is this
   * very page — a reload while a checkout error banner is on screen, for instance. */
  readonly backHref = computed(() => {
    const previous = this.history.previous();
    return previous.startsWith('/subscribe') ? '/' : previous;
  });

  /** True while a checkout request is in flight. See [subscribe]. */
  readonly subscribing = signal(false);
  /** The message for a failed checkout request, or `null` when there is no failure. */
  readonly subscribeError = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  /** Reads the plan numbers. Called once from the constructor, and again from the retry control
   * in the error banner. */
  async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const plans = await this.api.billingPlans();
      this.plans.set(plans);
    } catch {
      this.loadError.set('Could not load the plans. Check your connection and try again.');
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Asks the backend for a checkout URL, then follows it — the one call every "Subscribe" control
   * in the app now leads to, moved here from `AllowanceMeterComponent` and `WallPanelComponent`
   * (issue #137).
   *
   * Accepts only a link that starts with `https://`, the same rule `FreemiusApiClient.kt`'s own
   * `isAcceptableLink` applies on the backend to a link the Freemius API returns. This route
   * builds its own link rather than relay one from the vendor, but the browser is about to leave
   * this page on the strength of this one string, so the check stays cheap insurance against a
   * malformed answer.
   */
  async subscribe(): Promise<void> {
    if (this.subscribing()) return;
    this.subscribing.set(true);
    this.subscribeError.set(null);
    try {
      const { url } = await this.api.checkout();
      if (!url.startsWith('https://')) throw new Error('the checkout link was not https');
      this.redirect(url);
      // This method leaves `subscribing` set to true after success. The browser is about to leave
      // this page, so there is nothing left to re-enable.
    } catch {
      this.subscribeError.set(CHECKOUT_ERROR);
      this.subscribing.set(false);
    }
  }

  /** Sends the browser to [url]. This method stays separate so a test can replace it. jsdom does
   * not implement real navigation, so a test cannot check `window.location` directly. */
  redirect(url: string): void {
    window.location.href = url;
  }
}
