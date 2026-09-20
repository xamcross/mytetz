import { Component, effect, inject, signal } from '@angular/core';
import { AccountStore } from '../core/account.store';
import { ApiService } from '../core/api.service';

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
          <span
            class="allowance-meter__count"
            [class.allowance-meter__count--tick]="ticked()"
            (animationend)="onCountAnimationEnd($event)"
          >
            <span aria-hidden="true">{{ account.remaining }} of {{ account.allowance }} left</span>
            <span aria-hidden="true" class="allowance-meter__period">{{
              ' ' + periodWords(account.status)
            }}</span>
            <span class="mt-sr-only"
              >{{ account.remaining }} of {{ account.allowance }} left
              {{ periodWords(account.status) }}</span
            >
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
          @if (subscribeError(); as message) {
            <span class="allowance-meter__error" role="alert">{{ message }}</span>
          }
          <button
            type="button"
            class="mt-pill mt-pill--coral allowance-meter__subscribe"
            [disabled]="subscribing()"
            [attr.aria-busy]="subscribing() ? 'true' : null"
            (click)="subscribe()"
          >
            {{ subscribing() ? 'Opening checkout…' : 'Subscribe' }}
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
        flex-wrap: wrap;
        gap: 4px 8px;
        font-size: 13px;
      }
      .allowance-meter__count {
        font-weight: 700;
        min-width: 0;
      }
      /* Animation I. A brief lift and a small scale-up when the count changes — never drawing
         more attention than the answer that just spent it. 160ms and 2px is the whole budget. */
      .allowance-meter__count--tick {
        animation: meter-tick var(--mt-dur-state) var(--mt-ease-settle) both;
      }
      @keyframes meter-tick {
        0% {
          transform: none;
        }
        40% {
          transform: translateY(calc(-1 * var(--mt-move-press))) scale(1.06);
        }
        100% {
          transform: none;
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .allowance-meter__count--tick {
          animation: none;
        }
      }
      .allowance-meter__detail {
        color: var(--mt-muted);
        min-width: 0;
      }
      /*
       * A defect found in a screenshot on 2026-09-19, and not by issue #100's own test: on
       * the account page at 390px, the account card's own meter did not wrap. "12 of 40 left
       * today" and "Resets September 20, 2026 at 3:00 PM." sat on one line, wider than the
       * card, and the page scrolled sideways by about 20px. Issue #100 only ever fixed the
       * header — see the comment below — and its own test only asserted that the detail text
       * stays visible in the account card, never that the card itself stays inside the page.
       *
       * The two white-space: nowrap rules now apply inside the header only, through
       * host-context(.bar), so a one-line meter stays true where the design calls for it —
       * the header bar is a fixed 64px, and a wrapped meter there would collide with the row
       * below it — and the account page's own card, which has no such height limit, wraps onto a
       * second line instead of pushing the page sideways.
       */
      :host-context(.bar) .allowance-meter__count,
      :host-context(.bar) .allowance-meter__detail {
        white-space: nowrap;
      }
      /*
       * Defect found in review of issue #106: the flex-wrap: wrap rule above, added so the
       * account card's own meter could wrap, reached the header too. At 768px, 772px, 776px,
       * 780px and 800px — a tablet in portrait — the header's meter wrapped onto a second,
       * 34px-tall line, with the detail text below the count. The header bar is a fixed 64px, so
       * a wrapped meter there collides with the row below it. This rule keeps the header's own
       * meter on one line, the same way it always was; the account card, outside the bar
       * element, keeps the wrap.
       */
      :host-context(.bar) .allowance-meter {
        flex-wrap: nowrap;
      }
      .allowance-meter__error {
        color: var(--mt-err-ink);
        font-weight: 700;
      }
      /*
       * Issue #100, extended by a review of issue #133, and finished by issue #142 and issue
       * #153. At a phone width, the count and the detail together are wider than the header bar
       * — a real run once measured a scroll width of 488px inside a 390px window. The same
       * review found that the row stays too narrow for the detail from 769px up to about 790px,
       * once the nav shows again next to the meter. The owner then decided the header states no
       * detail text at all, for any account status and at any width: "Trial ends …" left first,
       * in issue #142, and "Resets …" left last, in issue #153. This one rule now hides the
       * detail inside the header at every width. host-context(.bar) matches the header's own bar
       * element only; the account page renders this same component outside that element, in its
       * own card, so the account page keeps the full detail text at every width.
       */
      :host-context(.bar) .allowance-meter__detail {
        display: none;
      }
      @media (max-width: 767px) {
        /*
         * Issue #133. Below 768px, the header row is too narrow for the count and its period
         * word together: "12 of 40 left in your trial" runs past the meter's own share of the
         * row and lands on top of the "Account" link. This rule hides the period word only, and
         * only inside the header, so the row reads "12 of 40 left" — true, and short enough to
         * fit. The aria-hidden attribute marks both the digits and the period word as
         * decorative, and the sibling .mt-sr-only span carries the one true sentence a screen
         * reader announces, in the header and on the account page alike. The account page keeps
         * this rule's own visible period word, because it renders outside the bar element, and
         * this rule needs a host-context(.bar) match to apply.
         */
        :host-context(.bar) .allowance-meter__period {
          display: none;
        }
        /*
         * Issue #100, round 2. The checkout error can wrap into several lines. Inside the row,
         * a tall child does not grow the bar: the bar keeps its own fixed height of 64px, and
         * the child overflows over the bar's other elements instead. A real run measures the
         * error box from y=-16 to y=89, well outside the bar's own 0-to-64 range.
         *
         * These two rules take the error out of the row and put it as a small card below the
         * bar. The card hangs from this component's own host element, so it moves with the
         * header when the page scrolls, and it needs no positioned ancestor in another file. A
         * card with position: fixed was the first attempt. The bar is not sticky, so such a card
         * stays on the screen, away from the header, after the learner scrolls. The host is about
         * 38px tall and sits in the middle of the 64px bar, so 16px below the host is below the
         * bar. The layout test asserts that result, and not this arithmetic.
         */
        :host-context(.bar) {
          position: relative;
        }
        :host-context(.bar) .allowance-meter__error {
          position: absolute;
          top: calc(100% + 16px);
          right: 0;
          z-index: 1;
          width: max-content;
          max-width: 260px;
          padding: 8px 12px;
          background: var(--mt-err-bg);
          border: var(--mt-border-w) solid var(--mt-err-border);
          border-radius: var(--mt-r-row);
        }
      }
    `,
  ],
})
export class AllowanceMeterComponent {
  private readonly account = inject(AccountStore);
  private readonly api = inject(ApiService);
  readonly view = this.account.view;

  /** Animation I. True for the one render after `remaining` changes from one real value to
   * another — never on the first render, and never when the view changes but the count does
   * not. Cleared by [onCountAnimationEnd], not by a timer: a timer in a zoneless component needs
   * its own destroy guard, and `animationend` needs none — the same rule `landed` follows on
   * `focus-card.component.ts`. */
  protected readonly ticked = signal(false);
  private previousRemaining: number | null = null;

  constructor() {
    effect(() => {
      const remaining = this.view()?.remaining ?? null;
      if (
        remaining !== null &&
        this.previousRemaining !== null &&
        remaining !== this.previousRemaining
      ) {
        this.ticked.set(true);
      }
      this.previousRemaining = remaining;
    });
  }

  /** Ends animation I's tick, on the animation's own last frame rather than on a timer. Guards
   * `event.animationName` the same way [onBodyAnimationEnd] on `focus-card.component.ts` does,
   * so an unrelated animationend bubbling up from a child never clears this early. */
  onCountAnimationEnd(event: AnimationEvent): void {
    if (event.animationName === 'meter-tick') this.ticked.set(false);
  }

  /** True while a checkout request is in flight. The button stays disabled during this time.
   * This stops a second click from sending a second request before the redirect happens. See
   * `WallPanelComponent.subscribing`, which the same pattern comes from. */
  readonly subscribing = signal(false);
  /** The message for a failed checkout request, or `null` when there is no failure. */
  readonly subscribeError = signal<string | null>(null);

  /** Asks the backend for a checkout URL, then follows it. See `WallPanelComponent.subscribe`,
   * which this method copies: the meter and the wall panel show the same button for the same
   * reason, so both start checkout the same way. */
  async subscribe(): Promise<void> {
    if (this.subscribing()) return;
    this.subscribing.set(true);
    this.subscribeError.set(null);
    try {
      const { url } = await this.api.checkout();
      this.redirect(url);
      // This method leaves `subscribing` set to true after success. The browser is about to
      // leave this page, so there is nothing left to re-enable.
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

  /** True for a status that carries a live count. See [METERED_STATUSES]. */
  metered(status: string): boolean {
    return METERED_STATUSES.has(status);
  }

  /** The word after the count. A trial names itself. A paid status names the day, because the
   * subscriber allowance resets once every 24 hours and not at one fixed clock time. */
  periodWords(status: string): string {
    return status === 'TRIALING' ? 'in your trial' : 'today';
  }

  /** The trial end, as a date, or `null` before a trial has one. Treats an absent key
   * (`undefined`) the same way as an explicit `null` — see `periodEndText` on
   * `AccountPageComponent` for the full reason. */
  trialEndText(epochMillis: number | null | undefined): string | null {
    return epochMillis == null ? null : formatDate(epochMillis);
  }

  /**
   * The next reset, as a date and a time, or `null` when no window has started yet.
   *
   * `null` is a real state and not a loading gap: it means the count is fresh and nothing has been
   * spent from it, so there is nothing to say about a reset.
   *
   * Carries the date and not only the time. A reset can land on the day after the one the learner
   * is reading on, and a time with no date would then read as today's when it is tomorrow's.
   *
   * Treats an absent key (`undefined`) the same way as an explicit `null` — see `periodEndText`
   * on `AccountPageComponent` for the full reason.
   */
  resetText(epochMillis: number | null | undefined): string | null {
    return epochMillis == null ? null : formatDateTime(epochMillis);
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
