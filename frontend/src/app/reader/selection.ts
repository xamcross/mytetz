import { SpanPayload } from '../core/models';

/**
 * Converts a DOM Range into character offsets over `root.textContent`.
 *
 * ## The invariant this depends on
 *
 * The server validates a highlight by checking `text === storedBody.substring(start, end)`
 * ({@link https://dom.spec.whatwg.org/} has nothing to do with that check — it lives in the
 * backend's `SessionService.validateSpan`) — so the offsets returned here are only correct if
 * `root.textContent` is character-for-character identical to the stored explanation body the server
 * is checking against. Nothing in this file can verify that: `selectionToSpan` only ever sees
 * `root`, never the server's string.
 *
 * Concretely, that means whoever renders `root` (the reader components built in 1.15/1.16) must
 * make sure nothing inside it contributes characters to `textContent` beyond the body itself:
 * - no buttons, icon labels, or visually-hidden hints inside `root`;
 * - no highlight/mark wrapper that inserts its own characters;
 * - splitting the body across multiple block elements is fine — `textContent` walks every
 *   descendant regardless of nesting — but anything that relied on an element's *rendering* (a
 *   `<br>`, a CSS `white-space` gap) to stand in for a character that exists in the stored string
 *   will silently go missing from `textContent`, which contains none of that.
 *
 * Get this wrong and the failure mode is not a crash: `selectionToSpan` still returns a
 * self-consistent `{text, start, end}` (`root.textContent.slice(start, end) === text` holds
 * regardless) — `start`/`end` just no longer line up with the server's string, so every explain
 * request built from it fails `SPAN_MISMATCH`, from code that looks correct in isolation.
 *
 * `rootTextMatchesBody` below gives that invariant a mechanical check, for 1.15/1.16 to run once
 * after rendering — see its own doc comment for what it does and does not cover.
 *
 * Returns `null` when the selection is empty (`range.collapsed`), when either end of it lies
 * outside `root`, or when the selected text is empty or all whitespace.
 */
export function selectionToSpan(root: HTMLElement, range: Range): SpanPayload | null {
  if (range.collapsed) return null;
  if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return null;

  const start = offsetOf(root, range.startContainer, range.startOffset);
  const end = offsetOf(root, range.endContainer, range.endOffset);

  // No `start >= end` guard: a live Range's start can never be positioned after its end (the
  // invariant `setStart`/`setEnd` both maintain), so `start <= end` always holds here. The one case
  // where they'd come out numerically *equal* despite `range` being non-collapsed — boundary points
  // that differ in container/offset but have no text between them, e.g. either side of an empty
  // element — falls out of the `text.length === 0` check below instead, since the trimmed slice is
  // then `''`.
  const full = root.textContent ?? '';

  // Issue #169. A learner's drag rarely lands on a word's own edge: a double-click grabs a
  // leading or trailing space, and a drag that stops a little short or a little long lands inside
  // a word instead of at its edge. `snapToWholeWords` gives both problems one fix: it trims
  // whitespace and punctuation from the two ends, then grows each end outward to the edge of the
  // word it now stands inside, if it stands inside one at all. See that function's own comment for
  // the two ways it can decide where a word ends.
  const snapped = snapToWholeWords(full, start, end);
  const text = full.slice(snapped.start, snapped.end);
  if (text.length === 0) return null;

  return { text, start: snapped.start, end: snapped.end };
}

/**
 * A run of word characters inside a string, as a half-open range: `[start, end)`.
 *
 * `wordRunsBySegmenter` and `wordRunsByPattern` below build this list two different ways, one
 * per browser, but `snapToWholeWords` reads only this shape — so the two producers stay
 * interchangeable, and a test can force either one.
 */
interface WordRun {
  readonly start: number;
  readonly end: number;
}

/**
 * A character the phrase must never start or end on: Unicode whitespace, or Unicode punctuation
 * (`\p{P}`, every script's own comma, full stop, quote mark and dash). A phrase that keeps a
 * stray full stop reads as two words joined, not as the one word the learner meant — the
 * issue's own "electrons." example. A symbol such as an emoji is not punctuation, so this leaves
 * it alone; a learner who selects one on purpose keeps it, the same as before this issue.
 */
const EDGE_TRIM_PATTERN = /[\s\p{P}]/u;

/**
 * One word: a run of letters and digits (`\p{L}` and `\p{N}` — every script's own letters and
 * digits, not only Latin ones), with a single apostrophe or hyphen allowed between two such runs.
 * That one rule covers "don't" and "well-known" alike, and it also covers a chain of them, such
 * as "well-known-enough".
 */
const WORD_PATTERN = /[\p{L}\p{N}]+(?:['’-][\p{L}\p{N}]+)*/gu;

/**
 * The regex fallback's word runs, for a browser with no `Intl.Segmenter`.
 *
 * This finds every match of [WORD_PATTERN] in `text`, in order. It has one real limit next to
 * `wordRunsBySegmenter`: a script written with no space between words, such as Chinese or
 * Japanese, has no gap for the pattern to stop at, so a whole run of that script's own letters
 * comes back as a single "word" — correct for the tests this issue asks for (Latin script, and
 * Cyrillic or Greek letters, all space-separated), but not the true word boundary for a script
 * that carries none.
 */
function wordRunsByPattern(text: string): WordRun[] {
  const runs: WordRun[] = [];
  for (const match of text.matchAll(WORD_PATTERN)) {
    runs.push({ start: match.index, end: match.index + match[0].length });
  }
  return runs;
}

/**
 * `Intl.Segmenter`'s own word runs — `isWordLike` segments only, since a space or a punctuation
 * mark is its own segment too and is never a word.
 *
 * `Intl.Segmenter` knows the true word rule of every script it supports, including a script
 * written with no space between words. The MDN compatibility table
 * (https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl/Segmenter)
 * lists it for Chrome 87+, Edge 87+ and Safari 14.1+, and for Firefox only from version 125
 * (April 2024) — read from MDN's own browser-compat-data for this page, not guessed. A learner on
 * an older Firefox, or on another browser with no `Intl.Segmenter` at all, gets [wordRunsByPattern]
 * instead; [chooseWordRuns] below is the one place that decides between them, checked fresh on
 * every call so a test can force either path.
 */
function wordRunsBySegmenter(text: string): WordRun[] {
  const segmenter = new Intl.Segmenter(undefined, { granularity: 'word' });
  const runs: WordRun[] = [];
  for (const { segment, index, isWordLike } of segmenter.segment(text)) {
    if (isWordLike) runs.push({ start: index, end: index + segment.length });
  }
  return runs;
}

function chooseWordRuns(text: string): WordRun[] {
  return typeof Intl.Segmenter === 'function' ? wordRunsBySegmenter(text) : wordRunsByPattern(text);
}

/**
 * Trims whitespace and punctuation from the two ends of `[start, end)`, then grows each end
 * outward to the edge of the word it stands inside, if it stands inside one.
 *
 * The two steps run in that order on purpose. Trimming first means a trailing full stop is gone
 * before the growth step ever looks at that end, so growth never has to tell "the learner's drag
 * ended on this word" apart from "the learner's drag ended on the punctuation after this word" —
 * by the time growth runs, only the second case is still possible, since trimming already
 * removed the first.
 *
 * Growth touches only a boundary that lands strictly inside a word. A boundary already on a
 * word's own edge is left exactly where it is, and a boundary that lands on a character that is
 * no word at all (a symbol, or a script `Intl.Segmenter` does not treat as word-like) is left
 * alone too — this only ever moves a boundary outward, never inward, and never invents a word
 * where there is none.
 *
 * Returns `start === end` when nothing is left after trimming — a selection that held only
 * whitespace, only punctuation, or both, the same "no span" outcome an all-whitespace selection
 * already gave before this issue.
 */
function snapToWholeWords(
  full: string,
  rawStart: number,
  rawEnd: number,
): { start: number; end: number } {
  let start = rawStart;
  let end = rawEnd;
  while (start < end && EDGE_TRIM_PATTERN.test(full[start])) start++;
  while (end > start && EDGE_TRIM_PATTERN.test(full[end - 1])) end--;
  if (start === end) return { start, end };

  const runs = chooseWordRuns(full);
  const startRun = runs.find((run) => run.start <= start && start < run.end);
  if (startRun) start = startRun.start;
  const endRun = runs.find((run) => run.start < end && end <= run.end);
  if (endRun) end = endRun.end;
  return { start, end };
}

/**
 * Character offset of the boundary point (`container`, `offset`) within `root.textContent`.
 *
 * Built on `Range.prototype.toString()` rather than a hand-rolled `TreeWalker` walk. The DOM spec
 * defines `Node.textContent` ("descendant text content",
 * https://dom.spec.whatwg.org/#concept-descendant-text-content) and `Range.prototype.toString()`
 * ("stringification behavior", https://dom.spec.whatwg.org/#dom-range-stringifier) identically: both
 * are the concatenation of `Text` node `.data`, in tree order, with nothing else contributing and no
 * separators inserted. That identity alone isn't enough to justify this, though — it says the two
 * concatenation *rules* match, not that this call sums the right *subset* of text. What scopes the
 * sum to `root`'s own subtree is anchoring the marker's start at `(root, 0)`: per the spec's
 * definition of "contained" (https://dom.spec.whatwg.org/#contained), a node only contributes to a
 * range's stringification if its root equals the range's root *and* it falls entirely between the
 * range's start and end in tree order. With start fixed at `(root, 0)`, nothing positioned before
 * `root`'s own content — an ancestor's earlier text, an earlier sibling of `root` elsewhere in the
 * document — can be "after start", so none of it is contained; and nothing positioned after
 * `(container, offset)` — `root`'s own later content, `root`'s later siblings — is "before end"
 * either. What's left, by elimination, is exactly the prefix of `root.textContent` up to the
 * boundary point, which is what makes the length of this range equal the offset this function
 * returns.
 *
 * So the length of a range from `(root, 0)` to `(container, offset)` *is* the character offset into
 * `root.textContent`, by construction, for any boundary point `setEnd` accepts — including one where
 * `container` is an element with no text-node descendants at all (an empty `<em></em>` sitting
 * between two words, say).
 *
 * A `TreeWalker(root, SHOW_TEXT)` walk cannot locate that boundary point without a second,
 * error-prone fallback pass: finding no text node inside the target, it has nothing to stop it
 * short of the end of `root`, and the total it accumulates by then — the length of every text node
 * in `root` — is not "no answer", it's a wrong one that happens to type-check. Deferring to `Range`
 * sidesteps that failure mode instead of patching around it: the browser's own boundary-point
 * algorithm already knows how to place `(container, offset)` in tree order without needing a text
 * node to land on, because it isn't looking for one — it's looking for a position.
 *
 * Requires `container` to be `root` or a descendant of `root` (verified by the caller via
 * `root.contains` before this runs); `setEnd` does not itself guarantee that, and a `container`
 * outside `root`'s tree can force the constructed range to collapse — see `selectionToSpan`'s tests
 * for both the same-document-different-branch case (does not collapse; caught by `root.contains`
 * instead) and the different-document case (does collapse) this file's tests pin separately.
 */
function offsetOf(root: HTMLElement, container: Node, offset: number): number {
  const marker = document.createRange();
  marker.setStart(root, 0);
  marker.setEnd(container, offset);
  return marker.toString().length;
}

/**
 * Mechanically checks the invariant documented on `selectionToSpan`: that `root.textContent` is
 * exactly the stored explanation `body` the server will validate offsets against.
 *
 * Intended for 1.15/1.16 to call once, right after rendering `root`'s content and before wiring up
 * selection handling — not on every selection, and not as a substitute for getting the render right
 * in the first place.
 *
 * What this does not cover: it says nothing about *why* the two strings diverge (a stray control, a
 * dropped separator, stale content not yet re-rendered) — only that they do, or don't, match right
 * now. A selection made in the gap between a body update and its re-render is also outside what a
 * single post-render check can catch; that's a lifecycle concern for the caller, not something this
 * function can see.
 */
export function rootTextMatchesBody(root: HTMLElement, body: string): boolean {
  return (root.textContent ?? '') === body;
}
