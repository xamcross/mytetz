import { Component, inject, signal, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ApiService } from './core/api.service';

/**
 * The bootstrapped root. `app.routes.ts` wires `/` to the catalogue and `/learn/:sessionId` to the
 * reader, but nothing rendered either without a `<router-outlet>` here — the app compiled and every
 * component-level test passed (each drives its component directly through `RouterTestingHarness` or
 * a bare `TestBed`, never through this root), while the real, router-driven app showed nothing but
 * this scaffold's own health line. Found by Task 1.17's Playwright suite, which is the first thing in
 * this codebase to load the app the way a browser actually does — `page.goto('/')` and read the DOM.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `
    <main>
      <h1>mytetz</h1>
      <p>backend: {{ status() }}</p>
    </main>
    <router-outlet />
  `,
})
export class App implements OnInit {
  private readonly api = inject(ApiService);
  readonly status = signal('checking…');

  async ngOnInit(): Promise<void> {
    try {
      const health = await this.api.health();
      this.status.set(health.mongo ? 'ok' : 'degraded');
    } catch {
      this.status.set('unreachable');
    }
  }
}
