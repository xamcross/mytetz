# Privacy policy

Last updated: 2026-09-20. mytetz announces a change to this policy on this page. It
gives the page a new "Last updated" date.

This policy explains what mytetz.com ("mytetz") does with your data. It uses plain
language, as Article 12 of the GDPR asks.

## Who mytetz is

mytetz.com is the controller of your data under the GDPR. mytetz.com is also the
contact point for every request in this policy. Write to support@mytetz.com for a
question about your data or this policy.

## The cookies mytetz sets

mytetz sets two strictly necessary cookies on every visit. `mytetz_pid` identifies
your browser. `mytetz_sid` carries your session once you sign in. Both cookies are
`HttpOnly` and `Secure`. Both cookies carry `SameSite=Lax`.

A Google sign-in sets two more cookies: `mytetz_g_state` and `mytetz_g_verifier`.
Each one is short-lived and strictly necessary. Each one expires 10 minutes after
mytetz sets it, or once the sign-in completes.

Every cookie above is strictly necessary for the service you ask for. Article 5(3)
of the ePrivacy Directive lets mytetz set such a cookie with no banner and no
consent. mytetz sets no tracking cookie: no analytics cookie, and no advertising
cookie. No banner asks for your consent, because there is nothing on this site to
consent to.

## The data mytetz holds

An email address is necessary to open an account. mytetz cannot open an account
with no email address. mytetz stores these groups of data:

- An account: your email address, an optional Google id, and two account times.
  This lets you sign in and keeps your place.
- A sign-in session: a session id and its expiry. This keeps you signed in.
- A learning session: the topic, each phrase you highlight, and each explanation
  you read. This lets you continue where you left off.
- A quiz attempt: your answers to one quiz. This shows your own result.
- An allowance counter: how many tokens you used today, or in your trial. This
  enforces the token limit.
- A subscription record: your payment status and your billing dates. This lets
  mytetz grant or refuse access.
- A billing event: a record of one Freemius webhook. This guards against a
  duplicate charge or a duplicate grant.
- A visitor with no account: the `mytetz_pid` cookie value, and, once that visitor
  starts a session, a learning session and an allowance counter under that same
  id. See "How long mytetz keeps data" below for how long each one lasts.

## Who processes your data

- fly.io hosts the site, in Frankfurt.
- MongoDB Atlas holds the database, on AWS in eu-central-1.
- Cloudflare runs the network edge, and, when a widget appears, the Turnstile
  sign-in check.
- Anthropic receives the highlighted span and its ancestor chain, for one
  explanation. Anthropic never receives your name or your email address. Anthropic
  states that it deletes an API input and output within 30 days. Anthropic states
  that it does not train its models on this data.
- Resend delivers the magic-link email, only while mytetz uses the Resend sending
  mode. Resend receives your email address and the sign-in link, and nothing else.
- Google runs the Google sign-in. mytetz asks Google for your email address only,
  and for no other Google data.
- Freemius processes payment. Freemius is the merchant of record. Freemius is the
  seller toward you for a paid subscription. Freemius handles your invoice, your
  payment method and a refund request. Your email address reaches Freemius when
  you open a checkout or the customer portal.
- Cloudflare Turnstile checks that a sign-in request comes from a person. mytetz
  contacts Turnstile only while this check is switched on. While it is off, no
  request ever reaches Turnstile, and every sign-in still works.
- Wikimedia receives two kinds of request, only while the operator has switched
  the diagram-image feature on. First, mytetz's own server sends the highlighted
  phrase to commons.wikimedia.org, to search for a licensed picture. This request
  carries no account data and no learner IP address. Second, your own browser
  loads the picture directly from upload.wikimedia.org. This request carries no
  referrer, but it does carry your browser's IP address, the same as any direct
  picture request on the web. While this feature is off, which is the default,
  neither request ever happens.

mytetz sends no request to any log or alert provider today. When mytetz adds one,
this section names it before it starts, because such a provider would then receive
a user id and, on rare occasions, an email address from mytetz's own technical
logs.

## Transfers outside the EU

- mytetz's own server and database run in Frankfurt, in the EU.
- A highlighted phrase reaches Anthropic, a company in the United States.
- Your email address reaches Resend (Plus Five Five, Inc., United States) for a
  magic-link email, and Freemius (United States) for a checkout or the customer
  portal.
- Every request passes Cloudflare, a company in the United States.
- fly.io and MongoDB are companies of the United States that run mytetz's own
  server and database, in Frankfurt.

The GDPR asks for a safeguard for a transfer like this, for example the EU-U.S.
Data Privacy Framework or standard contractual clauses. Cloudflare's own policy
names both of these as its own safeguards. Write to support@mytetz.com for the
safeguard of a specific transfer.

## What can become public

A highlighted phrase you pick, and the explanation mytetz writes for it, can
become a public page at a fixed web address. Another learner who reaches the same
phrase shares that same page. This can happen before anyone reviews the page. Once
an operator reviews and approves the page, it can also appear in a public list and
in a search engine. This page never carries your name, your email address or any
other detail that names you. But the words you highlighted can stay visible to the
public, including a visitor who has never signed in, for as long as mytetz keeps
that page.

## Automatic checks

mytetz runs three automatic checks on a request. A rate limit slows a burst of
requests from one address. A trial cap refuses a fourth free trial from one
address in one rolling day. A bot check, Cloudflare Turnstile, blocks a sign-in
that fails an automatic test, only while the operator switches it on. None of
these three checks makes a decision with a legal effect on you, or a similarly
significant effect on you. Each one only paces or blocks a request. Write to
support@mytetz.com if a check ever blocks you in error.

## Your IP address

mytetz reads the IP address of every request, to run the checks above and to
protect the service.

- The rate limit keeps your IP address in the server's own memory only, and never
  in the database. A server restart clears it.
- The trial cap stores your IP address in the database, to cap a new trial at 3
  per address in a rolling day. This record expires automatically at the end of
  that day.
- Cloudflare Turnstile, only while it is switched on, sends your IP address to
  Cloudflare for one sign-in check.
- Cloudflare and fly.io each see your IP address as an ordinary part of running
  the network and the server.

Your IP address can also appear once in a technical log line, when your address
reaches the trial cap.

## How long mytetz keeps data

A sign-in session lasts 30 days and slides forward each time you use the site. A
billing event lasts 90 days. mytetz may remove an old, unread explanation after 90
days or more. This does not affect your own data, because an explanation holds no
personal data. A learning session started with no account lasts 90 days from its
last activity, then mytetz removes it automatically. A learning session moves to
your account when you sign in, and it then lasts until you delete your account. An
allowance counter resets automatically each day, whether or not you have an
account. mytetz keeps your account until you delete it. mytetz has no rule that
deletes an inactive account.

## Delete your account

The [account page](/account) deletes your account on request. Deletion removes
your account row, every sign-in session, every learning session, every quiz
attempt and your allowance counter. Deletion does not remove an explanation. An
explanation belongs to no one learner, and holds no personal data, so it can stay
on the site for other learners to read.

Deletion does not cancel a paid subscription. Cancel a subscription first, with
"Manage subscription" on the account page. Your subscription record stays in the
database after you delete your account, because deletion does not remove it
today.

## The legal basis for processing

mytetz processes your account data to perform the contract you enter by signing
up, under Article 6(1)(b) of the GDPR. This covers your sign-in, your session,
your allowance and, once you subscribe, your payment. mytetz processes the
strictly necessary cookies, and a security check such as Turnstile, under its own
legitimate interest in running a working, fair and abuse-resistant service, under
Article 6(1)(f). Where the law of your country asks for your consent for a step
this policy does not already cover, mytetz asks for it before that step, under
Article 6(1)(a).

## Your rights

Under the GDPR you have:

- a right of access to your data (Article 15);
- a right to have wrong data corrected (Article 16);
- a right to erasure (Article 17), which the account page already gives you for
  your own account;
- a right to restrict processing in some cases (Article 18);
- a right to receive your data in a portable form (Article 20);
- a right to object to processing based on a legitimate interest (Article 21);
- a right to withdraw a consent at any time, as easily as you gave it, with no
  effect on a step already taken (Article 7(3)).

Write to support@mytetz.com to exercise a right this list does not already let
you exercise yourself on the account page. You also have the right to lodge a
complaint with a data protection supervisory authority, under Article 77 of the
GDPR. This is ordinarily the authority of the country where you live or work.

## A minimum age

You must be at least 16 years old to use mytetz. A lower age may apply, if your
own country's law sets one for a child's own consent to an information-society
service, down to a floor of 13 years (GDPR, Article 8). If you sign in with a
Google account, the age rules of that Google account also apply.

## Contact

Write to support@mytetz.com for a question about this policy or about your data.
