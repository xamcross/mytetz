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
        Last updated: 2026-09-20. mytetz announces a change to this policy on this page. It gives
        the page a new "Last updated" date.
      </p>
      <p>
        This policy explains what mytetz.com ("mytetz") does with your data. It uses plain language,
        as Article 12 of the GDPR asks.
      </p>

      <section>
        <h2>Who mytetz is</h2>
        <p>
          mytetz.com is the controller of your data under the GDPR. mytetz.com is also the contact
          point for every request in this policy. Write to
          <a href="mailto:support@mytetz.com">support@mytetz.com</a> for a question about your data
          or this policy.
        </p>
      </section>

      <section>
        <h2>The cookies mytetz sets</h2>
        <p>
          mytetz sets two strictly necessary cookies on every visit.
          <code>mytetz_pid</code> identifies your browser. <code>mytetz_sid</code> carries your
          session once you sign in. Both cookies are <code>HttpOnly</code> and <code>Secure</code>.
          Both cookies carry <code>SameSite=Lax</code>.
        </p>
        <p>
          A Google sign-in sets two more cookies: <code>mytetz_g_state</code> and
          <code>mytetz_g_verifier</code>. Each one is short-lived and strictly necessary. Each one
          expires 10 minutes after mytetz sets it, or once the sign-in completes.
        </p>
        <p>
          Every cookie above is strictly necessary for the service you ask for. Article 5(3) of the
          ePrivacy Directive lets mytetz set such a cookie with no banner and no consent. mytetz
          sets no tracking cookie: no analytics cookie, and no advertising cookie. No banner asks
          for your consent, because there is nothing on this site to consent to.
        </p>
      </section>

      <section>
        <h2>The data mytetz holds</h2>
        <p>
          An email address is necessary to open an account. mytetz cannot open an account with no
          email address. mytetz stores these groups of data:
        </p>
        <ul>
          <li>
            An account: your email address, an optional Google id, and two account times. This lets
            you sign in and keeps your place.
          </li>
          <li>A sign-in session: a session id and its expiry. This keeps you signed in.</li>
          <li>
            A learning session: the topic, each phrase you highlight, and each explanation you read.
            This lets you continue where you left off.
          </li>
          <li>A quiz attempt: your answers to one quiz. This shows your own result.</li>
          <li>
            An allowance counter: how many tokens you used today, or in your trial. This enforces
            the token limit.
          </li>
          <li>
            A subscription record: your payment status and your billing dates. This lets mytetz
            grant or refuse access.
          </li>
          <li>
            A billing event: a record of one Freemius webhook. This guards against a duplicate
            charge or a duplicate grant.
          </li>
          <li>
            A visitor with no account: the <code>mytetz_pid</code> cookie value, and, once that
            visitor starts a session, a learning session and an allowance counter under that same
            id. See "How long mytetz keeps data" below for how long each one lasts.
          </li>
        </ul>
      </section>

      <section>
        <h2>Who processes your data</h2>
        <ul>
          <li>fly.io hosts the site, in Frankfurt.</li>
          <li>MongoDB Atlas holds the database, on AWS in eu-central-1.</li>
          <li>Cloudflare runs the sign-in challenge and the network edge.</li>
          <li>
            Anthropic receives the highlighted span and its ancestor chain, for one explanation.
            Anthropic never receives your name or your email address. Anthropic states that it
            deletes an API input and output within 30 days. Anthropic states that it does not train
            its models on this data.
          </li>
          <li>
            Resend delivers the magic-link email, only while mytetz uses the Resend sending mode.
            Resend receives your email address and the sign-in link, and nothing else.
          </li>
          <li>
            Google runs the Google sign-in. mytetz asks Google for your email address only, and for
            no other Google data.
          </li>
          <li>
            Freemius processes payment. Freemius is the merchant of record. Freemius is the seller
            toward you for a paid subscription. Freemius handles your invoice, your payment method
            and a refund request. Your email address reaches Freemius when you open a checkout or
            the customer portal.
          </li>
          <li>
            Cloudflare Turnstile checks that a sign-in request comes from a person. mytetz contacts
            Turnstile only while this check is switched on. While it is off, no request ever reaches
            Turnstile, and every sign-in still works.
          </li>
          <li>
            Wikimedia receives two kinds of request, only while the operator has switched the
            diagram-image feature on. First, mytetz's own server sends the highlighted phrase to
            commons.wikimedia.org, to search for a licensed picture. This request carries no account
            data and no learner IP address. Second, your own browser loads the picture directly from
            upload.wikimedia.org. This request carries no referrer, but it does carry your browser's
            IP address, the same as any direct picture request on the web. While this feature is
            off, which is the default, neither request ever happens.
          </li>
        </ul>
        <p>
          mytetz sends no request to any log or alert provider today. When mytetz adds one, this
          section names it before it starts, because such a provider would then receive a user id
          and, on rare occasions, an email address from mytetz's own technical logs.
        </p>
      </section>

      <section>
        <h2>Transfers outside the EU</h2>
        <ul>
          <li>mytetz's own server and database run in Frankfurt, in the EU.</li>
          <li>A highlighted phrase reaches Anthropic, a company in the United States.</li>
          <li>
            Your email address reaches Resend (Plus Five Five, Inc., United States) for a magic-link
            email, and Freemius (United States) for a checkout or the customer portal.
          </li>
          <li>Every request passes Cloudflare, a company in the United States.</li>
          <li>
            fly.io and MongoDB are companies of the United States that run mytetz's own server and
            database, in Frankfurt.
          </li>
        </ul>
        <p>
          The GDPR asks for a safeguard for a transfer like this, for example the EU-U.S. Data
          Privacy Framework or standard contractual clauses. Cloudflare's own policy names both of
          these as its own safeguards. Write to
          <a href="mailto:support@mytetz.com">support@mytetz.com</a> for the safeguard of a specific
          transfer.
        </p>
      </section>

      <section>
        <h2>What can become public</h2>
        <p>
          A highlighted phrase you pick, and the explanation mytetz writes for it, can become a
          public page at a fixed web address. Another learner who reaches the same phrase shares
          that same page. This can happen before anyone reviews the page. Once an operator reviews
          and approves the page, it can also appear in a public list and in a search engine. This
          page never carries your name, your email address or any other detail that names you. But
          the words you highlighted can stay visible to the public, including a visitor who has
          never signed in, for as long as mytetz keeps that page.
        </p>
      </section>

      <section>
        <h2>Automatic checks</h2>
        <p>
          mytetz runs three automatic checks on a request. A rate limit slows a burst of requests
          from one address. A trial cap refuses a fourth free trial from one address in one rolling
          day. A bot check, Cloudflare Turnstile, blocks a sign-in that fails an automatic test,
          only while the operator switches it on. None of these three checks makes a decision with a
          legal effect on you, or a similarly significant effect on you. Each one only paces or
          blocks a request. Write to <a href="mailto:support@mytetz.com">support@mytetz.com</a> if a
          check ever blocks you in error.
        </p>
      </section>

      <section>
        <h2>Your IP address</h2>
        <p>
          mytetz reads the IP address of every request, to run the checks above and to protect the
          service.
        </p>
        <ul>
          <li>
            The rate limit keeps your IP address in the server's own memory only, and never in the
            database. A server restart clears it.
          </li>
          <li>
            The trial cap stores your IP address in the database, to cap a new trial at 3 per
            address in a rolling day. This record expires automatically at the end of that day.
          </li>
          <li>
            Cloudflare Turnstile, only while it is switched on, sends your IP address to Cloudflare
            for one sign-in check.
          </li>
          <li>
            Cloudflare and fly.io each see your IP address as an ordinary part of running the
            network and the server.
          </li>
        </ul>
        <p>
          Your IP address can also appear once in a technical log line, when your address reaches
          the trial cap.
        </p>
      </section>

      <section>
        <h2>How long mytetz keeps data</h2>
        <ul>
          <li>A sign-in session: 30 days. Each use of the site moves the 30 days forward.</li>
          <li>
            A learning session of a visitor with no account: 90 days from its start. Then mytetz
            removes it automatically.
          </li>
          <li>
            A learning session of an account: until you delete your account. A sign-in moves your
            sessions to your account.
          </li>
          <li>An allowance counter: one day. It resets each day, with or without an account.</li>
          <li>A billing event: 90 days.</li>
          <li>
            Your account: until you delete it. mytetz has no rule that deletes an inactive account.
          </li>
        </ul>
        <p>
          mytetz can remove an old, unread explanation after 90 days or more. An explanation holds
          no personal data, so this rule does not change your data.
        </p>
      </section>

      <section>
        <h2>Delete your account</h2>
        <p>
          The <a routerLink="/account">account page</a> deletes your account on request. Deletion
          removes your account row, every sign-in session, every learning session, every quiz
          attempt, your allowance counter and your subscription record. Deletion does not remove an
          explanation. An explanation belongs to no one learner, and holds no personal data, so it
          can stay on the site for other learners to read.
        </p>
        <p>
          The account page refuses a deletion while your subscription can still renew. Cancel a
          subscription first, with "Manage subscription" on the account page.
        </p>
      </section>

      <section>
        <h2>The legal basis for processing</h2>
        <p>
          mytetz processes your account data to perform the contract you enter by signing up, under
          Article 6(1)(b) of the GDPR. This covers your sign-in, your session, your allowance and,
          once you subscribe, your payment. mytetz processes the strictly necessary cookies, and a
          security check such as Turnstile, under its own legitimate interest in running a working,
          fair and abuse-resistant service, under Article 6(1)(f). Where the law of your country
          asks for your consent for a step this policy does not already cover, mytetz asks for it
          before that step, under Article 6(1)(a).
        </p>
      </section>

      <section>
        <h2>Your rights</h2>
        <p>Under the GDPR you have:</p>
        <ul>
          <li>a right of access to your data (Article 15);</li>
          <li>a right to have wrong data corrected (Article 16);</li>
          <li>
            a right to erasure (Article 17), which the account page already gives you for your own
            account;
          </li>
          <li>a right to restrict processing in some cases (Article 18);</li>
          <li>a right to receive your data in a portable form (Article 20);</li>
          <li>a right to object to processing based on a legitimate interest (Article 21);</li>
          <li>
            a right to withdraw a consent at any time, as easily as you gave it, with no effect on a
            step already taken (Article 7(3)).
          </li>
        </ul>
        <p>
          Write to <a href="mailto:support@mytetz.com">support@mytetz.com</a> to exercise a right
          this list does not already let you exercise yourself on the account page. You also have
          the right to lodge a complaint with a data protection supervisory authority, under Article
          77 of the GDPR. This is ordinarily the authority of the country where you live or work.
        </p>
      </section>

      <section>
        <h2>A minimum age</h2>
        <p>
          You must be at least 16 years old to use mytetz. A lower age may apply, if your own
          country's law sets one for a child's own consent to an information-society service, down
          to a floor of 13 years (GDPR, Article 8). If you sign in with a Google account, the age
          rules of that Google account also apply.
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
