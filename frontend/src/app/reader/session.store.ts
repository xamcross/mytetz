import { DestroyRef, Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { AccountStore } from '../core/account.store';
import { ApiService } from '../core/api.service';
import { ExplainRequest, NodeView, SessionView, SpanPayload, Verb } from '../core/models';
import { ExplainEvent, ExplainStreamError, explainStream } from '../core/sse.client';

/** The shape of `explainStream` (Task 1.13), named so it can be replaced in a test. */
export type ExplainStreamFn = (
  sessionId: string,
  body: ExplainRequest,
  signal?: AbortSignal,
) => AsyncGenerator<ExplainEvent>;

/**
 * The seam between this store and the network.
 *
 * Defaults to the real `explainStream`, so production wiring needs no provider. It exists so the
 * orchestration below — the state machine, the `superseded` replacement, the two error branches and
 * the re-fetch — can be driven from a supplied event sequence in `session.store.spec.ts` rather than
 * only from a Playwright run. The SSE *parser* already has direct coverage from Task 1.13; what had
 * none was everything this file does with what the parser produces.
 */
export const EXPLAIN_STREAM = new InjectionToken<ExplainStreamFn>('EXPLAIN_STREAM', {
  providedIn: 'root',
  factory: () => explainStream,
});

/**
 * A failure the learner has to be told about, in the one shape both of the store's two failure
 * sources reduce to.
 *
 * [kind] separates "the reader has nothing to show" from "this one explain failed but the session
 * is intact", because they need different UI: the first replaces the page, the second is a banner
 * over a working reader.
 *
 * [discardedText] is true only when prose was genuinely on screen and has been withdrawn — that is,
 * when a `delta` or a `superseded` arrived before the failure. It is deliberately **not**
 * `ExplainStreamError.partiallyStreamed`, which answers a different question: that flag means
 * `explainStream` yielded at least one event, and the server emits `meta` before it calls the model.
 * So the ordinary shape of a failed generation — `meta`, then `error` — sets `partiallyStreamed`
 * with an empty card behind it, and a message keyed on it would tell the learner that a partial
 * answer they never saw had been withdrawn. The distinction Critical C exists to draw has to be
 * drawn from what this store actually rendered, which is the one place that knows.
 *
 * [retryable] means "re-issuing this exact request could plausibly succeed", which is *not* the same
 * as "the learner can do something about it". A quota refusal carries a [retryAfter] and no retry
 * button; `SPAN_MISMATCH` is neither — the same request would fail identically.
 */
export interface ReaderError {
  kind: 'load' | 'explain';
  code: string;
  message: string;
  retryAfter: number | null;
  discardedText: boolean;
  retryable: boolean;
}

/**
 * The codes for which re-issuing the identical request is worth offering as a button.
 *
 * `GENERATION_FAILED` is the one the brief names: the model call failed or its output was rejected,
 * and the next attempt samples again. The other three are this client's own codes for a connection
 * that broke rather than a request that was refused (`sse.client.ts` mints `HTTP_ERROR`/`NO_BODY`;
 * `STREAM_TRUNCATED` is minted below). Everything else is deliberately excluded — a quota or spend
 * refusal is a wait, not a retry, and `SPAN_MISMATCH`/`SESSION_FULL`/`DEPTH_LIMIT` are statements
 * about the request or the session that repeating cannot change.
 */
const RETRYABLE_CODES: ReadonlySet<string> = new Set([
  'GENERATION_FAILED',
  'HTTP_ERROR',
  'NO_BODY',
  'STREAM_TRUNCATED',
]);

/**
 * The load-path codes for which the same `GET /api/sessions/{id}` can succeed on a second attempt.
 *
 * The set is small because the endpoint answers few codes. `INTERNAL` is the catch-all in
 * `ErrorMapping.kt`. A database that is briefly unreachable arrives under it, and the same read
 * then works a moment later. The GET is idempotent and calls no model, so a retry is cheap.
 *
 * `CORRUPT_SESSION` is deliberately absent, and it is the reason this set exists. The backend
 * raises it for a stored session that no longer describes a tree. Its whole justification is that
 * no retry fixes it and that an operator must look at the document. A "Try again" button on it
 * makes another 500 and another log line that nobody reads.
 *
 * An allowlist, not a denylist, and for the same reason as [RETRYABLE_CODES] above. A new server
 * code arrives here as "not retryable", which shows the learner the server message and no button.
 * The opposite default offers a button for a refusal that no button can clear.
 */
const RETRYABLE_LOAD_CODES: ReadonlySet<string> = new Set(['INTERNAL']);

/** What `retry()` should re-issue, or `null` when the last failure was a load. */
interface LastExplain {
  span: SpanPayload;
  verb: Verb;
  parentNodeId: string;
}

/**
 * The reader's whole state: one session, where the learner is in it, and whatever is streaming.
 *
 * Not `providedIn: 'root'`. It is provided by `ReaderPageComponent`, so each visit to
 * `/learn/:sessionId` gets a store whose lifetime is the reader's — a root-scoped store would carry
 * one session's nodes, focus and half-streamed text into the next session opened in the same tab.
 */
@Injectable()
export class SessionStore {
  private readonly api = inject(ApiService);
  private readonly stream = inject(EXPLAIN_STREAM);
  // `providedIn: 'root'`, injected here on purpose. This store is provided per reader route, but a
  // learner's account is one thing for the whole tab, so the root instance is the correct one to
  // share.
  private readonly account = inject(AccountStore);

  /** The id being read, kept separately from [session] so a failed load can still be retried. */
  private sessionId: string | null = null;
  private lastExplain: LastExplain | null = null;
  /** The generation currently streaming, so it can be abandoned — see [abandon]. */
  private inFlight: AbortController | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.abandon());
  }

  readonly session = signal<SessionView | null>(null);
  readonly currentNodeId = signal<string | null>(null);
  /**
   * The curated title of the session's topic, or `null` when the catalogue did not supply one.
   *
   * `SessionView` carries the slug and not the title, so the reader used to rebuild the title from
   * the slug. That rebuild is a guess. It breaks on the first curated title that is not a
   * mechanical transform of its slug: a proper noun with internal capitals, an acronym, a comma.
   * `GET /api/catalog/topics/{slug}` holds the real title, and until now it had no client.
   *
   * `null` is a real outcome and not only a failure. The catalogue answers 404 for a topic that a
   * curator unpublished, while a session on that topic still loads. The reader therefore keeps its
   * slug-derived label as a fallback. See [ReaderPageComponent.topicLabel].
   */
  readonly topicTitle = signal<string | null>(null);
  readonly streamingText = signal('');
  readonly isStreaming = signal(false);
  readonly error = signal<ReaderError | null>(null);
  /** True while `GET /api/sessions/{id}` is in flight for the initial load. Distinct from
   * [isStreaming]: one means "there is nothing to show yet", the other "there is, and more is
   * coming". */
  readonly loading = signal(false);

  /**
   * The body of the node the learner is looking at, or `''` when there is nothing to show.
   *
   * `''` covers three cases that are all "no prose yet" from the card's point of view: nothing
   * loaded, a focus id with no node behind it, and a node whose explanation body is missing from the
   * response. The last should not happen — `GET /api/sessions/{id}` returns a body for every node —
   * and if it ever does, an empty card is the honest rendering: the alternative, showing some other
   * node's body, would put the learner's selections against the wrong string.
   */
  readonly currentBody = computed(() => {
    const node = this.currentNode();
    const explanations = this.session()?.explanations;
    if (!node || !explanations) return '';
    return explanations[node.explanationKey] ?? '';
  });

  /**
   * Root-first path to the current node, inclusive — the same list the backend's
   * `ContextChain.pathTo` builds for the prompt, so the breadcrumb and the model's context stay in
   * lockstep.
   *
   * Diverges from the backend in one way, deliberately: `pathTo` raises `CorruptSessionException` on
   * a dangling parent or a parent cycle, because a chain that is merely *shorter* than it should be
   * would silently change what the model is asked. Here the same conditions stop the walk and return
   * what was reached. A malformed tree cannot arrive through the API — the server raises on every
   * read of one — so this is a guard against hanging the browser, not an alternative policy; and
   * refusing to render the reader at all would be a worse answer to a session the server is happy
   * to serve.
   */
  readonly breadcrumb = computed(() => {
    const nodes = this.session()?.nodes;
    const start = this.currentNode();
    if (!nodes || !start) return [];

    const byId = new Map(nodes.map((n) => [n.nodeId, n]));
    const path: NodeView[] = [];
    const visited = new Set<string>();
    let current: NodeView | undefined = start;
    while (current && !visited.has(current.nodeId)) {
      visited.add(current.nodeId);
      path.unshift(current);
      current = current.parentNodeId === null ? undefined : byId.get(current.parentNodeId);
    }
    return path;
  });

  /**
   * The whole trail, flattened depth-first from the root so every node is immediately followed by
   * its own descendants.
   *
   * Flat rather than nested because the rail draws one indented list and reads `depth` off each
   * node; a nested shape would need the same flattening at the render site. **Preorder rather than
   * the array's own order**, because `nodes` is chronological — a node appended under an older
   * parent lands after nodes that are not its ancestors, and an indented list drawn in that order
   * shows it nested under whichever node happens to precede it. The indentation would then assert a
   * parentage the data does not have.
   *
   * Siblings keep their chronological order, so the rail still reads as the order the learner
   * actually explored.
   *
   * Nodes not reachable from the root are appended at the end rather than dropped: an unreachable
   * node is not renderable as part of the tree, but silently omitting it would remove a branch of
   * the learner's own trail from the only control that navigates back to it.
   */
  readonly tree = computed(() => {
    const session = this.session();
    if (!session) return [];

    const children = new Map<string | null, NodeView[]>();
    for (const node of session.nodes) {
      const siblings = children.get(node.parentNodeId);
      if (siblings) siblings.push(node);
      else children.set(node.parentNodeId, [node]);
    }

    const ordered: NodeView[] = [];
    const placed = new Set<string>();
    const visit = (node: NodeView): void => {
      if (placed.has(node.nodeId)) return;
      placed.add(node.nodeId);
      ordered.push(node);
      for (const child of children.get(node.nodeId) ?? []) visit(child);
    };

    const root = session.nodes.find((n) => n.nodeId === session.rootNodeId);
    if (root) visit(root);
    for (const node of session.nodes) if (!placed.has(node.nodeId)) ordered.push(node);
    return ordered;
  });

  private readonly currentNode = computed(() => {
    const id = this.currentNodeId();
    if (id === null) return undefined;
    return this.session()?.nodes.find((n) => n.nodeId === id);
  });

  /**
   * Reads the session. A 404 is a normal outcome, not an exception: Task 1.12 answers "no such
   * session" and "this session is not yours" with the same 404 and the same body, on purpose, so
   * that a guessed id is not an oracle. The message below preserves that — it says the session
   * could not be opened and names both possibilities, without resolving which.
   */
  async load(sessionId: string): Promise<void> {
    // Angular reuses this component across a parameter-only route change, so a load can arrive with
    // the previous session's generation still streaming into these signals.
    this.abandon();
    this.sessionId = sessionId;
    this.lastExplain = null;
    this.topicTitle.set(null);
    this.loading.set(true);
    this.error.set(null);
    try {
      const view = await this.api.session(sessionId);
      this.session.set(view);
      this.currentNodeId.set(view.currentNodeId);
      // Started here, and deliberately not awaited. The reader can paint from the session alone,
      // and the label has a fallback, so a second round trip must not delay the first paint.
      void this.loadTopicTitle(sessionId, view.topicSlug);
    } catch (err) {
      this.session.set(null);
      this.currentNodeId.set(null);
      this.error.set(loadErrorFor(err));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Reads the curated title of a topic and records it.
   *
   * A failure is not reported to the learner. The session is on screen and it is correct. The
   * label falls back to the slug, which the reader derived for every session before this method
   * existed. An error banner over a working reader, for a cosmetic label, is the worse answer.
   *
   * [forSessionId] guards a late answer. Angular reuses this component when only the route
   * parameter changes, so a second `load` can start before this request returns. Without the
   * guard, the first topic's title lands under the second session's breadcrumb.
   */
  private async loadTopicTitle(forSessionId: string, topicSlug: string): Promise<void> {
    try {
      const topic = await this.api.topic(topicSlug);
      if (this.sessionId === forSessionId) this.topicTitle.set(topic.title);
    } catch {
      if (this.sessionId === forSessionId) this.topicTitle.set(null);
    }
  }

  /**
   * Moves the focus within the loaded session. No request: every body is already here.
   *
   * Clears [error] too. Without this, a wall or a sign-in panel raised by a failed explain stays
   * on screen after the learner moves to another node, and a page reload is the only escape. A
   * learner who has moved on is no longer looking at the request that failed.
   */
  goTo(nodeId: string): void {
    const exists = this.session()?.nodes.some((n) => n.nodeId === nodeId) ?? false;
    if (!exists) return;
    this.currentNodeId.set(nodeId);
    this.error.set(null);
  }

  /**
   * Generates a child explanation for a highlighted span and streams it in.
   *
   * ## The four events, and why `superseded` is not optional
   *
   * `delta` appends. `superseded` **replaces**: it means another instance persisted this content key
   * first, so everything streamed so far is this instance's own sampling and is not what the store
   * holds. Quiz and exam generation read the stored body, so a learner left with unreplaced text can
   * be examined on material they were never shown. `meta` is a hint only (`cached` describes the
   * store before the per-key lock, so in a stampede every caller sees `false`) and drives nothing
   * here. `done` ends the stream.
   *
   * ## The two failure shapes, handled differently
   *
   * `explainStream` throws `ExplainStreamError` for both, and `partiallyStreamed` is what separates
   * them:
   *
   * - **false** — refused before the response began (quota, spend breaker, a span the server could
   *   not match, a session that is not yours). Nothing was rendered; there is nothing to withdraw,
   *   and the banner says only what was refused.
   * - **true** — the generation broke after prose was already on screen. That prose is discarded.
   *   It is not stored anywhere — `SessionService` drops the abandoned node — so leaving it up would
   *   show the learner a passage that no explanation body matches, and every span they highlighted
   *   in it would come back `SPAN_MISMATCH`. The banner says the partial answer was withdrawn, which
   *   is why the flag is carried on the error rather than consumed here.
   *
   * A stream that simply ends without `done` is treated as the second shape under its own code: the
   * alternative — settling back to "not streaming" with no error — leaves half an answer on screen
   * and no account of what happened to it.
   *
   * ## Why the session is re-read on `done`
   *
   * The node id is not on the wire, deliberately (`SessionRoutes.MetaEvent`'s KDoc: the id is minted
   * inside the flow and a cancelled generation drops the node, so any id sent early would be a
   * promise the endpoint cannot keep). Re-reading also picks up the authoritative body — including
   * the winner's, after a `superseded` — rather than trusting the concatenated deltas.
   */
  async explain(span: SpanPayload, verb: Verb): Promise<void> {
    const sessionId = this.sessionId;
    const parentNodeId = this.currentNodeId();
    // A second explain while one is in flight would be a second *paid* generation for a span the
    // learner highlighted once — the same guard, for the same reason, as the catalogue's
    // double-click protection on session creation.
    if (sessionId === null || parentNodeId === null || this.isStreaming()) return;

    this.lastExplain = { span, verb, parentNodeId };
    this.isStreaming.set(true);
    this.streamingText.set('');
    this.error.set(null);

    // Abandoned if the learner leaves — see `abandon()`. `explainStream` passes this to `fetch`, so
    // an abort closes the connection rather than leaving it draining into a store nobody reads.
    const controller = new AbortController();
    this.inFlight = controller;

    let sawDone = false;
    // Whether anything was actually rendered. This — not `ExplainStreamError.partiallyStreamed` —
    // is what `discardedText` reports; see `ReaderError`.
    let streamedAnything = false;
    try {
      for await (const event of this.stream(
        sessionId,
        { parentNodeId, span, verb },
        controller.signal,
      )) {
        switch (event.event) {
          case 'delta':
            streamedAnything = true;
            this.streamingText.update((text) => text + event.data.t);
            break;
          case 'superseded':
            streamedAnything = true;
            this.streamingText.set(event.data.body);
            break;
          case 'done':
            sawDone = true;
            break;
          case 'meta':
            break;
          default: {
            // `sse.client.ts` casts the parser output into `ExplainEvent` and checks nothing, so an
            // event name that this client does not know arrives here. TypeScript narrows `event` to
            // `never`, because the union says that this cannot happen.
            //
            // `GraphChunk.Superseded` is a chunk and not a flag on `done` for one stated reason:
            // "an unhandled event type is a visible gap; an unread boolean is not". That holds for
            // a sealed `when` in Kotlin. For this consumer it holds only if this branch exists.
            //
            // The branch logs and continues. It does not throw. A server that adds an event must
            // not break a client that predates it, and the events that follow are still valid.
            // `streamedAnything` stays false, because this branch rendered nothing.
            const unrecognised: { event: string } = event;
            console.warn(
              `[SessionStore] ignored an unknown explain event: "${unrecognised.event}". ` +
                'The server sends an event that this client does not know.',
            );
            break;
          }
        }
      }

      // Nobody is reading this any more: no error to report, and above all no re-fetch of a session
      // the learner has navigated away from.
      if (controller.signal.aborted) return;

      if (!sawDone) {
        this.failStream({
          code: 'STREAM_TRUNCATED',
          message: 'the connection closed before the explanation finished',
          retryAfter: null,
          discardedText: streamedAnything,
        });
        return;
      }

      await this.refresh(sessionId);
    } catch (err) {
      // An abort raises here too (as an `AbortError` from `fetch`), and it is this client's own
      // doing rather than a failure to report.
      if (controller.signal.aborted) return;
      const failure =
        err instanceof ExplainStreamError
          ? {
              code: err.code,
              message: err.message,
              retryAfter: err.retryAfter,
              // Not `err.partiallyStreamed`: `meta` precedes the model call, so that flag is set for
              // the commonest failure of all with nothing on screen behind it. See `ReaderError`.
              discardedText: streamedAnything,
            }
          : {
              code: 'UNKNOWN',
              message: 'the explanation could not be loaded',
              retryAfter: null,
              discardedText: streamedAnything,
            };
      this.failStream(failure);
    } finally {
      // Only if this is still the current generation. An abandoned one unwinds whenever its fetch
      // gets round to rejecting, which can be after the learner has started something else in the
      // reused component — and `isStreaming.set(false)` from a generation nobody is watching would
      // then clear the flag out from under the one they are. The same guard also decides the
      // account refresh below, and for the same reason: a learner who clicks through several spans
      // before any of them finishes must not fire one account read per click.
      if (this.inFlight === controller) {
        this.inFlight = null;
        this.isStreaming.set(false);
        // A generation that reaches this point, win or refuse, changes the learner's remaining
        // allowance. A completed generation spends one unit. A refusal can too: TRIAL_EXHAUSTED
        // means the pool is now zero, and the meter must say so at once, not after a reload. The
        // read is one small GET, so its cost is worth paying on both outcomes. A cache hit spends
        // nothing, so this same call then returns the same numbers, which needs no special case.
        //
        // SIGN_IN_REQUIRED is the one refusal this skips. A signed-out visitor gets that same
        // code on every attempt, and the account read would only ever answer 401 in reply.
        if (this.error()?.code !== 'SIGN_IN_REQUIRED') {
          void this.account.load();
        }
      }
    }
  }

  /**
   * Drops an in-flight generation on the floor.
   *
   * Run when the reader is destroyed and when a different session is loaded into the same component
   * instance. Without it, navigating away mid-generation leaves the `fetch` open, the generator
   * writing deltas into signals nobody renders, and — worst — a `done` firing a re-fetch of a
   * session the learner has left. The backend anticipates exactly this: `streamExplanation` rethrows
   * `CancellationException` so that `SessionService.explain` drops the abandoned node rather than
   * resuming a learner on a branch they walked away from, and that path only runs if the client
   * actually closes the connection.
   *
   * Clears the streamed prose too, and does not wait for the abandoned generation's own `finally` to
   * do it. Half an explanation of *the previous session's* node is not a loading state in the new
   * one — it is another session's text under this session's breadcrumb, and it would survive until
   * the next `explain()` happened to overwrite it. `isStreaming` is cleared here for the same
   * reason: left set, the new session's card reads "Generating…" with its verbs disabled while
   * nothing is being generated at all.
   */
  private abandon(): void {
    this.inFlight?.abort();
    this.inFlight = null;
    this.streamingText.set('');
    this.isStreaming.set(false);
  }

  /**
   * Re-issues whatever last failed — the load, or the explain.
   *
   * Re-running the same explain is cheap when it works: the graph is content-addressed, so a retry
   * of a generation that did in fact complete server-side is served from the store rather than
   * sampled again.
   */
  async retry(): Promise<void> {
    const failure = this.error();
    if (failure === null || !failure.retryable) return;

    if (failure.kind === 'load') {
      if (this.sessionId !== null) await this.load(this.sessionId);
      return;
    }

    const last = this.lastExplain;
    if (last === null) return;
    // Retry from the node the original request was made against, not from wherever the learner has
    // clicked since — the span's offsets index that node's body and no other.
    this.currentNodeId.set(last.parentNodeId);
    await this.explain(last.span, last.verb);
  }

  dismissError(): void {
    this.error.set(null);
  }

  /**
   * Re-reads the session after a successful generation.
   *
   * The focus follows the server's own `currentNodeId` rather than "the last node in the array":
   * `SessionRepository.appendNode` sets it in the same update that pushes the node, so it *is* the
   * node just created, and taking it from the field the server maintains keeps this from re-deriving
   * a value the response already states.
   *
   * `streamingText` is cleared only once the new body is in place, so the card never has a frame with
   * neither.
   */
  private async refresh(sessionId: string): Promise<void> {
    try {
      const view = await this.api.session(sessionId);
      this.session.set(view);
      this.currentNodeId.set(view.currentNodeId);
      this.streamingText.set('');
      this.lastExplain = null;
    } catch (err) {
      // The generation itself succeeded — the body was inserted before `done` was sent — so the
      // session already on screen is not wrong, only stale. It is deliberately left in place and
      // this is reported as a load failure, which makes `retry()` re-issue the cheap idempotent GET
      // rather than the generation. `ReaderPageComponent` shows a load failure as a full-page dead
      // end only when there is no session to show, so this lands as a banner over a working reader.
      //
      // Note what this does *not* claim: `SessionService.explain` appends the node after its last
      // emit, so a client holding `done` knows the explanation was stored but not that the node was
      // appended. The message says the first and not the second.
      this.error.set({
        ...loadErrorFor(err),
        message: 'The explanation was generated, but this session could not be re-read just now.',
      });
      this.streamingText.set('');
    }
  }

  private failStream(failure: Omit<ReaderError, 'kind' | 'retryable'>): void {
    // Discarded rather than left on screen — see this class's `explain` doc comment.
    this.streamingText.set('');
    this.error.set({
      kind: 'explain',
      ...failure,
      retryable: RETRYABLE_CODES.has(failure.code),
    });
  }
}

/** The `{code, message, retryAfter}` body every backend refusal uses (`ErrorMapping.kt`). Kept
 * private here for the same reason `sse.client.ts` and `catalog-page.component.ts` each keep their
 * own: it is a wire shape, not a domain model. */
interface ApiErrorBody {
  code: string;
  message: string;
  retryAfter?: number | null;
}

function asApiErrorBody(value: unknown): ApiErrorBody | null {
  if (
    value !== null &&
    typeof value === 'object' &&
    typeof (value as Record<string, unknown>)['code'] === 'string' &&
    typeof (value as Record<string, unknown>)['message'] === 'string'
  ) {
    return value as ApiErrorBody;
  }
  return null;
}

function loadErrorFor(err: unknown): ReaderError {
  const status = err instanceof HttpErrorResponse ? err.status : 0;
  const body = err instanceof HttpErrorResponse ? asApiErrorBody(err.error) : null;

  if (status === 404) {
    return {
      kind: 'load',
      code: body?.code ?? 'NOT_FOUND',
      // Written here rather than echoing the server's "no such session": the server answers an
      // unknown session and someone else's session identically on purpose, and a message that
      // asserted the first would be wrong half the time and would leak the distinction the rest.
      message:
        'That session could not be opened. It may no longer exist, or it may have been started ' +
        'in a different browser.',
      retryAfter: null,
      discardedText: false,
      retryable: false,
    };
  }

  // The server described the failure, so the learner reads that description. The explain path
  // already does this. The load path did not: it replaced every message with the connectivity
  // sentence below and set `retryable` to true. A CORRUPT_SESSION therefore became "check your
  // connection", under a button that the backend says can fix nothing.
  if (body !== null) {
    return {
      kind: 'load',
      code: body.code,
      message: body.message,
      retryAfter: body.retryAfter ?? null,
      discardedText: false,
      retryable: RETRYABLE_LOAD_CODES.has(body.code),
    };
  }

  // No `ApiError` in the response. The request did not reach this API, or something in front of it
  // answered. A connectivity message is honest here, and only here.
  return {
    kind: 'load',
    code: 'LOAD_FAILED',
    message: 'Could not load this session. Check your connection and try again.',
    retryAfter: null,
    discardedText: false,
    retryable: true,
  };
}
