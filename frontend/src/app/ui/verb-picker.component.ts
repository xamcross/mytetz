import {
  Component,
  ElementRef,
  afterNextRender,
  inject,
  input,
  output,
  viewChild,
  viewChildren,
} from '@angular/core';
import { SpanPayload, Verb } from '../core/models';

/** Where the picker sits, in the coordinates of the element that hosts it. */
export interface PickerAnchor {
  top: number;
  left: number;
}

/**
 * Why the picker closed. The host must treat the two paths differently.
 *
 * `escape` is a keyboard dismissal, so the host returns focus to the text the learner was reading.
 * `outside-press` arrives from a `mousedown` listener, and a focus move inside `mousedown` cancels
 * a drag the learner has just started — which is how a learner reselects a phrase.
 */
export type PickerDismissal = 'escape' | 'outside-press';

/**
 * The picker's own height cap. The host uses the same number to decide whether the picker fits
 * below the phrase or must go above it.
 *
 * The `max-height` in the styles below repeats the value. An Angular `styles` block is a plain
 * string and cannot read a TypeScript constant, so the two are separate literals. Change one and
 * change the other.
 */
export const PICKER_HEIGHT = 264;

/** The five verbs a highlight can ask for.
 *
 * `SEED` is the session's own root and belongs to no highlight. */
const VERBS: ReadonlyArray<{ verb: Verb; name: string; caption: string }> = [
  { verb: 'EXPLAIN', name: 'Explain it', caption: 'Plain words, no jargon' },
  { verb: 'DIG_DEEPER', name: 'Dig deeper', caption: 'One level more technical' },
  { verb: 'BROADER_PICTURE', name: 'Broader picture', caption: 'Where this sits in the whole' },
  { verb: 'SIDE_VIEW', name: 'Side view', caption: 'The same idea from elsewhere' },
  { verb: 'VISUALIZE', name: 'Show me a diagram', caption: 'A sketch instead of a paragraph' },
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
      (keydown.escape)="onEscape()"
      (keydown.tab)="onTab($event)"
      (keydown.shift.tab)="onTab($event)"
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
            (click)="onVerbClick(v.verb)"
          >
            <span class="picker__name">{{ v.name }}</span>
            <span class="picker__caption" [id]="'cap-' + v.verb">{{ v.caption }}</span>
          </button>
        }
      </div>
      <!--
        Finding F13 of the design review. A phone has no Escape key, and a tap outside the sheet
        was the only exit, which a learner had to guess. This button gives the sheet a visible
        way to close, and it shows below 768px only: a wide screen already keeps Escape and a
        press outside, so a second, redundant close control would only add noise there.
      -->
      <button
        #cancelBtn
        type="button"
        class="mt-pill mt-pill--ghost picker__cancel"
        (click)="onCancel()"
      >
        Cancel
      </button>
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
        max-height: 264px;
        overflow: auto;
        padding: 16px;
        display: flex;
        flex-direction: column;
        gap: 10px;
        background: var(--mt-surface);
        border: var(--mt-border-w) solid var(--mt-border);
        border-radius: var(--mt-r-panel);
        box-shadow: var(--mt-float);
        /* Animation D. On a wide screen the popover grows from the phrase it explains, so the
           origin sits at the corner nearest that phrase. */
        transform-origin: top left;
        animation: picker-open var(--mt-dur-panel) var(--mt-ease-out) both;
      }
      @keyframes picker-open {
        from {
          opacity: 0;
          transform: translateY(calc(-1 * var(--mt-move-near))) scale(0.96);
        }
        to {
          opacity: 1;
          transform: none;
        }
      }
      /* The host is the app-verb-picker tag itself, which the focus card's own template gives
         the picker--out class through animate.leave — see the comment there for why it has to be
         the host and not an element inside this file. :host() lets that class, added outside
         this component, still select the visible box inside it.
         pointer-events: none, because animate.leave keeps this element, and its five buttons, in
         the DOM for the whole close animation. Before this issue the picker left the DOM at
         once, so nothing under a fading box could ever be pressed by mistake or covered from a
         press meant for whatever opens in its place — a fresh picker for a new selection, or the
         plain body text underneath. The close is a dismissal already decided; nothing the
         leaving box still shows needs a pointer to reach it. */
      :host(.picker--out) .picker {
        animation: picker-close var(--mt-dur-state) var(--mt-ease-in) both;
        pointer-events: none;
      }
      @keyframes picker-close {
        to {
          opacity: 0;
          transform: translateY(calc(-1 * var(--mt-move-press))) scale(0.98);
        }
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
        /* --mt-edge, not --mt-border: this is a control edge, and SC 1.4.11 needs 3:1. */
        border: var(--mt-border-w) solid var(--mt-edge);
        border-radius: var(--mt-r-row);
        background: var(--mt-surface);
        color: var(--mt-ink);
        transition:
          background var(--mt-dur-press) var(--mt-ease-press),
          box-shadow var(--mt-dur-press) var(--mt-ease-press);
      }
      /* The primary verb is coral-filled (see the rule below), so a plain background change on
         hover would paint over its fill. This rule answers a pointer for every other verb only.
         (hover: hover) and not a plain :hover: see styles.css's own comment on .mt-pill:hover
         for why a touch screen needs this guard. */
      @media (hover: hover) {
        .picker__verb:not(.picker__verb--primary):hover {
          background: var(--mt-sunk);
        }
      }
      /* The fill is --mt-coral-press and not --mt-coral. The name is 16px, so white on
         --mt-coral measures 3.01:1 and fails AA. Both lines are white; the face and the size
         keep them apart.
         These four declarations are also what .mt-pill--coral draws, and the duplication is
         deliberate. That class is a modifier of .mt-pill, and .mt-pill--coral:active changes only
         the shadow — .mt-pill itself supplies the 2px move that goes with it. On a button that is
         not a pill, the modifier would press half way. The button would also have to give up its
         own background and border shorthands to let the modifier through the cascade, which
         trades a visible duplication for a hidden dependency on specificity. */
      .picker__verb--primary {
        background: var(--mt-coral-press);
        border-color: var(--mt-coral-press);
        color: var(--mt-surface);
        box-shadow: var(--mt-lift-coral);
      }
      /* This is also what .mt-pill--coral:hover draws, for the same reason as the block above. */
      @media (hover: hover) {
        .picker__verb--primary:hover {
          box-shadow: var(--mt-lift-hover-coral);
        }
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
      /* F13. Hidden above 768px on purpose: a wide screen already closes the picker on Escape and
         on a press outside, so a second, always-on close control would only add noise there.
         \`.mt-pill mt-pill--ghost\` on the tag above already supplies every colour and border this
         control needs, so this rule only ever sets \`display\`. */
      .picker__cancel {
        display: none;
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
          /* The sheet rises from the edge it is attached to. */
          transform-origin: bottom center;
          animation: picker-rise var(--mt-dur-panel) var(--mt-ease-out) both;
        }
        .picker__cancel {
          display: block;
          width: 100%;
          text-align: center;
        }
        @keyframes picker-rise {
          from {
            transform: translateY(100%);
          }
          to {
            transform: none;
          }
        }
        :host(.picker--out) .picker {
          animation: picker-fall var(--mt-dur-state) var(--mt-ease-in) both;
        }
        @keyframes picker-fall {
          to {
            transform: translateY(100%);
          }
        }
      }
      /* The phone sheet travels 100% of its own height, a distance no --mt-move-* token covers,
         so its reduced-motion form is explicit rather than token-driven — a plain cross-fade with
         no travel at all. This rule stays in this file rather than in styles.css: issue #102 found
         that a component's own rule always outranks a same-class rule in the global sheet, so an
         override that must win here has to live here. This replaces the animation: none rule
         issue #102 left in its place, which silenced the phone sheet but gave desktop's own new
         entrance and exit nothing to fall back to either. */
      @media (prefers-reduced-motion: reduce) {
        .picker {
          animation: picker-fade 1ms linear both;
        }
        :host(.picker--out) .picker {
          animation: picker-fade 1ms linear reverse both;
        }
        @keyframes picker-fade {
          from {
            opacity: 0;
          }
          to {
            opacity: 1;
          }
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
  /** One output for every dismissal, with the reason attached — see [PickerDismissal]. A second
   * output for the keyboard path would let a host subscribe to one and forget the other. */
  readonly dismissed = output<PickerDismissal>();

  readonly verbs = VERBS;

  private readonly verbButtons = viewChildren<ElementRef<HTMLButtonElement>>('verb');
  private readonly cancelButton = viewChild<ElementRef<HTMLButtonElement>>('cancelBtn');

  /**
   * True from the moment this picker first asks its host to dismiss it.
   *
   * `animate.leave` keeps this component mounted, with every listener below still bound, for the
   * whole close animation — the host only destroys it once that animation ends. Before this
   * issue the picker left the DOM at once, so no press or key could ever reach a picker already
   * on its way out; now one can, unless every listener checks this first. Set once and never
   * cleared: a picker that is closing never re-opens, a new one does.
   */
  private closing = false;

  constructor() {
    // The picker exists only while it is open, so "on creation" is "on open". `afterNextRender`
    // never runs on the server, which keeps this off the render path.
    afterNextRender(() => this.verbButtons()[0]?.nativeElement.focus());
  }

  /** A press outside the picker closes it. A press inside it does nothing. */
  onDocumentPress(event: Event): void {
    if (this.closing) return;
    const target = event.target;
    if (target instanceof Node && this.host.nativeElement.contains(target)) return;
    this.closing = true;
    this.dismissed.emit('outside-press');
  }

  /** Escape closes the picker. See [closing] for why this checks it first. */
  onEscape(): void {
    if (this.closing) return;
    this.closing = true;
    this.dismissed.emit('escape');
  }

  /** A verb chosen while the picker is still open. See [closing] for why this checks it first:
   * a press that lands on a still-mounted, already-closing picker must choose nothing. */
  onVerbClick(verb: Verb): void {
    if (this.closing) return;
    this.chosen.emit(verb);
  }

  /**
   * Finding F13 of the design review. The Cancel control closes the picker the same way Escape
   * does — the reason is `'escape'`, and not a reason of its own — so the host returns focus to
   * the same place either way. See [closing] for why this checks it first.
   */
  onCancel(): void {
    if (this.closing) return;
    this.closing = true;
    this.dismissed.emit('escape');
  }

  /**
   * Every control Tab should reach, in order: the five verbs, and Cancel where it is visible.
   *
   * `getComputedStyle` and not [closing] or a stored flag: Cancel's own visibility is decided by
   * a media query in this file's styles, and nothing in TypeScript measures the viewport (see the
   * class doc comment) — this reads the one result of that query the DOM already carries, inside
   * an event handler and never on the render path, the same way [FocusCardComponent.onSelectionChanged]
   * reads `window.getSelection()`.
   */
  private focusableControls(): HTMLElement[] {
    const verbs = this.verbButtons().map((b) => b.nativeElement);
    const cancel = this.cancelButton()?.nativeElement;
    if (cancel !== undefined && getComputedStyle(cancel).display !== 'none') {
      return [...verbs, cancel];
    }
    return verbs;
  }

  /**
   * Keeps Tab inside the picker. Without this, Tab walks into the page behind an open dialog.
   *
   * The template binds this to `keydown.tab` **and** to `keydown.shift.tab`. Angular builds a full
   * key name from the modifiers that are held, so `keydown.tab` alone never fires while Shift is
   * down, and the backward half of the trap below would be dead code.
   *
   * Guarded by [closing] too: a picker already on its way out must not trap a learner's Tab
   * press inside a control that is no longer really there for them.
   */
  onTab(event: Event): void {
    if (this.closing) return;
    // Angular types `$event` as `Event` for a compound key pseudo-event, so the narrow happens
    // here. The template call site stays type-checked.
    const key = event as KeyboardEvent;
    const controls = this.focusableControls();
    if (controls.length === 0) return;
    const first = controls[0];
    const last = controls[controls.length - 1];
    const active = this.host.nativeElement.ownerDocument.activeElement;
    if (key.shiftKey && active === first) {
      key.preventDefault();
      last.focus();
    } else if (!key.shiftKey && active === last) {
      key.preventDefault();
      first.focus();
    }
  }
}
