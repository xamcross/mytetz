import { Component, inject } from '@angular/core';
import { AccountStore } from '../core/account.store';

/** The statuses that carry a live count. Every other status — `NONE`, `EXPIRED`, or a status this
 * client does not yet know — shows a subscribe link and no count, because there is nothing true
 * to count for an account with no active allowance. */
const METERED_STATUSES: ReadonlySet<string> = new Set([
  'TRIALING',
  'ACTIVE',
  'CANCELLED',
  'PAST_DUE',
]);

/**
 * The remaining-allowance display in the shell's header.
 *
 * Reads `AccountStore` directly rather than taking an input: the shell places this component once,
 * beside the status dot, and every visit to every page needs the same number.
 *
 * Renders nothing when `view()` is `null`. A signed-out visitor has no account, and no meter is the
 * honest answer — not a zero, and not a dash.
 */
@Component({
  selector: 'app-allowance-meter',
  imports: [],
  template: `
    @if (view(); as account) {
      <div class="allowance-meter">
        @if (metered(account.status)) {
          <span class="allowance-meter__count">
            {{ account.remaining }} of {{ account.allowance }} left
            {{ periodWords(account.status) }}
          </span>
          @if (account.status === 'TRIALING') {
            @if (trialEndText(account.trialEndsAtEpochMillis); as end) {
              <span class="allowance-meter__detail">Trial ends {{ end }}.</span>
            }
          } @else {
            @if (resetText(account.resetsAtEpochMillis); as reset) {
              <span class="allowance-meter__detail">Resets {{ reset }}.</span>
            }
          }
        } @else {
          <!-- Present and inert, the same as the wall panel's own button: there is no checkout
               route yet for either one to call. Task 13 wires both. -->
          <button type="button" class="mt-pill mt-pill--coral allowance-meter__subscribe">
            Subscribe
          </button>
        }
      </div>
    }
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
      }
      .allowance-meter {
        display: flex;
        align-items: baseline;
        gap: 8px;
        font-size: 13px;
      }
      .allowance-meter__count {
        font-weight: 700;
        white-space: nowrap;
      }
      .allowance-meter__detail {
        color: var(--mt-muted);
        white-space: nowrap;
      }
    `,
  ],
})
export class AllowanceMeterComponent {
  private readonly account = inject(AccountStore);
  readonly view = this.account.view;

  /** True for a status that carries a live count. See [METERED_STATUSES]. */
  metered(status: string): boolean {
    return METERED_STATUSES.has(status);
  }

  /** The word after the count. A trial names itself. A paid status names the day, because the
   * subscriber allowance resets once every 24 hours and not at one fixed clock time. */
  periodWords(status: string): string {
    return status === 'TRIALING' ? 'in your trial' : 'today';
  }

  /** The trial end, as a date, or `null` before a trial has one. */
  trialEndText(epochMillis: number | null): string | null {
    return epochMillis === null ? null : formatDate(epochMillis);
  }

  /**
   * The next reset, as a date and a time, or `null` when no window has started yet.
   *
   * `null` is a real state and not a loading gap: it means the count is fresh and nothing has been
   * spent from it, so there is nothing to say about a reset.
   *
   * Carries the date and not only the time. A reset can land on the day after the one the learner
   * is reading on, and a time with no date would then read as today's when it is tomorrow's.
   */
  resetText(epochMillis: number | null): string | null {
    return epochMillis === null ? null : formatDateTime(epochMillis);
  }
}

/**
 * Formats in UTC rather than the visitor's own zone.
 *
 * The meter's spec pins these against a fixed instant. A format that read the machine's own zone
 * would make the rendered text depend on where the test runs, and on where the browser sits.
 */
function formatDate(epochMillis: number): string {
  return new Date(epochMillis).toLocaleDateString('en-US', {
    timeZone: 'UTC',
    month: 'long',
    day: 'numeric',
    year: 'numeric',
  });
}

function formatDateTime(epochMillis: number): string {
  return new Date(epochMillis).toLocaleString('en-US', {
    timeZone: 'UTC',
    month: 'long',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}
