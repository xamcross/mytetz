import { Component, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AllowanceMeterComponent } from '../account/allowance-meter.component';
import { AccountStore } from '../core/account.store';
import { LogoMarkComponent } from './logo-mark.component';
import { BackendState, StatusDotComponent } from './status-dot.component';

/**
 * The 64px top bar, and the frame every page sits in.
 *
 * The design draws three nav items: Topics, Sessions and Glossary. Sessions needs a backend that
 * does not exist yet, and a link to a dead end is worse than no link, so it stays out. Glossary now
 * has a route — a Ktor page at `/glossary`, not an Angular one (issue #48) — so it gets a plain
 * `href`, not a `routerLink`: see the "Guides" footer link below for why a static page outside
 * `app.routes.ts` needs a full document load and not a router navigation.
 *
 * Below 768px the nav item goes. The wordmark already routes to the catalog.
 *
 * Issue #31 adds one link before the meter: `Sign in`, to `/auth`, while `AccountStore.view` is
 * `null`, and `Account`, to `/account`, once a sign-in fills that view. Every page shares this
 * shell, so the link reaches a visitor on every page, and not only the reader's own wall.
 */
@Component({
  selector: 'app-shell',
  imports: [
    RouterLink,
    RouterLinkActive,
    LogoMarkComponent,
    StatusDotComponent,
    AllowanceMeterComponent,
  ],
  template: `
    <header class="bar">
      <div class="bar__left">
        <a class="bar__mark" routerLink="/">
          <app-logo-mark />
          mytetz
        </a>
        <nav class="bar__nav" aria-label="Sections">
          <a
            class="bar__link"
            routerLink="/"
            routerLinkActive="bar__link--active"
            ariaCurrentWhenActive="page"
            [routerLinkActiveOptions]="{ exact: true }"
            >Topics</a
          >
          <a class="bar__link" href="/glossary">Glossary</a>
        </nav>
      </div>
      <div class="bar__right">
        @if (account.view()) {
          <a class="bar__link bar__account" routerLink="/account">Account</a>
        } @else {
          <a class="bar__link bar__account" routerLink="/auth">Sign in</a>
        }
        <app-allowance-meter />
        <app-status-dot [state]="backend()" />
      </div>
    </header>
    <ng-content />
    <footer class="foot">
      <!--
        A plain href, and not a routerLink. /guides is a static HTML file under
        frontend/public/guides, and app.routes.ts has no 'guides' path, so a routerLink would
        reach the '**' route and open NotFoundPageComponent. The href forces a full document
        load, which is what a static page needs.
      -->
      <a class="foot__link" href="/guides">Guides</a>
      <!--
        A plain href, and not a routerLink, for the same reason as the /guides link above:
        /how-it-works is a Ktor-rendered page, and app.routes.ts has no 'how-it-works' path.
      -->
      <a class="foot__link" href="/how-it-works">How it works</a>
      <!--
        A plain href, and not a routerLink, for the same reason as the /how-it-works link above:
        /faq is a Ktor-rendered page, and app.routes.ts has no 'faq' path.
      -->
      <a class="foot__link" href="/faq">FAQ</a>
      <a class="foot__link" routerLink="/privacy">Privacy</a>
      <a class="foot__link" routerLink="/terms">Terms</a>
      <a class="foot__link" routerLink="/imprint">Imprint</a>
    </footer>
  `,
  styles: [
    `
      :host {
        display: block;
        min-height: 100vh;
      }
      .bar {
        height: 64px;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 16px;
        padding: 0 32px;
        background: var(--mt-surface);
        border-bottom: var(--mt-border-w) solid var(--mt-rule);
      }
      .bar__left {
        display: flex;
        align-items: center;
        gap: 28px;
        min-width: 0;
      }
      /* 24px at weight 600 is large text, so the fill coral is safe here. */
      .bar__mark {
        display: inline-flex;
        align-items: center;
        gap: 10px;
        font-family: var(--mt-display);
        font-size: 24px;
        font-weight: 600;
        color: var(--mt-coral);
        text-decoration: none;
      }
      .bar__nav {
        display: flex;
        gap: 20px;
      }
      .bar__right {
        display: flex;
        align-items: center;
        gap: 16px;
      }
      .bar__link {
        font-size: 15px;
        font-weight: 700;
        color: var(--mt-muted);
        text-decoration: none;
        padding-bottom: 3px;
        border-bottom: 3px solid transparent;
      }
      .bar__link--active {
        font-weight: 800;
        color: var(--mt-teal);
        border-bottom-color: var(--mt-teal);
      }
      .bar__account {
        white-space: nowrap;
      }
      /*
       * Issue #133. Below 768px the nav goes, so a Ktor visitor never sees a live link into a
       * page that has no route yet. The threshold was 767px, one pixel short of 768px: at exactly
       * 768px, with the nav still shown, the bar has no room left for "Topics", "Glossary", the
       * "Account" link, the meter and the status dot together — a real run measures "Glossary"
       * running 16.6px into "Account". 768px is also the exact width issue #106 measured the
       * meter against, so this fix moves the threshold up by one pixel instead of touching either
       * measurement.
       */
      @media (max-width: 768px) {
        .bar {
          padding: 0 20px;
        }
        .bar__nav {
          display: none;
        }
      }
      /*
       * Issue #133. Below 480px, the wordmark, the "Account" link, the meter and the status dot
       * still do not fit on one line for a learner in trial: the header's own padding and the
       * gap inside the right-hand group are wide enough to push the "Account" link on top of the
       * last letter of "mytetz". This rule narrows both, the same way issue #132's own footer
       * rule narrows the footer's padding below 480px. This block stands after the rule above on
       * purpose: the two share the bar selector, at the same specificity, so the later one wins
       * for a width the two ranges share.
       *
       * 8px is the tightest padding this rule uses without also narrowing an account with no
       * live count — the row of the "Account" link, the "Subscribe" button and the status dot.
       * At 320px that account still ends 4.7px short of an 8px gap from the wordmark, a real
       * overlap of zero but a gap the layout test still flags as narrow. Closing that last 4.7px
       * needs a padding near 5px, tighter than a phone header should read, so this rule stops
       * here — see the issue's own report for the exact measurement.
       */
      @media (max-width: 479px) {
        .bar {
          padding: 0 8px;
        }
        .bar__right {
          gap: 8px;
        }
      }
      .foot {
        display: flex;
        flex-wrap: wrap;
        justify-content: center;
        gap: 20px;
        padding: 24px 32px;
        border-top: var(--mt-border-w) solid var(--mt-rule);
      }
      .foot__link {
        font-size: 13px;
        font-weight: 700;
        /* A fixed line-height, and not the browser default, so a wrapped link is always
           taller than a one-line link by a clear margin. */
        line-height: 20px;
        color: var(--mt-muted);
        text-decoration: none;
        /* Issue #132: a link keeps its own text on one line. flex-wrap on .foot then moves a
           whole link to the next row, rather than shrinking the link and wrapping its words. */
        white-space: nowrap;
      }
      .foot__link:hover {
        color: var(--mt-teal);
      }
      /* Issue #132: six links do not fit in one row below about 480px. flex-wrap on .foot moves
         a link to a new row, and the smaller side padding matches the phone padding of .bar.
         This block stands after the .foot rule on purpose: the two rules have the same
         specificity, so the later one wins. */
      @media (max-width: 767px) {
        .foot {
          padding: 24px 20px;
          gap: 12px 20px;
        }
      }
    `,
  ],
})
export class AppShellComponent {
  readonly backend = input.required<BackendState>();
  protected readonly account = inject(AccountStore);
}
