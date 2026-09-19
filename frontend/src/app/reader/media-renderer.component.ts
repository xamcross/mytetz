import { Component, computed, effect, input, signal } from '@angular/core';
import { Media } from '../core/models';

/** The fixed word an image gets when its SVG carries no `<title>` element. */
const FALLBACK_ALT = 'Diagram';

/**
 * Renders a `VISUALIZE` explanation's media: the diagram always, the image and its attribution
 * only when Wikimedia Commons found one.
 *
 * ## The diagram never goes through `[innerHTML]`
 *
 * Angular's own HTML sanitiser has no `svg` element on its allowlist — read at
 * `https://raw.githubusercontent.com/angular/angular/main/packages/core/src/sanitization/html_sanitizer.ts`
 * on 2026-09-19, whose own comment states: "this currently consciously doesn't support SVG." A
 * `[innerHTML]` binding would strip or mangle the diagram. This component instead builds a
 * `data:image/svg+xml` URL from the server's own sanitised SVG source, and binds it to an `<img>`
 * element's ordinary `[src]`. Angular's URL sanitiser allows a `data:` URL through an ordinary
 * `[src]` binding — read at
 * `https://raw.githubusercontent.com/angular/angular/main/packages/core/src/sanitization/url_sanitizer.ts`
 * on 2026-09-19, whose `SAFE_URL_PATTERN` blocks only a `javascript:` scheme. No `DomSanitizer`
 * method is called anywhere in this file.
 *
 * The browser itself is the second boundary. MDN's own page on SVG used as an image — read at
 * `https://developer.mozilla.org/en-US/docs/Web/SVG/Guides/SVG_as_an_image` on 2026-09-19 — states
 * that a script inside such an SVG does not run, and an external reference inside it does not
 * load, and that both restrictions apply to an `<img>` element specifically.
 *
 * **The namespace matters.** A `data:image/svg+xml` URL is parsed as a standalone XML document,
 * never merged into the host page, and a browser decodes it as an image only when its root
 * element carries `xmlns="http://www.w3.org/2000/svg"`. Without that attribute the `<img>` never
 * fires `load`, and a learner sees a broken image with no console error — the server's own
 * sanitiser adds it to every document this component ever receives, so a hand-written fixture
 * that drops it, in a test or anywhere else, no longer describes a document this feature can
 * actually produce.
 *
 * ## A diagram that fails to decode
 *
 * A learner must never see a bare broken-image icon after a generation that spent one unit of
 * their allowance. The `<img>`'s own `(error)` event — raised whenever the browser could not
 * decode the `src` it was given, the missing-namespace case above among them — sets
 * [diagramFailed], which swaps the diagram frame and the zoom control for one `role="status"`
 * line instead. [diagramFailed] resets on its own the moment [media] changes to a different
 * diagram, through the constructor's own `effect`: this component's instance can outlive one
 * focus node (`SessionStore.currentMedia` supplies a new value as the learner moves between
 * nodes), so a failure of an earlier diagram must not hide a later diagram that decodes.
 *
 * ## The accessible title
 *
 * An `<img>` carries no `<title>` element of its own, so the accessible name comes from the
 * `alt` attribute instead. This component parses the already-sanitised SVG string once, with the
 * browser's own `DOMParser`, and reads the first `<title>` element's text. It never inserts the
 * parsed content into the live document; it only reads a text value out of it. It falls back to a
 * fixed word when the SVG carries no `<title>` element.
 *
 * A source that does not parse never throws: `DOMParser` returns a document that carries a
 * `<parsererror>` element instead — confirmed by hand against both jsdom (this component's own
 * unit tests) and a real Chromium, which use two different namespaces for that element, but
 * `querySelector('parsererror')` matches either one, since a plain CSS selector ignores
 * namespaces. A real Chromium was also confirmed to keep parsing past the error and to still
 * return an earlier, well-formed `<title>` element if one came before it — so this check runs
 * before the `<title>` lookup and answers the fixed fallback word outright, rather than reading
 * one further into a document the server's own sanitiser should never have let reach this far.
 *
 * ## The Wikimedia Commons image
 *
 * `image` is Wikimedia's own hosted file, reached through an ordinary `[src]`, `loading="lazy"`
 * and `referrerpolicy="no-referrer"` — the last so that Wikimedia's own server never learns which
 * session, and so which learner, requested the file. `alt` is the file's own title text, a plain
 * string field, not HTML.
 *
 * The requirements name this a licence obligation: "the UI always shows the attribution next to
 * the image." The license text links to `commonsPageUrl`, with `target="_blank"` and
 * `rel="noopener noreferrer"` — the standard pair against the two hazards a `target="_blank"` link
 * opens: the new tab reaching back into `window.opener`, and the new tab inheriting this page's
 * own referrer.
 *
 * ## The attribution
 *
 * `attributionHtml` is a small HTML string the server built itself from Wikimedia's own metadata —
 * plain text plus at most one `https` link, never a Commons editor's own markup passed through. A
 * plain anchor and plain text sit inside Angular's sanitiser allowlist, so an ordinary
 * `[innerHTML]` binding here is a genuine check, unlike the dead end an `<svg>` would be. This
 * binding calls no `DomSanitizer` method either. This component's own spec proves the sanitiser
 * runs on the real DOM, with no spy standing in for it: a hostile value carrying a `<script>` and
 * an `onerror` attribute is bound through this same path, and neither survives.
 *
 * ## The zoom control
 *
 * The requirements ask for a zoomable diagram. One button toggles the frame between two classes:
 * fitted to the card, and a larger, fixed width inside a frame that already scrolls
 * (`.media__diagram-frame`'s own `overflow: auto`). The diagram never opens in a new tab or window:
 * a browser blocks a `data:` URL navigation at the top level, and an SVG a browser opens as its own
 * document — rather than draws as an image — can run a script, the exact boundary Decision 7 keeps
 * closed by using `<img>` at all.
 */
@Component({
  selector: 'app-media-renderer',
  imports: [],
  template: `
    <figure class="media">
      @if (diagramFailed()) {
        <p class="media__diagram-fallback" role="status">The diagram could not be shown.</p>
      } @else {
        <div class="media__diagram-frame" [class.media__diagram-frame--zoomed]="zoomed()">
          <img
            class="media__diagram"
            [src]="diagramSrc()"
            [alt]="diagramAlt()"
            (error)="onDiagramError()"
          />
        </div>
        <button
          type="button"
          class="media__zoom mt-pill mt-pill--ghost"
          [attr.aria-pressed]="zoomed()"
          (click)="toggleZoom()"
        >
          {{ zoomed() ? 'Fit to card' : 'View larger' }}
        </button>
      }

      @if (media().image; as image) {
        <div class="media__image">
          <img
            class="media__image-photo"
            [src]="image.imageUrl"
            [alt]="image.title"
            loading="lazy"
            referrerpolicy="no-referrer"
          />
          <figcaption class="media__caption" data-testid="attribution">
            <a
              class="media__license"
              [href]="image.commonsPageUrl"
              target="_blank"
              rel="noopener noreferrer"
              >{{ image.license }}</a
            >
            <span class="media__attribution" [innerHTML]="image.attributionHtml"></span>
          </figcaption>
        </div>
      }
    </figure>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .media {
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      /* The frame itself always scrolls, so a diagram wider than the card never overflows it,
         zoomed or not. The zoom control below only changes the image's own width inside it. */
      .media__diagram-frame {
        overflow: auto;
        max-width: 100%;
        border-radius: var(--mt-r-panel);
        background: var(--mt-sunk);
      }
      .media__diagram {
        display: block;
        max-width: 100%;
      }
      /* Fixed rather than percentage-of-viewport: a diagram is a handful of simple shapes, and a
         fixed larger width is enough to read the detail a "fit to card" view compresses, without
         a second layout system to size it against. */
      .media__diagram-frame--zoomed .media__diagram {
        max-width: none;
        width: 640px;
      }
      .media__zoom {
        align-self: flex-start;
      }
      .media__diagram-fallback {
        margin: 0;
        padding: 14px 16px;
        border-radius: var(--mt-r-panel);
        background: var(--mt-err-bg);
        border: var(--mt-border-w) solid var(--mt-err-border);
        color: var(--mt-err-ink);
        font-size: 13px;
        font-weight: 700;
      }
      .media__image {
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .media__image-photo {
        display: block;
        max-width: 100%;
        border-radius: var(--mt-r-panel);
      }
      .media__caption {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        font-size: 12px;
        font-weight: 600;
        color: var(--mt-muted);
      }
      .media__license {
        font-weight: 700;
        color: inherit;
      }
    `,
  ],
})
export class MediaRendererComponent {
  readonly media = input.required<Media>();

  /** Whether the diagram shows at its larger, fixed width. Local to this component: the choice
   * does not outlive the card, and no other component reads it. */
  protected readonly zoomed = signal(false);

  /** Whether the diagram's own `<img>` raised `error` — see this class's own doc comment, "A
   * diagram that fails to decode". */
  protected readonly diagramFailed = signal(false);

  protected readonly diagramSrc = computed(
    () => 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(this.media().diagram.source),
  );

  protected readonly diagramAlt = computed(() => {
    const source = this.media().diagram.source;
    const parsed = new DOMParser().parseFromString(source, 'image/svg+xml');
    // Checked first, and answered outright: a real Chromium keeps parsing past the error and can
    // still hand back an earlier, well-formed <title> — see this class's own doc comment.
    if (parsed.querySelector('parsererror')) return FALLBACK_ALT;
    const title = parsed.querySelector('title')?.textContent?.trim();
    return title && title.length > 0 ? title : FALLBACK_ALT;
  });

  constructor() {
    // A new diagram source clears the failure flag. The doc comment of this class gives the
    // reason: an earlier failure must not hide a later diagram.
    effect(() => {
      this.diagramSrc();
      this.diagramFailed.set(false);
    });
  }

  protected toggleZoom(): void {
    this.zoomed.update((z) => !z);
  }

  protected onDiagramError(): void {
    this.diagramFailed.set(true);
  }
}
