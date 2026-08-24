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
