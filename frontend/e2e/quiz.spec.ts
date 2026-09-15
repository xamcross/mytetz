import { test, expect } from '@playwright/test';
import type { QuizResultView, QuizTemplateView } from '../src/app/core/models';
import { mockQuiz, openQuantumPhysicsSession, stubCatalogueAndSession } from './support';

/**
 * The end-to-end guard on Task 13's own scenario: a learner opens a Test Me quiz, answers every
 * question, and sees a score. Every backend call is stubbed, the same way `learn.spec.ts` stubs
 * the catalogue and session endpoints — see `mockQuiz` in `./support.ts`.
 *
 * The template on the wire carries three questions and no `correctIndex` on any option, which
 * matches `QuizQuestionView`'s real shape. `QuizPanelComponent` only learns which answers were
 * right once the result comes back from the answers endpoint.
 */
const TEMPLATE: QuizTemplateView = {
  attemptId: 'attempt-1',
  kind: 'TEST_ME',
  questions: [
    {
      questionId: 'q1',
      stem: 'What does quantum mechanics describe?',
      options: ['Matter and light', 'Only sound', 'Only heat', 'Only motion'],
    },
    {
      questionId: 'q2',
      stem: 'How small is the subatomic scale?',
      options: [
        'Smaller than 0.1 nanometers',
        'Larger than a metre',
        'About one centimetre',
        'About one kilometre',
      ],
    },
    {
      questionId: 'q3',
      stem: 'Which theory is fundamental and physical?',
      options: ['Quantum mechanics', 'Classical mechanics', 'Thermodynamics', 'Fluid dynamics'],
    },
  ],
};

const RESULT: QuizResultView = {
  score: 2,
  total: 3,
  correctIndices: { q1: 0, q2: 0, q3: 0 },
  rationales: {
    q1: 'Quantum mechanics describes matter and light.',
    q2: 'The subatomic scale is smaller than 0.1 nanometers.',
    q3: 'Quantum mechanics is the fundamental physical theory.',
  },
};

test('takes a Test Me quiz across three questions and sees the score', async ({ page }) => {
  await stubCatalogueAndSession(page);
  await mockQuiz(page, 's1', TEMPLATE, RESULT);

  await openQuantumPhysicsSession(page);
  await page.getByTestId('test-me').click();

  // `QuizPanelComponent`'s outer element carries `role="dialog"`, and no other dialog is open in
  // this flow (the verb picker only opens on a text selection, which this test never makes), so
  // this locator is unambiguous.
  const quiz = page.locator('[role="dialog"]');

  for (const [index, question] of TEMPLATE.questions.entries()) {
    await expect(quiz.getByText(question.stem)).toBeVisible();
    await quiz.getByRole('button', { name: question.options[0], exact: true }).click();
    const isLastQuestion = index === TEMPLATE.questions.length - 1;
    await quiz
      .getByRole('button', { name: isLastQuestion ? 'See results' : 'Next question', exact: true })
      .click();
  }

  // The result screen renders `{{ result()?.score }} / {{ result()?.total }}` as one heading.
  await expect(quiz.getByText(`${RESULT.score} / ${RESULT.total}`)).toBeVisible();
});
