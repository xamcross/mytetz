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
 * that shape only ever came from a broken or a stale link, and not from a sign-in in progress.
 */
@Component({
  selector: 'app-auth-landing',
  imports: [SignInPanelComponent],
  template: `
    @if (message(); as text) {
      <main class="auth-landing">
        <div class="mt-card mt-card--error banner banner--error" role="alert">
          <p class="banner__message">{{ text }}</p>
        </div>
      </main>
    } @else if (hasNoReason()) {
      <main class="auth-landing">
        <app-sign-in-panel />
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
