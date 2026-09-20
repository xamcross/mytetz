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
        <h2>Represented by</h2>
        <p>This page names no representative beyond mytetz.com, the operator of this service.</p>
      </section>

      <section>
        <h2>Register entry</h2>
        <p>mytetz.com discloses no commercial register entry on this page.</p>
      </section>

      <section>
        <h2>VAT identification number</h2>
        <p>mytetz.com discloses no VAT identification number on this page.</p>
      </section>

      <section>
        <h2>Responsible for content</h2>
        <p>
          mytetz.com is responsible for the content of this website. Contact:
          <a href="mailto:support@mytetz.com">support&#64;mytetz.com</a>.
        </p>
      </section>
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
