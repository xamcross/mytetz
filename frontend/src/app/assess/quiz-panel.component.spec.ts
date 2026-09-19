import { readFileSync } from 'node:fs';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiService } from '../core/api.service';
import { QuizResultView, QuizTemplateView } from '../core/models';
import { QuizPanelComponent } from './quiz-panel.component';

describe('QuizPanelComponent', () => {
  let fixture: ComponentFixture<QuizPanelComponent>;
  let component: QuizPanelComponent;
  let api: ApiService;

  const template: QuizTemplateView = {
    attemptId: 'a1',
    kind: 'TEST_ME',
    questions: [
      { questionId: 'q1', stem: 'Stem one', options: ['a', 'b', 'c', 'd'] },
      { questionId: 'q2', stem: 'Stem two', options: ['e', 'f', 'g', 'h'] },
    ],
  };
  const result: QuizResultView = {
    score: 1,
    total: 2,
    correctIndices: { q1: 0, q2: 1 },
    rationales: { q1: 'why one', q2: 'why two' },
  };

  /** Builds one panel with the standard inputs, and renders it. A test that needs a fresh
   * `api.startQuiz` stub calls this again after changing the stub, the same way the reader's own
   * fixtures are rebuilt in `sign-in-panel.component.spec.ts`. */
  function create(): ComponentFixture<QuizPanelComponent> {
    const created = TestBed.createComponent(QuizPanelComponent);
    created.componentRef.setInput('sessionId', 's1');
    created.componentRef.setInput('kind', 'TEST_ME');
    created.componentRef.setInput('nodeId', 'n1');
    created.detectChanges();
    return created;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [QuizPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiService);
    vi.spyOn(api, 'startQuiz').mockResolvedValue(template);
    vi.spyOn(api, 'submitQuizAnswers').mockResolvedValue(result);

    fixture = create();
    component = fixture.componentInstance;
  });

  it('loads a quiz on init and shows the first question', async () => {
    await fixture.whenStable();

    expect(api.startQuiz).toHaveBeenCalledWith('s1', 'TEST_ME', 'n1');
    expect(component.phase()).toBe('question');
    expect(component.currentQuestion()?.questionId).toBe('q1');
  });

  it('renders as a labelled dialog', async () => {
    await fixture.whenStable();
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('[role="dialog"]') as HTMLElement;
    expect(dialog).toBeTruthy();
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-label')).toBeTruthy();
  });

  it('moves focus into the panel once a question loads', async () => {
    await fixture.whenStable();
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('[role="dialog"]') as HTMLElement;
    expect(dialog.contains(document.activeElement)).toBe(true);
  });

  it('closes on Escape, the same as VerbPickerComponent', async () => {
    const closed: void[] = [];
    component.close.subscribe(() => closed.push(undefined));
    await fixture.whenStable();
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('[role="dialog"]') as HTMLElement;
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(closed.length).toBe(1);
  });

  it('keeps Tab inside the panel', async () => {
    await fixture.whenStable();
    component.choose(0);
    fixture.detectChanges();

    const options = Array.from(
      fixture.nativeElement.querySelectorAll('.quiz-panel__option'),
    ) as HTMLButtonElement[];
    const next = fixture.nativeElement.querySelector(
      '.quiz-panel__actions .mt-pill--coral',
    ) as HTMLButtonElement;

    // Tab from the last focusable element (Next, now enabled) wraps back to the first (option a).
    next.focus();
    next.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    fixture.detectChanges();
    expect(document.activeElement).toBe(options[0]);

    // Shift+Tab from the first focusable element wraps back to the last.
    options[0].dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true }),
    );
    fixture.detectChanges();
    expect(document.activeElement).toBe(next);
  });

  it('returns focus to whatever opened it, once it closes', async () => {
    const trigger = document.createElement('button');
    document.body.appendChild(trigger);
    trigger.focus();

    const opened = create();
    await opened.whenStable();
    opened.destroy();

    expect(document.activeElement).toBe(trigger);
    document.body.removeChild(trigger);
  });

  it('shows the stem and every option of the current question', async () => {
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Stem one');
    const options = Array.from(
      fixture.nativeElement.querySelectorAll('.quiz-panel__option'),
    ) as HTMLElement[];
    expect(options.map((o) => o.textContent?.trim())).toEqual(['a', 'b', 'c', 'd']);
  });

  it('moves to the next question only after an option is chosen', async () => {
    await fixture.whenStable();

    expect(component.answered()).toBe(false);
    component.choose(0);
    expect(component.answered()).toBe(true);
    await component.next();
    expect(component.currentIndex()).toBe(1);
    expect(component.currentQuestion()?.questionId).toBe('q2');
  });

  it('disables the next button until an option is chosen', async () => {
    await fixture.whenStable();
    fixture.detectChanges();

    const next = fixture.nativeElement.querySelector(
      '.quiz-panel__actions .mt-pill--coral',
    ) as HTMLButtonElement;
    expect(next.disabled).toBe(true);

    component.choose(1);
    fixture.detectChanges();
    expect(next.disabled).toBe(false);
  });

  it('labels the last question\'s button "See results"', async () => {
    await fixture.whenStable();
    component.choose(0);
    await component.next();
    fixture.detectChanges();

    const next = fixture.nativeElement.querySelector(
      '.quiz-panel__actions .mt-pill--coral',
    ) as HTMLButtonElement;
    expect(next.textContent?.trim()).toBe('See results');
  });

  it('submits every answer at once on the last question and shows the result', async () => {
    await fixture.whenStable();
    component.choose(0);
    await component.next();
    component.choose(1);
    await component.next();

    expect(api.submitQuizAnswers).toHaveBeenCalledWith('s1', 'a1', [
      { questionId: 'q1', chosenIndex: 0 },
      { questionId: 'q2', chosenIndex: 1 },
    ]);
    expect(component.phase()).toBe('result');
    expect(component.result()?.score).toBe(1);
  });

  it('reveals correctness and a rationale only after the result is in, never before', async () => {
    await fixture.whenStable();
    fixture.detectChanges();

    // Nothing on the question screen names correct or wrong — the API cannot judge one answer at a
    // time, so this component must not pretend that it can. See this component's own doc comment.
    expect(fixture.nativeElement.textContent).not.toContain('Correct');

    component.choose(0);
    await component.next();
    component.choose(1);
    await component.next();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('why one');
    expect(text).toContain('why two');
    expect(text).toContain('Correct');
  });

  it('emits close when Leave is pressed during a question', async () => {
    const closed: void[] = [];
    component.close.subscribe(() => closed.push(undefined));
    await fixture.whenStable();
    fixture.detectChanges();

    const leave = fixture.nativeElement.querySelector('.quiz-panel__actions .mt-pill--ghost');
    (leave as HTMLButtonElement).click();

    expect(closed.length).toBe(1);
  });

  it('emits close from the result screen', async () => {
    const closed: void[] = [];
    component.close.subscribe(() => closed.push(undefined));
    await fixture.whenStable();
    component.choose(0);
    await component.next();
    component.choose(1);
    await component.next();
    fixture.detectChanges();

    // `>` on purpose: a direct child of `.quiz-panel` names the one Close button on this screen,
    // and not some other ghost button a later change might add inside the review list.
    const close = fixture.nativeElement.querySelector(
      '.quiz-panel > button.mt-pill--ghost',
    ) as HTMLButtonElement;
    close.click();
    expect(closed.length).toBe(1);
  });

  it('surfaces a failure to start the quiz', async () => {
    vi.spyOn(api, 'startQuiz').mockRejectedValue(new Error('boom'));
    fixture = create();

    await fixture.whenStable();

    expect(fixture.componentInstance.error()).not.toBeNull();
  });

  it('surfaces a failure to submit the answers', async () => {
    vi.spyOn(api, 'submitQuizAnswers').mockRejectedValue(new Error('boom'));
    await fixture.whenStable();
    component.choose(0);
    await component.next();
    component.choose(1);
    await component.next();

    expect(component.error()).not.toBeNull();
    expect(component.phase()).not.toBe('result');
  });

  /**
   * Issue #103. `aria-pressed` already states the chosen option for a screen reader. A sighted
   * learner with low vision needs a second signal that does not depend on colour: a check glyph,
   * plus a heavier edge. The glyph carries `aria-hidden`, because `aria-pressed` already says the
   * same thing and a screen reader must not read it twice.
   */
  describe('the chosen option carries a second signal that is not a colour', () => {
    it('shows no check glyph until an option is chosen, then shows one, hidden from a screen reader', async () => {
      await fixture.whenStable();
      fixture.detectChanges();

      const options = () =>
        Array.from(fixture.nativeElement.querySelectorAll('.quiz-panel__option')) as HTMLElement[];

      expect(options()[0].querySelector('.quiz-panel__check')).toBeNull();

      component.choose(0);
      fixture.detectChanges();

      const check = options()[0].querySelector('.quiz-panel__check');
      expect(check).not.toBeNull();
      expect(check?.getAttribute('aria-hidden')).toBe('true');
      // Every other option stays plain.
      expect(options()[1].querySelector('.quiz-panel__check')).toBeNull();
    });

    it('keeps aria-pressed on the chosen option, alongside the glyph', async () => {
      await fixture.whenStable();
      component.choose(1);
      fixture.detectChanges();

      const options = Array.from(
        fixture.nativeElement.querySelectorAll('.quiz-panel__option'),
      ) as HTMLElement[];
      expect(options[1].getAttribute('aria-pressed')).toBe('true');
      expect(options[0].getAttribute('aria-pressed')).toBe('false');
    });

    it('gives the chosen option a thicker edge than an unchosen one, in the CSS and not only the fill', () => {
      // A colour change alone is not a second signal. The rule itself must declare a heavier
      // border, read from the real file and not copied here.
      const source = readFileSync('src/app/assess/quiz-panel.component.ts', 'utf8');
      const base = source.match(/\.quiz-panel__option\s*\{([^}]*)\}/)?.[1];
      const chosen = source.match(/\.quiz-panel__option--chosen\s*\{([^}]*)\}/)?.[1];
      if (!base) throw new Error('quiz-panel.component.ts must declare .quiz-panel__option');
      if (!chosen) {
        throw new Error('quiz-panel.component.ts must declare .quiz-panel__option--chosen');
      }
      expect(base).toMatch(/border(?:-width)?:\s*var\(--mt-border-w\)/);
      expect(chosen).toMatch(/border-width:\s*3px/);
    });
  });

  /**
   * Round 2 of issue #103. The design review builds the quiz option "from .mt-card", and .mt-card
   * carries the Candy lift. The first version of .quiz-panel__option dropped it, so the option
   * read as a flat box and not as a control a learner presses. This restores the same rest shadow,
   * hover lift, press and transition that .mt-pill already carries in styles.css.
   */
  describe('the quiz option keeps the Candy lift', () => {
    const source = readFileSync('src/app/assess/quiz-panel.component.ts', 'utf8');

    /** The body of the first CSS rule for `selector`. */
    function rule(selector: string): string {
      const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const match = source.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`));
      if (!match) throw new Error(`quiz-panel.component.ts must declare a rule for ${selector}`);
      return match[1];
    }

    /** The body of every `@media (hover: hover) { ... }` block, joined together — the same method
     * `styles.spec.ts` uses, so a hover rule outside that guard never counts here either. */
    function hoverGuardedText(): string {
      let text = '';
      let from = 0;
      for (;;) {
        const start = source.indexOf('@media (hover: hover)', from);
        if (start === -1) return text;
        const open = source.indexOf('{', start);
        let depth = 0;
        for (let i = open; i < source.length; i++) {
          if (source[i] === '{') depth++;
          if (source[i] === '}') {
            depth--;
            if (depth === 0) {
              text += source.slice(open + 1, i) + '\n';
              from = i + 1;
              break;
            }
          }
        }
      }
    }

    it('draws the rest shadow every .mt-pill carries', () => {
      expect(rule('.quiz-panel__option')).toMatch(/box-shadow:\s*var\(--mt-lift\)/);
    });

    it('names the same press-and-hover transition .mt-pill uses', () => {
      expect(rule('.quiz-panel__option')).toMatch(/var\(--mt-dur-press\)\s*var\(--mt-ease-press\)/);
    });

    it('lifts on hover, guarded by (hover: hover), the same distance and shadow as .mt-pill', () => {
      const hoverText = hoverGuardedText();
      expect(hoverText).toMatch(/\.quiz-panel__option:hover:not\(:disabled\)[^{]*\{[^}]*\}/);
      const hoverRule = hoverText.match(
        /\.quiz-panel__option:hover:not\(:disabled\)[^{]*\{([^}]*)\}/,
      )?.[1];
      if (!hoverRule) throw new Error('the hover rule must be guarded by (hover: hover)');
      expect(hoverRule).toMatch(/transform:\s*translateY\(-1px\)/);
      expect(hoverRule).toMatch(/box-shadow:\s*var\(--mt-lift-hover\)/);
    });

    it('presses down, the same distance and shadow as .mt-pill', () => {
      const active = rule('.quiz-panel__option:active:not(:disabled)');
      expect(active).toMatch(/transform:\s*translateY\(2px\)/);
      expect(active).toMatch(/box-shadow:\s*var\(--mt-press\)/);
    });

    it('draws no shadow at all once disabled, the same as a disabled .mt-pill', () => {
      expect(rule('.quiz-panel__option:disabled')).toMatch(/box-shadow:\s*none/);
    });
  });
});
