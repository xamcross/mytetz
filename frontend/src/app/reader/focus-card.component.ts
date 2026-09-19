import {
  Component,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Media, SpanPayload, Verb } from '../core/models';
import {
  PICKER_HEIGHT,
  PickerAnchor,
  PickerDismissal,
  VerbPickerComponent,
} from '../ui/verb-picker.component';
import { MediaRendererComponent } from './media-renderer.component';
import { rootTextMatchesBody, selectionToSpan } from './selection';

/**
 * How long the status paragraph keeps "The explanation is ready." before it goes quiet again.
 *
 * Four seconds gives a screen reader time to read the whole sentence at an ordinary rate, with a
 * margin for a slower voice or a queue of other announcements. After this time the element goes
 * back to empty text. So a later stream's "on its way" text is always a real change, and not a
 * repeat of text that happens to still be there.
 */
const READY_STATUS_MILLIS = 4000;

/**
 * The card the learner actually reads, and the only place a selection is turned into a span.
 *
 * ## Three rules about what may live inside `.focus__body`, all load-bearing
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
 * gets its own element outside the selectable root. The picker is not on screen while a stream
 * runs.
 *
 * **The picker is a sibling of the paragraph, never a child.** The design draws a highlighted
 * phrase with an amber background. A wrapper element inside `.focus__body` would break rule one,
 * so the colour comes from `.focus__body::selection` instead and no node is added. The picker
 * itself sits after the paragraph, inside the card. A test asserts both.
 *
 * Rule one is checked rather than merely documented: `rootTextMatchesBody` runs after every render
 * that changes the body, and a mismatch means no picker opens, and says why. That is Task 1.14's
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
  imports: [VerbPickerComponent, MediaRendererComponent],
  template: `
    <article #cardEl class="focus mt-card mt-card--raised">
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
        tabindex="-1"
        (mouseup)="onSelectionChanged()"
        (touchend)="onSelectionChanged()"
      >{{ body() }}</p>

      @if (isStreaming() || streamingText().length > 0) {
        <p class="focus__streaming" aria-live="off">
          {{ streamingText() }}
          @if (isStreaming()) {
            <span class="focus__caret" aria-hidden="true">▍</span>
          }
        </p>
      }
      <!--
        One role="status" element for the whole card, changed twice per stream: once when a
        stream starts, once when it ends. It never binds streamingText(), so a token never
        touches it - that is what keeps it to two changes and not one per token. It sits outside
        the @if above, so it is in the DOM, with no text, before a learner's first stream ever
        starts. A screen reader ignores a live region that gets its text the same moment it joins
        the DOM, so the empty element has to be there first.
      -->
      <p class="mt-sr-only focus__stream-status" role="status">{{ streamStatus() }}</p>

      @if (media(); as m) {
        <app-media-renderer [media]="m" />
      }

      <p class="focus__hint" [class.focus__hint--warning]="!bodyMatches()">{{ hint() }}</p>

      <button
        type="button"
        class="mt-pill mt-pill--ghost"
        data-testid="test-me"
        (click)="testMeRequested.emit()"
      >
        Test me
      </button>

      @if (pickerSpan(); as chosenSpan) {
        <app-verb-picker
          [span]="chosenSpan"
          [anchor]="anchor()!"
          (chosen)="request($event)"
          (dismissed)="close($event)"
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
        background: var(--mt-chip);
        overflow: hidden;
      }
      /* --mt-amber on --mt-rule measured 1.20:1 and failed SC 1.4.11. --mt-amber-ink-2 is the
         same amber family, already proven at 4.5:1 on an amber surface for text, and it clears
         3:1 against --mt-chip too. palette.spec.ts proves the exact ratio from these two tokens. */
      .focus__band {
        display: block;
        width: 40%;
        height: 100%;
        border-radius: 4px;
        background: var(--mt-amber-ink-2);
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
      /* The one place in the project that removes a focus ring, and it is deliberate.
         The paragraph carries tabindex="-1" so Escape can give a keyboard learner their place in
         the text back. Measured in a real Chromium: the return does match :focus-visible, because
         Escape is a key press, so a 3px teal ring would draw around 62ch of prose. The paragraph
         is not a control, and tabindex="-1" keeps it out of the Tab order, so no learner can
         arrive here by tabbing and then be lost. Every control keeps its ring. */
      .focus__body:focus-visible {
        outline: none;
      }
      /* The design draws the highlighted phrase in amber. A wrapper element inside this paragraph
         would shift every offset and break the invariant above, so the native selection carries
         the colour instead. No node is added. */
      .focus__body::selection {
        background-color: var(--mt-amber);
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
      /* A learner who asks for less motion still sees that a stream runs: the caret stays
         visible, and the band stops moving. This rule stays here, and not in styles.css: Angular
         gives this component's own .focus__caret and .focus__band rules a higher specificity
         than a plain class in the global stylesheet can reach, so a global override there would
         never win. */
      @media (prefers-reduced-motion: reduce) {
        .focus__caret {
          animation: none;
          opacity: 1;
        }
        .focus__band {
          animation: none;
          transform: none;
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
  /** The diagram and image for the node in focus, or `null` when it carries none — every verb but
   * `VISUALIZE`, and a `VISUALIZE` node before the page supplies the field. */
  readonly media = input<Media | null>(null);
  /** True when the stream that just ended did not succeed. The reader page binds this from
   * `SessionStore.error() !== null`, read at the same point `isStreaming()` turns false — see the
   * comment in the `finally` block of `SessionStore.explain` for the write order that makes this
   * safe. A failed
   * stream must not say "The explanation is ready.", because it is not. The learner already reads
   * why, from the reader page's own error banner, sign-in panel, or subscribe wall. */
  readonly explainFailed = input.required<boolean>();
  /** The step number and the verb of the node in focus, for the eyebrow. The reader page supplies
   * both from `NodeView`. */
  readonly step = input<number | null>(null);
  readonly verbLabel = input<string>('');
  /** The topic's name, which the design draws as the card's own heading. It sits outside
   * `.focus__body`, so it contributes no character to the string the offsets index. */
  readonly topicLabel = input<string>('');
  /** True for a completed session. No new node may join one, so a highlight can open no picker —
   * the check is added to [canExplain], the one gate every path to the picker already runs
   * through. The body stays selectable, and still renders correctly, only nothing follows from it. */
  readonly readOnly = input<boolean>(false);

  /** The span *and* the verb, together: the learner picks the phrase first and the verb second, and
   * the request needs both. A bare `spanSelected` output would leave the page holding one half of a
   * request whose other half lives on buttons it does not own. */
  readonly explainRequested = output<{ span: SpanPayload; verb: Verb }>();

  /** The learner wants a quiz on the node in focus. This never depends on a selection, so the
   * button stays available whenever the card itself shows a body. */
  readonly testMeRequested = output<void>();

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
  /** The text the status paragraph shows. A signal of its own, and not `streamStatus` itself,
   * because the constructor's effect below writes it from a timer, well after the render that
   * first read `isStreaming()`. */
  private readonly streamAnnouncement = signal('');
  /** [isStreaming] on the previous run of the effect below, so that effect can tell "a stream just
   * started" and "a stream just ended" apart from "isStreaming stayed the same". */
  private wasStreaming = false;
  /** The pending clear of "The explanation is ready.", or `null` when none is pending. */
  private readyStatusTimer: ReturnType<typeof setTimeout> | null = null;

  readonly canExplain = computed(
    () =>
      !this.readOnly() && this.bodyMatches() && !this.isStreaming() && this.selectedSpan() !== null,
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
    if (this.readOnly()) {
      return 'This session is complete, so it is read-only. Start a new session to keep exploring this topic.';
    }
    if (!this.bodyMatches()) {
      return 'This passage cannot be highlighted right now — what is on screen does not match the stored explanation. Reload the page to try again.';
    }
    if (this.isStreaming()) return 'Generating…';
    return 'Highlight a phrase, then choose how to go deeper.';
  });

  /** The text of the one `role="status"` element that tells a screen reader about a stream. See
   * the constructor's effect below for the two moments this changes. */
  protected readonly streamStatus = computed(() => this.streamAnnouncement());

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

    // The screen reader announcement for a stream. This effect reads only [isStreaming], and
    // never [streamingText], so one appended token never runs it — that is what keeps the status
    // paragraph to two changes per stream. It also acts only on an *edge* of [isStreaming]: true
    // right after false, or false right after true. The steady value in between changes nothing.
    effect(() => {
      const streaming = this.isStreaming();
      const wasStreaming = this.wasStreaming;
      this.wasStreaming = streaming;

      if (streaming && !wasStreaming) {
        // A new stream starts. Any "ready" text a previous stream left waiting to clear is now
        // stale, so its timer goes too.
        this.clearReadyStatusTimer();
        this.streamAnnouncement.set('The explanation is on its way.');
      } else if (!streaming && wasStreaming) {
        if (this.explainFailed()) {
          // The stream ended, but it did not succeed. "Ready" would be false, so the element goes
          // quiet instead. See [explainFailed]'s own comment for where the learner reads why.
          this.streamAnnouncement.set('');
          return;
        }
        this.streamAnnouncement.set('The explanation is ready.');
        // See [READY_STATUS_MILLIS] for how long this text stays. Cleared on destroy below, so a
        // card the learner has already left never writes to a signal nobody reads any more.
        this.readyStatusTimer = setTimeout(() => {
          this.readyStatusTimer = null;
          this.streamAnnouncement.set('');
        }, READY_STATUS_MILLIS);
      }
    });

    inject(DestroyRef).onDestroy(() => this.clearReadyStatusTimer());
  }

  private clearReadyStatusTimer(): void {
    if (this.readyStatusTimer === null) return;
    clearTimeout(this.readyStatusTimer);
    this.readyStatusTimer = null;
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

  /**
   * The learner dismissed the picker. The affordance goes; the selection is theirs to remake.
   *
   * Escape also returns focus to the paragraph, so a keyboard reader keeps their place in the
   * text. The paragraph carries `tabindex="-1"` for that. An attribute contributes no character to
   * `textContent`, and it sits in the open tag, so the invariant above is untouched.
   *
   * A press outside the picker returns nothing. That path runs inside a `mousedown` listener, and
   * a focus move there cancels the drag the learner has started — which is how they reselect.
   */
  close(reason: PickerDismissal): void {
    this.clearSelection();
    if (reason === 'escape') this.bodyRef().nativeElement.focus();
  }

  request(verb: Verb): void {
    const span = this.selectedSpan();
    if (span === null || !this.canExplain()) return;
    this.explainRequested.emit({ span, verb });
    this.clearSelection();
  }

  /** Drops the phrase and its anchor, which together take the picker off screen. */
  private clearSelection(): void {
    this.selectedSpan.set(null);
    this.anchor.set(null);
  }
}
