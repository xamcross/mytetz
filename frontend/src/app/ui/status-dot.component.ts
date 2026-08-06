import { Component, computed, input } from '@angular/core';

/** What the health check found. `checking` is the state before the first answer arrives. */
export type BackendState = 'checking' | 'ok' | 'degraded' | 'unreachable';

const LABELS: Readonly<Record<BackendState, string>> = {
  checking: 'Backend: checking',
  ok: 'Backend ok',
  degraded: 'Backend degraded',
  unreachable: 'Backend unreachable',
};

/**
 * The backend health, as a dot on the right of the top bar.
 *
 * The design draws a streak pill in this place. No streak exists, so the health check that the
 * root component already runs takes the space instead.
 *
 * The colour is not the whole signal. `aria-label` states the result in words, so a reader who
 * cannot tell the colours apart gets the same answer.
 */
@Component({
  selector: 'app-status-dot',
  imports: [],
  template: `
    <span
      class="dot"
      [class]="'dot dot--' + state()"
      role="img"
      [attr.aria-label]="label()"
      [attr.title]="label()"
    ></span>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
      }
      .dot {
        width: 10px;
        height: 10px;
        border-radius: 999px;
        border: 2px solid var(--mt-surface);
        outline: 2px solid var(--mt-border);
      }
      .dot--checking {
        background: var(--mt-faint);
      }
      .dot--ok {
        background: var(--mt-teal);
      }
      .dot--degraded {
        background: var(--mt-amber);
      }
      .dot--unreachable {
        background: var(--mt-err-ink);
      }
    `,
  ],
})
export class StatusDotComponent {
  readonly state = input.required<BackendState>();
  protected readonly label = computed(() => LABELS[this.state()]);
}
