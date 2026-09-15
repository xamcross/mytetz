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
      .legal-page {
        max-width: 720px;
        margin: 0 auto;
        padding: 48px 20px 64px;
        display: flex;
        flex-direction: column;
        gap: 28px;
      }
      h1 {
        font-size: 32px;
      }
      h2 {
        font-size: 20px;
        margin-bottom: 8px;
      }
      p {
        margin: 0;
        font-size: 15px;
        line-height: 1.6;
        color: var(--mt-prose);
      }
    `,
  ],
})
export class TermsPageComponent {}
