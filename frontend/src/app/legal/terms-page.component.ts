import { Component } from '@angular/core';

/**
 * `/terms` — the terms of service.
 *
 * A static page. It reads no `window`, `document` or `localStorage` on its render path.
 *
 * The full text also lives in `docs/legal/terms.md`, and the facts it rests on are in the table in
 * `docs/legal/README.md`. Issue #24 tracks the source text; this template must not drift from it.
 */
@Component({
  selector: 'app-terms-page',
  imports: [],
  template: `
    <main class="legal-page">
      <h1>Terms of service</h1>
      <p class="legal-page__meta">
        Last updated: 2026-09-20. mytetz announces a change to these terms on this page, with a new
        "Last updated" date. mytetz treats your first use of the service after that date as your
        acceptance of the change.
      </p>

      <section>
        <h2>The service</h2>
        <p>
          mytetz.com ("mytetz") is a web app that explains a topic in short, plain text. You pick a
          topic and read it. A highlight on any phrase asks a language model to write more detail,
          and a person reviews the seed text of every topic before it is published. A model-written
          explanation can still hold an error, so it is not professional, medical, legal or
          financial advice, and you must not rely on it as one. You must be at least 16 years old to
          use mytetz, or the lower age your own country's law sets for a child's own consent, down
          to a floor of 13 years. You must use mytetz only for a lawful purpose, and you must not
          try to disrupt the service, overload it, or access an account that is not your own.
        </p>
      </section>

      <section>
        <h2>The trial and the subscription</h2>
        <p>
          A new reader gets a free 7-day trial, worth 40 tokens, with no credit card. One token pays
          for one new explanation or one quiz. After the trial, a new explanation or quiz needs a
          paid subscription. mytetz costs $12 each month. A subscriber gets 25 tokens each day, and
          the count resets every day. A subscription renews each month until you cancel it. mytetz
          may grant a short grace period after a missed renewal payment, so a late renewal does not
          cut off your access without warning.
        </p>
      </section>

      <section>
        <h2>Payment and cancellation</h2>
        <p>
          Freemius processes every payment for mytetz, and Freemius is the merchant of record:
          Freemius, not mytetz, is the seller toward you for a paid subscription, and Freemius
          handles your invoice, your payment method, a chargeback and a refund request. You cancel a
          subscription on the account page: select "Manage subscription" to open the Freemius
          customer portal, where you can cancel, change a payment method or read an invoice.
        </p>
        <p>
          <strong>Your right of withdrawal.</strong> As a consumer in the EU, you have 14 days from
          the day you subscribe to withdraw from the contract with no reason, under Article 9 of
          Directive 2011/83/EU. You may use the model form in Annex I(B) of that Directive, or any
          other clear statement, sent to support&#64;mytetz.com. This right ends early once you ask
          mytetz to start the paid service right away and you confirm that you know this ends your
          right of withdrawal, under Article 16(m) of the same Directive — selecting a paid feature
          during your trial or your subscription period is such a request. Outside the withdrawal
          period, a refund request goes through the Freemius customer portal or to
          support&#64;mytetz.com, and Freemius decides it under its own refund process, as the
          merchant of record.
        </p>
      </section>

      <section>
        <h2>Your account</h2>
        <p>
          You sign in with an email magic link or with a Google account. You are responsible for
          keeping your sign-in method secure. You may delete your account at any time on the account
          page. Deletion removes your account, every reading session, every quiz attempt and your
          allowance counter, and it cannot be undone. Deletion does not remove an explanation you
          triggered: an explanation is shared with other learners and holds no personal data, so it
          can stay on the site after your account is gone. In the same way, a phrase you highlight,
          and the explanation mytetz writes for it, can become a public page that stays visible to
          other visitors, including one who has never signed in, even after you delete your account.
          mytetz, and not you, owns the explanation text a model writes; you keep no exclusive right
          in a phrase you merely highlight.
        </p>
      </section>

      <section>
        <h2>Liability</h2>
        <p>
          mytetz gives no warranty that an explanation is correct, complete or fit for a particular
          purpose, beyond what the law of your country makes mandatory. To the fullest extent the
          law allows, mytetz is not liable for a loss that follows from your reliance on a
          model-written explanation. Nothing in these terms limits a liability that the law does not
          allow a business to limit, and nothing in these terms removes a right that the law of your
          country gives you as a consumer.
        </p>
      </section>

      <section>
        <h2>Governing law</h2>
        <p>
          The law of the country where the operator of mytetz.com is established applies to these
          terms. If you are a consumer, the mandatory consumer-protection law of the country where
          you live stays in force regardless of this clause.
        </p>
      </section>

      <section>
        <h2>Changes to these terms</h2>
        <p>
          mytetz may change these terms from time to time, for example to reflect a new feature, a
          new price or a change in the law. mytetz posts every change on this page, with a new "Last
          updated" date. A change that reduces your rights takes effect only for a subscription
          period that starts after the change is posted.
        </p>
        <p>Write to support&#64;mytetz.com for a question about these terms.</p>
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
