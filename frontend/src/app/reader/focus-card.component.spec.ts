import { ErrorHandler } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { FocusCardComponent } from './focus-card.component';
import { SessionStore } from './session.store';
import { rootTextMatchesBody } from './selection';
import { SpanPayload, Verb } from '../core/models';

const BODY = 'The pillars of modern physics.';

describe('FocusCardComponent', () => {
  let fixture: ComponentFixture<FocusCardComponent>;
  let requests: Array<{ span: SpanPayload; verb: Verb }>;
  /** Anything Angular reported while handling an event — a listener that throws never reaches
   * `dispatchEvent`'s caller, so this is the only place a handler crash is visible. */
  let errors: unknown[];

  beforeEach(async () => {
    errors = [];
    TestBed.configureTestingModule({
      imports: [FocusCardComponent],
      providers: [
        { provide: ErrorHandler, useValue: { handleError: (e: unknown) => errors.push(e) } },
        // Only one spec below injects `SessionStore`, but the module can be configured once per
        // test, before the first `TestBed.inject`. Adding the providers here, rather than inside
        // that one test, keeps every test on the same setup.
        SessionStore,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    fixture = TestBed.createComponent(FocusCardComponent);
    requests = [];
    fixture.componentInstance.explainRequested.subscribe((r) => requests.push(r));
    fixture.componentRef.setInput('body', BODY);
    fixture.componentRef.setInput('streamingText', '');
    fixture.componentRef.setInput('isStreaming', false);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => {
    window.getSelection()?.removeAllRanges();
    // A test below may turn on fake timers. Real timers are the default for every other test in
    // this file, so each test leaves the clock the way it found it.
    vi.useRealTimers();
  });

  const bodyEl = (): HTMLElement => fixture.nativeElement.querySelector('.focus__body');
  const statusEl = (): HTMLElement => fixture.nativeElement.querySelector('.focus__stream-status');
  const verbButton = (verb: Verb): HTMLButtonElement | null =>
    fixture.nativeElement.querySelector(`button[data-verb="${verb}"]`);
  /** True when the picker is on screen, which is the only time a verb can be pressed. */
  const pickerLive = (): boolean => verbButton('EXPLAIN') !== null;

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

  it('renders the topic label as the card heading', () => {
    // `topicLabel` defaults to `''`. A caller that forgets the binding therefore renders an empty
    // <h1> and nothing reports it — that defect shipped once already, past the whole suite. This
    // component owns the risky default, so the guard lives here.
    fixture.componentRef.setInput('topicLabel', 'Quantum Physics');
    fixture.detectChanges();
    const heading: HTMLElement = fixture.nativeElement.querySelector('.focus__topic');
    expect(heading).not.toBeNull();
    expect(heading.textContent?.trim()).toBe('Quantum Physics');
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

  it('emits testMeRequested when the Test me control is pressed, and never touches the body text', () => {
    const spy = vi.fn();
    fixture.componentInstance.testMeRequested.subscribe(spy);
    const button: HTMLButtonElement =
      fixture.nativeElement.querySelector('[data-testid="test-me"]');

    button.click();

    expect(spy).toHaveBeenCalled();
    // The button is a sibling of the paragraph, not a child of it. A button inside
    // `.focus__body` would add its own text to the string the offsets index.
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

  it('clears its pending timer on destroy, so a card the learner has left writes to nothing', () => {
    vi.useFakeTimers();
    const setTimeoutSpy = vi.spyOn(globalThis, 'setTimeout');

    fixture.componentRef.setInput('isStreaming', true);
    fixture.detectChanges();
    fixture.componentRef.setInput('isStreaming', false);
    fixture.detectChanges();

    // Finds the exact timer this component started for the "ready" text, by its own delay, so
    // the assertion below cannot pass on an unrelated `clearTimeout` call from somewhere else.
    const readyTimerCallIndex = setTimeoutSpy.mock.calls.findIndex(([, delay]) => delay === 4000);
    expect(readyTimerCallIndex).toBeGreaterThanOrEqual(0);
    const readyTimerId = setTimeoutSpy.mock.results[readyTimerCallIndex].value;

    const clearTimeoutSpy = vi.spyOn(globalThis, 'clearTimeout');
    fixture.destroy();

    expect(clearTimeoutSpy).toHaveBeenCalledWith(readyTimerId);
  });
});
