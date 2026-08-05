import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { EXPLAIN_STREAM, ExplainStreamFn, SessionStore } from './session.store';
import { ExplainRequest, SessionView, SpanPayload } from '../core/models';
import { ExplainEvent, ExplainStreamError } from '../core/sse.client';

/**
 * A session with a **branch**, not a straight line — see the report's Problem F.
 *
 * The brief's fixture was `n0 → n1` and nothing else, so `breadcrumb` and `tree` could both be
 * satisfied by an implementation that returned `session.nodes` unchanged. Here `n1` and `n2` are
 * siblings under the root and `n3` hangs off `n1`, so:
 *
 * - the breadcrumb to `n1` must **exclude** `n2` (a sibling) and `n3` (a descendant) — returning
 *   every node gives four, not two;
 * - the tree's preorder (`n0, n1, n3, n2`) differs from the array's chronological order
 *   (`n0, n1, n2, n3`), so an implementation that returns the array unchanged now fails.
 */
const view: SessionView = {
  sessionId: 's1',
  topicSlug: 'quantum-physics',
  rootNodeId: 'n0',
  currentNodeId: 'n1',
  nodes: [
    {
      nodeId: 'n0',
      parentNodeId: null,
      explanationKey: 'k0',
      span: '',
      verb: 'SEED',
      variant: 0,
      depth: 0,
    },
    {
      nodeId: 'n1',
      parentNodeId: 'n0',
      explanationKey: 'k1',
      span: 'fundamental physical theory',
      verb: 'EXPLAIN',
      variant: 0,
      depth: 1,
    },
    {
      nodeId: 'n2',
      parentNodeId: 'n0',
      explanationKey: 'k2',
      span: 'classical mechanics',
      verb: 'SIDE_VIEW',
      variant: 0,
      depth: 1,
    },
    {
      nodeId: 'n3',
      parentNodeId: 'n1',
      explanationKey: 'k3',
      span: 'wave function',
      verb: 'DIG_DEEPER',
      variant: 0,
      depth: 2,
    },
  ],
  explanations: {
    k0: 'Quantum mechanics is…',
    k1: 'The pillars of modern physics…',
    k2: 'Classical mechanics assumes…',
    k3: 'A wave function assigns an amplitude…',
  },
};

/** What `GET /api/sessions/s1` returns after an explain under `n1` has been stored. The server
 * moves `currentNodeId` onto the appended node itself (`SessionRepository.appendNode` sets it in
 * the same update that pushes the node), so this mirrors the real response rather than inventing
 * a shape. */
const viewAfterExplain: SessionView = {
  ...view,
  currentNodeId: 'n4',
  nodes: [
    ...view.nodes,
    {
      nodeId: 'n4',
      parentNodeId: 'n1',
      explanationKey: 'k4',
      span: 'pillars',
      verb: 'EXPLAIN',
      variant: 0,
      depth: 2,
    },
  ],
  explanations: { ...view.explanations, k4: 'The four pillars are…' },
};

const span: SpanPayload = { text: 'pillars', start: 4, end: 11 };

const delta = (t: string): ExplainEvent => ({ event: 'delta', data: { t } });
const meta = (contentKey: string): ExplainEvent => ({
  event: 'meta',
  data: { contentKey, cached: false },
});
const superseded = (body: string): ExplainEvent => ({ event: 'superseded', data: { body } });
const done = (contentKey: string): ExplainEvent => ({
  event: 'done',
  data: { contentKey, grounded: true },
});

/** Drains the microtask queue, which is what an in-flight `for await` over an async generator
 * needs in order to reach its end and issue the `done` re-fetch. */
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

describe('SessionStore', () => {
  let store: SessionStore;
  let http: HttpTestingController;
  /** Every `(sessionId, body)` the store passed to `explainStream`. */
  let streamCalls: Array<{ sessionId: string; body: ExplainRequest }>;
  /** The event sequence the test under test wants delivered — see the report's Problem E. Set per
   * test; the default fails loudly rather than silently streaming nothing. */
  let script: ExplainStreamFn;

  beforeEach(() => {
    streamCalls = [];
    script = () => {
      throw new Error('this test called explain() without installing a stream script');
    };

    TestBed.configureTestingModule({
      providers: [
        SessionStore,
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          // The seam Problem E asks for: the store's orchestration is driven by a supplied event
          // sequence, so the state machine, the error mapping, the `superseded` replacement and the
          // re-fetch are all unit-covered without a fake `fetch`. The real network path stays with
          // Task 1.17.
          provide: EXPLAIN_STREAM,
          useValue: ((sessionId, body, signal) => {
            streamCalls.push({ sessionId, body });
            return script(sessionId, body, signal);
          }) satisfies ExplainStreamFn,
        },
      ],
    });
    store = TestBed.inject(SessionStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function loadSession(response: SessionView = view): Promise<void> {
    const loaded = store.load('s1');
    http.expectOne('/api/sessions/s1').flush(response);
    await loaded;
  }

  it('loads a session and exposes the current body', async () => {
    await loadSession();

    expect(store.currentBody()).toBe('The pillars of modern physics…');
    // The brief stopped at the body. Also pinned: the store adopts the *server's* idea of where the
    // learner is, rather than defaulting to the root or to the last node in the array — n1 is
    // neither.
    expect(store.currentNodeId()).toBe('n1');
    expect(store.session()?.topicSlug).toBe('quantum-physics');
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeNull();
  });

  it('builds a root-first breadcrumb along one branch only', async () => {
    await loadSession();

    expect(store.breadcrumb().map((n) => n.nodeId)).toEqual(['n0', 'n1']);

    // The part the brief's straight-line fixture could not express: moving to the *other* branch
    // must re-derive the path, not extend or truncate the previous one. An implementation
    // returning `nodes` up to the current index would answer ['n0','n1','n2'] here.
    store.goTo('n2');
    expect(store.breadcrumb().map((n) => n.nodeId)).toEqual(['n0', 'n2']);
  });

  it('goTo moves the focus without a network call', async () => {
    await loadSession();

    store.goTo('n0');

    expect(store.currentBody()).toBe('Quantum mechanics is…');
    expect(store.breadcrumb().map((n) => n.nodeId)).toEqual(['n0']);
    http.verify();
  });

  it('ignores goTo for a node that is not in this session', async () => {
    await loadSession();

    store.goTo('nope');

    // Left where it was rather than blanking the reader: a focus id with no node behind it renders
    // an empty card, which is indistinguishable from an explanation that came back empty.
    expect(store.currentNodeId()).toBe('n1');
    expect(store.currentBody()).toBe('The pillars of modern physics…');
  });

  it('exposes the trail as a parent-ordered tree', async () => {
    await loadSession();

    // Preorder, so each node is immediately followed by its own descendants. The rail indents by
    // depth, and in the array's chronological order (n0, n1, n2, n3 → depths 0,1,1,2) n3 would be
    // drawn one level under n2 — which is not its parent. This ordering is what makes the
    // indentation mean parentage.
    expect(store.tree().map((n) => n.depth)).toEqual([0, 1, 2, 1]);
    expect(store.tree().map((n) => n.nodeId)).toEqual(['n0', 'n1', 'n3', 'n2']);
  });

  it('keeps a node whose parent is missing rather than dropping it from the trail', async () => {
    await loadSession({
      ...view,
      nodes: [
        ...view.nodes,
        {
          nodeId: 'orphan',
          parentNodeId: 'gone',
          explanationKey: 'k0',
          span: 'stray',
          verb: 'EXPLAIN',
          variant: 0,
          depth: 1,
        },
      ],
    });

    // A walk from the root alone would silently omit it, and a rail that silently omits part of the
    // learner's trail is a branch they can no longer navigate back to.
    expect(store.tree().map((n) => n.nodeId)).toEqual(['n0', 'n1', 'n3', 'n2', 'orphan']);
  });

  it('streams deltas into streamingText as they arrive, then re-fetches and moves the focus', async () => {
    const seen: string[] = [];
    script = async function* () {
      yield meta('k4');
      yield delta('The four ');
      seen.push(store.streamingText());
      yield delta('pillars are…');
      seen.push(store.streamingText());
      yield done('k4');
    };

    await loadSession();
    const explaining = store.explain(span, 'EXPLAIN');
    await tick();

    // Progressive, not all-at-once: each snapshot was taken between two yields, so it reflects what
    // the learner would have seen at that instant.
    expect(seen).toEqual(['The four ', 'The four pillars are…']);
    expect(streamCalls).toEqual([
      { sessionId: 's1', body: { parentNodeId: 'n1', span, verb: 'EXPLAIN' } },
    ]);

    http.expectOne('/api/sessions/s1').flush(viewAfterExplain);
    await explaining;

    expect(store.currentNodeId()).toBe('n4');
    expect(store.currentBody()).toBe('The four pillars are…');
    expect(store.streamingText()).toBe('');
    expect(store.isStreaming()).toBe(false);
    expect(store.error()).toBeNull();
  });

  it('replaces the streamed text when a superseded event arrives', async () => {
    let afterSuperseded = '';
    script = async function* () {
      yield delta('this instance sampled ');
      yield delta('prose that was never stored');
      yield superseded('The stored body, which is what a quiz will be built from.');
      afterSuperseded = store.streamingText();
      yield done('k4');
    };

    await loadSession();
    const explaining = store.explain(span, 'EXPLAIN');
    await tick();
    http.expectOne('/api/sessions/s1').flush(viewAfterExplain);
    await explaining;

    // Replace, not append. Appending would leave the learner reading text the store does not hold,
    // and quiz/exam generation reads the stored body.
    expect(afterSuperseded).toBe('The stored body, which is what a quiz will be built from.');
    expect(afterSuperseded).not.toContain('this instance sampled');
  });

  it('reports a pre-stream refusal without discarding anything, and does not re-fetch', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError(
        'QUOTA_EXCEEDED',
        "you have used today's allowance",
        3600,
        false,
      );
    };

    await loadSession();
    await store.explain(span, 'EXPLAIN');

    expect(store.error()).toEqual({
      kind: 'explain',
      code: 'QUOTA_EXCEEDED',
      message: "you have used today's allowance",
      retryAfter: 3600,
      discardedText: false,
      retryable: false,
    });
    expect(store.streamingText()).toBe('');
    expect(store.isStreaming()).toBe(false);
    // Nothing was generated, so the focus must not move and the session must not be re-read —
    // afterEach's http.verify() fails if a re-fetch went out.
    expect(store.currentNodeId()).toBe('n1');
  });

  it('discards partially streamed prose when the stream fails mid-generation', async () => {
    let beforeThrow = '';
    script = async function* (): AsyncGenerator<ExplainEvent> {
      yield delta('half an answer');
      beforeThrow = store.streamingText();
      throw new ExplainStreamError(
        'GENERATION_FAILED',
        'the explanation could not be generated; try again',
        null,
        true,
      );
    };

    await loadSession();
    await store.explain(span, 'EXPLAIN');

    // The distinction the two branches turn on: here there genuinely was text on screen, and it is
    // gone afterwards. It is not stored anywhere server-side, and leaving it up would let the
    // learner select against prose no explanation body matches (every such span is SPAN_MISMATCH).
    expect(beforeThrow).toBe('half an answer');
    expect(store.streamingText()).toBe('');
    expect(store.error()?.discardedText).toBe(true);
    expect(store.error()?.code).toBe('GENERATION_FAILED');
    // GENERATION_FAILED is the one explain failure a plain "Retry" can act on.
    expect(store.error()?.retryable).toBe(true);
    expect(store.isStreaming()).toBe(false);
    expect(store.currentNodeId()).toBe('n1');
  });

  it('does not let an abandoned generation clear the streaming flag for the one that replaced it', async () => {
    let releaseAbandoned!: () => void;
    const abandonedGate = new Promise<void>((resolve) => (releaseAbandoned = resolve));
    script = async function* () {
      yield delta('session one');
      await abandonedGate;
      yield done('k4');
    };

    await loadSession();
    const abandoned = store.explain(span, 'EXPLAIN');
    await tick();

    // Loading a session abandons the generation in flight, but the abandoned generator only unwinds
    // when its own fetch gets round to rejecting — which can be long after the learner has started
    // something else in the same, reused component.
    const reloading = store.load('s1');
    http.expectOne('/api/sessions/s1').flush(view);
    await reloading;

    let releaseCurrent!: () => void;
    const currentGate = new Promise<void>((resolve) => (releaseCurrent = resolve));
    script = async function* () {
      yield delta('session two');
      await currentGate;
      yield done('k4');
    };
    const current = store.explain(span, 'EXPLAIN');
    await tick();
    expect(store.isStreaming()).toBe(true);

    releaseAbandoned();
    await tick();

    // The property: the dead generation's `finally` must not report "nothing is streaming" over a
    // live one. It would leave the card's caret and its disabled verbs contradicting each other
    // while text was still arriving.
    expect(store.isStreaming()).toBe(true);
    expect(store.streamingText()).toBe('session two');

    releaseCurrent();
    await tick();
    http.expectOne('/api/sessions/s1').flush(viewAfterExplain);
    await current;
    await abandoned;

    expect(store.isStreaming()).toBe(false);
  });

  it('does not claim prose was discarded when the failure landed before any arrived', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      yield meta('k4');
      // `partiallyStreamed` is true here and says nothing useful: it means `explainStream` yielded
      // at least one event, and the server emits `meta` *before* the model call. So the ordinary
      // shape of a failed generation — meta, then error — arrives with the flag set and an empty
      // card. Telling the learner their partial answer was withdrawn, when none was ever shown, is
      // the one distinction Critical C exists to draw, drawn wrongly.
      throw new ExplainStreamError('GENERATION_FAILED', 'could not generate', null, true);
    };

    await loadSession();
    await store.explain(span, 'EXPLAIN');

    expect(store.error()?.code).toBe('GENERATION_FAILED');
    expect(store.error()?.discardedText).toBe(false);
  });

  it('treats a stream that ends without done as a failure rather than a success', async () => {
    script = async function* () {
      yield delta('half an answer');
    };

    await loadSession();
    await store.explain(span, 'EXPLAIN');

    // Without this the reader would settle back to "not streaming", with no error, no new node and
    // no explanation of where the half-written answer went.
    expect(store.error()?.code).toBe('STREAM_TRUNCATED');
    expect(store.error()?.retryable).toBe(true);
    expect(store.streamingText()).toBe('');
    expect(store.isStreaming()).toBe(false);
    expect(store.currentNodeId()).toBe('n1');
  });

  it('ignores a second explain while one is already streaming', async () => {
    let release!: () => void;
    const held = new Promise<void>((resolve) => (release = resolve));
    script = async function* () {
      yield delta('first');
      await held;
      yield done('k4');
    };

    await loadSession();
    const explaining = store.explain(span, 'EXPLAIN');
    await tick();

    // A second explain would be a second paid generation against the learner's daily allowance,
    // for a span they highlighted once.
    await store.explain({ text: 'modern', start: 15, end: 21 }, 'DIG_DEEPER');
    expect(streamCalls.length).toBe(1);

    release();
    await tick();
    http.expectOne('/api/sessions/s1').flush(viewAfterExplain);
    await explaining;
  });

  it('retry re-issues the last explain after a GENERATION_FAILED refusal', async () => {
    script = async function* (): AsyncGenerator<ExplainEvent> {
      throw new ExplainStreamError('GENERATION_FAILED', 'could not generate', null, false);
    };

    await loadSession();
    await store.explain(span, 'EXPLAIN');
    expect(store.error()?.retryable).toBe(true);

    script = async function* () {
      yield delta('The four pillars are…');
      yield done('k4');
    };
    const retrying = store.retry();
    await tick();
    http.expectOne('/api/sessions/s1').flush(viewAfterExplain);
    await retrying;

    // The same span and verb, not a fresh guess at either.
    expect(streamCalls.map((c) => c.body)).toEqual([
      { parentNodeId: 'n1', span, verb: 'EXPLAIN' },
      { parentNodeId: 'n1', span, verb: 'EXPLAIN' },
    ]);
    expect(store.currentNodeId()).toBe('n4');
    expect(store.error()).toBeNull();
  });

  it('keeps the session on screen when the re-read after a generation fails', async () => {
    script = async function* () {
      yield delta('The four pillars are…');
      yield done('k4');
    };

    await loadSession();
    const explaining = store.explain(span, 'EXPLAIN');
    await tick();
    http.expectOne('/api/sessions/s1').flush(null, { status: 500, statusText: 'Server Error' });
    await explaining;

    // The generation succeeded and was paid for. Dropping the session here would blank a reader
    // whose contents are stale, not wrong.
    expect(store.session()).not.toBeNull();
    expect(store.currentBody()).toBe('The pillars of modern physics…');
    expect(store.error()?.retryable).toBe(true);
    expect(store.streamingText()).toBe('');
    expect(store.isStreaming()).toBe(false);

    // And the retry is the idempotent GET, not a second generation — streamCalls stays at one.
    const retrying = store.retry();
    http.expectOne('/api/sessions/s1').flush(viewAfterExplain);
    await retrying;

    expect(streamCalls.length).toBe(1);
    expect(store.currentNodeId()).toBe('n4');
    expect(store.error()).toBeNull();
  });

  it('surfaces a 404 as a normal, non-retryable outcome without claiming which reason', async () => {
    const loaded = store.load('s1');
    http
      .expectOne('/api/sessions/s1')
      .flush({ code: 'NOT_FOUND', message: 'no such session' }, { status: 404, statusText: '' });
    await loaded;

    // Task 1.12 made "no such session" and "not yours" the same 404 on purpose, so the reader must
    // not resolve it either way.
    expect(store.error()?.kind).toBe('load');
    expect(store.error()?.code).toBe('NOT_FOUND');
    expect(store.error()?.retryable).toBe(false);
    expect(store.session()).toBeNull();
    expect(store.currentBody()).toBe('');
    expect(store.loading()).toBe(false);
  });

  it('logs an event name it does not know instead of dropping it', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    script = async function* (): AsyncGenerator<ExplainEvent> {
      yield meta('k4');
      // A name from a server that is newer than this client. `sse.client.ts` casts the parser
      // output into the closed union and checks nothing, so the value arrives here unchanged.
      yield { event: 'rewritten', data: { body: 'the authoritative text' } } as unknown as
        ExplainEvent;
      yield delta('The four pillars are…');
      yield done('k4');
    };

    await loadSession();
    const explaining = store.explain(span, 'EXPLAIN');
    await tick();
    http.expectOne('/api/sessions/s1').flush(viewAfterExplain);
    await explaining;

    // `GraphChunk.Superseded` is a chunk and not a flag on `done`, and the stated reason is that
    // "an unhandled event type is a visible gap; an unread boolean is not". A silent drop makes
    // that argument false for the only consumer there is.
    expect(warn).toHaveBeenCalled();
    expect(String(warn.mock.calls[0][0])).toContain('rewritten');

    // The unknown event must not stop the events that follow it, and must not be mistaken for one.
    expect(store.error()).toBeNull();
    expect(store.currentNodeId()).toBe('n4');
    warn.mockRestore();
  });

  it('reports the server account of a load failure, and offers no retry for one the server calls unretryable', async () => {
    const loaded = store.load('s1');
    http.expectOne('/api/sessions/s1').flush(
      {
        code: 'CORRUPT_SESSION',
        message: "this session's stored data is inconsistent and cannot be read",
      },
      { status: 500, statusText: 'Server Error' },
    );
    await loaded;

    // `ErrorMapping.kt` raises CORRUPT_SESSION as a 500 for one reason: no retry fixes it, and an
    // operator must look at the session. The load path answered every non-404 with a connectivity
    // message and a retry button. Each press made another 500 and another log line.
    expect(store.error()?.code).toBe('CORRUPT_SESSION');
    expect(store.error()?.message).toBe(
      "this session's stored data is inconsistent and cannot be read",
    );
    expect(store.error()?.retryable).toBe(false);

    // `retry()` must send no request. `afterEach` calls `http.verify()`, which fails on one.
    await store.retry();
  });

  it('offers a retry when the session fails to load for any other reason', async () => {
    const loaded = store.load('s1');
    http.expectOne('/api/sessions/s1').flush(null, { status: 500, statusText: 'Server Error' });
    await loaded;

    expect(store.error()?.kind).toBe('load');
    expect(store.error()?.retryable).toBe(true);

    const retrying = store.retry();
    http.expectOne('/api/sessions/s1').flush(view);
    await retrying;

    expect(store.error()).toBeNull();
    expect(store.currentBody()).toBe('The pillars of modern physics…');
  });
});
