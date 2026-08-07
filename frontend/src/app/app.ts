import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AccountStore } from './core/account.store';
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
 *
 * `ngOnInit` also starts the account load here. Both sign-in routes redirect the browser back to
 * `/`, so this one line also fills the meter right after sign-in, with no other wiring. The two
 * reads run side by side and neither one waits for the other. A slow or failed account read must
 * not hold up the status dot, and the reverse is also true. `AccountStore.load` already turns a
 * `401` into "signed out" and puts every other failure into its own `error` signal, so this method
 * needs no `try` around the call.
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
  private readonly account = inject(AccountStore);
  readonly backend = signal<BackendState>('checking');

  async ngOnInit(): Promise<void> {
    void this.account.load();
    try {
      const health = await this.api.health();
      this.backend.set(health.mongo ? 'ok' : 'degraded');
    } catch {
      this.backend.set('unreachable');
    }
  }
}
