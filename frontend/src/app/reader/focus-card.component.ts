import {
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { SpanPayload, Verb } from '../core/models';
import { PICKER_HEIGHT, PickerAnchor, VerbPickerComponent } from '../ui/verb-picker.component';
import { rootTextMatchesBody, selectionToSpan } from './selection';

/**
 * The card the learner actually reads, and the only place a selection is turned into a span.
 *
 * ## Two rules about what may live inside `.focus__body`, both load-bearing
 *
 * **Nothing but the body's own characters.** `selectionToSpan` returns offsets into
 * `root.textContent`, and the server validates them with `storedBody.substring(start, end) == text`.
 * Anything else inside that element — a label, an icon, a highlight wrapper, or template whitespace
 * — shifts every offset and turns every explain into `SPAN_MISMATCH`. Note the interpolation sits
 * flush against its tags below: the Angular compiler removes a *blank* text node but collapses a
 * non-blank one's whitespace runs to a single space, so `<p #body>\n  {{ body() }}\n</p>` would
 * render a leading and a trailing space that the server's string does not have.
 *
 * **`streamingText` is rendered somewhere else.** A stream in progress is prose that is not yet in
 * any stored body, so a selection measured across it indexes a string the server has never seen. It
 * gets its own element outside the selectable root, and the verbs are disabled while it runs.
 *
 * **The picker is a sibling of the paragraph, never a child.** The design draws a highlighted
 * phrase with an amber background. A wrapper element inside `.focus__body` would break rule one,
 * so the colour comes from `.focus__body::selection` instead and no node is added. The picker
 * itself sits after the paragraph, inside the card. A test asserts both.
 *
 * Rule one is checked rather than merely documented: `rootTextMatchesBody` runs after every render
 * that changes the body, and a mismatch disables the verbs and says so. That is Task 1.14's
 * invariant given the caller it was written for — a request built from mismatched offsets cannot
 * succeed, so the affordance that builds it should not be live.
 *
 * ## SSR
 *
 * `window.getSelection()` is reached only from an event handler, never from the render path, and
 * `afterRenderEffect` is documented not to run on the server at all — so this component stays
 * renderable under the server-side rendering the design spec defers to spec C.
 */
@Component({
  selector: 'app-focus-card',
  imports: [VerbPickerComponent],
  template: `
    <article #cardEl class="focus mt-card">
      <div class="focus__head">
        <span class="mt-eyebrow mt-eyebrow--coral">{{ eyebrow() }}</span>
        @if (isStreaming()) {
          <span class="focus__track" role="presentation"><span class="focus__band"></span></span>
        }
      </div>

      <h1 class="focus__topic">{{ topicLabel() }}</h1>

      <!--
        Prettier is held off this element deliberately, and it is not cosmetic: run over it,
        Prettier moves the interpolation onto its own line, the Angular compiler keeps one space per
        whitespace run in a non-blank text node, and every offset measured against this element is
        then one character out - so every explain comes back SPAN_MISMATCH. Verified, not assumed:
        the reformatted version fails four tests in this component's spec. See the class comment
        above for the full invariant. The directive below applies to the very next node, so nothing
        may be inserted between it and the paragraph.
      -->
      <!-- prettier-ignore -->
      <p
        #bodyEl
        class="focus__body"
        data-testid="focus-body"
        (mouseup)="onSelectionChanged()"
        (touchend)="onSelectionChanged()"
      >{{ body() }}</p>

      @if (isStreaming() || streamingText().length > 0) {
        <p class="focus__streaming" role="status" aria-live="polite">
          {{ streamingText() }}
          @if (isStreaming()) {
            <span class="focus__caret" aria-hidden="true">▍</span>
          }
        </p>
      }

      <p class="focus__hint" [class.focus__hint--warning]="!bodyMatches()">{{ hint() }}</p>

      @if (pickerSpan(); as chosenSpan) {
        <app-verb-picker
          [span]="chosenSpan"
          [anchor]="anchor()!"
          (chosen)="request($event)"
          (dismissed)="close()"
        />
      }
    </article>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .focus {
        position: relative;
        border-radius: var(--mt-r-card);
        box-shadow: var(--mt-lift-card);
        padding: 32px 36px;
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .focus__head {
        display: flex;
        align-items: center;
        gap: 12px;
      }
      /* The design draws a filled bar with a percentage. No bounded quantity exists to bind one
         to, and an invented percentage is a promise the app cannot keep. The track appears only
         while a stream runs, and it says "something is happening" and nothing more. */
      .focus__track {
        flex: 1;
        height: 8px;
        border-radius: 4px;
        background: var(--mt-rule);
        overflow: hidden;
      }
      .focus__band {
        display: block;
        width: 40%;
        height: 100%;
        border-radius: 4px;
        background: var(--mt-amber);
        animation: focus-travel 1.6s ease-in-out infinite;
      }
      @keyframes focus-travel {
        0% {
          transform: translateX(-100%);
        }
        100% {
          transform: translateX(250%);
        }
      }
      .focus__topic {
        font-size: 26px;
      }
      .focus__body {
        margin: 0;
        font-size: 19px;
        line-height: 1.65;
        font-weight: 500;
        color: var(--mt-prose);
        max-width: 62ch;
        white-space: pre-wrap;
        text-wrap: pretty;
      }
      /* The design draws the highlighted phrase in amber. A wrapper element inside this paragraph
         would shift every offset and break the invariant above, so the native selection carries
         the colour instead. No node is added. */
      .focus__body::selection {
        background: var(--mt-amber);
        color: var(--mt-amber-ink);
      }
      .focus__streaming {
        margin: 0;
        padding: 16px;
        font-size: 19px;
        line-height: 1.65;
        font-weight: 500;
        color: var(--mt-prose);
        max-width: 62ch;
        white-space: pre-wrap;
        background: var(--mt-sunk);
        border-radius: var(--mt-r-panel);
        border-left: 3px solid var(--mt-coral-press);
        user-select: none;
      }
      .focus__caret {
        color: var(--mt-coral-text);
        animation: focus-blink 1s step-end infinite;
      }
      @keyframes focus-blink {
        0%,
        49% {
          opacity: 1;
        }
        50%,
        100% {
          opacity: 0;
        }
      }
      .focus__hint {
        margin: 0;
        font-size: 13px;
        font-weight: 700;
        color: var(--mt-muted);
      }
      .focus__hint--warning {
        padding: 14px 16px;
        border-radius: var(--mt-r-panel);
        background: var(--mt-err-bg);
        border: var(--mt-border-w) solid var(--mt-err-border);
        color: var(--mt-err-ink);
      }
      @media (max-width: 767px) {
        .focus {
          padding: 20px;
        }
        .focus__topic {
          font-size: 22px;
        }
        .focus__body,
        .focus__streaming {
          font-size: 17px;
        }
      }
    `,
  ],
})
export class FocusCardComponent {
  readonly body = input.required<string>();
  readonly streamingText = input.required<string>();
  readonly isStreaming = input.required<boolean>();
  /** The step number and the verb of the node in focus, for the eyebrow. The reader page supplies
   * both from `NodeView`. */
  readonly step = input<number | null>(null);
  readonly verbLabel = input<string>('');
  /** The topic's name, which the design draws as the card's own heading. It sits outside
   * `.focus__body`, so it contributes no character to the string the offsets index. */
  readonly topicLabel = input<string>('');

  /** The span *and* the verb, together: the learner picks the phrase first and the verb second, and
   * the request needs both. A bare `spanSelected` output would leave the page holding one half of a
   * request whose other half lives on buttons it does not own. */
  readonly explainRequested = output<{ span: SpanPayload; verb: Verb }>();

  // `#bodyEl`, not `#body`: a template reference variable shadows the component's own members
  // inside the template, so `#body` would make `{{ body() }}` resolve to the HTMLParagraphElement
  // instead of the input — a compile error here, and a silent shadowing hazard in general.
  private readonly bodyRef = viewChild.required<ElementRef<HTMLElement>>('bodyEl');
  private readonly cardRef = viewChild.required<ElementRef<HTMLElement>>('cardEl');
  private readonly selectedSpan = signal<SpanPayload | null>(null);
  protected readonly anchor = signal<PickerAnchor | null>(null);
  /** False when the rendered root and the stored body have diverged — see the class comment. */
  protected readonly bodyMatches = signal(true);
  /** The body the last post-render check ran against, so a change can be told from a re-render. */
  private checkedBody: string | null = null;

  readonly canExplain = computed(
    () => this.bodyMatches() && !this.isStreaming() && this.selectedSpan() !== null,
  );

  /** The span the picker should show, or `null` when the picker must not be on screen at all. */
  protected readonly pickerSpan = computed(() =>
    this.canExplain() && this.anchor() !== null ? this.selectedSpan() : null,
  );

  protected readonly eyebrow = computed(() => {
    const step = this.step();
    const verb = this.verbLabel();
    if (this.isStreaming()) return 'Writing the next explanation';
    if (step === null || verb === '') return 'Your explanation';
    return `Step ${step} · ${verb}`;
  });

  protected readonly hint = computed(() => {
    if (!this.bodyMatches()) {
      return 'This passage cannot be highlighted right now — what is on screen does not match the stored explanation. Reload the page to try again.';
    }
    if (this.isStreaming()) return 'Generating…';
    return 'Highlight a phrase, then choose how to go deeper.';
  });

  constructor() {
    afterRenderEffect({
      // `read`, not `mixedReadWrite`: this reads `textContent` and writes only signals. It re-runs
      // when `body()` changes, not on every render, and Angular guarantees it runs at least once.
      read: () => {
        const body = this.body();
        const matches = rootTextMatchesBody(this.bodyRef().nativeElement, body);
        this.bodyMatches.set(matches);
        if (body !== this.checkedBody) {
          this.checkedBody = body;
          // The offsets held here index the body that was on screen a moment ago. Against the new
          // one they name a phrase the learner never highlighted.
          this.selectedSpan.set(null);
          this.anchor.set(null);
        }
      },
    });
  }

  /**
   * Reads whatever the learner has just highlighted.
   *
   * `getRangeAt(0)` raises `IndexSizeError` when `rangeCount` is 0, which is every mouseup that
   * ends a click rather than a drag — so the count is checked rather than the range assumed.
   * `selectionToSpan` returns `null` for a collapsed, whitespace-only, or escaping selection, and
   * all of those land in the same place: no span, picker closed.
   */
  onSelectionChanged(): void {
    const root = this.bodyRef().nativeElement;

    // Re-checked here as well as after render, because `textContent` is not a signal: something that
    // rewrites the text nodes *in place*, leaving `body()` untouched, never makes the post-render
    // check dirty and never re-runs it. Chrome's built-in page translation and Grammarly both do
    // exactly that, and on a learning site with international readers it is ordinary rather than
    // exotic — the affordance would stay live over text the server has never seen, and every request
    // would come back SPAN_MISMATCH. This is the instant the offsets are computed, so it is the
    // instant worth checking; the comparison is one paragraph long.
    const matches = rootTextMatchesBody(root, this.body());
    this.bodyMatches.set(matches);
    if (!matches) {
      this.selectedSpan.set(null);
      this.anchor.set(null);
      return;
    }

    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) {
      this.selectedSpan.set(null);
      this.anchor.set(null);
      return;
    }

    const range = selection.getRangeAt(0);
    const span = selectionToSpan(root, range);
    this.selectedSpan.set(span);
    this.anchor.set(span === null ? null : this.anchorFor(range));
  }

  /**
   * Where the picker goes, in the card's own coordinates.
   *
   * Every read here happens inside the `mouseup`/`touchend` handler that called this method, and
   * never on the render path. That is what keeps the reader server-renderable.
   *
   * jsdom has no layout engine. `Element.getBoundingClientRect` still exists there and returns a
   * zero rect, but `Range.getClientRects`/`Range.getBoundingClientRect` are not implemented at
   * all (checked against this project's own jsdom 28, not assumed) — a plain call throws. The
   * `typeof` checks below stand in for that missing pair so a unit test still gets a zero rect
   * and the picker still renders, the same outcome the class doc comment for this method
   * promises. Neither check plays any part in a real browser, where both methods exist.
   */
  private anchorFor(range: Range): PickerAnchor {
    const card = this.cardRef().nativeElement.getBoundingClientRect();
    const rects = typeof range.getClientRects === 'function' ? range.getClientRects() : null;
    // The last rect, not the union: a selection that wraps over two lines should open the picker
    // under where it ended, which is where the learner's pointer is.
    const rect =
      rects && rects.length > 0
        ? rects[rects.length - 1]
        : typeof range.getBoundingClientRect === 'function'
          ? range.getBoundingClientRect()
          : ({ top: 0, right: 0, bottom: 0, left: 0, width: 0, height: 0 } as DOMRect);

    const width = Math.min(520, card.width);
    const left = Math.max(0, Math.min(rect.left - card.left, card.width - width));

    const below = rect.bottom - card.top + 8;
    // If the picker would run past the bottom of the card, it goes above the phrase instead.
    const fits = below + PICKER_HEIGHT <= card.height;
    const top = fits ? below : Math.max(0, rect.top - card.top - PICKER_HEIGHT - 8);

    return { top, left };
  }

  /** The learner dismissed the picker. The affordance goes; the selection is theirs to remake. */
  close(): void {
    this.selectedSpan.set(null);
    this.anchor.set(null);
  }

  request(verb: Verb): void {
    const span = this.selectedSpan();
    if (span === null || !this.canExplain()) return;
    this.explainRequested.emit({ span, verb });
    this.close();
  }
}
