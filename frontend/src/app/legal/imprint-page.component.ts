import { Component } from '@angular/core';

/**
 * `/imprint` — the legal imprint (Impressum).
 *
 * A static page. It reads no `window`, `document` or `localStorage` on its render path.
 *
 * Every section here names the provider's own legal identity, which is owner work tracked in
 * issue #24. This page ships only the headings an imprint needs, each with a marker, in square
 * brackets, naming it as owner text in place of the fact.
 */
@Component({
  selector: 'app-imprint-page',
  imports: [],
  template: `
    <main class="legal-page">
      <h1>Imprint</h1>

      <section>
        <h2>Provider</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Contact</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Represented by</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Register entry</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>VAT identification number</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Responsible for content</h2>
        <p>[owner text]</p>
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
