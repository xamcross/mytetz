import {
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  viewChildren,
} from '@angular/core';
import { SpanPayload, Verb } from '../core/models';

/** Where the picker sits, in the coordinates of the element that hosts it. */
export interface PickerAnchor {
  top: number;
  left: number;
}

/**
 * The picker's own height cap. The CSS below enforces it, and the host uses the same number to
 * decide whether the picker fits below the phrase or must go above it. One number, two readers.
 */
export const PICKER_HEIGHT = 240;

/** The four verbs a highlight can ask for.
 *
 * `SEED` is the session's own root and belongs to no highlight. `VISUALIZE` is slice 4: the
 * design draws a fifth row for it, and that row arrives with the feature. */
const VERBS: ReadonlyArray<{ verb: Verb; name: string; caption: string }> = [
  { verb: 'EXPLAIN', name: 'Explain it', caption: 'Plain words, no jargon' },
  { verb: 'DIG_DEEPER', name: 'Dig deeper', caption: 'One level more technical' },
  { verb: 'BROADER_PICTURE', name: 'Broader picture', caption: 'Where this sits in the whole' },
  { verb: 'SIDE_VIEW', name: 'Side view', caption: 'The same idea from elsewhere' },
];

/**
 * What a learner sees after they highlight a phrase.
 *
 * On a wide screen it is a card anchored under the highlight. Below 768px the same card rises from
 * the bottom edge, because a popover beside a thumb on a 390px screen covers the text it explains.
 * The two modes are one CSS media query, and no TypeScript measures a viewport — the reader stays
 * server-renderable.
 *
 * The component measures nothing at all. Its host computes the anchor inside an event handler and
 * passes it in. That split is what makes the picker testable: unit tests run in jsdom, which has
 * no layout engine and returns a zero rect for everything.
 */
@Component({
  selector: 'app-verb-picker',
  imports: [],
  host: {
    // A press anywhere else closes the picker. `document:mousedown` is a host listener, so Angular
    // attaches it only in a browser and the render path stays clean.
    '(document:mousedown)': 'onDocumentPress($event)',
  },
  template: `
    <div
      #root
      class="picker"
      role="dialog"
      aria-label="Explain the highlighted phrase"
      [style.--picker-top]="anchor().top + 'px'"
      [style.--picker-left]="anchor().left + 'px'"
      (keydown.escape)="dismissed.emit()"
      (keydown.tab)="onTab($any($event))"
    >
      <p class="picker__lead">“{{ span().text }}” — go on:</p>
      <div class="picker__grid">
        @for (v of verbs; track v.verb; let first = $first) {
          <button
            #verb
            type="button"
            class="picker__verb"
            [class.picker__verb--primary]="first"
            [attr.data-verb]="v.verb"
            [attr.aria-label]="v.name"
            [attr.aria-describedby]="'cap-' + v.verb"
            (click)="chosen.emit(v.verb)"
          >
            <span class="picker__name">{{ v.name }}</span>
            <span class="picker__caption" [id]="'cap-' + v.verb">{{ v.caption }}</span>
          </button>
        }
      </div>
    </div>
  `,
  styles: [
    `
      :host {
        display: contents;
      }
      .picker {
        position: absolute;
        top: var(--picker-top, 0);
        left: var(--picker-left, 0);
        z-index: 20;
        width: min(520px, 100%);
        max-height: 240px;
        overflow: auto;
        padding: 16px;
        display: flex;
        flex-direction: column;
        gap: 10px;
        background: var(--mt-surface);
        border: var(--mt-border-w) solid var(--mt-border);
        border-radius: var(--mt-r-panel);
        box-shadow: var(--mt-float);
      }
      .picker__lead {
        margin: 0;
        font-size: 13px;
        font-weight: 700;
        color: var(--mt-muted);
      }
      .picker__grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 9px;
      }
      .picker__verb {
        text-align: left;
        padding: 11px 14px;
        display: flex;
        flex-direction: column;
        gap: 1px;
        border: var(--mt-border-w) solid var(--mt-border);
        border-radius: var(--mt-r-row);
        background: var(--mt-surface);
        color: var(--mt-ink);
      }
      /* The fill is --mt-coral-press and not --mt-coral. The name is 16px, so white on
         --mt-coral measures 3.01:1 and fails AA. Both lines are white; the face and the size
         keep them apart. */
      .picker__verb--primary {
        background: var(--mt-coral-press);
        border-color: var(--mt-coral-press);
        color: var(--mt-surface);
        box-shadow: var(--mt-lift-coral);
      }
      .picker__name {
        font-family: var(--mt-display);
        font-size: 16px;
        font-weight: 600;
      }
      .picker__caption {
        font-size: 12px;
        font-weight: 600;
        color: var(--mt-muted);
      }
      .picker__verb--primary .picker__caption {
        color: var(--mt-surface);
      }
      /* Below 768px the same card rises from the bottom edge. \`top\` and \`left\` are set again
         here, so the anchor the host passed in is simply unused — no !important, and no
         measurement in TypeScript. */
      @media (max-width: 767px) {
        .picker {
          position: fixed;
          top: auto;
          left: 0;
          right: 0;
          bottom: 0;
          width: auto;
          max-height: 60vh;
          border-radius: var(--mt-r-card) var(--mt-r-card) 0 0;
          animation: picker-rise 200ms ease-out;
        }
      }
      @keyframes picker-rise {
        from {
          transform: translateY(100%);
        }
        to {
          transform: translateY(0);
        }
      }
    `,
  ],
})
export class VerbPickerComponent {
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly span = input.required<SpanPayload>();
  readonly anchor = input.required<PickerAnchor>();

  readonly chosen = output<Verb>();
  readonly dismissed = output<void>();

  readonly verbs = VERBS;

  private readonly verbButtons = viewChildren<ElementRef<HTMLButtonElement>>('verb');

  constructor() {
    // The picker exists only while it is open, so "on creation" is "on open". `afterNextRender`
    // never runs on the server, which keeps this off the render path.
    afterNextRender(() => this.verbButtons()[0]?.nativeElement.focus());
  }

  /** A press outside the picker closes it. A press inside it does nothing. */
  onDocumentPress(event: Event): void {
    const target = event.target;
    if (target instanceof Node && this.host.nativeElement.contains(target)) return;
    this.dismissed.emit();
  }

  /** Keeps Tab inside the picker. Without this, Tab walks into the page behind an open dialog. */
  onTab(event: KeyboardEvent): void {
    const buttons = this.verbButtons().map((b) => b.nativeElement);
    if (buttons.length === 0) return;
    const first = buttons[0];
    const last = buttons[buttons.length - 1];
    const active = this.host.nativeElement.ownerDocument.activeElement;
    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
