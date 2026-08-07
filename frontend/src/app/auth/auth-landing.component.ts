import { Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';

/**
 * `/auth` — where the browser lands after a magic-link or Google sign-in attempt that did not
 * complete.
 *
 * The `auth` query parameter names one of the two reasons a sign-in attempt can fail. Any other
 * value, or none at all — which is the shape of a successful sign-in — has nothing to report
 * here, so this route sends the visitor on to the catalogue instead of showing a page about
 * nothing.
 */
@Component({
  selector: 'app-auth-landing',
  imports: [],
  template: `
    @if (message(); as text) {
      <main class="auth-landing">
        <div class="mt-card mt-card--error banner banner--error" role="alert">
          <p class="banner__message">{{ text }}</p>
        </div>
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
      default:
        return null;
    }
  });

  constructor() {
    effect(() => {
      // Runs for every reason this route has nothing to say about, including no reason at all.
      if (this.message() === null) void this.router.navigateByUrl('/');
    });
  }
}
