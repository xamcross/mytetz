# Slice 0 and Slice 1 — handover

This document records the state of the branch `slice-0-1-learning-engine` at the end of
implementation. It lists what the branch delivers, what stops a deployment, what an operator must
do, and which decisions the owner must make.

This document uses ASD-STE100 Simplified Technical English.

---

## 1. What the branch delivers

The branch delivers the walking skeleton and the learning engine.

- A curated catalogue of 29 published topics.
- A content-addressed explanation store. Two learners who follow the same path share one document.
- A per-principal quota and a global daily spend ceiling.
- Sessions with a tree of nodes, a breadcrumb and a trail rail.
- A streaming explain endpoint that uses Server-Sent Events.
- An Angular reader that shows the text as it arrives.

Test totals at the end of implementation:

| Suite | Command | Tests |
|---|---|---|
| Backend | `./gradlew build` | 325 |
| Frontend unit | `npm test -- --watch=false` | 103 |
| End-to-end | `npx playwright test` | 6 |

---

## 2. What stops a deployment

The application cannot start until you set two secrets. Set both before you deploy.

```
fly secrets set MYTETZ_COOKIE_SIGNING_KEY="$(openssl rand -base64 32)" --app mytetz
fly secrets set ANTHROPIC_API_KEY="<your key>" --app mytetz
```

The cookie signing key has no default. This behaviour is deliberate. A default key lets any caller
select its own principal, and that gives a free quota reset.

The Anthropic account has no credit. Add credit before a learner can generate an explanation. The
catalogue works without credit, because it makes no model call.

---

## 3. Actions for the operator

Do these before the site carries real traffic.

1. **Rotate the Cloudflare API token.** The token has access to all six zones. Its value went into
   a chat transcript. Re-issue it and restrict it to the `mytetz.com` zone.
2. **Add log collection and three alerts.** The application has no metrics and no alerting. The
   code writes three tokens that an operator must see:
   - `SPEND_UNRECORDED` — the spend ledger understates the true cost.
   - `CORRUPT_SESSION` — a session document is damaged. A human must look at it.
   - `unrecognised stop reason` — the model returned a stop reason that the validator does not
     know. The validator rejects the answer.
   Today a person sees these only if that person runs `fly logs` at the moment of the event.
3. **Apply Cloudflare rate limiting.** The Cloudflare token did not have the `Zone → Rate Limit →
   Edit` permission, so nobody applied the rule. This rule is the second bound on the explain
   endpoint. See section 5.
4. **Run the acceptance checks.** Section 6 lists them. They need a live deployment and credit.

---

## 4. Decisions for the owner

Four decisions are open. Each one is a decision by omission today.

1. **Topic pages do not exist.** The design specification asks for "catalogue and topic pages" and
   for a topic-detail endpoint that carries a resolved seed. No task in the plan creates one. The
   organic search strategy depends on these pages. Server-side rendering is deliberately out of
   scope for this specification, so the pages and the rendering method are best decided together.
2. **Nothing sets a session to `COMPLETED`.** The enumeration and its wire format work. No code
   makes the transition.
3. **A repeated question uses the session node budget.** A learner who reads the same trail again
   adds a node each time. The 200-node ceiling can end a session on cache hits that cost nothing.
4. **An unpublished topic does not stop a session that runs.** A curator who unpublishes a topic
   stops new sessions. A session in progress continues. The reasoning is in the `SessionService`
   documentation.

---

## 5. Known limits, with numbers

### The explain endpoint bound is loose

The explain endpoint has a rate limit of 30 requests for each address in 10 minutes.

- An honest learner meets the daily quota of 20 explanations first. The rate limit never binds
  that learner.
- The window is fixed. One day holds 144 windows. One address can therefore make
  **4,320 explanations each day**.
- One generation costs approximately $0.01. The maximum is approximately $0.11.
- One address can therefore spend **$43 to $475 each day**.
- The global daily ceiling is $50. The ceiling cannot see this spend, because a cancelled
  generation records no cost.

Two conditions make the bound weaker:

- The counters stay in the process. The `fly.toml` file stops an idle machine. A cold start gives
  a full allowance again.
- A caller that reaches `mytetz.fly.dev` directly can set its own `CF-Connecting-IP` header. Each
  value makes a new bucket. Cloudflare rate limiting (section 3, item 3) closes this path.

**The recommended next step** is one of these:

- Record an estimated cost when a stream stops early.
- Add a per-address daily cap that all machines share, with a time-to-live index.

### The spend ledger understates the cost

The ledger records a cost only when the model stream completes. These paths spend money and record
nothing:

- The learner disconnects before the stream completes.
- The provider stream ends without a stop reason. The 120-second whole-call timeout does this.

No token count exists on these paths, so no layer can know the cost. The rate limit above is the
bound, not the accounting.

### The explanation store has no eviction

The `explanations` collection grows and never shrinks. The change to the content key in the final
fix wave orphaned every document that existed before. The store fills again as learners ask
questions. The MongoDB Atlas M0 cluster has a 512 MB limit and no disk alert.

---

## 6. Acceptance checks that need a live deployment

Run these after you set the secrets and add credit.

1. The catalogue shows more than 20 topics and the search filters them.
2. A topic selection creates a session and shows the seed text.
3. A highlighted phrase and the Explain button stream an explanation. The text appears
   progressively.
4. The breadcrumb and the trail rail grow with each step. A breadcrumb button moves the focus.
5. A second session on the same path uses the cached explanation and makes no model call. Check
   the `requestCount` field on the explanation document.
6. A learner who passes the daily allowance gets a `QUOTA_EXCEEDED` error. A tripped spend breaker
   still serves a cache hit.

**Check the central promise.** Open "microscopic realm" under Quantum Physics and under
Microbiology. Compare the two explanation documents. They must differ.

---

## 7. Deferred items

The final whole-branch review triaged 39 open items. The review marked most of them "fine to
ship". These items remain and are small:

- The Gradle wrapper checksum is now pinned. Nobody verified the hash value against the published
  checksum, because the review had no network access.
- `RETRYABLE_LOAD_CODES` holds `INTERNAL` only. A transient fault that maps to another code shows
  no retry button.
- A duplicated documentation block sits before `Mongo.ping()`.
- The mutation test harness does not check the result of `mv` when it restores a file. The next
  run refuses to start if a backup file remains, so the failure is loud.
- A raw count of failed tests is not a count of proved behaviours. A mutation that causes a
  timeout also fails the tests that follow it, because the test harness stays in a bad state.
