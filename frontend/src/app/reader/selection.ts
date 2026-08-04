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

  const full = root.textContent ?? '';
  const raw = full.slice(start, end);

  // A double-click on a word routinely grabs a leading or trailing space along with it; an
  // untrimmed span would carry that space into `text` and fail the server's exact-match gate the
  // moment the rendered whitespace doesn't collapse identically to the stored body's. Trimming and
  // re-anchoring both ends keeps `text` and `[start, end)` in agreement with each other — and with
  // `full`, since `raw`'s own indices came from `full` in the first place.
  const leading = raw.length - raw.trimStart().length;
  const trailing = raw.length - raw.trimEnd().length;
  const text = raw.trim();
  if (text.length === 0) return null;

  return { text, start: start + leading, end: end - trailing };
}

/**
 * Character offset of the boundary point (`container`, `offset`) within `root.textContent`.
 *
 * Built on `Range.prototype.toString()` rather than a hand-rolled `TreeWalker` walk. The DOM spec
 * defines `Node.textContent` ("descendant text content",
 * https://dom.spec.whatwg.org/#concept-descendant-text-content) and `Range.prototype.toString()`
 * ("stringification behavior", https://dom.spec.whatwg.org/#dom-range-stringifier) identically: both
 * are the concatenation of `Text` node `.data`, in tree order, with nothing else contributing and no
 * separators inserted. So the length of a range from `(root, 0)` to `(container, offset)` *is* the
 * character offset into `root.textContent`, by construction, for any boundary point `setEnd`
 * accepts — including one where `container` is an element with no text-node descendants at all (an
 * empty `<em></em>` sitting between two words, say).
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
