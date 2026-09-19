import { Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { SignInPanelComponent } from './sign-in-panel.component';

/**
 * `/auth` — the one route with a way in for a visitor who has not clicked a highlight first, and
 * where the browser also lands after a magic-link or Google sign-in attempt that did not
 * complete.
 *
 * The `auth` query parameter names one of three reasons a sign-in attempt can fail. `expired` and
 * `failed` come from a completed attempt. `unavailable` comes from `GET /api/auth/google` itself,
 * before an attempt starts, when this deployment's Google configuration is missing. Each of the
 * three shows the error banner, through [message].
 *
 * No `auth` parameter at all is the direct-visit case issue #31 adds: [hasNoReason] is then true,
 * and the route renders the same `<app-sign-in-panel />` the reader's own wall shows. A parameter
 * present but unrecognised keeps the old behaviour, and sends the visitor on to the catalogue —
 * that shape only ever came from a broken or a stale link, and not from a sign-in in progress. The
 * page renders nothing at all for that one moment before the redirect completes.
 *
 * Issue #101 adds the sign-in panel below the error card, for each of the three recognised
 * reasons. `AuthRoutes.kt` sets each one on a redirect:
 *
 * - `expired`: the magic-link token was already used, or it is past its life span. The panel
 *   lets the learner ask for a new link at once.
 * - `failed`: a Google sign-in attempt did not finish — a Turnstile check failed, the OAuth
 *   state did not match, or the exchange with Google raised an error. The panel lets the learner
 *   try again, by Google or by email.
 * - `unavailable`: this deployment holds no Google OAuth configuration. The message itself says
 *   "Use email instead", so the panel is not a spare control here — it is the one the message
 *   points the learner to.
 *
 * All three reasons still leave email sign-in open, so the panel belongs under every message. No
 * reason here means "sign-in itself is down"; a future reason of that kind would need its own
 * decision, and not this same default.
 */
@Component({
  selector: 'app-auth-landing',
  imports: [SignInPanelComponent],
  template: `
    @if (message() !== null || hasNoReason()) {
      <main class="auth-landing">
        @if (message(); as text) {
          <div class="mt-card mt-card--error banner banner--error" role="alert">
            <p class="banner__message">{{ text }}</p>
          </div>
        }
        @if (message() !== null || hasNoReason()) {
          <app-sign-in-panel />
        }
      </main>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .auth-landing {
        max-width: 480px;
        margin: 48px auto 0;
        padding: 0 20px;
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
export class AuthLandingComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly reason = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('auth'))),
    { initialValue: null },
  );

  readonly message = computed(() => {
    switch (this.reason()) {
      case 'expired':
        return 'That link has expired or was already used.';
      case 'failed':
        return 'Sign-in did not complete.';
      case 'unavailable':
        return 'Google sign-in is not available right now. Use email instead.';
      default:
        return null;
    }
  });

  readonly hasNoReason = computed(() => this.reason() === null);

  constructor() {
    effect(() => {
      // A reason this route does not recognise. Not the same case as no reason at all — see
      // [hasNoReason] — so this is the one case left that still sends the visitor on.
      if (this.message() === null && !this.hasNoReason()) void this.router.navigateByUrl('/');
    });
  }
}
