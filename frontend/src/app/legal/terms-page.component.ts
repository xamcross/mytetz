import { Component } from '@angular/core';

/**
 * `/terms` — the terms of service.
 *
 * A static page. It reads no `window`, `document` or `localStorage` on its render path.
 *
 * Every section here is legal wording, which is owner work tracked in issue #24. This page ships
 * only the headings a terms-of-service page needs, each with a marker, in square brackets, naming
 * it as owner text in place of the wording.
 */
@Component({
  selector: 'app-terms-page',
  imports: [],
  template: `
    <main class="legal-page">
      <h1>Terms of service</h1>

      <section>
        <h2>The service</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>The trial and the subscription</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Payment and cancellation</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Your account</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Liability</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Governing law</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Changes to these terms</h2>
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
export class TermsPageComponent {}
