import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
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
 */
@Component({
  selector: 'app-quiz-panel',
  template: `
    <div
      class="quiz-panel mt-card mt-card--raised"
      role="dialog"
      aria-modal="true"
      [attr.aria-label]="title()"
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
              class="mt-pill quiz-panel__option"
              [class.quiz-panel__option--chosen]="chosenIndex() === $index"
              [attr.aria-pressed]="chosenIndex() === $index"
              (click)="choose($index)"
            >
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
      .quiz-panel__option {
        text-align: left;
        justify-content: flex-start;
      }
      .quiz-panel__option--chosen {
        background: var(--mt-amber-bg);
        border-color: var(--mt-amber);
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
