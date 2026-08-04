import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

/**
 * Placeholder standing in for Task 1.16's reader — the focus-card view with breadcrumb and trail
 * rail the design spec describes.
 *
 * ## Why this file exists in a task that is explicitly not supposed to build the reader
 *
 * `app.routes.ts` lazy-loads it: `loadComponent: () => import('./reader/reader-page.component')`.
 * TypeScript resolves a dynamic import's module specifier at compile time even though the import
 * itself only executes at runtime, so without *some* file at this path `npm run build` fails
 * before a single test runs — a genuine ordering problem, not a design choice: Task 1.16's own
 * file does not exist yet, and this task (1.15) is required to leave `npm run build` green.
 *
 * The alternative — leaving `learn/:sessionId` out of `app.routes.ts` until Task 1.16 lands —
 * was rejected: the brief's own interface contract for this task is "selecting a topic creates a
 * session and navigates to `/learn/:sessionId`", so the route has to exist for that hand-off to
 * be real, not just asserted by a unit test that never touches the actual router config.
 *
 * ## What Task 1.16 inherits
 *
 * This entire file, to replace outright. Nothing here is load-bearing for that task: no
 * `ApiService` call, no store, no rendering or error-handling decisions to unwind — it reads the
 * `sessionId` route param and renders it, nothing else. Task 1.16 owns the reader's actual shape
 * from scratch.
 */
@Component({
  selector: 'app-reader-page',
  imports: [],
  template: `
    <main>
      <p>Session {{ sessionId }} — the reader is coming in Task 1.16.</p>
    </main>
  `,
})
export class ReaderPageComponent {
  private readonly route = inject(ActivatedRoute);
  readonly sessionId = this.route.snapshot.paramMap.get('sessionId');
}
