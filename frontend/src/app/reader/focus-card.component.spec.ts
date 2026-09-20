import { readFileSync } from 'node:fs';
import { ErrorHandler } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FocusCardComponent, freezeOutOfFlow } from './focus-card.component';
import { EXPLAIN_STREAM, ExplainStreamFn, SessionStore } from './session.store';
import { rootTextMatchesBody } from './selection';
import { AccountStore } from '../core/account.store';
import { Media, SessionView, SpanPayload, Verb } from '../core/models';
import { ExplainEvent, ExplainStreamError } from '../core/sse.client';

const BODY = 'The pillars of modern physics.';

/**
 * Round 2 of issue #104. jsdom has no layout engine, so every offset and every rect below is
 * zero — the same limit `anchorFor`'s own doc comment names. This still proves the function
 * reads position before it changes it, and writes all three properties `position: absolute`
 * needs, which is what a mutation of the read-then-write order or a missing property would break.
 */
describe('freezeOutOfFlow', () => {
  it('takes an element out of flow at its own present offset', () => {
    const el = document.createElement('p');
    document.body.appendChild(el);

    freezeOutOfFlow(el);

    expect(el.style.position).toBe('absolute');
    expect(el.style.top).toBe('0px');
    expect(el.style.left).toBe('0px');
    expect(el.style.width).toBe('0px');

    el.remove();
  });
});

/**
 * Rule 3 of the design review: nothing may animate the paragraph under a learner's drag. This
 * reads the component's own shipped source, the way `palette.spec.ts` and `styles.spec.ts` read
 * theirs, rather than trusting that a later edit leaves the rule alone.
 */
describe('the bare .focus__body style rule', () => {
  const source = readFileSync('src/app/reader/focus-card.component.ts', 'utf8');

  /** The body of the one rule whose selector is exactly `.focus__body` — not `--landed`, not
   * `:focus-visible`, not `::selection`. The match requires only whitespace between the selector
   * and its opening brace, which the modifier and pseudo-selector forms all fail. */
  function bareFocusBodyBlock(): string {
    const match = source.match(/\.focus__body\s*\{([^}]*)\}/);
    if (!match) throw new Error('the component must declare a bare .focus__body rule');
    return match[1];
  }

  it('has no transition and no animation', () => {
    const block = bareFocusBodyBlock();
    expect(block).not.toMatch(/\btransition\b/);
    expect(block).not.toMatch(/\banimation\b/);
  });
});

describe('FocusCardComponent', () => {
  let fixture: ComponentFixture<FocusCardComponent>;
  let requests: Array<{ span: SpanPayload; verb: Verb }>;
  /** Anything Angular reported while handling an event — a listener that throws never reaches
   * `dispatchEvent`'s caller, so this is the only place a handler crash is visible. */
  let errors: unknown[];
  /** The event sequence `SessionStore.explain` receives, for the specs that drive the real store
   * through an explain call. Set per spec; the default fails loudly rather than silently
   * streaming nothing, the same guard `session.store.spec.ts` uses. */
  let script: ExplainStreamFn;

  beforeEach(async () => {
    errors = [];
    script = () => {
      throw new Error('this spec called explain() without installing a stream script');
    };
    TestBed.configureTestingModule({
      imports: [FocusCardComponent],
      providers: [
        { provide: ErrorHandler, useValue: { handleError: (e: unknown) => errors.push(e) } },
        // Only a few specs below inject `SessionStore`, but the module can be configured once
        // per test, before the first `TestBed.inject`. Adding the providers here, rather than
        // inside those specs, keeps every test on the same setup.
        SessionStore,
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: EXPLAIN_STREAM,
          useValue: ((sessionId, body, signal) =>
            script(sessionId, body, signal)) satisfies ExplainStreamFn,
        },
      ],
    });
    fixture = TestBed.createComponent(FocusCardComponent);
    requests = [];
    fixture.componentInstance.explainRequested.subscribe((r) => requests.push(r));
    fixture.componentRef.setInput('body', BODY);
    fixture.componentRef.setInput('streamingText', '');
    fixture.componentRef.setInput('isStreaming', false);
    fixture.componentRef.setInput('explainFailed', false);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => {
    window.getSelection()?.removeAllRanges();
    // A test below may turn on fake timers. Real timers are the default for every other test in
    // this file, so each test leaves the clock the way it found it.
    vi.useRealTimers();
    // A few specs below drive a real `SessionStore` over HTTP. This fails loudly if one of them
    // leaves a request unanswered.
    TestBed.inject(HttpTestingController).verify();
  });

  const bodyEl = (): HTMLElement => fixture.nativeElement.querySelector('.focus__body');
  const statusEl = (): HTMLElement => fixture.nativeElement.querySelector('.focus__stream-status');
  const verbButton = (verb: Verb): HTMLButtonElement | null =>
    fixture.nativeElement.querySelector(`button[data-verb="${verb}"]`);
  /** True when the picker is on screen, which is the only time a verb can be pressed. */
  const pickerLive = (): boolean => verbButton('EXPLAIN') !== null;

  /** A one-node session, just enough for `SessionStore.explain` to run against a real
   * `currentNodeId`. Mirrors the fixture shape `session.store.spec.ts` uses. */
  const SESSION_VIEW: SessionView = {
    sessionId: 's1',
    topicSlug: 'quantum-physics',
    rootNodeId: 'n0',
    currentNodeId: 'n0',
    nodes: [
      {
        nodeId: 'n0',
        parentNodeId: null,
        explanationKey: 'k0',
        span: '',
        verb: 'SEED',
        variant: 0,
        depth: 0,
      },
    ],
    status: 'ACTIVE',
    explanations: { k0: 'Quantum mechanics is…' },
  };

  /** What `GET /api/sessions/s1` returns after a successful explain under `n0`. */
  const SESSION_VIEW_AFTER_EXPLAIN: SessionView = {
    ...SESSION_VIEW,
    currentNodeId: 'n1',
    nodes: [
      ...SESSION_VIEW.nodes,
      {
        nodeId: 'n1',
        parentNodeId: 'n0',
        explanationKey: 'k1',
        span: 'pillars',
        verb: 'EXPLAIN',
        variant: 0,
        depth: 1,
      },
    ],
    explanations: { ...SESSION_VIEW.explanations, k1: 'The four pillars are…' },
  };

  const EXPLAIN_SPAN: SpanPayload = { text: 'pillars', start: 4, end: 11 };

  const streamDelta = (t: string): ExplainEvent => ({ event: 'delta', data: { t } });
  const streamDone = (contentKey: string): ExplainEvent => ({
    event: 'done',
    data: { contentKey, grounded: true },
  });

  /** Drains the microtask queue. `SessionStore.load` starts the topic-title read and does not
   * await it, and this is what lets that request reach the HTTP mock — the same reason
   * `session.store.spec.ts`'s own `tick` helper exists. */
  const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

  /** Loads session `s1` into a real `SessionStore`, answering both requests a load makes, the
   * way `session.store.spec.ts`'s own `loadSession` helper does. */
  async function loadRealSession(store: SessionStore, http: HttpTestingController): Promise<void> {
    const loaded = store.load('s1');
    http.expectOne('/api/sessions/s1').flush(SESSION_VIEW);
    await loaded;
    http.expectOne('/api/catalog/topics/quantum-physics').flush({
      slug: 'quantum-physics',
      title: 'Quantum Physics',
      category: 'Physics',
      summary: 'A summary.',
    });
    await tick();
  }

  function select(start: number, end: number): void {
    const range = document.createRange();
    const text = bodyEl().firstChild as Text;
    range.setStart(text, start);
    range.setEnd(text, end);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
    bodyEl().dispatchEvent(new Event('mouseup'));
    fixture.detectChanges();
  }

  it('renders the body as the only text inside the selectable root', () => {
    // The whole of Task 1.14's invariant, mechanically. `<p #body>\n  {{ body }}\n</p>` would put a
    // leading and a trailing space in here — the Angular compiler collapses a run of whitespace to
    // one character rather than removing it, since the text node is not blank — and every offset
    // computed against this element would then be one out, so every explain would come back
    // SPAN_MISMATCH from a template that looks perfectly ordinary.
    expect(bodyEl().textContent).toBe(BODY);
    expect(rootTextMatchesBody(bodyEl(), BODY)).toBe(true);
  });

  it('keeps streaming text out of the selectable root', async () => {
    fixture.componentRef.setInput('isStreaming', true);
    fixture.componentRef.setInput('streamingText', 'A new answer is arriving…');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('A new answer is arriving…');
    // If the two shared an element, a selection made mid-stream would be measured against the
    // settled body plus however much of the new answer had arrived — offsets that match nothing the
    // server holds.
    expect(bodyEl().textContent).toBe(BODY);
  });

  it('emits the highlighted span with the verb of the button pressed', () => {
    select(4, 11);

    expect(pickerLive()).toBe(true);
    verbButton('DIG_DEEPER')!.click();

    expect(requests).toEqual([
      { span: { text: 'pillars', start: 4, end: 11 }, verb: 'DIG_DEEPER' },
    ]);
  });

  it('clears the span, rather than throwing, on a mouseup that selected nothing', () => {
    select(4, 11);
    expect(pickerLive()).toBe(true);

    // A click that ends without a drag — the commonest mouseup there is. `getRangeAt(0)` raises
    // IndexSizeError at rangeCount 0 (verified against this project's own jsdom, not assumed).
    //
    // Asserted through both consequences, because neither alone is enough: Angular routes a
    // listener throw to `ErrorHandler` rather than back out through `dispatchEvent`, so an
    // `expect(...).not.toThrow()` around the dispatch passes either way — that shape of this test
    // let a mutation removing the `rangeCount` guard survive. And a throw here would leave the
    // *previous* span selected with the verbs still live, so the learner's next click would explain
    // a phrase they had already deselected.
    window.getSelection()?.removeAllRanges();
    bodyEl().dispatchEvent(new Event('mouseup'));
    fixture.detectChanges();

    expect(errors).toEqual([]);
    expect(pickerLive()).toBe(false);
    expect(requests).toEqual([]);
  });

  it('drops a selection that no longer indexes the body on screen', async () => {
    select(4, 11);
    expect(pickerLive()).toBe(true);

    fixture.componentRef.setInput('body', 'A different explanation entirely.');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    // The offsets were measured against the previous body. Kept, they would name a phrase of the
    // new one that the learner never highlighted.
    expect(pickerLive()).toBe(false);
  });

  it('offers no verb, and says why, when the rendered root stops matching the body', async () => {
    // Exactly the hazard `selectionToSpan`'s doc comment names: something inside the root
    // contributing characters the server's stored string does not have.
    bodyEl().appendChild(document.createTextNode(' Explain'));
    fixture.componentRef.setInput('body', 'The pillars of modern physics, restated.');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    select(4, 11);

    expect(pickerLive()).toBe(false);
    expect(requests).toEqual([]);
    // Disabling without saying why is its own small "nothing happened".
    expect(fixture.nativeElement.textContent).toContain('cannot be highlighted');
  });

  it('re-checks the body when a selection is made, catching a rewrite no render saw', () => {
    // `rootTextMatchesBody` reads `textContent`, which is not a signal, so a rewrite *in place* —
    // with `body()` unchanged — never makes the post-render check dirty and never re-runs it.
    // Chrome's built-in page translation and Grammarly both do exactly this, and on a learning site
    // with international readers it is common rather than exotic. The result would be a live
    // affordance over text the server has never seen: every request back as SPAN_MISMATCH.
    (bodyEl().firstChild as Text).data = 'Los pilares de la física moderna.';

    select(4, 11);

    expect(pickerLive()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('cannot be highlighted');
    expect(requests).toEqual([]);
  });

  it('recovers on the next selection when the rewritten text is put back', () => {
    (bodyEl().firstChild as Text).data = 'Los pilares de la física moderna.';
    select(4, 11);
    expect(pickerLive()).toBe(false);

    // Turning the translation off, or dismissing the extension, restores the text — and `body()`
    // has not changed through any of this, so the post-render check is still not dirty. Recovery
    // therefore has to come from the same selection-time re-check that spotted the problem, which
    // is a property of where that check lives rather than something the code says anywhere.
    (bodyEl().firstChild as Text).data = BODY;

    select(4, 11);

    expect(pickerLive()).toBe(true);
    expect(fixture.nativeElement.textContent).not.toContain('cannot be highlighted');
    verbButton('EXPLAIN')!.click();
    expect(requests).toEqual([{ span: { text: 'pillars', start: 4, end: 11 }, verb: 'EXPLAIN' }]);
  });

  it('keeps the picker out of the selectable root', () => {
    // The picker's own markup inside `.focus__body` would shift every offset by however many
    // characters it contributes, and every explain would come back SPAN_MISMATCH. The picker is a
    // sibling of the paragraph and never a child of it.
    select(4, 11);

    expect(pickerLive()).toBe(true);
    expect(bodyEl().querySelector('[role="dialog"]')).toBeNull();
    expect(bodyEl().textContent).toBe(BODY);
    expect(rootTextMatchesBody(bodyEl(), BODY)).toBe(true);
  });

  // Finding F11 of the design review. The topic name used to render three times at once: this
  // card's own <h1>, the breadcrumb's root crumb, and the trail rail's root row. The card now
  // carries the <h1> at step 1 only — reader-page.component.ts supplies a hidden one of its own
  // past step 1, so the reader page still keeps exactly one <h1> at every step.
  it('renders the topic label as the card heading at step 1', () => {
    // `topicLabel` defaults to `''`. A caller that forgets the binding therefore renders an empty
    // <h1> and nothing reports it — that defect shipped once already, past the whole suite. This
    // component owns the risky default, so the guard lives here.
    fixture.componentRef.setInput('topicLabel', 'Quantum Physics');
    fixture.componentRef.setInput('step', 1);
    fixture.detectChanges();
    const heading: HTMLElement = fixture.nativeElement.querySelector('.focus__topic');
    expect(heading).not.toBeNull();
    expect(heading.textContent?.trim()).toBe('Quantum Physics');
  });

  it('drops the <h1> past step 1, so the topic name does not show a third time', () => {
    fixture.componentRef.setInput('topicLabel', 'Quantum Physics');
    fixture.componentRef.setInput('step', 2);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.focus__topic')).toBeNull();
  });

  it('returns focus to the body paragraph when Escape closes the picker', () => {
    select(4, 11);
    expect(pickerLive()).toBe(true);

    verbButton('EXPLAIN')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    fixture.detectChanges();

    // Without this the learner lands on <body> and loses their place in the text. The paragraph
    // carries `tabindex="-1"` so it can hold focus at all.
    expect(pickerLive()).toBe(false);
    expect(document.activeElement).toBe(bodyEl());
  });

  it('moves focus nowhere when a press outside closes the picker', () => {
    select(4, 11);
    expect(pickerLive()).toBe(true);

    document.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    fixture.detectChanges();

    // This path runs inside `mousedown`. A focus move here cancels the drag a learner starts when
    // they reselect a phrase, which is the path the end-to-end suite exercises. So the paragraph
    // must not take focus, although Escape gives it focus in the test above.
    expect(pickerLive()).toBe(false);
    expect(document.activeElement).not.toBe(bodyEl());
  });

  it('adds no character to the selectable root when the paragraph gains a tabindex', () => {
    // An attribute lives in the open tag and contributes nothing to `textContent`. This states it
    // rather than assuming it, because the whole invariant rests on that string.
    expect(bodyEl().getAttribute('tabindex')).toBe('-1');
    expect(bodyEl().textContent).toBe(BODY);
    expect(rootTextMatchesBody(bodyEl(), BODY)).toBe(true);
  });

  it('disables highlighting and says why on a read-only, completed session', () => {
    fixture.componentRef.setInput('readOnly', true);
    fixture.detectChanges();

    select(4, 11);

    expect(pickerLive()).toBe(false);
    expect(requests).toEqual([]);
    expect(fixture.nativeElement.textContent).toContain('complete');
  });

  it('shows the media renderer when the node in focus carries media', () => {
    const media: Media = {
      diagram: { kind: 'SVG', source: '<svg><circle cx="1" cy="1" r="1"/></svg>' },
      image: null,
    };
    expect(fixture.nativeElement.querySelector('app-media-renderer')).toBeNull();

    fixture.componentRef.setInput('media', media);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-media-renderer')).not.toBeNull();
  });

  it('shows no media renderer when the node in focus carries none', () => {
    fixture.componentRef.setInput('media', null);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-media-renderer')).toBeNull();
  });

  it('closes the picker when a stream starts', async () => {
    select(4, 11);
    expect(pickerLive()).toBe(true);

    // A stream in progress is prose that is in no stored body yet, so no selection over it can be
    // turned into a request. The affordance goes away rather than staying on screen and refusing.
    fixture.componentRef.setInput('isStreaming', true);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(pickerLive()).toBe(false);
  });

  it('takes the live region off the streamed text itself, so a screen reader never reads one token', () => {
    // The old markup put `role="status"` on this paragraph, so every appended token counted as a
    // change. A screen reader then read fragments, or read nothing useful. The live region moves
    // to its own element below, and this paragraph turns its own announcement off.
    fixture.componentRef.setInput('isStreaming', true);
    fixture.componentRef.setInput('streamingText', 'The four pillars');
    fixture.detectChanges();

    const streaming: HTMLElement = fixture.nativeElement.querySelector('.focus__streaming');
    expect(streaming.getAttribute('role')).toBeNull();
    expect(streaming.getAttribute('aria-live')).toBe('off');
  });

  it('holds a status element in the DOM before any stream starts, with no text in it', () => {
    // A screen reader ignores a live region that gets its first text at the same moment it joins
    // the DOM. The element must exist, empty, from the very first render, and not only once a
    // stream begins.
    const status = statusEl();
    expect(status).not.toBeNull();
    expect(status.getAttribute('role')).toBe('status');
    expect(status.textContent?.trim()).toBe('');
  });

  it('says the explanation is on its way once a stream starts', () => {
    fixture.componentRef.setInput('isStreaming', true);
    fixture.componentRef.setInput('streamingText', 'The four');
    fixture.detectChanges();

    expect(statusEl().textContent?.trim()).toBe('The explanation is on its way.');
  });

  it('says the explanation is ready once the stream ends', () => {
    fixture.componentRef.setInput('isStreaming', true);
    fixture.detectChanges();
    fixture.componentRef.setInput('isStreaming', false);
    fixture.detectChanges();

    expect(statusEl().textContent?.trim()).toBe('The explanation is ready.');
  });

  /**
   * Issue #139. `ReaderPageComponent` binds `tokenResultText` from `SessionStore.tokenResult()`,
   * already settled by the time `isStreaming` turns false — see `SessionStore.reportTokenResult`'s
   * own KDoc. This component joins it into the one "ready" sentence, so a screen reader hears one
   * message and not two: issue #99 already forbids a second live region announcing at the same
   * moment, and a second write to this same region a moment later would read as two messages just
   * as surely.
   */
  describe('the token result, joined into the one "ready" message', () => {
    it('joins a used-token result into the one sentence', () => {
      fixture.componentRef.setInput('isStreaming', true);
      fixture.detectChanges();
      fixture.componentRef.setInput('tokenResultText', '1 token used. 35 tokens left.');
      fixture.componentRef.setInput('isStreaming', false);
      fixture.detectChanges();

      expect(statusEl().textContent?.trim()).toBe(
        'The explanation is ready. 1 token used. 35 tokens left.',
      );
    });

    it('joins a no-token-used result into the one sentence', () => {
      fixture.componentRef.setInput('isStreaming', true);
      fixture.detectChanges();
      fixture.componentRef.setInput('tokenResultText', 'No token used. This text existed already.');
      fixture.componentRef.setInput('isStreaming', false);
      fixture.detectChanges();

      expect(statusEl().textContent?.trim()).toBe(
        'The explanation is ready. No token used. This text existed already.',
      );
    });

    it('says only "ready" when there is no token result to report', () => {
      fixture.componentRef.setInput('isStreaming', true);
      fixture.detectChanges();
      fixture.componentRef.setInput('isStreaming', false);
      fixture.detectChanges();

      expect(statusEl().textContent?.trim()).toBe('The explanation is ready.');
    });

    it('says nothing about a token when the stream failed, even with a result text bound', () => {
      // Belt and braces: `ReaderPageComponent` never binds a token result on a failed explain (see
      // `SessionStore.reportTokenResult`), but the announcement must stay silent even if it did —
      // issue #99's own rule that a failed stream never says "ready".
      fixture.componentRef.setInput('isStreaming', true);
      fixture.detectChanges();
      fixture.componentRef.setInput('tokenResultText', '1 token used. 35 tokens left.');
      fixture.componentRef.setInput('explainFailed', true);
      fixture.componentRef.setInput('isStreaming', false);
      fixture.detectChanges();

      expect(statusEl().textContent?.trim()).toBe('');
    });
  });

  it('announces one full stream exactly two times, and not once for every token', () => {
    // `SessionStore.isStreaming` and `SessionStore.streamingText` are the two signals the reader
    // page binds into this component's inputs. This test drives those same signals the way
    // `SessionStore.explain` drives them: `isStreaming` true, `streamingText` reset, one append
    // per token, then `isStreaming` false. The proof is then about a real stream, and not about a
    // story of one.
    vi.useFakeTimers();
    const store = TestBed.inject(SessionStore);

    const changes: string[] = [];
    let previousText = statusEl().textContent?.trim() ?? '';
    const recordChange = (): void => {
      const text = statusEl().textContent?.trim() ?? '';
      if (text !== previousText) {
        changes.push(text);
        previousText = text;
      }
    };

    // Mirrors the two opening lines of `SessionStore.explain`.
    store.isStreaming.set(true);
    store.streamingText.set('');
    fixture.componentRef.setInput('isStreaming', store.isStreaming());
    fixture.componentRef.setInput('streamingText', store.streamingText());
    fixture.detectChanges();
    recordChange();

    const tokens = [
      'The',
      ' four',
      ' pillars',
      ' of',
      ' modern',
      ' physics',
      ' are',
      ' mass',
      ',',
      ' energy',
      ',',
      ' space',
      ' and',
      ' time',
      '.',
    ];
    for (const token of tokens) {
      // Mirrors `SessionStore.explain`'s `delta` branch: one append per token.
      store.streamingText.update((text) => text + token);
      fixture.componentRef.setInput('streamingText', store.streamingText());
      fixture.detectChanges();
      recordChange();
    }

    // Mirrors the `finally` block of `SessionStore.explain`.
    store.isStreaming.set(false);
    fixture.componentRef.setInput('isStreaming', store.isStreaming());
    fixture.detectChanges();
    recordChange();

    expect(changes).toEqual(['The explanation is on its way.', 'The explanation is ready.']);
  });

  it('never announces "ready" when a stream fails, and clears the status text instead', async () => {
    // A real 429, a real network drop, and `SPAN_MISMATCH` all take this shape: `meta`/`delta`
    // events already sent, then `SessionStore.explain` throws. Mirrors
    // `session.store.spec.ts`'s own "discards partially streamed prose" test.
    const store = TestBed.inject(SessionStore);
    const http = TestBed.inject(HttpTestingController);
    vi.spyOn(TestBed.inject(AccountStore), 'load').mockResolvedValue(undefined);
    await loadRealSession(store, http);

    const changes: string[] = [];
    let previousText = statusEl().textContent?.trim() ?? '';
    const recordChange = (): void => {
      const text = statusEl().textContent?.trim() ?? '';
      if (text !== previousText) {
        changes.push(text);
        previousText = text;
      }
    };
    // Mirrors what `ReaderPageComponent` binds: `explainFailed` follows `store.error() !== null`.
    const pushFromStore = (): void => {
      fixture.componentRef.setInput('isStreaming', store.isStreaming());
      fixture.componentRef.setInput('streamingText', store.streamingText());
      fixture.componentRef.setInput('explainFailed', store.error() !== null);
    };

    script = async function* (): AsyncGenerator<ExplainEvent> {
      yield streamDelta('half an answer');
      throw new ExplainStreamError(
        'GENERATION_FAILED',
        'the explanation could not be generated; try again',
        null,
        true,
      );
    };

    const explaining = store.explain(EXPLAIN_SPAN, 'EXPLAIN');
    pushFromStore();
    fixture.detectChanges();
    recordChange();

    await explaining;
    pushFromStore();
    fixture.detectChanges();
    recordChange();

    expect(store.error()?.code).toBe('GENERATION_FAILED');
    expect(changes).toEqual(['The explanation is on its way.', '']);
  });

  it('never announces "ready" when the wall refuses a stream', async () => {
    // A wall refusal is a pre-stream refusal: nothing is rendered before the throw. Mirrors
    // `session.store.spec.ts`'s own "reports a pre-stream refusal" test, with a wall code.
    const store = TestBed.inject(SessionStore);
    const http = TestBed.inject(HttpTestingController);
    vi.spyOn(TestBed.inject(AccountStore), 'load').mockResolvedValue(undefined);
    await loadRealSession(store, http);

    const changes: string[] = [];
    let previousText = statusEl().textContent?.trim() ?? '';
    const recordChange = (): void => {
      const text = statusEl().textContent?.trim() ?? '';
      if (text !== previousText) {
        changes.push(text);
        previousText = text;
      }
    };
    const pushFromStore = (): void => {
      fixture.componentRef.setInput('isStreaming', store.isStreaming());
      fixture.componentRef.setInput('streamingText', store.streamingText());
      fixture.componentRef.setInput('explainFailed', store.error() !== null);
    };

    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError('TRIAL_EXHAUSTED', 'the free trial is used up', null, false);
    };

    const explaining = store.explain(EXPLAIN_SPAN, 'EXPLAIN');
    pushFromStore();
    fixture.detectChanges();
    recordChange();

    await explaining;
    pushFromStore();
    fixture.detectChanges();
    recordChange();

    expect(store.error()?.code).toBe('TRIAL_EXHAUSTED');
    expect(changes).toEqual(['The explanation is on its way.', '']);
  });

  it('announces a second, successful stream correctly after a failed one', async () => {
    const store = TestBed.inject(SessionStore);
    const http = TestBed.inject(HttpTestingController);
    vi.spyOn(TestBed.inject(AccountStore), 'load').mockResolvedValue(undefined);
    await loadRealSession(store, http);

    const changes: string[] = [];
    let previousText = statusEl().textContent?.trim() ?? '';
    const recordChange = (): void => {
      const text = statusEl().textContent?.trim() ?? '';
      if (text !== previousText) {
        changes.push(text);
        previousText = text;
      }
    };
    const pushFromStore = (): void => {
      fixture.componentRef.setInput('isStreaming', store.isStreaming());
      fixture.componentRef.setInput('streamingText', store.streamingText());
      fixture.componentRef.setInput('explainFailed', store.error() !== null);
    };

    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError('GENERATION_FAILED', 'could not generate', null, false);
    };
    let explaining = store.explain(EXPLAIN_SPAN, 'EXPLAIN');
    pushFromStore();
    fixture.detectChanges();
    recordChange();
    await explaining;
    pushFromStore();
    fixture.detectChanges();
    recordChange();

    // The failed attempt leaves nothing behind that could stop the next one from announcing
    // correctly: not a stuck flag, and not a leftover timer.
    script = async function* (): AsyncGenerator<ExplainEvent> {
      yield streamDelta('The four pillars are…');
      yield streamDone('k1');
    };
    explaining = store.explain(EXPLAIN_SPAN, 'EXPLAIN');
    pushFromStore();
    fixture.detectChanges();
    recordChange();

    // Drains the microtask queue, the same way `session.store.spec.ts`'s own tests do before
    // expecting the re-fetch: the generator's two `yield`s each need their own turn before
    // `SessionStore.explain` reaches `await this.refresh(sessionId)`.
    await tick();
    http.expectOne('/api/sessions/s1').flush(SESSION_VIEW_AFTER_EXPLAIN);
    await explaining;
    pushFromStore();
    fixture.detectChanges();
    recordChange();

    expect(changes).toEqual([
      'The explanation is on its way.',
      '',
      'The explanation is on its way.',
      'The explanation is ready.',
    ]);
  });

  it('clears "The explanation is ready." once it has stayed long enough to be read', () => {
    // Four seconds — see the comment on `READY_STATUS_MILLIS` in the component for the reason.
    // The test advances a fake clock, and it does not wait on a real one, so the test stays fast
    // and exact.
    vi.useFakeTimers();
    fixture.componentRef.setInput('isStreaming', true);
    fixture.detectChanges();
    fixture.componentRef.setInput('isStreaming', false);
    fixture.detectChanges();
    expect(statusEl().textContent?.trim()).toBe('The explanation is ready.');

    vi.advanceTimersByTime(4000);
    fixture.detectChanges();

    expect(statusEl().textContent?.trim()).toBe('');
  });

  it('shows the first streamed token in the DOM the instant it arrives, with no wait for an animation', () => {
    // Animation B enters the stream box once, and the box must never hold the token back while it
    // does. No `await fixture.whenStable()` runs here on purpose: the token has to be on screen
    // straight after the render that follows the first `delta` event, and not only after any
    // animation has had time to run.
    fixture.componentRef.setInput('isStreaming', true);
    fixture.componentRef.setInput('streamingText', 'The');
    fixture.detectChanges();

    const streaming: HTMLElement = fixture.nativeElement.querySelector('.focus__streaming');
    expect(streaming.textContent).toContain('The');
  });

  it('marks the body as landed for one animation when a new answer arrives, and not on the first render', async () => {
    // A fresh card must not carry the class: animation A answers an arrival the learner watched
    // happen, and the very first render of a session is not that.
    expect(bodyEl().classList.contains('focus__body--landed')).toBe(false);

    fixture.componentRef.setInput('body', 'A new pillar of the theory.');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(bodyEl().classList.contains('focus__body--landed')).toBe(true);
  });

  it('clears the landed class once its own animation ends, and ignores an unrelated one', async () => {
    fixture.componentRef.setInput('body', 'A new pillar of the theory.');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(bodyEl().classList.contains('focus__body--landed')).toBe(true);

    // A bubbled `animationend` from an animation this paragraph does not own must change nothing.
    bodyEl().dispatchEvent(
      Object.assign(new Event('animationend'), { animationName: 'some-other-animation' }),
    );
    fixture.detectChanges();
    expect(bodyEl().classList.contains('focus__body--landed')).toBe(true);

    bodyEl().dispatchEvent(
      Object.assign(new Event('animationend'), { animationName: 'focus-land' }),
    );
    fixture.detectChanges();
    expect(bodyEl().classList.contains('focus__body--landed')).toBe(false);
  });

  it('marks the second answer as landed too, even when the first one never got its animationend', async () => {
    // Round 2 of issue #104. If an animationend is ever missed — the card sits inside a
    // display: none ancestor at that moment, or the element leaves the DOM mid-animation — the
    // class stays true, and a class already true does not change when set true again, so the
    // next answer would not animate. A new stream starting is the smallest correct place to
    // guard against this: it runs well before the next body ever lands.
    fixture.componentRef.setInput('body', 'A new pillar of the theory.');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(bodyEl().classList.contains('focus__body--landed')).toBe(true);
    // No animationend dispatched here, on purpose — the class is left stuck true.

    fixture.componentRef.setInput('isStreaming', true);
    fixture.detectChanges();
    expect(bodyEl().classList.contains('focus__body--landed')).toBe(false);

    fixture.componentRef.setInput('isStreaming', false);
    fixture.componentRef.setInput('body', 'A second pillar of the theory.');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(bodyEl().classList.contains('focus__body--landed')).toBe(true);
  });

  it('keeps the selection offsets correct while the landed class is still on the paragraph', async () => {
    // The proof the class comment above the template asks for: a class binding touches no text
    // node, so the offsets `selectionToSpan` reads must be exactly as correct while the landed
    // class is on the paragraph as they are at any other time.
    fixture.componentRef.setInput('body', 'A new pillar of the theory.');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(bodyEl().classList.contains('focus__body--landed')).toBe(true);

    select(6, 12); // "pillar"

    expect(pickerLive()).toBe(true);
    verbButton('EXPLAIN')!.click();
    expect(requests).toEqual([{ span: { text: 'pillar', start: 6, end: 12 }, verb: 'EXPLAIN' }]);
  });

  it('clears its pending timer on destroy, so a card the learner has left writes to nothing', () => {
    vi.useFakeTimers();

    fixture.componentRef.setInput('isStreaming', true);
    fixture.detectChanges();
    fixture.componentRef.setInput('isStreaming', false);
    fixture.detectChanges();

    // The fake clock counts its own pending timers, so this test needs no spy on a global timer
    // function. A spy on `globalThis.setTimeout` that a test installs after `vi.useFakeTimers()`
    // wraps the fake function. `vi.useRealTimers()` then cannot put the real function back, and a
    // later restore of the spy leaves the fake function of a dead clock as the global
    // `setTimeout`. Each later `setTimeout` in the same worker then never fires (issue #151).
    expect(vi.getTimerCount()).toBe(1);

    fixture.destroy();

    expect(vi.getTimerCount()).toBe(0);
  });
});
