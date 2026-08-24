import { Component } from '@angular/core';

/**
 * The mytetz mark: a phrase, and the deeper phrase that opens out of it.
 *
 * The product is contextual resolution — the reader highlights a phrase, and an explanation
 * arrives inside the context the phrase came from. The mark draws that and nothing else. The
 * coral bar is the highlighted phrase. The elbow is the context that holds it. The teal bar is
 * the explanation that opens.
 *
 * Three shapes on a 32-unit grid, inside a tile. The tile is what makes the mark survive as a
 * browser tab icon: a bare teal glyph disappears against a dark tab strip, and a pale tile does
 * not. `public/icon.svg` draws the same four elements on the same grid, and `palette.spec.ts`
 * proves the two agree.
 *
 * Every paint names a token, because `styles.css` says only it and `palette.spec.ts` state a
 * colour.
 */
@Component({
  selector: 'app-logo-mark',
  template: `
    <svg viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect x="0" y="0" width="32" height="32" rx="10" fill="var(--mt-chip)" />
      <rect x="5" y="7" width="17" height="6" rx="3" fill="var(--mt-coral)" />
      <path
        d="M9 15V22H13"
        fill="none"
        stroke="var(--mt-teal)"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <rect x="13" y="19" width="14" height="6" rx="3" fill="var(--mt-teal)" />
    </svg>
  `,
  styles: [
    `
      /* The host carries the size, so a caller changes one value and the mark follows. */
      :host {
        display: inline-block;
        width: 28px;
        height: 28px;
        flex: none;
      }
      svg {
        display: block;
        width: 100%;
        height: 100%;
      }
    `,
  ],
})
export class LogoMarkComponent {}
