import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * `/privacy` — the privacy policy.
 *
 * A static page. It reads no `window`, `document` or `localStorage` on its render path, so it
 * renders the same way on the server and in the browser.
 *
 * The technical facts below come from `docs/superpowers/specs/2026-08-07-monetization-design.md`,
 * section 5. The legal wording — the controller's identity, the legal basis and the learner's
 * rights — is owner work, tracked in issue #24. Each place that waits for that text carries a
 * marker, in square brackets, naming it as owner text.
 */
@Component({
  selector: 'app-privacy-page',
  imports: [RouterLink],
  template: `
    <main class="legal-page">
      <h1>Privacy policy</h1>

      <section>
        <h2>Who we are</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>The cookies we set</h2>
        <p>
          mytetz sets two strictly necessary cookies on every visit:
          <code>mytetz_pid</code>, which identifies your browser, and <code>mytetz_sid</code>, which
          carries your session once you sign in.
        </p>
        <p>
          A Google sign-in sets two more cookies, both short-lived: <code>mytetz_g_state</code> and
          <code>mytetz_g_verifier</code>. Both cookies protect the sign-in exchange, and both expire
          once it completes.
        </p>
        <p>
          mytetz sets no tracking cookie. No banner asks for your consent, because there is nothing
          on this site to consent to.
        </p>
      </section>

      <section>
        <h2>Who processes your data</h2>
        <ul>
          <li>fly.io hosts the site, in Frankfurt.</li>
          <li>MongoDB Atlas holds the database, on AWS in eu-central-1.</li>
          <li>Cloudflare runs the sign-in challenge and the network edge.</li>
          <li>
            Anthropic receives the highlighted span and its ancestor chain for one explanation.
            Anthropic never receives your name or your email address.
          </li>
          <li>Resend delivers the magic-link email.</li>
          <li>Google runs the Google sign-in.</li>
          <li>Freemius processes payment. Freemius is the merchant of record.</li>
        </ul>
      </section>

      <section>
        <h2>How long we keep data</h2>
        <p>
          A sign-in session lasts 30 days and slides forward each time you use the site. A billing
          event lasts 90 days.
        </p>
      </section>

      <section>
        <h2>Delete your account</h2>
        <p>
          The <a routerLink="/account">account page</a> deletes your account on request. Deletion
          removes your account row, every sign-in session, every learning session, every quiz
          attempt and your allowance counter. Deletion does not remove an explanation — an
          explanation belongs to no one learner and holds no personal data.
        </p>
      </section>

      <section>
        <h2>The legal basis for processing</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Your rights</h2>
        <p>[owner text]</p>
      </section>

      <section>
        <h2>Contact</h2>
        <p>[owner text]</p>
      </section>
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      /* This page's own paragraphs run several to a section — the cookies section holds three
         in a row — so each one keeps a bottom margin. The shared .legal-page p rule in
         styles.css sets margin: 0 instead, for terms-page and imprint-page, which each hold one
         paragraph per section. The component's own attribute gives this rule the higher
         specificity it needs to win over that shared one. li repeats the same three properties,
         because the shared rule never selects it. */
      .legal-page p,
      .legal-page li {
        margin: 0 0 8px;
        font-size: 15px;
        line-height: 1.6;
        color: var(--mt-prose);
      }
      ul {
        margin: 0;
        padding-left: 20px;
      }
      code {
        font-family: monospace;
        background: var(--mt-chip);
        padding: 1px 5px;
        border-radius: 4px;
      }
    `,
  ],
})
export class PrivacyPageComponent {}
