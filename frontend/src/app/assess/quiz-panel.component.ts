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
} from '@angular/core';
import { ApiService } from '../core/api.service';
import { QuizAnswerPayload, QuizKind, QuizQuestionView, QuizResultView } from '../core/models';

type QuizPhase = 'loading' | 'question' | 'result';

/**
 * One flow for both quiz kinds: Test Me and Exam. It loads a template, collects one answer for
 * every question, submits the whole list once, and only then shows the result.
 *
 * The design mockup shows a question judged the instant it is answered. The real endpoint does
 * not support that: `POST /api/sessions/{id}/quizzes/{attemptId}/answers` scores a whole
 * `answers[]` array in one call, and there is no route that scores one question at a time. So
 * this component never claims an answer is right or wrong before that call returns. A learner
 * moves through every question with Next, then Finish, and sees every result together.
 *
 * This is a dialog, and it holds the same three keyboard behaviours as `VerbPickerComponent`.
 * Escape closes it. Tab and Shift+Tab keep focus inside it. Focus moves in when it opens, and
 * back to whatever opened it once it closes. Unlike the picker, this panel's own content changes
 * over time: first loading, then a question, then a result or an error. So the focus-trap query
 * and the initial focus move both read the panel's current buttons, rather than a fixed list.
 */
@Component({
  selector: 'app-quiz-panel',
  template: `
    <div
      class="quiz-panel mt-card mt-card--raised"
      role="dialog"
      aria-modal="true"
      tabindex="-1"
      [attr.aria-label]="title()"
      (keydown.escape)="close.emit()"
      (keydown.tab)="onTab($event)"
      (keydown.shift.tab)="onTab($event)"
    >
      @if (error(); as message) {
        <p class="quiz-panel__error" role="alert">{{ message }}</p>
        <button type="button" class="mt-pill mt-pill--ghost" (click)="close.emit()">Close</button>
      } @else if (phase() === 'loading') {
        <p role="status">Preparing your questions…</p>
      } @else if (phase() === 'question') {
        <span class="mt-eyebrow"
          >{{ title() }} · question {{ currentIndex() + 1 }} of {{ questions().length }}</span
        >
        <p class="quiz-panel__stem">{{ currentQuestion()?.stem }}</p>
        <div class="quiz-panel__options">
          @for (option of currentQuestion()?.options ?? []; track $index) {
            <button
              type="button"
              class="quiz-panel__option"
              [class.quiz-panel__option--chosen]="chosenIndex() === $index"
              [attr.aria-pressed]="chosenIndex() === $index"
              (click)="choose($index)"
            >
              @if (chosenIndex() === $index) {
                <!-- aria-pressed already states this fact for a screen reader, so the glyph
                     itself stays hidden from one. -->
                <span class="quiz-panel__check" aria-hidden="true">✓</span>
              }
              {{ option }}
            </button>
          }
        </div>
        <div class="quiz-panel__actions">
          <button type="button" class="mt-pill mt-pill--ghost" (click)="close.emit()">Leave</button>
          <button
            type="button"
            class="mt-pill mt-pill--coral"
            [disabled]="!answered()"
            (click)="next()"
          >
            {{ isLastQuestion() ? 'See results' : 'Next question' }}
          </button>
        </div>
      } @else if (phase() === 'result') {
        <h2 class="quiz-panel__score">{{ result()?.score }} / {{ result()?.total }}</h2>
        <ul class="quiz-panel__review">
          @for (question of questions(); track question.questionId) {
            <li>
              <p class="quiz-panel__review-stem">{{ question.stem }}</p>
              <p>
                @if (isCorrect(question.questionId)) {
                  Correct.
                } @else {
                  Correct answer: {{ correctOption(question) }}
                }
              </p>
              <p class="quiz-panel__rationale">{{ result()?.rationales?.[question.questionId] }}</p>
            </li>
          }
        </ul>
        <button type="button" class="mt-pill mt-pill--ghost" (click)="close.emit()">Close</button>
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .quiz-panel {
        display: flex;
        flex-direction: column;
        gap: 16px;
        padding: 32px 36px;
        max-width: 560px;
      }
      .quiz-panel__stem {
        margin: 0;
        font-size: 19px;
        font-weight: 600;
        color: var(--mt-ink);
      }
      .quiz-panel__options {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      /* Issue #103, finding F10. A pill is a one-line primitive: line-height: 1 and a 999px
         radius. A quiz option is prose, and a long one must wrap onto a second line with no
         overlap, so it draws its own block instead of reusing .mt-pill. */
      .quiz-panel__option {
        display: block;
        width: 100%;
        text-align: left;
        padding: 13px 15px;
        border: var(--mt-border-w) solid var(--mt-edge);
        border-radius: var(--mt-r-row);
        background: var(--mt-surface);
        color: var(--mt-ink);
        font: inherit;
        font-size: 15px;
        font-weight: 600;
        line-height: 1.45;
        transition: background var(--mt-dur-press) var(--mt-ease-press);
      }
      /* (hover: hover) and not a plain :hover: see styles.css's own comment on .mt-pill:hover
         for why a touch screen needs this guard. */
      @media (hover: hover) {
        .quiz-panel__option:hover:not(:disabled) {
          background: var(--mt-sunk);
        }
      }
      .quiz-panel__option:active:not(:disabled) {
        background: var(--mt-chip);
      }
      .quiz-panel__option:disabled {
        opacity: 0.55;
        cursor: not-allowed;
      }
      /* aria-pressed already states this for a screen reader. A sighted learner with low vision
         needs a signal that is not a colour too: a heavier edge, plus the check glyph below. The
         amber fill stays, but it is no longer the only signal. */
      .quiz-panel__option--chosen {
        background: var(--mt-amber-bg);
        border-color: var(--mt-amber);
        border-width: 3px;
        color: var(--mt-amber-ink);
      }
      .quiz-panel__check {
        display: inline-block;
        margin-right: 6px;
        font-weight: 800;
        color: var(--mt-amber-ink);
      }
      .quiz-panel__actions {
        display: flex;
        justify-content: space-between;
        gap: 12px;
      }
      .quiz-panel__score {
        margin: 0;
        font-size: 28px;
      }
      .quiz-panel__review {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .quiz-panel__review-stem {
        margin: 0;
        font-weight: 600;
        color: var(--mt-ink);
      }
      .quiz-panel__rationale {
        margin: 0;
        color: var(--mt-muted);
      }
      .quiz-panel__error {
        margin: 0;
        font-weight: 700;
        color: var(--mt-err-ink);
      }
    `,
  ],
})
export class QuizPanelComponent {
  private readonly api = inject(ApiService);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly destroyRef = inject(DestroyRef);

  readonly sessionId = input.required<string>();
  readonly kind = input.required<QuizKind>();
  readonly nodeId = input<string | null>(null);
  readonly close = output<void>();

  readonly phase = signal<QuizPhase>('loading');
  readonly error = signal<string | null>(null);
  readonly questions = signal<QuizQuestionView[]>([]);
  readonly currentIndex = signal(0);
  readonly chosenIndex = signal<number | null>(null);
  readonly result = signal<QuizResultView | null>(null);
  private attemptId: string | null = null;
  /** Every answer given so far, keyed by question id. A `Map` keeps insertion order, which is the
   * order the questions were asked in, so the submitted list reads in the same order as the quiz. */
  private readonly answersGiven = new Map<string, number>();

  readonly title = computed(() => (this.kind() === 'EXAM' ? 'Exam' : 'Test me'));
  readonly currentQuestion = computed<QuizQuestionView | null>(
    () => this.questions()[this.currentIndex()] ?? null,
  );
  readonly answered = computed(() => this.chosenIndex() !== null);
  readonly isLastQuestion = computed(() => this.currentIndex() === this.questions().length - 1);

  constructor() {
    effect(() => {
      // Reads sessionId/kind/nodeId so a caller that changes which quiz it wants gets a fresh
      // load. In practice a new component instance is created per quiz, the same as one
      // `SessionStore` per session.
      this.sessionId();
      this.kind();
      this.nodeId();
      void this.start();
    });

    // Read before the effect above renders anything of this panel's own. It therefore names
    // whatever the learner had focused a moment ago, ordinarily the button that opened this
    // panel. Read from `this.host`'s own document, and not from `document` directly, because a
    // test's fixture uses that document.
    const trigger = this.host.nativeElement.ownerDocument?.activeElement;
    this.destroyRef.onDestroy(() => {
      if (trigger instanceof HTMLElement && trigger !== trigger.ownerDocument.body) trigger.focus();
    });

    // Runs after every render this panel's own signals cause, not once: the panel's first render
    // is the loading phase, which holds no button at all, so the button a learner should land on
    // does not exist yet at that point. `tabindex="-1"` on the dialog root is the fallback for
    // exactly that render.
    afterRenderEffect({
      read: () => {
        this.phase();
        const panel = this.host.nativeElement;
        const active = panel.ownerDocument.activeElement;
        if (active instanceof HTMLElement && panel.contains(active)) return;
        (this.focusableElements()[0] ?? panel).focus();
      },
    });
  }

  /** Every element inside this panel a learner can currently reach with the keyboard. Queried
   * fresh on every call, rather than cached, because which buttons exist changes with `phase()`
   * and a disabled Next button must never be one of them. */
  private focusableElements(): HTMLElement[] {
    const buttons = this.host.nativeElement.querySelectorAll('button:not([disabled])');
    return Array.from(buttons) as HTMLElement[];
  }

  /**
   * Keeps Tab inside the panel, the same trap `VerbPickerComponent.onTab` runs. Without this, Tab
   * walks into the page behind what claims to be a modal dialog.
   */
  onTab(event: Event): void {
    const key = event as KeyboardEvent;
    const elements = this.focusableElements();
    if (elements.length === 0) return;
    const first = elements[0];
    const last = elements[elements.length - 1];
    const active = this.host.nativeElement.ownerDocument.activeElement;
    if (key.shiftKey && active === first) {
      key.preventDefault();
      last.focus();
    } else if (!key.shiftKey && active === last) {
      key.preventDefault();
      first.focus();
    }
  }

  private async start(): Promise<void> {
    this.phase.set('loading');
    this.error.set(null);
    this.answersGiven.clear();
    try {
      const template = await this.api.startQuiz(
        this.sessionId(),
        this.kind(),
        this.nodeId() ?? undefined,
      );
      this.attemptId = template.attemptId;
      this.questions.set(template.questions);
      this.currentIndex.set(0);
      this.chosenIndex.set(null);
      this.phase.set('question');
    } catch {
      this.error.set('This quiz could not be started. Try again in a moment.');
    }
  }

  choose(index: number): void {
    this.chosenIndex.set(index);
    const question = this.currentQuestion();
    if (question) this.answersGiven.set(question.questionId, index);
  }

  async next(): Promise<void> {
    if (!this.answered()) return;
    if (!this.isLastQuestion()) {
      this.currentIndex.update((i) => i + 1);
      const nextQuestion = this.questions()[this.currentIndex()];
      this.chosenIndex.set(this.answersGiven.get(nextQuestion.questionId) ?? null);
      return;
    }
    await this.submit();
  }

  private async submit(): Promise<void> {
    const attemptId = this.attemptId;
    if (attemptId === null) return;
    const answers: QuizAnswerPayload[] = Array.from(
      this.answersGiven,
      ([questionId, chosenIndex]) => ({ questionId, chosenIndex }),
    );
    try {
      const result = await this.api.submitQuizAnswers(this.sessionId(), attemptId, answers);
      this.result.set(result);
      this.phase.set('result');
    } catch {
      this.error.set('Your answers could not be scored. Try again in a moment.');
    }
  }

  isCorrect(questionId: string): boolean {
    return this.answersGiven.get(questionId) === this.result()?.correctIndices[questionId];
  }

  correctOption(question: QuizQuestionView): string {
    const index = this.result()?.correctIndices[question.questionId];
    return index === undefined ? '' : (question.options[index] ?? '');
  }
}
