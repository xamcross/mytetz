import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService } from './api.service';
import { AccountView } from './models';

/**
 * The signed-in learner's account, or `null` while nobody is signed in.
 *
 * Provided in root, unlike `SessionStore`. A browser tab has at most one signed-in account, and
 * that account outlives any one reading session or route.
 */
@Injectable({ providedIn: 'root' })
export class AccountStore {
  private readonly api = inject(ApiService);

  readonly view = signal<AccountView | null>(null);
  readonly loading = signal(false);
  /** A real failure to reach `GET /api/account` — not the same outcome as "signed out". See
   * `load()`, which is where the two are told apart. */
  readonly error = signal<string | null>(null);

  readonly signedIn = computed(() => this.view() !== null);

  /**
   * Reads the account.
   *
   * `401 SIGN_IN_REQUIRED` is the ordinary answer for a visitor with no session cookie, or an
   * expired one. It clears [view] and leaves [error] at `null` — a signed-out learner is not a
   * fault. Any other failure also clears [view], because there is no account to show, but it sets
   * [error] too, so a caller can tell "signed out" from "the request itself failed" instead of
   * reading both the same way.
   */
  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.view.set(await this.api.account());
    } catch (err) {
      this.view.set(null);
      if (!(err instanceof HttpErrorResponse) || err.status !== 401) {
        this.error.set('Could not load your account. Check your connection and try again.');
      }
    } finally {
      this.loading.set(false);
    }
  }
}
