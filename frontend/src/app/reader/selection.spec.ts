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
  //
  // Issue #169: the fixture carries a space before `<em>`, and not `'alpha<em></em>beta'` as the
  // brief first had it. With no space there, `root.textContent` reads as the one glued word
  // "alphabeta" — genuinely one word, with no gap anywhere for a word rule to find — so a
  // selection of "be" would grow to the whole "alphabeta" and this test would no longer be about
  // an element with no text-node descendants at all, only about word growth. The space keeps
  // "alpha" and "beta" two separate words, so this test still isolates what it always meant to:
  // `offsetOf` resolving a boundary anchored on the empty `<em>`.
  it('resolves a selection anchored on an element with no text-node descendants', () => {
    const el = elementWith('alpha <em></em>beta');
    const em = el.querySelector('em')!;
    const betaText = el.childNodes[2];

    const range = document.createRange();
    range.setStart(em, 0);
    range.setEnd(betaText, 2);

    const span = selectionToSpan(el, range);

    // The raw selection is "be" (2 characters into "beta"), which stands inside that word: issue
    // #169 grows it out to the word's own edge, "beta", the same as any other mid-word end.
    expect(span!.text).toBe('beta');
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('beta');
  });

  // Symmetric case: the test above anchors an empty-element container at the *start* of the
  // range; nothing covered one as the *end*. `offsetOf` treats both containers identically, so the
  // underlying argument (Range's own boundary-point placement, not a text-node search) covers this
  // either way — this pins that rather than leaving it assumed. See the test above's own comment
  // on issue #169 for why the fixture carries a space before `<em>`.
  it('resolves a selection ending on an element with no text-node descendants', () => {
    const el = elementWith('alpha <em></em>beta');
    const alphaText = el.firstChild!;
    const em = el.querySelector('em')!;

    const range = document.createRange();
    range.setStart(alphaText, 2);
    range.setEnd(em, 0);

    const span = selectionToSpan(el, range);

    // The raw selection is "pha " (2 characters into "alpha", plus the space before `<em>`): issue
    // #169 drops that trailing space and grows the start out to "alpha"'s own edge.
    expect(span!.text).toBe('alpha');
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('alpha');
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

/**
 * Issue #169. A learner's drag rarely lands on a word's own edge, and the server's exact-match
 * gate has no rule of its own to fix that up — so `selectionToSpan` must always hand back whole
 * words, with `text`, `start` and `end` moving together the same way the tests above already
 * pin.
 *
 * Runs `Intl.Segmenter` twice over, not once: the default run uses whatever this project's own
 * test environment supplies, and `withoutSegmenter` forces the regex fallback for the same
 * fixtures — see that helper's own comment for why a real `Intl.Segmenter` call cannot be left
 * out of a test suite that claims to cover both code paths.
 */
describe('selectionToSpan — snapping to whole words', () => {
  afterEach(() => document.body.replaceChildren());

  // Confirmed once, for the record: this project's unit tests run under Node's own `Intl`, which
  // has had `Intl.Segmenter` since Node 16 — so every test below that does not call
  // `withoutSegmenter` exercises the real `Intl.Segmenter` path, not a stand-in for it.
  it('this test environment actually has Intl.Segmenter, so the plain tests below use the real one', () => {
    expect(typeof Intl.Segmenter).toBe('function');
  });

  /**
   * Forces `snapToWholeWords`'s regex fallback for the duration of `fn`, by removing
   * `Intl.Segmenter` from the global `Intl` object and putting it back afterwards.
   *
   * `selection.ts` checks `typeof Intl.Segmenter` fresh on every call rather than once at import
   * time, which is what makes this safe: deleting the constructor here changes what the *next*
   * call to `selectionToSpan` sees, and restoring it afterwards leaves every other test in this
   * file, and every other file, none the wiser.
   */
  function withoutSegmenter<T>(fn: () => T): T {
    const withOptionalSegmenter = Intl as unknown as { Segmenter?: typeof Intl.Segmenter };
    const original = withOptionalSegmenter.Segmenter;
    delete withOptionalSegmenter.Segmenter;
    try {
      return fn();
    } finally {
      withOptionalSegmenter.Segmenter = original;
    }
  }

  // The evidence in the issue itself: "arrangement" and "electrons", each cut into by a drag
  // that stopped a little short of one word's edge and a little long past the other's.
  const ARRANGEMENT = 'the arrangement of electrons is complex.';

  function growsArrangementAndElectrons(): void {
    const el = elementWith(ARRANGEMENT);

    // Selects "angement of elect" (7..24): starts inside "arrangement", ends inside "electrons".
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 7, 24));

    expect(span!.text).toBe('arrangement of electrons');
    expect(span!.start).toBe(4);
    expect(span!.end).toBe(28);
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('arrangement of electrons');
  }

  it('grows a selection that starts inside a word to the start of that word', () => {
    const el = elementWith(ARRANGEMENT);

    // Selects "angement" (7..15): starts inside "arrangement", ends exactly on its own edge.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 7, 15));

    expect(span!.text).toBe('arrangement');
    expect(span!.start).toBe(4);
    expect(span!.end).toBe(15);
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('arrangement');
  });

  it('grows a selection that ends inside a word to the end of that word', () => {
    const el = elementWith(ARRANGEMENT);

    // Selects "elect" (19..24): starts exactly on "electrons"'s own edge, ends inside it.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 19, 24));

    expect(span!.text).toBe('electrons');
    expect(span!.start).toBe(19);
    expect(span!.end).toBe(28);
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('electrons');
  });

  it(
    'grows a selection that starts and ends inside two different words, at once',
    growsArrangementAndElectrons,
  );

  it('does not change a selection that already stands on word edges', () => {
    const el = elementWith(ARRANGEMENT);

    // Selects "electrons" (19..28) exactly: both ends already sit on that word's own edge.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 19, 28));

    expect(span!.text).toBe('electrons');
    expect(span!.start).toBe(19);
    expect(span!.end).toBe(28);
  });

  it('gives the whole word for a selection that stands entirely inside one long word', () => {
    const el = elementWith(ARRANGEMENT);

    // Selects "range" (6..11): both ends sit inside "arrangement", neither on its edge.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 6, 11));

    expect(span!.text).toBe('arrangement');
    expect(span!.start).toBe(4);
    expect(span!.end).toBe(15);
  });

  it('drops a full stop at the end of the phrase: "electrons." gives "electrons"', () => {
    const el = elementWith('Atoms bond mainly by sharing electrons. Ions differ.');

    // Selects "electrons." (29..39): starts exactly on the word's edge, ends past the full stop.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 29, 39));

    expect(span!.text).toBe('electrons');
    expect(span!.start).toBe(29);
    expect(span!.end).toBe(38);
    expect(el.textContent!.slice(span!.start, span!.end)).toBe('electrons');
  });

  // The issue's own evidence again, in miniature: a drag that starts inside a word and also
  // runs on past a full stop, both at once.
  it('grows a mid-word start and drops a trailing full stop in the one selection', () => {
    const el = elementWith('Atoms bond mainly by sharing electrons. Ions differ.');

    // Selects "ctrons." (32..39): starts inside "electrons", ends past its full stop.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 32, 39));

    expect(span!.text).toBe('electrons');
    expect(span!.start).toBe(29);
    expect(span!.end).toBe(38);
  });

  it('gives no span for a selection that holds only punctuation', () => {
    const el = elementWith('Hello ... World');

    // Selects "..." (6..9): three full stops, no letter or digit anywhere in it.
    expect(selectionToSpan(el, rangeOver(el.firstChild!, 6, 9))).toBeNull();
  });

  it('keeps an apostrophe inside a word part of that one word: "doesn\'t"', () => {
    const el = elementWith("The switch doesn't work in this well-known model.");

    // Selects "esn'" (14..18): inside "doesn't", on both sides of the apostrophe.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 14, 18));

    expect(span!.text).toBe("doesn't");
    expect(span!.start).toBe(11);
    expect(span!.end).toBe(18);
    expect(el.textContent!.slice(span!.start, span!.end)).toBe("doesn't");
  });

  it('keeps a hyphen inside a word part of that one word: "well-known"', () => {
    const el = elementWith("The switch doesn't work in this well-known model.");

    // Selects "l-know" (34..40): inside "well-known", on both sides of the hyphen.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 34, 40));

    expect(span!.text).toBe('well-known');
    expect(span!.start).toBe(32);
    expect(span!.end).toBe(42);
  });

  it('applies the same word rule to Cyrillic letters', () => {
    // "the charge is called an electron and the letter alpha", in Russian.
    const el = elementWith('В формуле встречается символ электрон и буква альфа.');

    // Selects "лект" (31..35): inside "электрон", touching neither of its own edges.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 31, 35));

    expect(span!.text).toBe('электрон');
    expect(span!.start).toBe(29);
    expect(span!.end).toBe(37);
  });

  it('applies the same word rule to Greek letters', () => {
    // "the charge is called electron in physics", in Greek.
    const el = elementWith('Το φορτίο ονομάζεται ηλεκτρόνιο στη φυσική.');

    // Selects "ορτί" (4..8): inside "φορτίο", touching neither of its own edges.
    const span = selectionToSpan(el, rangeOver(el.firstChild!, 4, 8));

    expect(span!.text).toBe('φορτίο');
    expect(span!.start).toBe(3);
    expect(span!.end).toBe(9);
  });

  // Problem-E discipline again (see the top describe block's own note on it): every assertion
  // above checks the fixture's own known text, not the function's own output round-tripped
  // against itself. The fallback tests below reuse that same discipline against the same known
  // strings, so a divergence between the two code paths shows up as a wrong answer, not a
  // self-consistent one.
  describe('the regex fallback (no Intl.Segmenter)', () => {
    it('grows a mid-word start and a mid-word end the same way the default path does', () => {
      withoutSegmenter(growsArrangementAndElectrons);
    });

    it('drops a full stop at the end of the phrase the same way the default path does', () => {
      withoutSegmenter(() => {
        const el = elementWith('Atoms bond mainly by sharing electrons. Ions differ.');

        const span = selectionToSpan(el, rangeOver(el.firstChild!, 32, 39));

        expect(span!.text).toBe('electrons');
        expect(span!.start).toBe(29);
        expect(span!.end).toBe(38);
      });
    });

    it('keeps an apostrophe and a hyphen inside a word the same way the default path does', () => {
      withoutSegmenter(() => {
        const el = elementWith("The switch doesn't work in this well-known model.");

        const doesnt = selectionToSpan(el, rangeOver(el.firstChild!, 14, 18));
        expect(doesnt!.text).toBe("doesn't");

        const wellKnown = selectionToSpan(el, rangeOver(el.firstChild!, 34, 40));
        expect(wellKnown!.text).toBe('well-known');
      });
    });

    it('applies the same word rule to Cyrillic letters as the default path does', () => {
      withoutSegmenter(() => {
        const el = elementWith('В формуле встречается символ электрон и буква альфа.');

        const span = selectionToSpan(el, rangeOver(el.firstChild!, 31, 35));

        expect(span!.text).toBe('электрон');
        expect(span!.start).toBe(29);
        expect(span!.end).toBe(37);
      });
    });

    it('gives no span for a selection that holds only punctuation, the same as the default path', () => {
      withoutSegmenter(() => {
        const el = elementWith('Hello ... World');

        expect(selectionToSpan(el, rangeOver(el.firstChild!, 6, 9))).toBeNull();
      });
    });
  });
});
