export type Verb =
  'SEED' | 'EXPLAIN' | 'DIG_DEEPER' | 'BROADER_PICTURE' | 'SIDE_VIEW' | 'VISUALIZE';

export interface TopicSummary {
  slug: string;
  title: string;
  category: string;
  summary: string;
}

export interface NodeView {
  nodeId: string;
  parentNodeId: string | null;
  explanationKey: string;
  span: string;
  verb: Verb;
  variant: number;
  depth: number;
}

export interface SessionView {
  sessionId: string;
  topicSlug: string;
  rootNodeId: string;
  currentNodeId: string;
  nodes: NodeView[];
  explanations: Record<string, string>;
}

export interface SpanPayload {
  text: string;
  start: number;
  end: number;
}

/** The body of `POST /api/sessions/{id}/explain` — mirrors the backend's `ExplainRequest`
 * exactly, so a caller assembling the wrong shape fails to compile rather than failing at
 * request time. `verb` has a server-side default of `EXPLAIN` but is required here: a client
 * that means to rely on the default should say so, not omit the field by accident. */
export interface ExplainRequest {
  parentNodeId: string;
  span: SpanPayload;
  verb: Verb;
  variant?: number;
}

/**
 * The body of `GET /api/account` — mirrors the backend's `AccountView` field for field.
 *
 * [trialEndsAtEpochMillis] and [currentPeriodEndsAtEpochMillis] hold `null` until slice B2 fills
 * them. They are here now so a later task reads a typed field and not `undefined`.
 */
export interface AccountView {
  email: string;
  status: string;
  trialEndsAtEpochMillis: number | null;
  currentPeriodEndsAtEpochMillis: number | null;
  allowance: number;
  remaining: number;
  resetsAtEpochMillis: number | null;
}

/**
 * The body of `GET /api/auth/config`. It mirrors the backend's `AuthConfigView`, field for field.
 *
 * `SignInPanelComponent` reads `turnstileSiteKey` to decide whether to load the Turnstile widget.
 * It reads `googleEnabled` and `magicLinkEnabled` to hide the matching control when its method is
 * not configured.
 */
export interface AuthConfig {
  turnstileSiteKey: string | null;
  googleEnabled: boolean;
  magicLinkEnabled: boolean;
}

/** A Test Me quiz covers one node. An exam covers the whole session. */
export type QuizKind = 'TEST_ME' | 'EXAM';

/** One question from `POST /api/sessions/{id}/quizzes`. It never carries the correct answer. */
export interface QuizQuestionView {
  questionId: string;
  stem: string;
  options: string[];
}

/** The body of `POST /api/sessions/{id}/quizzes`'s response. */
export interface QuizTemplateView {
  attemptId: string;
  kind: QuizKind;
  questions: QuizQuestionView[];
}

/** One learner answer, sent inside the `answers` array to
 * `POST /api/sessions/{id}/quizzes/{attemptId}/answers`. */
export interface QuizAnswerPayload {
  questionId: string;
  chosenIndex: number;
}

/** The body of `POST /api/sessions/{id}/quizzes/{attemptId}/answers`'s response. */
export interface QuizResultView {
  score: number;
  total: number;
  correctIndices: Record<string, number>;
  rationales: Record<string, string>;
}
