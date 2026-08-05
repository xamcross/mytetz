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
import { rootTextMatchesBody, selectionToSpan } from './selection';

/** The four things a learner can ask of a highlighted phrase. `SEED` and `VISUALIZE` are not
 * offered: the first is the session's own root and the second is not in Slice 1. */
const VERBS: ReadonlyArray<{ verb: Verb; label: string }> = [
  { verb: 'EXPLAIN', label: 'Explain' },
  { verb: 'DIG_DEEPER', label: 'Dig Deeper' },
  { verb: 'BROADER_PICTURE', label: 'Broader Picture' },
  { verb: 'SIDE_VIEW', label: 'Side View' },
];

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
  imports: [],
  template: `
    <article class="focus">
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

      <div class="focus__verbs" role="group" aria-label="Explain the highlighted phrase">
        @for (v of verbs; track v.verb) {
          <button
            type="button"
            class="focus__verb"
            [attr.data-verb]="v.verb"
            [disabled]="!canExplain()"
            (click)="request(v.verb)"
          >
            {{ v.label }}
          </button>
        }
      </div>
    </article>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .focus {
        border: 1px solid #ddd;
        border-radius: 10px;
        background: #fff;
        padding: 1.25rem 1.5rem;
      }
      .focus__body {
        margin: 0;
        font-size: 1.05rem;
        line-height: 1.65;
        white-space: pre-wrap;
      }
      .focus__streaming {
        margin: 1rem 0 0;
        font-size: 1.05rem;
        line-height: 1.65;
        white-space: pre-wrap;
        color: #333;
        border-left: 3px solid #1a56db;
        padding-left: 0.75rem;
        user-select: none;
      }
      .focus__caret {
        color: #1a56db;
      }
      .focus__hint {
        margin: 1rem 0 0.5rem;
        font-size: 0.85rem;
        color: #666;
      }
      .focus__hint--warning {
        color: #7a1f1f;
        font-weight: 600;
      }
      .focus__verbs {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
      }
      .focus__verb {
        padding: 0.45rem 0.9rem;
        border: 1px solid #ccd;
        border-radius: 999px;
        background: #f7f8ff;
        cursor: pointer;
        font-size: 0.9rem;
      }
      .focus__verb:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
    `,
  ],
})
export class FocusCardComponent {
  readonly body = input.required<string>();
  readonly streamingText = input.required<string>();
  readonly isStreaming = input.required<boolean>();

  /** The span *and* the verb, together: the learner picks the phrase first and the verb second, and
   * the request needs both. A bare `spanSelected` output would leave the page holding one half of a
   * request whose other half lives on buttons it does not own. */
  readonly explainRequested = output<{ span: SpanPayload; verb: Verb }>();

  readonly verbs = VERBS;

  // `#bodyEl`, not `#body`: a template reference variable shadows the component's own members
  // inside the template, so `#body` would make `{{ body() }}` resolve to the HTMLParagraphElement
  // instead of the input — a compile error here, and a silent shadowing hazard in general.
  private readonly bodyRef = viewChild.required<ElementRef<HTMLElement>>('bodyEl');
  private readonly selectedSpan = signal<SpanPayload | null>(null);
  /** False when the rendered root and the stored body have diverged — see the class comment. */
  protected readonly bodyMatches = signal(true);
  /** The body the last post-render check ran against, so a change can be told from a re-render. */
  private checkedBody: string | null = null;

  readonly canExplain = computed(
    () => this.bodyMatches() && !this.isStreaming() && this.selectedSpan() !== null,
  );

  readonly hint = computed(() => {
    if (!this.bodyMatches()) {
      return 'This passage cannot be highlighted right now — what is on screen does not match the stored explanation. Reload the page to try again.';
    }
    if (this.isStreaming()) return 'Generating…';
    const span = this.selectedSpan();
    if (span === null) return 'Highlight a phrase, then choose how to go deeper.';
    return `Selected: “${span.text}”`;
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
   * all of those land in the same place: no span, verbs disabled.
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
      return;
    }

    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) {
      this.selectedSpan.set(null);
      return;
    }
    this.selectedSpan.set(selectionToSpan(root, selection.getRangeAt(0)));
  }

  request(verb: Verb): void {
    const span = this.selectedSpan();
    if (span === null || !this.canExplain()) return;
    this.explainRequested.emit({ span, verb });
  }
}
