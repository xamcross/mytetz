import { parseSseStream, explainStream, ExplainStreamError } from './sse.client';
import { ExplainRequest } from './models';

function streamOf(...chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      chunks.forEach((c) => controller.enqueue(encoder.encode(c)));
      controller.close();
    },
  });
}

/** Delivers only the first `byteLength` bytes of `text`'s UTF-8 encoding, then closes — models a
 * connection that drops mid-byte, which a string-level split cannot construct. */
function truncatedByteStreamOf(text: string, byteLength: number): ReadableStream<Uint8Array> {
  const bytes = new TextEncoder().encode(text).slice(0, byteLength);
  return new ReadableStream({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    },
  });
}

async function collect(stream: ReadableStream<Uint8Array>) {
  const out = [];
  for await (const event of parseSseStream(stream)) out.push(event);
  return out;
}

describe('parseSseStream', () => {
  it('parses complete frames', async () => {
    const events = await collect(
      streamOf(
        'event: meta\ndata: {"cached":false}\n\n',
        'event: delta\ndata: {"t":"Hello"}\n\n',
        'event: done\ndata: {"contentKey":"abc"}\n\n',
      ),
    );

    expect(events.map((e) => e.event)).toEqual(['meta', 'delta', 'done']);
    expect(events[1].data).toEqual({ t: 'Hello' });
  });

  it('reassembles a frame split across chunk boundaries', async () => {
    const events = await collect(streamOf('event: del', 'ta\ndata: {"t":"Hi', ' there"}\n\n'));

    expect(events.length).toBe(1);
    expect(events[0].data).toEqual({ t: 'Hi there' });
  });

  it('handles multiple frames arriving in one chunk', async () => {
    const events = await collect(
      streamOf('event: delta\ndata: {"t":"a"}\n\nevent: delta\ndata: {"t":"b"}\n\n'),
    );

    expect(events.map((e) => (e.data as { t: string }).t)).toEqual(['a', 'b']);
  });

  it('ignores comments and blank keep-alives', async () => {
    const events = await collect(streamOf(': keep-alive\n\nevent: delta\ndata: {"t":"x"}\n\n'));

    expect(events.length).toBe(1);
  });

  it('tolerates CRLF line endings', async () => {
    const events = await collect(streamOf('event: delta\r\ndata: {"t":"x"}\r\n\r\n'));

    expect(events.length).toBe(1);
  });

  // -- Problem A: nothing at end-of-stream may be silently discarded ------------------------

  it('parses a final frame that has no trailing blank line', async () => {
    // The server always terminates frames with a blank line, but a reader must not depend on
    // that: a connection that ends the instant the last byte is written is indistinguishable
    // from one that dropped the trailing "\n\n", and dropping the final `done` silently would
    // leave a caller waiting forever for a stream that already finished successfully.
    const events = await collect(streamOf('event: done\ndata: {"contentKey":"abc"}'));

    expect(events.length).toBe(1);
    expect(events[0]).toEqual({ event: 'done', data: { contentKey: 'abc' } });
  });

  it('flushes a trailing multi-byte sequence that never completes, instead of dropping it silently', async () => {
    // A chunk boundary splitting a character that a LATER chunk goes on to complete needs no
    // special handling: TextDecoder's own `{stream: true}` buffering already stitches that back
    // together (verified separately; not what this test is about). The actual bug is a
    // connection that ends *mid-character*, with nothing left to complete it — e.g. a
    // generation cut off mid-token. "€" is E2 82 AC in UTF-8; only its first byte is delivered.
    // Without a final `decoder.decode()` those dangling bytes are held inside the decoder
    // forever and vanish with no trace. With it, they surface as U+FFFD — corrupted, but visible
    // rather than silently absent.
    const full = 'event: delta\ndata: {"t":"incomplete€"}\n\n';
    const euroByteOffset = new TextEncoder().encode(full.slice(0, full.indexOf('€'))).length;
    const events = await collect(truncatedByteStreamOf(full, euroByteOffset + 1));

    expect(events.length).toBe(1);
    expect(events[0].event).toBe('delta');
    expect((events[0].data as string).endsWith('�')).toBe(true);
  });

  // -- Problem F: strip a single leading space per the SSE spec, not the whole line ---------

  it('strips only a single leading space from a data line, per the SSE spec', async () => {
    // Two leading spaces and a trailing space: only the first leading space is field-name
    // punctuation. A non-JSON payload is used so JSON.parse's own whitespace tolerance can't
    // mask a `.trim()` regression — the raw-string fallback returns exactly what was parsed.
    const events = await collect(streamOf('event: raw\ndata:  x \n\n'));

    expect(events[0]).toEqual({ event: 'raw', data: ' x ' });
  });

  // -- Problem G: CRLF handling must not silently reintroduce a bug at chunk boundaries ------

  it('tolerates a bare CR at the very end of a chunk, completed by LF in the next', async () => {
    const events = await collect(streamOf('event: delta\r', '\ndata: {"t":"y"}\r\n\r\n'));

    expect(events.length).toBe(1);
    expect(events[0]).toEqual({ event: 'delta', data: { t: 'y' } });
  });

  // -- default event name, unpinned by any of the brief's five tests ------------------------

  it('defaults the event name to "message" when the event field is absent', async () => {
    const events = await collect(streamOf('data: {"t":"z"}\n\n'));

    expect(events[0].event).toBe('message');
  });

  // -- multi-line `data:` — spec-correct per the JSDoc, previously untested -----------------

  it('joins multiple data: lines with a newline, per the SSE spec', async () => {
    // Not JSON, deliberately: this wire never sends multi-line data today, so there is no real
    // payload to reach for, and a JSON one would risk hiding a join-order bug behind
    // JSON.parse's own leniency the way Problem F's test avoids for the same reason.
    const events = await collect(streamOf('event: x\ndata: a\ndata: b\n\n'));

    expect(events[0]).toEqual({ event: 'x', data: 'a\nb' });
  });
});

describe('explainStream', () => {
  const originalFetch = globalThis.fetch;
  const requestBody: ExplainRequest = {
    parentNodeId: 'root',
    span: { text: 'x', start: 0, end: 1 },
    verb: 'EXPLAIN',
  };

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  function sseResponse(body: string, init?: ResponseInit): Response {
    return new Response(streamOf(body), {
      status: 200,
      headers: { 'content-type': 'text/event-stream' },
      ...init,
    });
  }

  it('POSTs the body with same-origin credentials and SSE headers, and yields parsed events', async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValue(
        sseResponse(
          'event: meta\ndata: {"contentKey":"k","cached":false}\n\n' +
            'event: done\ndata: {"contentKey":"k","grounded":true}\n\n',
        ),
      );
    globalThis.fetch = fetchSpy as unknown as typeof fetch;

    const events = [];
    for await (const event of explainStream('sess-1', requestBody)) events.push(event);

    expect(events.map((e) => e.event)).toEqual(['meta', 'done']);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe('/api/sessions/sess-1/explain');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('same-origin');
    expect(init.headers).toEqual({
      'content-type': 'application/json',
      accept: 'text/event-stream',
    });
    expect(JSON.parse(init.body)).toEqual(requestBody);
  });

  it('passes an AbortSignal through to fetch when given one', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(sseResponse('event: done\ndata: {}\n\n'));
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    const controller = new AbortController();

    for await (const _ of explainStream('sess-1', requestBody, controller.signal)) {
      // drain
    }

    expect(fetchSpy.mock.calls[0][1].signal).toBe(controller.signal);
  });

  it('throws an ExplainStreamError built from the JSON body when the response is not ok, unmarked as partial', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ code: 'QUOTA_EXCEEDED', message: 'no more today', retryAfter: 3600 }),
        {
          status: 429,
        },
      ),
    ) as unknown as typeof fetch;

    await expect(async () => {
      for await (const _ of explainStream('sess-1', requestBody)) {
        // should not yield before throwing
      }
    }).rejects.toMatchObject({
      code: 'QUOTA_EXCEEDED',
      message: 'no more today',
      retryAfter: 3600,
      partiallyStreamed: false,
    });
  });

  it('falls back to a generic, non-partial error when a non-ok response body is not parseable JSON', async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(new Response('not json', { status: 503 })) as unknown as typeof fetch;

    let caught: unknown;
    try {
      for await (const _ of explainStream('sess-1', requestBody)) {
        // no-op
      }
    } catch (e) {
      caught = e;
    }

    expect(caught).toBeInstanceOf(ExplainStreamError);
    expect((caught as ExplainStreamError).code).toBe('HTTP_ERROR');
    expect((caught as ExplainStreamError).partiallyStreamed).toBe(false);
  });

  it('marks a mid-stream error as partial when prior events were already yielded', async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        sseResponse(
          'event: meta\ndata: {"contentKey":"k","cached":false}\n\n' +
            'event: delta\ndata: {"t":"partial"}\n\n' +
            'event: error\ndata: {"code":"GENERATION_FAILED","message":"model call failed"}\n\n',
        ),
      ) as unknown as typeof fetch;

    const seen: unknown[] = [];
    let caught: unknown;
    try {
      for await (const event of explainStream('sess-1', requestBody)) seen.push(event);
    } catch (e) {
      caught = e;
    }

    expect(seen.map((e) => (e as { event: string }).event)).toEqual(['meta', 'delta']);
    expect(caught).toBeInstanceOf(ExplainStreamError);
    expect((caught as ExplainStreamError).code).toBe('GENERATION_FAILED');
    expect((caught as ExplainStreamError).partiallyStreamed).toBe(true);
  });

  it('does not mark an error as partial when it is the very first event on the wire', async () => {
    // The discriminating case: a 200-status stream is not, by itself, evidence that anything
    // was rendered. Hard-coding `partiallyStreamed: true` for every mid-stream error would pass
    // the test above and fail silently here.
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        sseResponse(
          'event: error\ndata: {"code":"GENERATION_FAILED","message":"failed immediately"}\n\n',
        ),
      ) as unknown as typeof fetch;

    const seen: unknown[] = [];
    let caught: unknown;
    try {
      for await (const event of explainStream('sess-1', requestBody)) seen.push(event);
    } catch (e) {
      caught = e;
    }

    expect(seen).toEqual([]);
    expect(caught).toBeInstanceOf(ExplainStreamError);
    expect((caught as ExplainStreamError).partiallyStreamed).toBe(false);
  });

  it('passes a superseded event through as a typed event rather than treating it as an error', async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        sseResponse(
          'event: meta\ndata: {"contentKey":"k","cached":false}\n\n' +
            'event: superseded\ndata: {"body":"the authoritative text"}\n\n' +
            'event: done\ndata: {"contentKey":"k","grounded":true}\n\n',
        ),
      ) as unknown as typeof fetch;

    const events = [];
    for await (const event of explainStream('sess-1', requestBody)) events.push(event);

    expect(events.map((e) => e.event)).toEqual(['meta', 'superseded', 'done']);
    const superseded = events[1] as { event: string; data: { body: string } };
    expect(superseded.data).toEqual({ body: 'the authoritative text' });
  });
});
