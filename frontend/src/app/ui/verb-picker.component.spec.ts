import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Verb } from '../core/models';
import { PickerDismissal, VerbPickerComponent } from './verb-picker.component';

/**
 * The picker replaces a static row of four buttons. The row was always on screen and was disabled
 * until a phrase was highlighted. The picker is not on screen at all until then, so "the
 * affordance is not live" is now "the picker is absent" and no longer "the button is disabled".
 *
 * Nothing here asserts a position. Unit tests run in jsdom, which has no layout engine, so every
 * rect is zero. Task 8's manual pass is what proves the placement.
 */
describe('VerbPickerComponent', () => {
  let fixture: ComponentFixture<VerbPickerComponent>;
  let chosen: Verb[];
  let dismissed: number;

  let reasons: PickerDismissal[];

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [VerbPickerComponent] });
    fixture = TestBed.createComponent(VerbPickerComponent);
    chosen = [];
    dismissed = 0;
    reasons = [];
    fixture.componentInstance.chosen.subscribe((v) => chosen.push(v));
    fixture.componentInstance.dismissed.subscribe((r) => {
      dismissed += 1;
      reasons.push(r);
    });
    fixture.componentRef.setInput('span', { text: 'escape velocity', start: 4, end: 19 });
    fixture.componentRef.setInput('anchor', { top: 0, left: 0 });
    fixture.detectChanges();
    // The initial focus runs in `afterNextRender`, which needs the fixture to settle first.
    await fixture.whenStable();
  });

  const button = (verb: Verb): HTMLButtonElement =>
    fixture.nativeElement.querySelector(`button[data-verb="${verb}"]`);
  const root = (): HTMLElement => fixture.nativeElement.querySelector('[role="dialog"]');
  /** A real Tab press on whatever holds focus, which is how the trap is reached in a browser. */
  const pressTab = (shiftKey: boolean): void => {
    document.activeElement?.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true }),
    );
    fixture.detectChanges();
  };

  it('offers the four text verbs and no other', () => {
    // SEED is the session's own root and VISUALIZE is slice 4. Neither belongs to a highlight.
    const verbs = Array.from(fixture.nativeElement.querySelectorAll('button[data-verb]')).map((b) =>
      (b as HTMLElement).getAttribute('data-verb'),
    );
    expect(verbs).toEqual(['EXPLAIN', 'DIG_DEEPER', 'BROADER_PICTURE', 'SIDE_VIEW']);
  });

  it('names each verb briefly, and describes it separately', () => {
    // The caption is a description and not part of the name. Joined into the name it would read
    // "Explain it Plain words, no jargon", and it would collide with the trail rail's own
    // "Explain" rows under a getByRole query.
    const explain = button('EXPLAIN');
    expect(explain.getAttribute('aria-label')).toBe('Explain it');
    const describedBy = explain.getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    expect(fixture.nativeElement.querySelector(`#${describedBy}`).textContent).toContain(
      'Plain words',
    );
  });

  it('quotes the phrase the learner highlighted', () => {
    expect(root().textContent).toContain('escape velocity');
  });

  it('emits the verb that was pressed', () => {
    button('SIDE_VIEW').click();
    expect(chosen).toEqual(['SIDE_VIEW']);
  });

  it('is a dialog with a name', () => {
    expect(root().getAttribute('role')).toBe('dialog');
    expect(root().getAttribute('aria-label')).toBe('Explain the highlighted phrase');
  });

  it('dismisses on Escape, and says the reason was the keyboard', () => {
    root().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(dismissed).toBe(1);
    // The host returns focus to the text on this reason alone. See `PickerDismissal`.
    expect(reasons).toEqual(['escape']);
    expect(chosen).toEqual([]);
  });

  it('dismisses on a press outside itself, and says the reason was that press', () => {
    document.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    fixture.detectChanges();
    expect(dismissed).toBe(1);
    // This path runs inside `mousedown`. A host that moved focus here would cancel the drag the
    // learner has just started, so the reason keeps the two paths apart.
    expect(reasons).toEqual(['outside-press']);
  });

  it('stays open on a press inside itself', () => {
    button('EXPLAIN').dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    fixture.detectChanges();
    expect(dismissed).toBe(0);
  });

  it('moves focus to the first verb when it opens', () => {
    // §7.6 makes this load-bearing. The picker replaces a row of buttons that was always on
    // screen, so a keyboard learner reached the verbs by tabbing to them. Now the picker appears
    // under the phrase, and nothing takes the learner there unless the component does it.
    expect(document.activeElement).toBe(button('EXPLAIN'));
  });

  it('wraps Tab from the last verb back to the first', () => {
    button('SIDE_VIEW').focus();
    pressTab(false);
    expect(document.activeElement).toBe(button('EXPLAIN'));
  });

  it('wraps Shift+Tab from the first verb back to the last', () => {
    // The backward half of the trap needs its own binding: Angular builds a full key name from
    // the modifiers that are held, so `keydown.tab` alone never fires while Shift is down.
    button('EXPLAIN').focus();
    pressTab(true);
    expect(document.activeElement).toBe(button('SIDE_VIEW'));
  });

  it('leaves Tab alone away from the two ends', () => {
    // The trap holds the first and the last verb only. Between them the browser's own Tab order
    // applies, and a trap that moved focus on every press would break it.
    button('DIG_DEEPER').focus();
    pressTab(false);
    expect(document.activeElement).toBe(button('DIG_DEEPER'));
  });

  it('places itself where the anchor says', () => {
    // The value, not the resulting pixel. jsdom has no layout, so the assertion is that the
    // component passes the anchor through to CSS rather than that the browser honoured it.
    fixture.componentRef.setInput('anchor', { top: 120, left: 40 });
    fixture.detectChanges();
    expect(root().style.getPropertyValue('--picker-top')).toBe('120px');
    expect(root().style.getPropertyValue('--picker-left')).toBe('40px');
  });
});
