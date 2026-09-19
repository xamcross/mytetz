import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AllowanceMeterComponent } from './allowance-meter.component';
import { AccountStore } from '../core/account.store';
import { ApiService } from '../core/api.service';

/** How often the page reads the account again while a post-purchase poll runs. */
const POLL_INTERVAL_MILLIS = 2000;

/** How long the page waits for a changed status or period end before it gives up. */
const POLL_TIMEOUT_MILLIS = 30000;

/**
 * `/account` — the signed-in learner's own account page.
 *
 * The page reads `AccountStore.view` for the email, the status, the period end and the meter.
 * The page also reads `AccountStore.error`. A failed refresh keeps the meter's old numbers on
 * screen. This page is the one place that reports the failure. See `AccountStore.error`'s own
 * comment for the full reason.
 *
 * The page loads the account on every visit. Freemius sends the browser back here after
 * checkout. The return URL carries an `action` query parameter and other values, including the
 * learner's own email. The page reads only `action`, and only as a hint to poll. It reads that
 * hint one time, from the route the page opened with, before it removes the query string. It
 * takes no status, no date and no allowance from the URL. A fresh `GET /api/account` stays the
 * only trusted source.
 *
 * When the hint is present, the page reads the account again every 2 seconds. The poll stops on
 * a changed status, on a changed period end, after 30 seconds, or when the page closes. [polling]
 * and [pollTimedOut] carry the two messages a learner sees during and after that wait.
 *
 * The page removes the query string from the address bar once the first load settles. This
 * clears the learner's own email out of the address bar and the browser history.
 *
 * "Manage subscription" shows only for a learner with a live or a recent subscription — status
 * `ACTIVE`, `PAST_DUE` or `CANCELLED`. See [manageVisible]. A click asks
 * `POST /api/billing/portal` for a link, then sends the browser to the Freemius customer portal.
 * There the learner can cancel, change a payment method, or read an invoice. [manageSubscription]
 * carries the request, and [redirect] carries the leave-this-page step, the same split
 * `WallPanelComponent.subscribe` and `WallPanelComponent.redirect` use for the checkout link.
 *
 * "Delete account" opens a confirmation panel first — see [confirmingDelete]. The backend needs a
 * fresh sign-in to complete a deletion. A stale session answers `403 CONFIRMATION_REQUIRED`, and
 * [confirmDelete] shows a message that tells the learner to sign in again, rather than a generic
 * failure.
 */
@Component({
  selector: 'app-account-page',
  imports: [AllowanceMeterComponent, RouterLink],
  template: `
    <main class="account-page">
      @if (loading() && view() === null) {
        <p class="visually-hidden" role="status">Loading your account…</p>
      }

      @if (error(); as message) {
        <div class="mt-card mt-card--error banner banner--error" role="alert">
          <p class="banner__message">{{ message }}</p>
        </div>
      }

      @if (view(); as account) {
        <div class="mt-card mt-card--raised account-page__card">
          <div class="account-page__row">
            <span class="account-page__label">Email</span>
            <span class="account-page__value">{{ account.email }}</span>
          </div>
          <div class="account-page__row">
            <span class="account-page__label">Status</span>
            <span class="account-page__value">{{ account.status }}</span>
          </div>
          @if (periodEndText(account.currentPeriodEndsAtEpochMillis); as periodEnd) {
            <div class="account-page__row">
              <span class="account-page__label">Current period ends</span>
              <span class="account-page__value">{{ periodEnd }}</span>
            </div>
          }

          <app-allowance-meter />

          @if (polling()) {
            <p class="account-page__poll-status" role="status">
              We are waiting for the payment confirmation. Your new allowance shows here in a
              moment.
            </p>
          } @else if (pollTimedOut()) {
            <p class="account-page__poll-status" role="status">
              The confirmation is not here yet. Load this page again in a minute.
            </p>
          }

          @if (actionError(); as message) {
            <p class="account-page__error" role="alert">{{ message }}</p>
          }

          <div class="account-page__actions">
            @if (manageVisible()) {
              <button
                type="button"
                class="mt-pill mt-pill--ghost"
                data-action="manage-subscription"
                [disabled]="openingPortal()"
                (click)="manageSubscription()"
              >
                Manage subscription
              </button>
            }
            <a class="mt-pill mt-pill--ghost" routerLink="/terms">Terms</a>
            <button
              type="button"
              class="mt-pill mt-pill--ghost"
              data-action="sign-out"
              (click)="signOut()"
            >
              Sign out
            </button>
            @if (!confirmingDelete()) {
              <button
                type="button"
                class="mt-pill mt-pill--ghost"
                data-action="delete-account"
                (click)="startDelete()"
              >
                Delete account
              </button>
            }
          </div>

          @if (confirmingDelete()) {
            <div class="mt-card mt-card--dashed account-page__confirm" role="alertdialog">
              <p class="account-page__confirm-text">
                This permanently deletes your account, every reading session and the allowance
                meter. It does not delete any explanation — those stay in the catalogue for other
                learners. This cannot be undone.
              </p>
              <div class="account-page__actions">
                <button
                  type="button"
                  class="mt-pill mt-pill--coral"
                  data-action="delete-account-confirm"
                  [disabled]="deleting()"
                  (click)="confirmDelete()"
                >
                  Yes, delete my account
                </button>
                <button
                  type="button"
                  class="mt-pill mt-pill--ghost"
                  data-action="delete-account-cancel"
                  [disabled]="deleting()"
                  (click)="cancelDelete()"
                >
                  Cancel
                </button>
              </div>
            </div>
          }
        </div>
      }
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .account-page {
        max-width: 640px;
        margin: 0 auto;
        padding: 36px 20px;
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .account-page__card {
        padding: 32px 36px;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 16px;
      }
      .account-page__row {
        display: flex;
        gap: 8px;
        font-size: 15px;
      }
      .account-page__label {
        font-weight: 700;
        color: var(--mt-muted);
      }
      .account-page__value {
        font-weight: 600;
      }
      .account-page__error {
        margin: 0;
        font-size: 13px;
        font-weight: 700;
        color: var(--mt-err-ink);
      }
      .account-page__poll-status {
        margin: 0;
        font-size: 13px;
        font-weight: 600;
        color: var(--mt-muted);
      }
      .account-page__actions {
        display: flex;
        flex-wrap: wrap;
        gap: 10px;
      }
      .account-page__confirm {
        width: 100%;
        padding: 20px 24px;
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .account-page__confirm-text {
        margin: 0;
        font-size: 14px;
        line-height: 1.55;
        font-weight: 500;
        color: var(--mt-muted);
      }
      .visually-hidden {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
        white-space: nowrap;
      }
      .banner {
        padding: 20px 24px;
      }
      .banner__message {
        margin: 0;
        font-size: 15px;
        line-height: 1.55;
        font-weight: 500;
      }
    `,
  ],
})
export class AccountPageComponent implements OnInit {
  private readonly account = inject(AccountStore);
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly view = this.account.view;
  readonly error = this.account.error;
  readonly loading = this.account.loading;

  /** The message for a failed sign-out request, or `null` when there is no failure. This signal
   * stays apart from [error]. [error] belongs to `AccountStore` and reports only a failed
   * `GET /api/account`. */
  readonly actionError = signal<string | null>(null);

  /** True when [view] carries a status a learner can manage at the vendor portal: an active
   * subscription, one behind on a payment, or one already cancelled. A trial has no vendor
   * subscription yet. An expired subscription has none left to manage. Both statuses hide the
   * control. */
  readonly manageVisible = computed(() => {
    const status = this.view()?.status;
    return status === 'ACTIVE' || status === 'PAST_DUE' || status === 'CANCELLED';
  });

  /** True while a portal-link request is in flight. The button disables on this. A second click
   * before the redirect happens must not send a second request. */
  readonly openingPortal = signal(false);

  /** True while the post-purchase poll runs. See the class doc comment. */
  readonly polling = signal(false);

  /** True once the poll's 30-second limit passes with no change. */
  readonly pollTimedOut = signal(false);

  private pollIntervalId: ReturnType<typeof setInterval> | null = null;
  private pollDeadlineId: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    // Stops both poll timers on the way out, so a torn-down page never calls `AccountStore.load`
    // again, and never reads the account of whichever session opens `/account` next.
    inject(DestroyRef).onDestroy(() => this.stopPoll());
  }

  ngOnInit(): void {
    // Read now, before the first `await` below, and keep the answer in [pollHint]. A later read
    // of `route.snapshot` is not safe: `removeQueryString`'s own `Router.navigate` call reuses
    // this component once it completes, and the router replaces `ActivatedRoute.snapshot` with
    // the snapshot of the cleared URL — one with no `action` parameter left to find.
    const pollHint = this.route.snapshot.queryParamMap.has('action');
    void this.loadAndMaybePoll(pollHint);
  }

  /** Reads the account, clears Freemius's return parameters from the address bar, then starts
   * the poll when [pollHint] is true and the first read left a signed-in view. */
  private async loadAndMaybePoll(pollHint: boolean): Promise<void> {
    await this.account.load();
    await this.removeQueryString();
    if (!pollHint) return;
    const view = this.account.view();
    if (view === null) return;
    this.startPoll(view.status, view.currentPeriodEndsAtEpochMillis);
  }

  /** Drops every query parameter from the current URL, with no new history entry. A no-op when
   * the URL already carries none, so a plain visit to `/account` triggers no navigation. */
  private async removeQueryString(): Promise<void> {
    if (Object.keys(this.route.snapshot.queryParams).length === 0) return;
    await this.router.navigate([], { queryParams: {}, replaceUrl: true });
  }

  /** Reads the account again every [POLL_INTERVAL_MILLIS], until [stopPoll] runs — on a changed
   * status or period end, on the [POLL_TIMEOUT_MILLIS] deadline, or on destroy. The deadline
   * timer starts before the interval timer, so a tick that lands on both fires the deadline
   * first: the interval then never fires again, and no request is left in flight once the
   * learner reads the timeout message. */
  private startPoll(status: string, periodEndsAtEpochMillis: number | null): void {
    this.polling.set(true);
    this.pollTimedOut.set(false);
    this.pollDeadlineId = setTimeout(() => {
      this.stopPoll();
      this.pollTimedOut.set(true);
    }, POLL_TIMEOUT_MILLIS);
    this.pollIntervalId = setInterval(() => {
      void this.account.load().then(() => {
        const view = this.account.view();
        const changed =
          view === null ||
          view.status !== status ||
          view.currentPeriodEndsAtEpochMillis !== periodEndsAtEpochMillis;
        if (changed) this.stopPoll();
      });
    }, POLL_INTERVAL_MILLIS);
  }

  private stopPoll(): void {
    if (this.pollIntervalId !== null) {
      clearInterval(this.pollIntervalId);
      this.pollIntervalId = null;
    }
    if (this.pollDeadlineId !== null) {
      clearTimeout(this.pollDeadlineId);
      this.pollDeadlineId = null;
    }
    this.polling.set(false);
  }

  /** The period end, as a date, or `null` when the account has none. The method formats the date
   * in UTC. `AllowanceMeterComponent.formatDate` does the same, for the same reason: a spec pins
   * the date against one fixed instant, and a format that read the machine's own zone would print
   * different text on a different machine.
   *
   * Treats an absent key (`undefined`) the same way as an explicit `null`. `AccountView`'s own
   * KDoc names the trap: the route serializer once omitted this key from the wire for a trial
   * learner. The backend fix removes that cause. This check stays as a second guard, against a
   * stale cached answer, and against a later regression that puts a default back. */
  periodEndText(epochMillis: number | null | undefined): string | null {
    return epochMillis == null ? null : formatDate(epochMillis);
  }

  /**
   * Sends `POST /api/billing/portal`, then sends the browser to the returned link.
   *
   * The server reads the signed-in learner's own email from the session. This method sends no
   * email and no id. A `404 NO_SUBSCRIPTION` and every other failure share one message: this
   * method cannot tell a missing subscription apart from a vendor outage, and a learner does not
   * need that difference to know what to try next.
   */
  async manageSubscription(): Promise<void> {
    if (this.openingPortal()) return;
    this.openingPortal.set(true);
    this.actionError.set(null);
    try {
      const { url } = await this.api.portal();
      this.redirect(url);
      // This method leaves `openingPortal` set to true after success. See `WallPanelComponent.subscribe`
      // for the reason: the browser is about to leave this page, and nothing here must enable the
      // button again.
    } catch {
      this.actionError.set(
        'Could not open the customer portal. Check your connection and try again.',
      );
      this.openingPortal.set(false);
    }
  }

  /** Sends the browser to [url]. This method stays separate so a test can replace it. jsdom does
   * not implement real navigation, so a test cannot check `window.location` directly. Mirrors
   * `WallPanelComponent.redirect`. */
  redirect(url: string): void {
    window.location.href = url;
  }

  async signOut(): Promise<void> {
    this.actionError.set(null);
    try {
      await this.api.signOut();
      // This method reads the account again from the server. It does not clear the view on its
      // own. `POST /api/auth/sign-out` clears the session cookie, so the next `GET /api/account`
      // answers 401, and `load()` clears the view itself on that 401 — the same rule the class
      // comment names for the return from checkout: trust the server, not a local guess.
      await this.account.load();
    } catch {
      this.actionError.set('Could not sign out. Check your connection and try again.');
    }
  }

  /** True while the confirm panel for account deletion is open. */
  readonly confirmingDelete = signal(false);

  /** True while a delete request is in flight. Both buttons in the confirm panel disable on this,
   * so a second click cannot send a second `POST /api/account/delete`. */
  readonly deleting = signal(false);

  startDelete(): void {
    this.actionError.set(null);
    this.confirmingDelete.set(true);
  }

  cancelDelete(): void {
    this.confirmingDelete.set(false);
  }

  /**
   * Sends `POST /api/account/delete`.
   *
   * A `403` means the session is not fresh. `AuthRoutes.kt`'s own comment on the route states the
   * rule: a fresh sign-in is the confirmation. This method reads that one status and shows a
   * message that tells the learner to sign in again, rather than the generic failure text every
   * other status gets.
   *
   * A success reads the account again, the same pattern [signOut] uses. The cleared cookie makes
   * that read answer `401`, and `AccountStore.load` clears the view on a `401` — so the page ends
   * on the signed-out state with no separate message to keep in step with the server.
   */
  async confirmDelete(): Promise<void> {
    this.actionError.set(null);
    this.deleting.set(true);
    try {
      await this.api.deleteAccount();
      this.confirmingDelete.set(false);
      await this.account.load();
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 403) {
        this.actionError.set(
          'Sign in again through a fresh magic link, then delete your account right away.',
        );
      } else {
        this.actionError.set('Could not delete your account. Check your connection and try again.');
      }
    } finally {
      this.deleting.set(false);
    }
  }
}

function formatDate(epochMillis: number): string {
  return new Date(epochMillis).toLocaleDateString('en-US', {
    timeZone: 'UTC',
    month: 'long',
    day: 'numeric',
    year: 'numeric',
  });
}
