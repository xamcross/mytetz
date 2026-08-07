# Monetization — Design

**Project:** mytetz.com
**Date:** 2026-08-07
**Status:** Approved for planning
**Spec:** B of four (see the Scope section of the learning-engine design)

This document uses ASD-STE100 Simplified Technical English.

---

## 1. Purpose

The learning engine works and it costs money for each explanation. This specification adds the parts
that make a learner pay for it:

- An account.
- A 7-day trial.
- A €10 each month subscription through Freemius.
- A gate in front of every model call.

It also corrects a defect in the shipped defaults. The defaults lose money on a subscriber who uses
the full allowance. Section 3 gives the numbers.

---

## 2. Locked commercial decisions

| Decision | Choice | Rationale |
|---|---|---|
| Price | €10 each month | Unchanged from the learning-engine specification |
| Trial | 7 days, 40 explanations, no card | A pool wastes nothing. The cost ceiling for one trial is $0.42. |
| Gate position | The catalogue and the seeds are open. The reader is gated. | A crawler and a first-time visitor both read real text. Specification C stays possible. |
| Sign-in | An email magic link **and** Google | Two methods give redundancy. Mail delivery is the least reliable part of this design. |
| Payments | Freemius | 4.7% and no fixed fee. At €10 a fixed fee of $0.50 is another 5%. |
| Model | `claude-sonnet-5` | Near-Opus reasoning at 60% of the cost. Contextual isolation is a reasoning task. |
| Subscriber allowance | 25 explanations each day | The worst case is $7.88 each month against $10.29 of net revenue. |
| Entitlement source | A Mongo mirror, written by a Freemius webhook | No third-party call on the hot path of a streaming endpoint |

### In scope

Accounts by magic link and by Google. Revocable server-side sessions. The trial. Freemius checkout,
the webhook mirror and the subscription state machine. An entitlement gate in front of every model
call. A tier-aware allowance in `quota`. The model change. A seed pre-warm at publication.
Account deletion.

### Out of scope

Server-side rendering and the public explanation pages. Those stay in specification C. Team accounts
and seats. Annual plans, coupons and regional prices. Password sign-in. A refund happens in the
Freemius dashboard and arrives here as a webhook.

---

## 3. Unit economics

These numbers decide the allowance, so they come before the architecture.

### 3.1 The cost of one generation

One generation sends the topic title, the ancestor chain, the span and the span's sentence. That is
approximately **1,000 input tokens**. It returns thinking and 1 to 3 sentences. That is
approximately **500 output tokens**.

| Model | Rate for each 1M tokens, in / out | Cost for one generation |
|---|---|---|
| `claude-opus-5` | $5 / $25 | $0.0175 |
| **`claude-sonnet-5`** | **$3 / $15** | **$0.0105** |
| `claude-haiku-4-5` | $1 / $5 | $0.0035 |

`Pricing.kt` already holds these rates and already prices cache reads at 0.1× and cache writes at
1.25×. No pricing code changes.

The worst case for one generation is $0.105. `MYTETZ_MAX_OUTPUT_TOKENS` is 4,000 and output bills at
the output rate.

> Sonnet 5 has an introductory rate of $2 / $10 until 2026-08-31, which gives $0.007 for each
> generation. Every number in this document uses the standard rate of $3 / $15. Nothing breaks on
> that date. The weeks before it are cheaper than the plan.

### 3.2 The defect in the shipped defaults

`DEFAULT_DAILY_EXPLAINS` is 20 and `MYTETZ_MODEL_ID` is `claude-opus-5`. A subscriber who uses the
full allowance costs:

```
20 × 30 × $0.0175 = $10.50 each month
```

Net revenue after the Freemius fee is €9.53, which is approximately $10.29. The margin at full use
is **negative**, before fly.io, Atlas and the mail sender.

### 3.3 The chosen position

| Item | Value |
|---|---|
| Price | €10 each month |
| Freemius fee | 4.7%, no fixed fee |
| Net revenue | €9.53 ≈ $10.29 at €1 = $1.08 |
| Model | `claude-sonnet-5` |
| Allowance | 25 each day |
| Cost at full use | 25 × 30 × $0.0105 = **$7.88** |
| Gross margin at full use | 23% |

A daily allowance is a ceiling and not the expected spend. Most subscribers use a fraction of it.
The number above is the worst case, and the worst case must stay positive.

### 3.4 The cost of one trial

```
40 × $0.0105 = $0.42
```

At one conversion in ten, one subscriber costs $4.20 to acquire and returns $10.29 each month. The
payback lands near day twelve.

---

## 4. Architecture

### 4.1 Modules

Two modules join the eight that exist. No other module moves.

| Module | Responsibility | Depends on |
|---|---|---|
| `account` **(new)** | Users, magic-link tokens, the Google code exchange, server-side sessions. Holds a `MailSender` port with one adapter, in the shape of the `llm` port. | `persistence` |
| `billing` **(new)** | Freemius webhook verification, the `subscriptions` collection, the trial, and one function that resolves a user id to an allowance. | `persistence` |
| `quota` *(changed)* | `checkGeneration` and `recordGeneration` accept an `Allowance`. The module still knows nothing about a tier, a trial or money owed. | `persistence` |
| `api` *(changed)* | The authentication routes, the webhook route and the checkout route. Joins `account`, `billing` and `quota` on the explain path. | all |

`graph`, `session`, `catalog`, `llm`, `assess` and `persistence` do not change. `graph` still has no
concept of a user. That is what keeps the cache, the publication pipeline and the tests correct.

### 4.2 The new load-bearing boundary

`billing` answers one question: *what may this user id generate now?* It returns an `Allowance` or a
refusal.

- It never reads a user document.
- It never touches `graph` or `session`.
- It never sees an HTTP request.

`account` and `billing` meet in the API layer. `graph` and `quota` already meet there in the same
way.

### 4.3 Reads on the explain path

The cookie carries a signed opaque session id. One point read resolves it to a user id. A second
point read resolves the user id to an allowance. An in-process cache holds the second read for 60
seconds, and the webhook handler clears that cache.

With more than one machine, a cancellation needs a maximum of 60 seconds to take effect on the other
machines. This is acceptable for a cancellation. It is recorded here so that nobody finds it later.

### 4.4 The `Allowance` type

```kotlin
data class Allowance(val generations: Int, val windowMillis: Long)
```

`quota` gains this type and accepts it on both methods. `QuotaConfig.dailyExplains` becomes the
default for a subscriber. The module still holds no knowledge of a tier.

---

## 5. Identity

### 5.1 The cookie

`Principals.resolve` mints `anon:<uuid>`, signs it with HMAC-SHA256, and validates it by
reconstruction through `PrincipalId.anonymous(...)`. That code stays as it is.

A second cookie, `mytetz_sid`, carries a signed opaque session id of 16 random bytes as base64url.
The signed payload carries a domain separator. A signed anonymous principal can therefore never
work as a session id, and the reverse is also impossible. Both cookies use the existing
`MYTETZ_COOKIE_SIGNING_KEY`.

`PrincipalId.user(...)` becomes reachable for the first time. The principal for quota is
`user:<userId>`.

The anonymous cookie stays for every visitor. It still keys the rate limiter on the catalogue, where
no account exists.

### 5.2 The magic link

1. `POST /api/auth/magic-link` carries an email address. It always answers `204`, for a known address
   and for an unknown address. The endpoint must never show whether an account exists.
2. The server makes the address lower case and removes the outer spaces. It does **not** remove a dot
   or a plus-tag. Two addresses that a mail provider treats as one are still two identities, and a
   silent merge of two accounts is a defect.
3. The server mints a 32-byte token. It stores **`sha256(token)`** in `magicLinkTokens` under a TTL
   index at 15 minutes. It then mails the link. The raw token is never stored, for the reason that a
   password is never stored.
4. `GET /api/auth/magic-link/{token}` finds the hash with `findOneAndDelete`. A replayed link
   therefore fails on the second use and not on the second hour.
5. Both routes have a rate limit by address and by IP bucket, through the limiter in `RateLimit.kt`.

### 5.3 Google

The flow is Authorization Code with PKCE.

1. `GET /api/auth/google` sets a short-lived signed `state` cookie and redirects.
2. `GET /api/auth/google/callback` verifies the state, exchanges the code and reads the ID token.
3. The server **refuses a token whose `email_verified` is false**. Without that check a Google
   account that holds an unverified address can claim an address that belongs to a magic-link user.

### 5.4 How the two methods link

One user row exists for each normalised address. The first Google sign-in stores `googleSub`. A
later sign-in matches on the subject and not on the address, because an address can change and a
subject cannot.

A subject that arrives with an address already bound to a different subject is refused. The server
logs `ACCOUNT_LINK_CONFLICT`. The case is rare and a person must resolve it.

### 5.5 Why two methods

A magic link depends on mail delivery. Mail delivery is the least reliable part of this design. When
the mail provider fails, Google sign-in still works and the site stays usable.

### 5.6 Sessions

The `authSessions` collection holds one row for each session. The name is not `sessions`, because a
learning session already owns that name.

- The expiry is 30 days and it slides.
- The server rewrites the expiry a maximum of one time each hour. A busy reader therefore does not
  cost one write for each request.
- Sign-out deletes the row. Sign-out-everywhere deletes every row for the user.

**A refund or a cancellation does not delete a session.** The entitlement gate already refuses to
generate, and the learner keeps a trail that costs nothing to serve. A session is deleted only on
sign-out, on account deletion, or when an operator suspects a compromise.

### 5.7 Account deletion

`POST /api/account/delete` needs a fresh confirmation token. It removes:

- The user row.
- Every authentication session.
- Every learning session.
- Every quiz attempt.
- The principal counter.

**It does not remove an explanation.** An explanation is user-independent and immutable by
construction and holds no personal data. The content-addressed boundary in the learning-engine
specification answers the GDPR question at no cost.

The learner cancels in Freemius. Freemius holds the payment record and carries the retention duty as
the merchant of record.

---

## 6. The trial and the subscription

### 6.1 The trial is a pool

The trial gives **40 explanations, valid for 7 days, with no card**.

A pool is friendlier than a daily rate. A learner who reads the site on day one and returns on day
seven wastes nothing. A pool also needs no new mechanism: a trial is a `PrincipalCounter` with a
7-day window and an allowance of 40.

### 6.2 The bound on trial abuse

An email address is free, so 40 generations is the price of a throwaway address. Three limits apply,
in the order of how much work each one costs:

1. Cloudflare Turnstile in front of the magic-link request and the Google callback.
2. A maximum of three new trials for each IP bucket in 24 hours. The `principals` schema already
   carries the `ipBucket` field. A blocked visitor gets an immediate offer of checkout. A shared
   office address therefore does not become a dead end.
3. The global daily spend breaker, unchanged, is the final stop.

One hundred false accounts cost $42 and trip nothing. One thousand cost $420 and trip the breaker
at $50.

### 6.3 The states

One `subscriptions` row exists for each user. Only the webhook handler and the trial starter write
it.

| State | The writer | The row carries |
|---|---|---|
| `TRIALING` | The first sign-in | `trialEndsAt` |
| `ACTIVE` | A first payment or a renewal payment | `currentPeriodEndsAt` |
| `PAST_DUE` | A failed payment | `graceEndsAt`, 3 days later |
| `CANCELLED` | A cancellation | `currentPeriodEndsAt`, still paid |
| `EXPIRED` | An expiry, a refund or a chargeback | — |

### 6.4 Entitlement resolution

Entitlement is a pure function of the row and the current time. It holds no clock and it makes no
database read.

| State | Condition | Allowance |
|---|---|---|
| `TRIALING` | `now < trialEndsAt` | 40 over the trial window |
| `ACTIVE` | — | 25 each 24 hours |
| `CANCELLED` | `now < currentPeriodEndsAt` | 25 each 24 hours |
| `PAST_DUE` | `now < graceEndsAt` | 25 each 24 hours |
| Any other | — | None. The gate answers `SUBSCRIPTION_REQUIRED`. |

### 6.5 One transition needs a counter reset

A trial runs on a 7-day window. A subscription runs on a 24-hour window. The stored counter holds an
expiry written under the old window, so a converted subscriber keeps the trial's expiry until the
counter rolls.

The webhook handler therefore calls `resetCounter` when the window length changes. The read path
stays free of a side effect.

---

## 7. Freemius

### 7.1 Checkout

`POST /api/billing/checkout` returns a Freemius URL that carries the user id as the reference. The
server trusts nothing that the browser sends back. The webhook is the only source of truth.

### 7.2 The webhook

`POST /api/billing/webhook`.

1. Read the **raw body bytes before any deserialization**.
2. Compute HMAC-SHA256 over those bytes with the product secret.
3. Compare the result against the `x-signature` header with `MessageDigest.isEqual`.
4. Refuse a mismatch with `401` and log `WEBHOOK_SIGNATURE_MISMATCH`. Never parse the body.

This is the identical primitive and the identical constant-time compare that `Principal.kt` already
carries. No new cryptography is written.

### 7.3 The three properties that keep the mirror honest

**Idempotent.** The handler inserts each event id into `billingEvents` under a unique `_id`. A
duplicate insert is discarded, exactly as `graph` discards a duplicate explanation. The pattern is
already proved in this codebase.

**Ordered.** Every state write carries the event's own timestamp. A write older than the stored
`updatedAt` is dropped. A late "payment failed" therefore cannot overwrite a newer "renewed".

**Reconciled.** A nightly job re-reads Freemius for every subscription that is not in a terminal
state, and it corrects the mirror. It logs `BILLING_DRIFT` for each change. This is the answer to a
webhook that never arrives.

### 7.4 What a lapsed learner keeps

The learner keeps everything already generated: the sessions, the trail, the breadcrumb and every
cached explanation on it. The server refuses only new generation.

This is the rule that the spend breaker already follows. Cache hits keep serving. One rule applies in
two places.

---

## 8. Data model

### 8.1 New collections

**`users`**

```
{
  _id: ObjectId,
  email: String,            // normalised, unique
  googleSub: String?,       // unique, sparse
  createdAt, lastSeenAt: Instant
}
```

**`magicLinkTokens`**

```
{
  _id: String,              // sha256(token), hex
  email: String,            // normalised
  expiresAt: Instant,       // TTL index
  createdAt: Instant
}
```

**`authSessions`**

```
{
  _id: String,              // 16 random bytes, base64url
  userId: ObjectId,
  createdAt, lastSeenAt: Instant,
  expiresAt: Instant        // TTL index, 30-day slide
}
```

**`subscriptions`**

```
{
  _id: ObjectId,            // the user id
  status: "TRIALING" | "ACTIVE" | "PAST_DUE" | "CANCELLED" | "EXPIRED",
  trialEndsAt: Instant?,
  currentPeriodEndsAt: Instant?,
  graceEndsAt: Instant?,
  freemiusUserId: String?,
  freemiusSubscriptionId: String?,
  updatedAt: Instant,       // the source event's timestamp, for the ordering rule
  createdAt: Instant
}
```

**`billingEvents`**

```
{ _id: String, receivedAt: Instant }   // the Freemius event id
```

### 8.2 Indexes

| Collection | Index |
|---|---|
| `users` | `{email: 1}` unique; `{googleSub: 1}` unique sparse |
| `magicLinkTokens` | TTL on `{expiresAt: 1}` |
| `authSessions` | TTL on `{expiresAt: 1}`; `{userId: 1}` |
| `subscriptions` | None. The `_id` **is** the user id, so entitlement is a point read. |
| `billingEvents` | TTL on `{receivedAt: 1}`, 90 days |

**Every TTL field above is written through `EpochMillisAsBsonDateTime`.** `Principal.kt` documents
the reason: the MongoDB TTL monitor ignores a numeric field without an error, so a plain `Long` makes
the index do nothing and the collection grows for ever. That trap is paid for one time. Do not walk
into it four more times.

### 8.3 Changes to existing collections

**`principals`** needs no schema change. Its `_id` becomes `user:<userId>` for a signed-in learner.
`PrincipalId` already defines that namespace.

**`topics`** gains no field. The learning engine computes a seed key from the slug, the prompt
version and the model family. `ExplanationGraph.keyFor` does this and `SessionService.createWillGenerate`
already reports whether the seed exists. A stored `seedKey` would duplicate state that can drift.

The rule is therefore an invariant and not a column. **A published topic must have a seed
explanation in the store.** A boot-time step generates a missing one.

The invariant is a cost and latency guarantee. It is not a security boundary. An anonymous visitor
who opens a topic with no seed causes **one** generation for that topic, one time, for every
visitor after. The exposure without the pre-warm is therefore the size of the catalogue, which is
29 topics and approximately $0.30, and the existing rate limiter and spend breaker both still
apply. The pre-warm removes that $0.30 and the cold-start delay that comes with it.

---

## 9. The gate

### 9.1 Order on `POST /api/sessions/{id}/explain`

| # | Check | Failure |
|---|---|---|
| 1 | A valid session cookie resolves to a user | `401 SIGN_IN_REQUIRED` |
| 2 | Entitlement resolves to an allowance | `403 SUBSCRIPTION_REQUIRED` |
| 3 | The span occupies `[start, end)` in the parent body | `400 SPAN_MISMATCH` |
| 4 | Quota, against the resolved allowance | See section 9.2 |
| 5 | The global spend breaker | `503 SPEND_LIMIT` |

Step 3 and every step below it come from the learning-engine specification and do not change. The
two new checks go in front. An unentitled caller therefore cannot probe span validation.

### 9.2 An exhausted allowance has two answers

A trial allowance does not roll over. A message that tells an exhausted trial user to wait is false.

| The state when the allowance runs out | The answer |
|---|---|
| `TRIALING` | `403 TRIAL_EXHAUSTED`. Only payment helps. |
| A paid state | `429 QUOTA_EXCEEDED` with a true `retryAfter` |

Two states give two answers and two panels in the user interface.

### 9.3 Which endpoints have a gate

Only the endpoints that can reach the model: `explain` and `quizzes`.

The catalogue, the topic detail, `GET /api/sessions/{id}` and `POST /api/topic-requests` stay open
behind the rate limiter. `POST /api/sessions` also stays open, because a pre-warmed seed is a cache
hit that costs nothing. That is what lets an anonymous visitor read real text. Section 8.3 gives the
bound that applies when a seed is absent.

### 9.4 The trail survives the wall

A visitor reads a seed anonymously, highlights a phrase, meets `SIGN_IN_REQUIRED` and signs in.
`SessionService` gains `reassignPrincipal`, which re-keys the session from `anon:<uuid>` to
`user:<id>`.

The session, the breadcrumb and the trail are still there after the sign-in. A lost trail at the
moment of highest intent is the most expensive defect in this specification.

---

## 10. API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/auth/magic-link` | Request a link. Always `204`. |
| `GET` | `/api/auth/magic-link/{token}` | Consume the link, set the cookie, redirect |
| `GET` | `/api/auth/google` | Start the OAuth redirect |
| `GET` | `/api/auth/google/callback` | Finish it, set the cookie, redirect |
| `POST` | `/api/auth/sign-out` | Delete this session |
| `POST` | `/api/auth/sign-out-all` | Delete every session for the user |
| `GET` | `/api/account` | The email, the state, the allowance, the remaining count, the reset time |
| `POST` | `/api/account/delete` | Delete the account after a fresh confirmation |
| `POST` | `/api/billing/checkout` | Return a Freemius checkout URL |
| `POST` | `/api/billing/webhook` | Freemius events, with a verified signature |

`GET /api/account` is the single source for the header, the wall and the allowance meter. One call
gives one truth.

---

## 11. Frontend

| Feature | Responsibility |
|---|---|
| `auth` | The sign-in panel, the magic-link sent state, the two callback landings |
| `account` | The subscription state, the allowance meter, the manage-subscription link |
| `core` | An `AccountStore` signal, and an HTTP interceptor that turns `401`, `403` and `429` into the correct panel |
| `reader` | Shows the remaining allowance. Renders the wall in place and keeps the trail. |

The session lives in an HttpOnly cookie, and that choice is not incidental. The learning-engine
specification forbids the reader to touch `window`, `document` or `localStorage` on the render path,
so that specification C can add server-side rendering later. A token in `localStorage` breaks that
rule for ever.

---

## 12. Configuration

### 12.1 New settings

| Setting | Value |
|---|---|
| `MYTETZ_MODEL_ID` / `MYTETZ_MODEL_FAMILY` | **`claude-sonnet-5`** (was `claude-opus-5`) |
| `MYTETZ_DAILY_EXPLAINS` | **25** (was 20). It is now the subscriber allowance. **It changes in B2 and not in B0** — before a gate exists it is the allowance of every anonymous visitor, and raising it early only spends money. |
| `MYTETZ_TRIAL_GENERATIONS` | 40 |
| `MYTETZ_TRIAL_DAYS` | 7 |
| `MYTETZ_GRACE_DAYS` | 3 |
| `MYTETZ_TRIALS_PER_IP_PER_DAY` | 3 |
| `MYTETZ_GLOBAL_DAILY_COST_CEILING_USD_MICROS` | Unchanged, $50 |
| `MYTETZ_MAX_OUTPUT_TOKENS` | Unchanged, 4,000 |
| `MYTETZ_PUBLIC_BASE_URL` | **Required.** A magic link and an OAuth redirect need an absolute URL. |
| `FREEMIUS_SECRET_KEY` | **Required** |
| `FREEMIUS_PRODUCT_ID` / `FREEMIUS_PLAN_ID` | Required |
| `GOOGLE_CLIENT_ID` | Required |
| `GOOGLE_CLIENT_SECRET` | **Required** |
| `MYTETZ_MAIL_MODE` | `resend` or `log`. There is no default. |
| `MYTETZ_MAIL_API_KEY` / `MYTETZ_MAIL_FROM` | Required when the mode is `resend` |

### 12.2 Which secrets refuse to start

`FREEMIUS_SECRET_KEY`, `GOOGLE_CLIENT_SECRET` and `MYTETZ_PUBLIC_BASE_URL` follow the rule in
`PrincipalCookieConfig` and not the permissive rule that the other settings use.

A defaulted webhook secret lets any caller grant itself a subscription. That is the same class of
failure as a defaulted cookie key, and there is no safe default value.

A local development machine selects the logging mail sender with an explicit
`MYTETZ_MAIL_MODE=log`. It never arrives there by a fallback.

---

## 13. The model change orphans the store

`modelFamily` is hashed into every content key. A change from `claude-opus-5` to `claude-sonnet-5`
therefore changes every key.

Section 5.1 of the learning-engine specification designed this behaviour. Old documents orphan
without harm and new documents generate when a learner asks. Three consequences follow.

1. Every seed regenerates. At 29 topics that costs approximately $0.30.
2. The store starts cold. The first subscribers pay the full generation cost. The store is cold at
   launch in any case.
3. The handover records that `explanations` has no eviction, and that an earlier key change already
   left orphans. **Slice B0 deletes both sets of orphans in the same deployment.** A known debt
   becomes a finished task instead of a doubled one.

---

## 14. Error handling

These rows join the table in the learning-engine specification.

| Failure | Behaviour |
|---|---|
| The mail provider is down | `503`. The interface offers Google sign-in. Never a silent `204`. |
| A magic link is expired or reused | A neutral page that offers a new link. It never shows whether the address exists. |
| Google reports `email_verified: false` | Refuse, log, offer the magic link |
| A Google subject collides with another account | Refuse. Log `ACCOUNT_LINK_CONFLICT`. A person must look. |
| A webhook signature does not match | `401`. Log `WEBHOOK_SIGNATURE_MISMATCH`. Never parse the body. |
| A webhook arrives out of order | The timestamp rule drops it |
| A webhook never arrives | The nightly job corrects it and logs `BILLING_DRIFT` |
| Freemius is unreachable at checkout | `503`. The learner keeps the trial and retries. |
| Mongo is unavailable on the entitlement read | **Fail closed.** This matches the rule in `QuotaService` and section 11 of the learning-engine specification, where an unavailable Mongo already gives `503`. |

### 14.1 New alert tokens

Four tokens join the three that the handover lists. An operator must have an alert on each one.

| Token | Meaning | Urgency |
|---|---|---|
| `BILLING_DRIFT` | The mirror and Freemius disagreed. The job corrected it. | Medium |
| `ACCOUNT_LINK_CONFLICT` | Two identities claim one address. A person must resolve it. | High |
| `WEBHOOK_SIGNATURE_MISMATCH` | A caller sent an unsigned or wrongly signed webhook | High |
| `MAIL_SEND_FAILED` | A learner cannot receive a magic link | High |

---

## 15. Testing strategy

Test-driven throughout, in the order of value.

- **Entitlement resolution, exhaustively.** It is a pure function of a row and a time. Every state
  crosses every expiry boundary at one second before and one second after. This function decides who
  pays, so it is proved and not sampled.
- **The webhook, adversarially.** An absent signature, a wrong signature, a signature computed over a
  re-serialized body, a replayed event id, an older timestamp, and an unknown event type. The
  re-serialization case proves that the handler must read raw bytes.
- **The magic link, adversarially.** Reuse, expiry, a token for a deleted account, and two concurrent
  redemptions against a Testcontainers Mongo. The last one has the same shape as the duplicate-key
  race test that already exists.
- **The gate order, as a leak test.** An anonymous caller must receive `SIGN_IN_REQUIRED`. It must
  never receive `SPAN_MISMATCH`.
- **The trial-to-paid transition.** The counter resets. A converted subscriber gets 25 in 24 hours
  and not 40 over the remaining trial days.
- **`reassignPrincipal`.** The trail survives the sign-in. This is the funnel, so it is a test and
  not a hope.
- **A pricing regression test** that pins `claude-sonnet-5` to $3 and $15. A silent rate change
  otherwise removes the margin without a signal.

**Deliberately not tested:** Freemius itself. The handler runs against recorded payloads. A person
verifies the live integration one time in the Freemius sandbox.

---

## 16. Delivery slices

| | Slice | Contents |
|---|---|---|
| **B0** | Cost | `claude-sonnet-5`, the orphan cleanup, the seed pre-warm, and the `Allowance` parameter in `quota`. No visible change. |
| **B1** | Accounts | The `account` module, the magic link, Google, `authSessions`, `reassignPrincipal`, the sign-in panel. The gate answers `SIGN_IN_REQUIRED`. No money yet. |
| **B2** | Trial | The `billing` module, the trial pool, entitlement resolution, `TRIAL_EXHAUSTED`, the allowance meter. The cost for each account is now bounded. |
| **B3** | Freemius | Checkout, the webhook, the state machine, the reconciliation job, `SUBSCRIPTION_REQUIRED`. **First revenue.** |
| **B4** | Hardening | Sign-out-everywhere, account deletion, Turnstile, the IP-bucket trial cap. |

**B0 ships first and alone.** The model change depends on no other decision in this document, and it
lowers the unit cost by 40% before one subscriber exists.

---

## 17. Known trade-offs

- **The wall costs organic reach.** A visitor reads a seed and stops. The published pages in
  specification C are the answer, and they do not exist yet. Between B3 and specification C the site
  converts only the visitors that it already has.
- **The trial is unmetered acquisition spend.** Turnstile and the IP cap bound it. They do not remove
  it. Below one conversion in twenty, either the pool gets smaller or a card moves in front of the
  trial.
- **The introductory rate for Sonnet 5 ends on 2026-08-31.** Every number here uses the standard rate
  of $3 and $15, so nothing breaks on that date. The weeks before it are cheaper than the plan.
- **The entitlement cache is stale for 60 seconds** across machines after a cancellation.
- **A refund does not sign the learner out.** The learner keeps a cached trail that costs nothing to
  serve. This is deliberate.
- **A future model change repeats the orphan cost.** That is the price that the learning-engine
  specification knowingly paid for cache invalidation by key.

---

## 18. Next steps

1. An implementation plan for slice B0 (writing-plans).
2. An implementation plan for slices B1 to B4.
3. Specification C — the SEO and AEO surface, which removes the first trade-off above.
