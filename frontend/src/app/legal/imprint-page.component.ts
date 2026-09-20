import { Component } from '@angular/core';

/**
 * `/imprint` — the legal imprint (Impressum).
 *
 * A static page. It reads no `window`, `document` or `localStorage` on its render path.
 *
 * The full text also lives in `docs/legal/imprint.md`. Issue #24 tracks the source text; this
 * template must not drift from it. `docs/legal/README.md` lists each gap this page leaves open —
 * a register entry, a VAT number and a named representative — because the owner gave this project
 * only two identifying values: "mytetz.com" and support&#64;mytetz.com, and Rule 3 of issue #24
 * forbids inventing anything more.
 *
 * The page does not carry a "Represented by", a "Register entry" or a "VAT identification number"
 * section. A section that only says "mytetz.com discloses none of this" tells a reader nothing;
 * the closing note below names the gap instead. `docs/legal/README.md`'s gap list still records
 * what the law of some countries asks for.
 */
@Component({
  selector: 'app-imprint-page',
  imports: [],
  template: `
    <main class="legal-page">
      <h1>Imprint</h1>
      <p class="legal-page__meta">
        Last updated: 2026-09-20. mytetz posts a change to this page with a new "Last updated" date.
      </p>

      <section>
        <h2>Provider</h2>
        <p>mytetz.com operates this website and the mytetz service.</p>
      </section>

      <section>
        <h2>Contact</h2>
        <p>Email: <a href="mailto:support@mytetz.com">support&#64;mytetz.com</a></p>
      </section>

      <section>
        <h2>Responsible for content</h2>
        <p>
          mytetz.com is responsible for the content of this website. Contact:
          <a href="mailto:support@mytetz.com">support&#64;mytetz.com</a>.
        </p>
      </section>

      <p class="legal-page__meta">
        This page names no representative, no commercial register entry and no VAT identification
        number. The owner decides whether to add this information later.
      </p>
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
      }
    `,
  ],
})
export class ImprintPageComponent {}
