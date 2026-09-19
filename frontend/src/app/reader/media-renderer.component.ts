import { Component, computed, input } from '@angular/core';
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
 * ## The accessible title
 *
 * An `<img>` carries no `<title>` element of its own, so the accessible name comes from the
 * `alt` attribute instead. This component parses the already-sanitised SVG string once, with the
 * browser's own `DOMParser`, and reads the first `<title>` element's text. It never inserts the
 * parsed content into the live document; it only reads a text value out of it. It falls back to a
 * fixed word when the SVG carries no `<title>` element.
 *
 * ## The attribution
 *
 * `attributionHtml` is a small HTML string the server built itself from Wikimedia's own metadata —
 * plain text plus at most one `https` link, never a Commons editor's own markup passed through. A
 * plain anchor and plain text sit inside Angular's sanitiser allowlist, so an ordinary
 * `[innerHTML]` binding here is a genuine check, unlike the dead end an `<svg>` would be. This
 * binding calls no `DomSanitizer` method either.
 */
@Component({
  selector: 'app-media-renderer',
  imports: [],
  template: `
    <figure class="media">
      <div class="media__diagram-frame">
        <img class="media__diagram" [src]="diagramSrc()" [alt]="diagramAlt()" />
      </div>
      @if (media().image; as image) {
        <figcaption class="media__caption" data-testid="attribution">
          <span class="media__license">{{ image.license }}</span>
          <span class="media__attribution" [innerHTML]="image.attributionHtml"></span>
        </figcaption>
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
      /* A pinch or a scroll-to-zoom control is later polish. This container only lets a diagram
         wider than the card scroll into view, rather than overflow it. */
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
      }
    `,
  ],
})
export class MediaRendererComponent {
  readonly media = input.required<Media>();

  protected readonly diagramSrc = computed(
    () => 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(this.media().diagram.source),
  );

  protected readonly diagramAlt = computed(() => {
    const source = this.media().diagram.source;
    const parsed = new DOMParser().parseFromString(source, 'image/svg+xml');
    const title = parsed.querySelector('title')?.textContent?.trim();
    return title && title.length > 0 ? title : FALLBACK_ALT;
  });
}
