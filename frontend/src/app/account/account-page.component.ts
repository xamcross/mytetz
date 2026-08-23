import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { AllowanceMeterComponent } from './allowance-meter.component';
import { AccountStore } from '../core/account.store';
import { ApiService } from '../core/api.service';

/**
 * `/account` — the signed-in learner's own account page.
 *
 * The page reads `AccountStore.view` for the email, the status, the period end and the meter.
 * The page also reads `AccountStore.error`. A failed refresh keeps the meter's old numbers on
 * screen. This page is the one place that reports the failure. See `AccountStore.error`'s own
 * comment for the full reason.
 *
 * The page loads the account on every visit. The page reads no query parameter. Freemius sends
 * the browser back here after checkout. The page does not trust anything in that return URL. A
 * fresh `GET /api/account` is the only trusted source.
 *
 * "Manage subscription" is present, and the button is inert. `POST /api/billing/checkout` is the
 * only billing link this backend exposes today. This task does not confirm that the same
 * checkout page also manages an existing subscription, so the manage button stays inert until a
 * task confirms this against Freemius's own documentation. See the task 13 report.
 *
 * "Delete account" opens a confirmation panel first — see [confirmingDelete]. The backend needs a
 * fresh sign-in to complete a deletion. A stale session answers `403 CONFIRMATION_REQUIRED`, and
 * [confirmDelete] shows a message that tells the learner to sign in again, rather than a generic
 * failure.
 */
@Component({
  selector: 'app-account-page',
  imports: [AllowanceMeterComponent],
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

          @if (actionError(); as message) {
            <p class="account-page__error" role="alert">{{ message }}</p>
          }

          <div class="account-page__actions">
            <!-- Present and inert — see the class doc comment. -->
            <button
              type="button"
              class="mt-pill mt-pill--ghost"
              data-action="manage-subscription"
              disabled
            >
              Manage subscription
            </button>
            <button
              type="button"
              class="mt-pill mt-pill--ghost"
              data-action="sign-out"
              (click)="signOut()"
            >
              Sign out
            </button>
            <button
              type="button"
              class="mt-pill mt-pill--ghost"
              data-action="sign-out-everywhere"
              (click)="signOutEverywhere()"
            >
              Sign out everywhere
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

  readonly view = this.account.view;
  readonly error = this.account.error;
  readonly loading = this.account.loading;

  /** The message for a failed sign-out request, or `null` when there is no failure. This signal
   * stays apart from [error]. [error] belongs to `AccountStore` and reports only a failed
   * `GET /api/account`. */
  readonly actionError = signal<string | null>(null);

  ngOnInit(): void {
    void this.account.load();
  }

  /** The period end, as a date, or `null` when the account has none. The method formats the date
   * in UTC. `AllowanceMeterComponent.formatDate` does the same, for the same reason: a spec pins
   * the date against one fixed instant, and a format that read the machine's own zone would print
   * different text on a different machine. */
  periodEndText(epochMillis: number | null): string | null {
    return epochMillis === null ? null : formatDate(epochMillis);
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

  async signOutEverywhere(): Promise<void> {
    this.actionError.set(null);
    try {
      await this.api.signOutAll();
      await this.account.load();
    } catch {
      this.actionError.set('Could not sign out everywhere. Check your connection and try again.');
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
