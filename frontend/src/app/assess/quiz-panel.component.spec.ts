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
});
