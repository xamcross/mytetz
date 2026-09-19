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
      /* --mt-amber on --mt-surface measures 1.44:1, and --mt-faint measures 2.75:1 (design
         review, section 3.2). Neither reaches the 3:1 SC 1.4.11 needs, so each state also draws
         its own shape. A sighted learner who cannot tell the colours apart still tells the
         states apart. */
      .dot--ok {
        background: var(--mt-teal);
      }
      /* checking: a ring. The gradient keeps the 10px box, so the dot never changes footprint
         when its state changes. */
      .dot--checking {
        background: radial-gradient(circle, transparent 45%, var(--mt-faint) 46% 100%);
      }
      /* degraded: a triangle. clip-path replaces the round corners with three straight edges. */
      .dot--degraded {
        background: var(--mt-amber);
        border-radius: 0;
        clip-path: polygon(50% 0%, 100% 100%, 0% 100%);
      }
      /* unreachable: a cross. clip-path cannot cut a hole from the middle of a box this small,
         so the two bars are drawn from pseudo-elements instead, and the base fill stays empty. */
      .dot--unreachable {
        position: relative;
        background: transparent;
      }
      .dot--unreachable::before {
        content: '';
        position: absolute;
        inset: 0;
        margin: auto;
        width: 100%;
        height: 2px;
        background: var(--mt-err-ink);
        transform: rotate(45deg);
      }
      .dot--unreachable::after {
        content: '';
        position: absolute;
        inset: 0;
        margin: auto;
        width: 100%;
        height: 2px;
        background: var(--mt-err-ink);
        transform: rotate(-45deg);
      }
    `,
  ],
})
export class StatusDotComponent {
  readonly state = input.required<BackendState>();
  protected readonly label = computed(() => LABELS[this.state()]);
}
