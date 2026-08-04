import { rootTextMatchesBody, selectionToSpan } from './selection';

function elementWith(html: string): HTMLElement {
  const el = document.createElement('div');
  el.innerHTML = html;
  document.body.appendChild(el);
  return el;
}

function rangeOver(node: Node, start: number, end: number): Range {
  const range = document.createRange();
  range.setStart(node, start);
  range.setEnd(node, end);
  return range;
}

describe('selectionToSpan', () => {
  afterEach(() => document.body.replaceChildren());

  it('maps a selection inside a single text node', () => {
    const el = elementWith('Quantum mechanics is the fundamental physical theory.');
    const text = el.firstChild!;

    const span = selectionToSpan(el, rangeOver(text, 25, 52));

    expect(span).toEqual({ text: 'fundamental physical theory', start: 25, end: 52 });
  });

  it('maps a selection spanning two text nodes', () => {
    const el = elementWith('The microscopic <mark>realm</mark> is small.');
    const first = el.childNodes[0];
    const inside = el.querySelector('mark')!.firstChild!;

    const range = document.createRange();
    range.setStart(first, 4);
    range.setEnd(inside, 5);

    const span = selectionToSpan(el, range);

    expect(span!.text).toBe('microscopic realm');
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('microscopic realm');
  });

  // Problem E: the brief's version of this test asserted `slice(start, end) === text`, comparing
  // the function's own outputs to each other. An implementation that is consistently wrong (e.g.
  // off by a fixed amount in a way that still round-trips against itself) would satisfy that. This
  // asserts both against the fixture's known string instead, so a wrong-but-self-consistent offset
  // actually fails.
  it('offsets always index into root.textContent', () => {
    const el = elementWith('alpha <em>beta</em> gamma');
    const gamma = el.childNodes[2];

    const span = selectionToSpan(el, rangeOver(gamma, 1, 6));

    expect(span!.text).toBe('gamma');
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('gamma');
  });

  it('returns null for a collapsed selection', () => {
    const el = elementWith('some text');

    expect(selectionToSpan(el, rangeOver(el.firstChild!, 3, 3))).toBeNull();
  });

  // Problem B: the brief's own diagnosis of this test ("setEnd with a node in a different tree
  // collapses the range, so this passes via the `range.collapsed` check and never reaches
  // `root.contains`") does not hold for what the test actually builds. Per the DOM spec
  // (https://dom.spec.whatwg.org/#concept-range-root, "the root of a live range is the root of its
  // start node"), `el` and `outside` are both children of `document.body`, so they share the same
  // tree root (the Document) — `setEnd` only forces a collapse when the new node's tree root
  // *differs* from the range's current root, or when the new end would land before the current
  // start. Neither holds here: same root, and `outside` follows `el` in document order. I confirmed
  // this empirically against jsdom (the engine these tests actually run under) with a standalone
  // script before writing this comment, not just from reading the spec text: `range.collapsed` is
  // `false`, `endContainer` is `outside.firstChild`, and it genuinely reaches the `root.contains`
  // guard. The `expect(range.collapsed).toBe(false)` below pins that directly, so a future engine
  // (or a jsdom regression) that collapsed this differently would fail loudly here rather than the
  // test silently passing for the reason the brief assumed.
  it('returns null when the selection escapes the root', () => {
    const el = elementWith('inside');
    const outside = elementWith('outside');

    const range = document.createRange();
    range.setStart(el.firstChild!, 0);
    range.setEnd(outside.firstChild!, 3);

    expect(range.collapsed).toBe(false);
    expect(selectionToSpan(el, range)).toBeNull();
  });

  // A genuinely different tree *does* collapse the range (unlike the case above) — this is the
  // scenario the brief's diagnosis actually describes, just attached to the wrong test. Pinned here
  // so the two DOM behaviours aren't conflated: same-document-different-branch reaches
  // `root.contains`; different-document collapses before that guard is ever reached.
  it('returns null for a selection collapsed by crossing into another document', () => {
    const el = elementWith('inside');
    const otherDocument = document.implementation.createHTMLDocument('other');
    const otherRoot = otherDocument.createElement('div');
    otherRoot.textContent = 'far away';
    otherDocument.body.appendChild(otherRoot);

    const range = document.createRange();
    range.setStart(el.firstChild!, 0);
    range.setEnd(otherRoot.firstChild!, 3);

    expect(range.collapsed).toBe(true);
    expect(selectionToSpan(el, range)).toBeNull();
  });

  // Problem C: the brief's single trimming test only covered leading whitespace, despite the prose
  // right above it motivating the feature with the trailing-space case ("double-clicking a word
  // usually grabs a trailing space"). These two tests cover both ends independently, and each pins
  // that the re-anchored offsets round-trip against the fixture's own known text — not just against
  // whatever `text` the function happened to return.
  it('trims leading whitespace and re-anchors the start offset', () => {
    const el = elementWith('the microscopic realm here');

    // Selects " microscopic realm" (indices 3..21): a leading space, no trailing space.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 3, 21));

    expect(span!.text).toBe('microscopic realm');
    expect(span!.start).toBe(4);
    expect(span!.end).toBe(21);
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('microscopic realm');
  });

  it('trims trailing whitespace and re-anchors the end offset', () => {
    const el = elementWith('the microscopic realm here');

    // Selects "microscopic realm " (indices 4..22): a trailing space, no leading space — the case
    // a double-click on "realm" actually produces.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 4, 22));

    expect(span!.text).toBe('microscopic realm');
    expect(span!.start).toBe(4);
    expect(span!.end).toBe(21);
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('microscopic realm');
  });

  it('returns null for a selection that is only whitespace', () => {
    const el = elementWith('the microscopic realm here');

    expect(selectionToSpan(el, rangeOver(el.firstChild!, 3, 4))).toBeNull();
  });

  // Problem D: the brief's `offsetBefore` located a boundary point by walking text nodes and
  // checking `target.contains(node)`. When the target element has no text-node descendants at all
  // (an empty inline element sitting between two words, say), that check never fires, the walker
  // runs off the end of `root`, and the function returned the total length of every text node in
  // `root` as if that were the answer — a plausible-looking offset for a boundary point it could not
  // actually resolve. None of the brief's six tests anchor a boundary on such an element, so this
  // adds one: the range starts at (emptyEm, 0), an element container with zero text children.
  it('resolves a selection anchored on an element with no text-node descendants', () => {
    const el = elementWith('alpha<em></em>beta');
    const em = el.querySelector('em')!;
    const betaText = el.childNodes[2];

    const range = document.createRange();
    range.setStart(em, 0);
    range.setEnd(betaText, 2);

    const span = selectionToSpan(el, range);

    expect(span!.text).toBe('be');
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('be');
  });

  // Symmetric case: the test above anchors an empty-element container at the *start* of the
  // range; nothing covered one as the *end*. `offsetOf` treats both containers identically, so the
  // underlying argument (Range's own boundary-point placement, not a text-node search) covers this
  // either way — this pins that rather than leaving it assumed.
  it('resolves a selection ending on an element with no text-node descendants', () => {
    const el = elementWith('alpha<em></em>beta');
    const alphaText = el.firstChild!;
    const em = el.querySelector('em')!;

    const range = document.createRange();
    range.setStart(alphaText, 2);
    range.setEnd(em, 0);

    const span = selectionToSpan(el, range);

    expect(span!.text).toBe('pha');
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('pha');
  });

  // Review item 2: the server's exact-match gate compares against a Kotlin `String`, which — like
  // JavaScript's — indexes by UTF-16 code unit, not Unicode code point. A character outside the
  // Basic Multilingual Plane (this microscope emoji, U+1F52C) is a surrogate *pair*: two code units
  // for one visible character. `String.prototype.length`/`.slice()` already count and index in code
  // units, so nothing in this file has to special-case it — but nothing pinned that the two
  // languages' models actually agree, and this is the one place in the project where a correctness
  // property spans both. If it ever broke, the symptom would be SPAN_MISMATCH on exactly the
  // explanations containing an emoji or similar. Indices verified by hand against the fixture
  // string's UTF-16 code units before writing this test's expectations.
  it('round-trips offsets across an astral-plane character (surrogate pair)', () => {
    const el = elementWith('before 🔬 after');

    const span = selectionToSpan(el, rangeOver(el.firstChild!, 7, 9));

    expect(span!.text).toBe('🔬');
    expect(span!.start).toBe(7);
    expect(span!.end).toBe(9);
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('🔬');
  });
});

// Problem A: `selectionToSpan`'s offsets are only correct if `root.textContent` is
// character-for-character identical to the stored explanation body the server validates against —
// nothing about `root` alone can tell whether that holds, since this function never sees the
// server's string. `rootTextMatchesBody` is the mechanical check 1.15/1.16 are expected to run once
// after rendering, before wiring up selection handling; these tests pin what it does and does not
// catch.
describe('rootTextMatchesBody', () => {
  afterEach(() => document.body.replaceChildren());

  it('is true when root.textContent exactly matches the stored body', () => {
    const el = elementWith('Quantum mechanics is the fundamental physical theory.');

    expect(rootTextMatchesBody(el, 'Quantum mechanics is the fundamental physical theory.')).toBe(
      true,
    );
  });

  // The invariant-violating case Problem A describes: a control inside `root` that contributes
  // characters `textContent` cannot distinguish from body text. `selectionToSpan` itself would
  // still return a self-consistent span here (`root.textContent.slice(start, end) === text` still
  // holds) — the whole danger is that nothing *looks* wrong locally. This is what
  // `rootTextMatchesBody` exists to catch instead.
  it('is false when root contains an element that contributes extra text', () => {
    const el = elementWith(
      'Quantum mechanics is the fundamental physical theory.<button>Explain</button>',
    );

    expect(rootTextMatchesBody(el, 'Quantum mechanics is the fundamental physical theory.')).toBe(
      false,
    );
  });
});
