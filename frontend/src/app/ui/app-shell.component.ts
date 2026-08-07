import { Component, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AllowanceMeterComponent } from '../account/allowance-meter.component';
import { BackendState, StatusDotComponent } from './status-dot.component';

/**
 * The 64px top bar, and the frame every page sits in.
 *
 * The design draws three nav items: Topics, Sessions and Glossary. Only Topics has a route. The
 * other two need a backend that does not exist, and a link to a dead end is worse than no link.
 * The spec's §12 lists what each one needs.
 *
 * Below 768px the nav item goes. The wordmark already routes to the catalog.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, StatusDotComponent, AllowanceMeterComponent],
  template: `
    <header class="bar">
      <div class="bar__left">
        <a class="bar__mark" routerLink="/">mytetz</a>
        <nav class="bar__nav" aria-label="Sections">
          <a
            class="bar__link"
            routerLink="/"
            routerLinkActive="bar__link--active"
            ariaCurrentWhenActive="page"
            [routerLinkActiveOptions]="{ exact: true }"
            >Topics</a
          >
        </nav>
      </div>
      <div class="bar__right">
        <app-allowance-meter />
        <app-status-dot [state]="backend()" />
      </div>
    </header>
    <ng-content />
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
      @media (max-width: 767px) {
        .bar {
          padding: 0 20px;
        }
        .bar__nav {
          display: none;
        }
      }
    `,
  ],
})
export class AppShellComponent {
  readonly backend = input.required<BackendState>();
}
