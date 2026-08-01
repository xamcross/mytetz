import { Component, inject, signal, OnInit } from '@angular/core';
import { ApiService } from './core/api.service';

@Component({
  selector: 'app-root',
  imports: [],
  template: `
    <main>
      <h1>mytetz</h1>
      <p>backend: {{ status() }}</p>
    </main>
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
