import { ExplainRequest } from './models';

export interface SseEvent {
  event: string;
  data: unknown;
}

/**
 * Parses a `text/event-stream` byte stream into `{event, data}` pairs, reassembling frames that
 * arrive split across `ReadableStream` chunk boundaries.
 *
 * Deliberately generic: this knows nothing of mytetz's own event vocabulary (`meta`, `delta`,
 * `superseded`, `done`, `error`) or that `error` means something different from the rest of it.
 * That interpretation belongs to the caller — see `explainStream`.
 */
export async function* parseSseStream(
  stream: ReadableStream<Uint8Array>,
): AsyncGenerator<SseEvent> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();

  // The undelivered tail: bytes decoded but not yet folded into a complete frame.
  let buffer = '';
  // How far into `buffer` a boundary search has already come up empty. Re-scanning from 0 on
  // every chunk is what makes a naive parser quadratic when one frame arrives in many small
  // pieces (a long `delta` streamed byte-by-byte, say) — this cursor keeps it amortised-linear.
  let searchFrom = 0;
  // True when the previous chunk ended in a bare "\r" whose partner "\n", if any, has not
  // arrived yet. CRLF normalisation is done per incoming chunk rather than on the whole
  // accumulated buffer (see below), so a "\r\n" split exactly at a chunk boundary needs this bit
  // of state or it degrades into a stray "\r" glued onto the wrong line.
  let pendingCr = false;

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        // Flushes TextDecoder's own internal state. A multi-byte UTF-8 character truncated at
        // the true end of the stream — a connection dropped mid-token, not just a chunk boundary
        // an earlier `{stream: true}` call already resolves on its own — is held there and
        // never reaches `buffer` unless this runs; without it those bytes vanish with no trace.
        buffer += decoder.decode();
        if (pendingCr) buffer += '\r';
        break;
      }

      buffer += normalizeChunk(decoder.decode(value, { stream: true }));
      yield* drainFrames();
    }

    yield* drainFrames();

    // The server always terminates a frame with a blank line, but a reader must not depend on
    // that: a connection that ends the instant its last byte is written looks identical, from
    // here, to one that dropped the trailing blank line. So whatever is left is parsed as one
    // final frame rather than silently discarded. This is deliberately looser than the WHATWG
    // EventSource algorithm, which discards a trailing incomplete event — appropriate for a
    // long-lived connection reused across many logical messages, less so for a single POST
    // response we control both ends of, where losing a `done` or an `error` to a socket that
    // closed a few bytes early is worse than parsing a frame with no closing blank line.
    if (buffer.trim().length > 0) {
      const parsed = parseFrame(buffer);
      if (parsed) yield parsed;
    }
  } finally {
    reader.releaseLock();
  }

  function normalizeChunk(text: string): string {
    let normalized = pendingCr ? '\r' + text : text;
    pendingCr = false;
    if (normalized.endsWith('\r')) {
      pendingCr = true;
      normalized = normalized.slice(0, -1);
    }
    return normalized.replace(/\r\n/g, '\n');
  }

  function* drainFrames(): Generator<SseEvent> {
    for (;;) {
      const boundary = buffer.indexOf('\n\n', searchFrom);
      if (boundary === -1) {
        // Nothing found in what's newly arrived either. Next search only needs to cover the
        // fresh tail, minus one character of overlap in case the boundary itself is split
        // across chunks (e.g. this chunk ends "...\n" and the next begins "\n...").
        searchFrom = Math.max(0, buffer.length - 1);
        return;
      }
      const frame = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      searchFrom = 0;
      const parsed = parseFrame(frame);
      if (parsed) yield parsed;
    }
  }
}

/**
 * One frame — the text between two blank lines — into an event, or `null` for a frame with no
 * `data:` line at all: a bare comment or a blank keep-alive carries nothing to deliver.
 *
 * Per the SSE spec a field's value is everything after the first colon with *one* leading space
 * stripped, not the whole line trimmed. Every payload here happens to be JSON, whose own parser
 * tolerates surrounding whitespace, so `.trim()` would be unobservable by accident rather than a
 * decision — deliberately not done here, and pinned by a non-JSON raw-fallback case in the test
 * file where the difference is actually visible.
 */
function parseFrame(frame: string): SseEvent | null {
  let event = 'message';
  const dataLines: string[] = [];

  for (const line of frame.split('\n')) {
    if (line.startsWith(':') || line.length === 0) continue;
    if (line.startsWith('event:')) event = stripLeadingSpace(line.slice('event:'.length));
    else if (line.startsWith('data:'))
      dataLines.push(stripLeadingSpace(line.slice('data:'.length)));
  }

  if (dataLines.length === 0) return null;

  const raw = dataLines.join('\n');
  try {
    return { event, data: JSON.parse(raw) };
  } catch {
    return { event, data: raw };
  }
}

function stripLeadingSpace(value: string): string {
  return value.startsWith(' ') ? value.slice(1) : value;
}

// ---------------------------------------------------------------------------------------------
// explainStream: the app's own wire vocabulary layered on top of the generic parser above.
// ---------------------------------------------------------------------------------------------

export interface MetaEventData {
  contentKey: string;
  cached: boolean;
}

export interface DeltaEventData {
  t: string;
}

/** The authoritative body of a key this instance lost a generation race for. Replace, don't
 * append: everything rendered so far under this key is this instance's own sampling, and quiz
 * generation reads the stored body, not what the learner was shown. */
export interface SupersededEventData {
  body: string;
}

export interface DoneEventData {
  contentKey: string;
  grounded: boolean;
}

/**
 * The four events a caller can iterate over. `error` is deliberately not a member of this union:
 * it never reaches a caller as a yielded value, only as a thrown `ExplainStreamError` — see
 * `explainStream`.
 */
export type ExplainEvent =
  | { event: 'meta'; data: MetaEventData }
  | { event: 'delta'; data: DeltaEventData }
  | { event: 'superseded'; data: SupersededEventData }
  | { event: 'done'; data: DoneEventData };

interface ErrorEventData {
  code: string;
  message: string;
  retryAfter?: number | null;
}

/**
 * One error type for both shapes a failure can take on this endpoint.
 *
 * A refusal decided before the stream opens — quota, spend breaker, a forged span, an unowned
 * session — carries a real HTTP status and this body. A failure discovered mid-generation cannot:
 * `StatusPages` cannot rewrite a response already streaming, so the server instead sends `event:
 * error` over an HTTP 200 with the same body shape. Wiring both into the same thrown type means a
 * caller writes one `catch` around one `for await`, branching on `.code` if it cares which
 * failure this was, rather than a `catch` for one shape and an in-loop check for the other.
 *
 * The two shapes *are* still distinguishable, and the distinction matters: a pre-stream refusal
 * throws before the caller's loop body has run even once, so nothing has been rendered — while a
 * mid-stream error throws only after one or more prior events already drove a UI update, so
 * something has. Recovering that from control flow (did the loop body ever execute?) works, but
 * makes every caller re-derive it. [partiallyStreamed] states it directly instead: `true` iff
 * `explainStream` yielded at least one event before this error was thrown.
 */
export class ExplainStreamError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly retryAfter: number | null = null,
    readonly partiallyStreamed: boolean = false,
  ) {
    super(message);
    this.name = 'ExplainStreamError';
  }
}

/**
 * POST + SSE. `EventSource` cannot be used here because it only issues GET requests, so this is
 * `fetch` plus `parseSseStream` over the response body.
 *
 * Throws `ExplainStreamError` for both failure shapes described above, and otherwise yields
 * `meta`, `delta`, `superseded` and `done` events in wire order. A `superseded` event is passed
 * through like any other — it is the caller's job (Tasks 1.14–1.16) to replace rendered text with
 * it, not this function's; dropping or specially filtering it here would make that impossible.
 */
export async function* explainStream(
  sessionId: string,
  body: ExplainRequest,
  signal?: AbortSignal,
): AsyncGenerator<ExplainEvent> {
  const response = await fetch(`/api/sessions/${sessionId}/explain`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
    body: JSON.stringify(body),
    // Explicit rather than relying on the default: the signed principal cookie is what the quota
    // keys on, and this endpoint is same-origin, so this is the credentials mode that matches —
    // stated rather than left to whatever `fetch` happens to default to.
    credentials: 'same-origin',
    signal,
  });

  // Nothing has been yielded yet on either branch below, so both errors are unconditionally
  // "nothing rendered" — stated explicitly at each call site rather than via a default parameter,
  // so a future third call site added here can't silently inherit the wrong value.
  if (!response.ok) throw await explainErrorFrom(response);
  if (!response.body) {
    throw new ExplainStreamError('NO_BODY', 'the explain response had no body', null, false);
  }

  let yieldedAny = false;
  for await (const event of parseSseStream(response.body)) {
    if (event.event === 'error') throw explainErrorFromData(event.data, yieldedAny);
    yieldedAny = true;
    // Unchecked: `event` came back from the generic parser typed as `{event: string, data:
    // unknown}`, and this cast is where that widens into the app's own closed union. Trusting it
    // without validation is deliberate — this is a first-party backend and every other JSON
    // response in this app (ApiService's `http.get<T>()`) is trusted the same way without runtime
    // validation — but it is the trust boundary: an unexpected event name or a malformed payload
    // here would masquerade as a well-typed `ExplainEvent` rather than fail loudly.
    yield event as ExplainEvent;
  }
}

async function explainErrorFrom(response: Response): Promise<ExplainStreamError> {
  try {
    return explainErrorFromData(await response.json(), false);
  } catch {
    return new ExplainStreamError('HTTP_ERROR', `explain failed: ${response.status}`, null, false);
  }
}

function explainErrorFromData(data: unknown, partiallyStreamed: boolean): ExplainStreamError {
  const body = data as Partial<ErrorEventData> | null;
  return new ExplainStreamError(
    body?.code ?? 'UNKNOWN',
    body?.message ?? 'explain failed',
    body?.retryAfter ?? null,
    partiallyStreamed,
  );
}
