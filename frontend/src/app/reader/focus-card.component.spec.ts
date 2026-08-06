import { ErrorHandler } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FocusCardComponent } from './focus-card.component';
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

  afterEach(() => window.getSelection()?.removeAllRanges());

  const bodyEl = (): HTMLElement => fixture.nativeElement.querySelector('.focus__body');
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

  it('disables every verb while a generation is streaming', async () => {
    select(4, 11);
    fixture.componentRef.setInput('isStreaming', true);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(pickerLive()).toBe(false);
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
});
