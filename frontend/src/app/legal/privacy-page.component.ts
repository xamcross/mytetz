import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * `/privacy` — the privacy policy.
 *
 * A static page. It reads no `window`, `document` or `localStorage` on its render path, so it
 * renders the same way on the server and in the browser.
 *
 * The technical facts below come from `docs/superpowers/specs/2026-08-07-monetization-design.md`,
 * section 5, and from the fact table in `docs/legal/README.md`, which cites the exact code each
 * fact rests on. The full text also lives in `docs/legal/privacy.md`. Issue #24 tracks the source
 * text; this template must not drift from it.
 */
@Component({
  selector: 'app-privacy-page',
  imports: [RouterLink],
  template: `
    <main class="legal-page">
      <h1>Privacy policy</h1>
      <p class="legal-page__meta">
        Last updated: 2026-09-20. mytetz announces a change to this policy on this page, with a new
        "Last updated" date.
      </p>

      <section>
        <h2>Who we are</h2>
        <p>
          mytetz.com is the controller of your data under the GDPR, and the contact point for every
          request in this policy. Write to
          <a href="mailto:support@mytetz.com">support@mytetz.com</a>
          for any question about your data or this policy.
        </p>
      </section>

      <section>
        <h2>The cookies we set</h2>
        <p>
          mytetz sets two strictly necessary cookies on every visit:
          <code>mytetz_pid</code>, which identifies your browser, and <code>mytetz_sid</code>, which
          carries your session once you sign in. Both are <code>HttpOnly</code> and
          <code>Secure</code>, and both carry <code>SameSite=Lax</code>.
        </p>
        <p>
          A Google sign-in sets two more cookies, both short-lived: <code>mytetz_g_state</code> and
          <code>mytetz_g_verifier</code>. Both cookies protect the sign-in exchange, and both expire
          10 minutes after they are set, or once the sign-in completes.
        </p>
        <p>
          Every cookie above is strictly necessary for the service you ask for, so Article 5(3) of
          the ePrivacy Directive lets mytetz set it with no banner and no consent. mytetz sets no
          tracking cookie: no analytics cookie, and no advertising cookie. No banner asks for your
          consent, because there is nothing on this site to consent to.
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
            Anthropic never receives your name or your email address. Anthropic states that it
            deletes an API input and output within 30 days, and that it does not train its models on
            this data.
          </li>
          <li>Resend delivers the magic-link email.</li>
          <li>
            Google runs the Google sign-in. mytetz asks Google for your email address only, and for
            no other Google data.
          </li>
          <li>
            Freemius processes payment. Freemius is the merchant of record: it is the seller toward
            you for a paid subscription, and it handles your invoice, your payment method and a
            refund request. Your email address reaches Freemius when you open a checkout or the
            customer portal.
          </li>
          <li>
            Cloudflare Turnstile checks that a sign-in request comes from a person. mytetz contacts
            Turnstile only while this check is switched on; while it is off, no request ever reaches
            Turnstile, and every sign-in still works.
          </li>
          <li>
            Wikimedia receives two kinds of request, and only while the operator has switched the
            diagram-image feature on. First, mytetz's own server sends the highlighted phrase to
            commons.wikimedia.org, to search for a licensed picture; this request carries no account
            data and no learner IP address. Second, your own browser then loads the picture directly
            from upload.wikimedia.org; this request carries no referrer, but it does carry your
            browser's IP address, the same as any direct picture request on the web. While this
            feature is off, which is the default, neither request ever happens.
          </li>
        </ul>
        <p>
          mytetz sends no request to any log or alert provider today. When mytetz adds one, this
          section names it before it starts, because such a provider would then receive a user id
          and, on rare occasions, an email address from mytetz's own technical logs.
        </p>
      </section>

      <section>
        <h2>What can become public</h2>
        <p>
          A highlighted phrase you pick, and the explanation mytetz writes for it, can become a
          public page at a fixed web address, together with other learners who reach the same
          phrase. This can happen before anyone reviews the page. Once an operator reviews and
          approves the page, it can also appear in a public list and in a search engine. This page
          never carries your name, your email address or any other detail that names you — but the
          words you highlighted can stay visible to the public, including a visitor who has never
          signed in, for as long as mytetz keeps that page.
        </p>
      </section>

      <section>
        <h2>How long we keep data</h2>
        <p>
          A sign-in session lasts 30 days and slides forward each time you use the site. A billing
          event lasts 90 days. mytetz may remove an old, unread explanation after 90 days or more;
          this does not affect your own data, because an explanation holds no personal data.
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
        <p>
          mytetz processes your account data to perform the contract you enter by signing up, under
          Article 6(1)(b) of the GDPR: this covers your sign-in, your session, your allowance and,
          once you subscribe, your payment. mytetz processes the strictly necessary cookies, and a
          security check such as Turnstile, under its own legitimate interest in running a working,
          fair and abuse-resistant service, under Article 6(1)(f). Where the law of your country
          asks for your consent for a step this policy does not already cover, mytetz asks for it
          before that step, under Article 6(1)(a).
        </p>
      </section>

      <section>
        <h2>Your rights</h2>
        <p>
          Under the GDPR you have: a right of access to your data (Article 15); a right to have
          wrong data corrected (Article 16); a right to erasure (Article 17), which the account page
          already gives you for your own account; a right to restrict processing in some cases
          (Article 18); a right to receive your data in a portable form (Article 20); and a right to
          object to processing based on a legitimate interest (Article 21). Write to
          <a href="mailto:support@mytetz.com">support@mytetz.com</a> to exercise a right this list
          does not already let you exercise yourself on the account page. You also have the right to
          lodge a complaint with a data protection supervisory authority, under Article 77 of the
          GDPR; this is ordinarily the authority of the country where you live or work.
        </p>
      </section>

      <section>
        <h2>A minimum age</h2>
        <p>
          You must be at least 16 years old to use mytetz, unless the law of your country sets a
          lower age for a child's own consent to an information-society service, in which case that
          lower age applies, down to a floor of 13 years (GDPR, Article 8). If you sign in with a
          Google account, the age rules of that Google account also apply.
        </p>
      </section>

      <section>
        <h2>Contact</h2>
        <p>
          Write to <a href="mailto:support@mytetz.com">support@mytetz.com</a> for a question about
          this policy or about your data.
        </p>
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
