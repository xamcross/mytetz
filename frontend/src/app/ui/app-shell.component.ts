import { Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AllowanceMeterComponent } from '../account/allowance-meter.component';
import { AccountStore } from '../core/account.store';
import { LogoMarkComponent } from './logo-mark.component';
import { BackendState, StatusDotComponent } from './status-dot.component';

/**
 * The 64px top bar, and the frame every page sits in.
 *
 * Issue #143: the nav holds Glossary and Guides, and no Topics link. The wordmark already opens
 * `/`, the same page the removed Topics link opened, so the owner's decision on 2026-09-20 drops
 * it as a duplicate. Glossary is a Ktor page at `/glossary` (issue #48), and Guides is a static
 * file under `frontend/public/guides` (issue #63); both need a plain `href` and not a
 * `routerLink` — see the "Guides" footer link below for why a page outside `app.routes.ts` needs
 * a full document load and not a router navigation. `RouterLinkActive` left with the removed
 * Topics link: no nav item routes inside the Angular application any more, so none can be marked
 * active.
 *
 * Below 768px the nav item goes. The wordmark already routes to the catalog.
 *
 * Issue #31 adds one link before the meter: `Sign in`, to `/auth`, while `AccountStore.view` is
 * `null`, and `Account`, to `/account`, once a sign-in fills that view. Every page shares this
 * shell, so the link reaches a visitor on every page, and not only the reader's own wall.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterLink, LogoMarkComponent, StatusDotComponent, AllowanceMeterComponent],
  template: `
    <header class="bar">
      <div class="bar__left">
        <a class="bar__mark" routerLink="/" aria-label="mytetz">
          <app-logo-mark />
          <span class="bar__mark-text">mytetz</span>
        </a>
        <nav class="bar__nav" aria-label="Sections">
          <a class="bar__link" href="/glossary">Glossary</a>
          <!--
            A plain href, and not a routerLink. /guides is a static HTML file under
            frontend/public/guides, and app.routes.ts has no 'guides' path, the same reason the
            Guides footer link below states.
          -->
          <a class="bar__link" href="/guides">Guides</a>
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
      @media (max-width: 767px) {
        .bar {
          padding: 0 20px;
        }
        .bar__nav {
          display: none;
        }
      }
      /*
       * Issue #133, review round 2. A padding of 8px below 480px (an earlier version of this
       * rule) moved the page content 12px away from the header's own left edge at 390px and
       * 412px, the two most common phone widths — the header no longer lined up with the page
       * title below it. The page content itself starts 20px from the left at a phone width, so
       * the bar's own side padding stays 20px at every phone width. Only the gap inside the
       * right-hand group narrows, for the two rare widths, 320px and 360px, where the wordmark,
       * the "Account" link, the meter and the status dot still crowd each other.
       */
      @media (max-width: 479px) {
        .bar__right {
          gap: 12px;
        }
      }
      /*
       * Issue #133. Below 360px the text of the wordmark takes about 87px that the row does not
       * have. The text then goes out of view, and the logo mark alone shows the link. The link
       * has aria-label="mytetz", so it keeps its name for a screen reader at each width. The
       * text is visible by default, and only this media query hides it. styles.spec.ts permits
       * no component copy of .mt-sr-only, so this rule uses display: none.
       */
      @media (max-width: 359px) {
        .bar__mark-text {
          display: none;
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
