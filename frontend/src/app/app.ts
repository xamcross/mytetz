import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ApiService } from './core/api.service';
import { AppShellComponent } from './ui/app-shell.component';
import { BackendState } from './ui/status-dot.component';

/**
 * The bootstrapped root. `app.routes.ts` wires `/` to the catalogue and `/learn/:sessionId` to the
 * reader.
 *
 * The `<router-outlet>` is load-bearing and it is tested. Without it the app compiles, every
 * component test passes, and the running site shows nothing.
 *
 * The health check is the same one the scaffold ran. Its result now reaches the dot in the top
 * bar rather than a line of text.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, AppShellComponent],
  template: `
    <app-shell [backend]="backend()">
      <router-outlet />
    </app-shell>
  `,
})
export class App implements OnInit {
  private readonly api = inject(ApiService);
  readonly backend = signal<BackendState>('checking');

  async ngOnInit(): Promise<void> {
    try {
      const health = await this.api.health();
      this.backend.set(health.mongo ? 'ok' : 'degraded');
    } catch {
      this.backend.set('unreachable');
    }
  }
}
