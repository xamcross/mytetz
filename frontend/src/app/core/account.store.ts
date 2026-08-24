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

  /** The count of calls to [load]. Each call captures its own number and checks it again after
   * its request settles — see [load]. A slow call whose number is no longer the latest may not
   * write [view], [error], or [loading]. */
  private generation = 0;

  /**
   * Reads the account.
   *
   * A `401` means the learner is signed out. The method clears [view] and leaves [error] at
   * `null`, because a signed-out learner is not a fault.
   *
   * Any other failure keeps the last known [view] and sets [error] with a message. A stale count
   * is a better answer than a meter that empties in the middle of a session.
   *
   * A generation counter guards every write, so two overlapping calls cannot race. Only the call
   * that is still the most recent one when its request settles may change [view], [error], or
   * [loading].
   */
  async load(): Promise<void> {
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set(null);
    try {
      const view = await this.api.account();
      if (this.generation !== generation) return;
      this.view.set(view);
    } catch (err) {
      if (this.generation !== generation) return;
      if (err instanceof HttpErrorResponse && err.status === 401) {
        this.view.set(null);
      } else {
        this.error.set('Could not load your account. Check your connection and try again.');
      }
    } finally {
      if (this.generation === generation) this.loading.set(false);
    }
  }
}
