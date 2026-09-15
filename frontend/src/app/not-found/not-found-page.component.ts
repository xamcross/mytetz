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
    <main class="not-found-page">
      <h1>Page not found</h1>
      <p>This page does not exist.</p>
      <a class="mt-pill mt-pill--ghost" routerLink="/">Back to topics</a>
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .not-found-page {
        max-width: 720px;
        margin: 0 auto;
        padding: 96px 20px;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 16px;
      }
      h1 {
        font-size: 32px;
      }
      p {
        margin: 0;
        font-size: 15px;
        line-height: 1.6;
        color: var(--mt-prose);
      }
    `,
  ],
})
export class NotFoundPageComponent {}
