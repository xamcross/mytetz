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

/** A session is `COMPLETED` once a learner ends it, or once 30 days pass with no activity on it.
 * The backend decides which on every read — see `SessionService.statusOf` — so the client only
 * ever reads the field and never derives it. */
export type SessionStatus = 'ACTIVE' | 'COMPLETED';

/** One diagram. `kind` names the diagram format. `MERMAID` is reserved for a later slice; today
 * the server only ever sends `SVG`. `source` is the sanitised SVG markup itself. */
export interface DiagramMedia {
  kind: 'SVG' | 'MERMAID';
  source: string;
}

/** One licensed image from Wikimedia Commons. `attributionHtml` is a small HTML string the
 * server built itself from Commons metadata — never a pass-through of a Commons editor's own
 * markup. The UI must always show it next to the image; this is a licence obligation. */
export interface ImageMedia {
  imageUrl: string;
  title: string;
  license: string;
  attributionHtml: string;
  commonsPageUrl: string;
}

/** The media a `VISUALIZE` explanation carries. `diagram` is always present. `image` is `null`
 * when Wikimedia Commons found no licensed image for the span; the diagram still renders alone. */
export interface Media {
  diagram: DiagramMedia;
  image: ImageMedia | null;
}

export interface SessionView {
  sessionId: string;
  topicSlug: string;
  rootNodeId: string;
  currentNodeId: string;
  nodes: NodeView[];
  status: SessionStatus;
  explanations: Record<string, string>;
  /** Sparse: an entry exists only for a content key whose explanation is a `VISUALIZE` answer.
   * Optional, and not just empty, because a session with no `VISUALIZE` node may omit the field
   * from the wire entirely — the same "a client that never asks for VISUALIZE pays nothing for the
   * new field" rule the field itself exists to honour. */
  media?: Record<string, Media>;
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
 * The body of `GET /api/billing/plans` — mirrors the backend's `BillingPlansResponse`, field for
 * field.
 *
 * `priceUsdPerMonth` is one whole number of US dollars, and never a fraction. `trialDays` and
 * `trialGenerations` describe the free trial. `subscriberDailyExplains` is the daily allowance a
 * paid plan gives. `SubscribePageComponent` reads every field; none has a default here, because
 * the backend's own response class has none — see `BillingPlansResponse`'s own KDoc.
 */
export interface BillingPlansView {
  priceUsdPerMonth: number;
  trialDays: number;
  trialGenerations: number;
  subscriberDailyExplains: number;
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
