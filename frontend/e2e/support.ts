import { Page } from '@playwright/test';
import type { SessionView } from '../src/app/core/models';

/** The seed body of the one stubbed topic every spec in this suite drills into. */
export const SEED =
  'Quantum mechanics is the fundamental physical theory that describes matter and light.';
/** A child explanation of the phrase "fundamental physical theory". */
export const CHILD =
  'The microscopic realm studied by quantum theory is the subatomic scale, smaller than 0.1 nanometers.';

const ROOT_NODE: SessionView['nodes'][number] = {
  nodeId: 'n0',
  parentNodeId: null,
  explanationKey: 'k0',
  span: '',
  verb: 'SEED',
  variant: 0,
  depth: 0,
};

const SEED_VIEW: SessionView = {
  sessionId: 's1',
  topicSlug: 'quantum-physics',
  rootNodeId: 'n0',
  currentNodeId: 'n0',
  nodes: [ROOT_NODE],
  explanations: { k0: SEED },
};

/** What `GET /api/sessions/s1` returns once a child has been explained: the same root plus one
 * `EXPLAIN` node for "fundamental physical theory", whose body is `childBody`. */
export function explainedView(childBody: string): SessionView {
  return {
    ...SEED_VIEW,
    currentNodeId: 'n1',
    nodes: [
      ROOT_NODE,
      {
        nodeId: 'n1',
        parentNodeId: 'n0',
        explanationKey: 'k1',
        span: 'fundamental physical theory',
        verb: 'EXPLAIN',
        variant: 0,
        depth: 1,
      },
    ],
    explanations: { k0: SEED, k1: childBody },
  };
}

/**
 * Registers every stub a spec needs before it can reach, and act inside, the reader: the one
 * published topic, `POST /api/sessions` minting session `s1`, and `GET /api/sessions/s1`.
 *
 * The GET stub is **stateful**, and has to be: `ReaderPageComponent` calls `SessionStore.load()`
 * unconditionally on every visit to `/learn/:sessionId` — including the one immediately after
 * `POST /api/sessions` returns — so it issues its own `GET /api/sessions/s1` before the learner has
 * done anything at all. `POST /api/sessions`'s own response body is not what the reader renders (the
 * catalogue reads only its `sessionId` before navigating); the *first* GET is. A single stub
 * returning the post-explain view — which is what the original brief's spec did — answers that first,
 * pre-explain GET with a session that already has a child node, and the reader jumps straight to it
 * before a phrase has ever been highlighted. So: the first `GET` returns [SEED_VIEW], and only a
 * second one — the re-read `SessionStore.explain()` issues after it sees `done` — returns
 * `afterExplain`, when a test supplies one. Tests that never expect a successful explain (the
 * refusal and truncation cases below) omit it, so an unexpected second GET is answered with the seed
 * view again rather than silently validating a state the test never asked for.
 *
 * Returns a `sessionGetCount` accessor so a test can assert exactly how many times the reader
 * re-read the session — the same technique acceptance criterion 5 asks for against the live
 * deployment's `requestCount`, applied here to prove a test's own claim about how many reads
 * happened rather than assuming it from the UI alone.
 */
export async function stubCatalogueAndSession(
  page: Page,
  afterExplain?: SessionView,
): Promise<{ sessionGetCount: () => number }> {
  await page.route('**/api/catalog/topics*', (route) =>
    route.fulfill({
      json: [
        { slug: 'quantum-physics', title: 'Quantum Physics', category: 'Physics', summary: 'Small things.' },
      ],
    }),
  );

  await page.route('**/api/sessions', (route) => route.fulfill({ json: SEED_VIEW }));

  let getCount = 0;
  await page.route('**/api/sessions/s1', (route) => {
    getCount += 1;
    const view = getCount >= 2 && afterExplain ? afterExplain : SEED_VIEW;
    route.fulfill({ json: view });
  });

  return { sessionGetCount: () => getCount };
}

/** Opens `/`, waits for the catalogue, and starts the one stubbed topic — the first three of
 * acceptance criteria 1-2 (catalogue lists topics; selecting one creates a session and renders its
 * seed), common to every spec below. */
export async function openQuantumPhysicsSession(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByRole('button', { name: /Quantum Physics/ }).click();
  await page.getByText(SEED).waitFor();
}

/**
 * Drags a real selection across `phrase` inside the element identified by `testId`, the way a
 * learner actually highlights text — mouse down at the start of the phrase, move to its end, mouse
 * up — rather than constructing a `Range` and calling `Selection.addRange` from injected script.
 *
 * This is Problem C from the task brief: the original spec fabricated a selection with
 * `document.createRange()` inside `page.evaluate` and dispatched a synthetic `mouseup` to wake the
 * card up. `FocusCardComponent.onSelectionChanged()` (unchanged by this suite) is still bound
 * directly to `(mouseup)`/`(touchend)` on the body paragraph — not `document`'s `selectionchange` —
 * so that synthetic approach still happens to fire the handler. But the brief's own instruction is to
 * prefer whatever real, Playwright-native input can express, and a mouse drag is exactly that: it
 * produces a genuine browser `Selection` through the browser's own hit-testing and text-selection
 * algorithm, and the resulting real `mouseup` is not staged. `evaluate` is used only to *measure*
 * where the phrase sits on screen (`Range.getClientRects()`, one rect per wrapped line) — never to
 * perform the selection itself.
 */
export async function selectPhrase(page: Page, testId: string, phrase: string): Promise<void> {
  const rects = await page.getByTestId(testId).evaluate((el, phrase) => {
    const text = el.firstChild;
    if (!text) throw new Error(`${el} has no text node to select within`);
    const full = el.textContent ?? '';
    const start = full.indexOf(phrase);
    if (start === -1) throw new Error(`"${phrase}" not found in "${full}"`);
    const range = document.createRange();
    range.setStart(text, start);
    range.setEnd(text, start + phrase.length);
    return Array.from(range.getClientRects()).map((r) => ({
      left: r.left,
      right: r.right,
      top: r.top,
      bottom: r.bottom,
    }));
  }, phrase);

  const first = rects[0];
  const last = rects[rects.length - 1];
  await page.mouse.move(first.left + 1, (first.top + first.bottom) / 2);
  await page.mouse.down();
  // Multiple steps, not a single jump, so this is a drag the same handful of mousemove events a real
  // gesture produces, not a teleport that happens to land in the right place.
  await page.mouse.move(last.right - 1, (last.bottom + last.top) / 2, { steps: 10 });
  await page.mouse.up();
}

/** One `text/event-stream` frame, in the wire format `sse.client.ts`'s `parseFrame` expects. */
export function sseFrame(event: string, data: unknown): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

/** A refusal decided before the response body ever opens — a non-200 with the
 * `{code, message, retryAfter}` shape `ErrorMapping.kt` writes for every backend refusal. */
export interface ExplainRefusal {
  status: number;
  body: { code: string; message: string; retryAfter?: number | null };
}

/**
 * Installs the *other* shape `explainStream` can throw: nothing is ever streamed, the response
 * simply comes back with a non-2xx status and this JSON body. Used for acceptance criterion 6's
 * `QUOTA_EXCEEDED` — a refusal with nothing rendered, as distinct from `mockExplainStream`'s
 * mid-stream failures, which have prose on screen first.
 */
export async function mockExplainRefusal(
  page: Page,
  sessionId: string,
  refusal: ExplainRefusal,
): Promise<void> {
  await page.addInitScript(
    ({ sessionId, refusal }) => {
      const path = `/api/sessions/${sessionId}/explain`;
      const realFetch = window.fetch.bind(window);
      window.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        const url = typeof input === 'string' ? input : input.toString();
        if (!url.endsWith(path)) return realFetch(input, init);
        if (init?.signal?.aborted) {
          return Promise.reject(new DOMException('The operation was aborted.', 'AbortError'));
        }
        return Promise.resolve(
          new Response(JSON.stringify(refusal.body), {
            status: refusal.status,
            headers: { 'content-type': 'application/json' },
          }),
        );
      };
    },
    { sessionId, refusal },
  );
}

/** A live, test-controlled explain stream: `send` delivers one frame exactly when called, `close`
 * ends the connection. Returned by `mockExplainStream` — see its doc comment. */
export interface ExplainStream {
  send(frame: string): Promise<void>;
  close(): Promise<void>;
}

/**
 * Problem A from the task brief: `route.fulfill()` — used for every other endpoint in this suite —
 * only ever delivers a complete `body` at once (confirmed against the Playwright API reference: its
 * `body`/`json`/`path`/`response` parameters are all whole-response, with no streamed or async-
 * iterable form). A single `fulfill` therefore hands the browser the whole SSE payload as one chunk,
 * which exercises none of Task 1.13's frame parser or the "visibly progressive" rendering Slice 1's
 * acceptance criterion 3 asks for — the stub would pass a suite that streaming had been silently
 * reverted to a single blocking response.
 *
 * `route.continue({ url })` cannot redirect to a real streaming server either, at least not without
 * new CORS plumbing to route around: the API reference states the replacement URL "must have the
 * same protocol as the original", which permits a different port but still sends the request over
 * the real network to it, so a same-origin `fetch('/api/sessions/s1/explain', {credentials:
 * 'same-origin'})` redirected cross-port becomes a genuinely cross-origin request, subject to CORS
 * preflight the app was never built to negotiate.
 *
 * So this replaces `window.fetch` itself, via `page.addInitScript` — which runs before any of the
 * page's own scripts, so it is in place before Angular ever calls it — for exactly one path,
 * `/api/sessions/{sessionId}/explain`, and returns a real `Response` wrapping a real
 * `ReadableStream<Uint8Array>`. Nothing else is touched: catalogue, session-creation and
 * session-refresh all still go through Angular's `HttpClient`, which this build never routed onto
 * `fetch` (`app.config.ts` calls `provideHttpClient()` without `withFetch()`, so it runs on
 * `XMLHttpRequest`) — those keep going through `page.route` exactly as the original brief had them,
 * untouched by this shim, and continue to exercise Playwright's real network-layer interception.
 *
 * **Pull-based and test-driven, not timer-based.** An earlier version of this shim scheduled each
 * chunk on its own `setTimeout`, baking a fixed real-time delay between frames into the mock itself.
 * That is a race by construction — under CPU pressure from Playwright's own parallel workers (six
 * simultaneous Chromium instances plus six `ng serve` compiles competing for the same cores is
 * ordinary in CI, not a corner case), a `setTimeout` due at 500ms can fire meaningfully later, and a
 * `toContainText` polling for a chunk that "should" have arrived by now times out for a reason that
 * has nothing to do with the product. The `ReadableStream`'s `pull(controller)` here instead blocks
 * on a promise that only resolves when the *test* calls `ExplainStream.send()`, which does the
 * enqueueing via a `page.evaluate` round-trip. So "partial text is on screen before the stream
 * completes" is not a timing bet — it is true by construction, because the test does not send the
 * rest of the frames until after it has already asserted the partial state.
 *
 * The abort path (Task 1.16's own deferred item) falls out of this for free: the shim listens for the
 * caller's `AbortSignal` and errors the stream with a real `DOMException('AbortError')`, exactly what
 * a genuine `fetch` does when its controller aborts mid-response — so navigating away mid-stream in
 * `learn.spec.ts`'s abort test drives the real `fetch`-rejection path through `sse.client.ts` and
 * `SessionStore`, not a fake that completes normally. A stream a test never calls `close()` on models
 * a generation still genuinely in progress from the server's point of view, which is exactly the
 * abort test's setup.
 */
export async function mockExplainStream(page: Page, sessionId: string): Promise<ExplainStream> {
  await page.addInitScript(
    ({ sessionId }) => {
      interface MockState {
        queue: string[];
        closed: boolean;
        waiters: Array<() => void>;
      }
      const w = window as unknown as { __mtzExplain: Map<string, MockState> };
      w.__mtzExplain = new Map<string, MockState>();
      w.__mtzExplain.set(sessionId, { queue: [], closed: false, waiters: [] });

      const path = `/api/sessions/${sessionId}/explain`;
      const realFetch = window.fetch.bind(window);

      window.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        const url = typeof input === 'string' ? input : input.toString();
        if (!url.endsWith(path)) return realFetch(input, init);

        const signal = init?.signal;
        if (signal?.aborted) {
          return Promise.reject(new DOMException('The operation was aborted.', 'AbortError'));
        }

        const state = w.__mtzExplain.get(sessionId)!;
        const encoder = new TextEncoder();
        let aborted = false;

        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            signal?.addEventListener('abort', () => {
              aborted = true;
              state.waiters.splice(0).forEach((wake) => wake());
              try {
                controller.error(new DOMException('The operation was aborted.', 'AbortError'));
              } catch {
                // Already closed or errored — nothing left to do.
              }
            });
          },
          async pull(controller) {
            while (state.queue.length === 0 && !state.closed && !aborted) {
              await new Promise<void>((resolve) => state.waiters.push(resolve));
            }
            if (aborted) return;
            if (state.queue.length > 0) {
              controller.enqueue(encoder.encode(state.queue.shift()!));
              return;
            }
            controller.close();
          },
        });

        return Promise.resolve(
          new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
        );
      };
    },
    { sessionId },
  );

  return {
    async send(frame: string): Promise<void> {
      await page.evaluate(
        ({ sessionId, frame }) => {
          interface MockState {
            queue: string[];
            waiters: Array<() => void>;
          }
          const w = window as unknown as { __mtzExplain: Map<string, MockState> };
          const state = w.__mtzExplain.get(sessionId);
          if (!state) throw new Error(`no explain mock installed for session "${sessionId}"`);
          state.queue.push(frame);
          state.waiters.splice(0).forEach((wake) => wake());
        },
        { sessionId, frame },
      );
    },
    async close(): Promise<void> {
      await page.evaluate(
        ({ sessionId }) => {
          interface MockState {
            closed: boolean;
            waiters: Array<() => void>;
          }
          const w = window as unknown as { __mtzExplain: Map<string, MockState> };
          const state = w.__mtzExplain.get(sessionId);
          if (!state) throw new Error(`no explain mock installed for session "${sessionId}"`);
          state.closed = true;
          state.waiters.splice(0).forEach((wake) => wake());
        },
        { sessionId },
      );
    },
  };
}
