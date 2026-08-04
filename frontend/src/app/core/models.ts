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
