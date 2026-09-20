import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * The `**` route — Angular renders this for a path that matches no other route.
 *
 * The server answers the same shell for a matching request, with status 404. See
 * `backend/api/src/main/kotlin/com/mytetz/api/SpaRoutes.kt`.
 */
@Component({
  selector: 'app-not-found-page',
  imports: [RouterLink],
  template: `
    <main class="legal-page not-found-page">
      <h1>Page not found</h1>
      <p>This page does not exist.</p>
      <a class="mt-pill mt-pill--ghost" routerLink="/">Back to the dashboard</a>
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      /* The fourth variant of the shared .legal-page block (design review, section 1.4, item 6):
         a taller top padding, a left-aligned column, and a smaller gap. The component's own
         attribute gives this rule the higher specificity it needs to win over the shared one. */
      .not-found-page {
        padding: 96px 20px;
        align-items: flex-start;
        gap: 16px;
      }
    `,
  ],
})
export class NotFoundPageComponent {}
