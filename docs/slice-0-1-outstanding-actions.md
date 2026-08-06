# Slice 0 and Slice 1 — outstanding manual actions

This document lists every action that a person must do. The code is complete and merged. No
action in this document is a code change that an agent can do alone. Each one needs a credential,
an external service, or a decision.

The phases are in order. A later phase depends on an earlier one. The decisions in phase 5 are
independent and you can make them at any time.

This document uses ASD-STE100 Simplified Technical English.

---

## Phase 1 — Security. Do this first.

### Step 1.1 — Rotate the Cloudflare API token

**Why.** The token has edit access to all six zones in the account. Its value went into a chat
transcript. A token with that scope must not stay in a transcript.

**How.**

1. Open the Cloudflare dashboard. Go to **My Profile → API Tokens**.
2. Find the token that the deployment uses. Select **Roll** to make a new value, or **Delete** and
   then make a new token.
3. Make the new token with these permissions only:
   - Zone → DNS → Edit
   - Zone → Zone Settings → Edit
   - Zone → Cache Purge → Purge
   - Zone → Rate Limit → Edit
4. Set **Zone Resources** to `Include → Specific zone → mytetz.com`. Do not use `All zones`.
5. Store the new value in a password manager. Do not put it in a chat, a file, or a commit.

**How to know it worked.** Run a read command with the new token. The command must succeed for
`mytetz.com` and fail for another zone in the account.

> The `Zone → Rate Limit → Edit` permission is new. The old token did not have it. Step 4.2 needs
> it.

---

## Phase 2 — Make the application start

The application cannot start today. It stops at boot because one secret has no value. This is
deliberate: a default signing key lets any caller select its own principal, and that gives a free
quota reset.

### Step 2.1 — Set the cookie signing key

```
fly secrets set MYTETZ_COOKIE_SIGNING_KEY="$(openssl rand -base64 32)" --app mytetz
```

The key must have 32 characters or more. The application refuses a shorter key at startup.

### Step 2.2 — Set the Anthropic API key

```
fly secrets set ANTHROPIC_API_KEY="<your key>" --app mytetz
```

### Step 2.3 — Add credit to the Anthropic account

The account has no credit. A request for an explanation fails with HTTP 400 until you add credit.

The catalogue does not need credit. It makes no model call.

### Step 2.4 — Deploy

```
fly deploy --local-only --ha=false --app mytetz
```

The `--ha=false` flag is not optional. Without it, fly.io makes two machines and doubles the cost.

**How to know it worked.**

```
curl -s https://mytetz.com/api/health
```

The answer must be `{"status":"ok","mongo":true,"ready":true}`.

The `ready` field is important. It is `false` while the application creates the database indexes
and loads the topic catalogue. Wait for `true` before you go to phase 3.

### Step 2.5 — Check the log line for the rate-limit key

```
fly logs --app mytetz | grep "rate limiting keys on"
```

The line must name `CF-Connecting-IP`.

**If the line names `Fly-Client-IP`,** the `MYTETZ_CLIENT_IP_HEADER` value in `fly.toml` has a
typo. Every visitor behind one Cloudflare edge then shares one allowance, and the site looks
broken to them. Correct the value and deploy again.

---

## Phase 3 — Verify the product

Do these checks in a browser at `https://mytetz.com`. They need phase 2.

### Step 3.1 — The seven acceptance checks

| # | Check | Pass condition |
|---|---|---|
| 1 | Open the catalogue | More than 20 topics appear. The search box filters them. |
| 2 | Select a topic | A session starts. The introductory text appears. |
| 3 | Highlight a phrase and press Explain | The text appears word by word, not all at one time. |
| 4 | Drill down twice | The breadcrumb and the trail rail grow. A breadcrumb button moves the focus. |
| 5 | Repeat the same path in a new session | The answer appears at once and no model call happens. |
| 6 | Pass the daily allowance of 20 | The reader shows `QUOTA_EXCEEDED` and a wait time. |
| 7 | Both test suites | Already green. No action. |

For check 5, confirm the cache in the database. Open the `explanations` collection in Atlas. Find
the document for that span. The `requestCount` field must be 2 or more, and `costMicros` must not
change.

### Step 3.2 — Check the central promise

This is the one check that proves the product works. Do not skip it.

1. Start a session on **Quantum Physics**. Drill into the phrase "microscopic realm".
2. Start a session on **Microbiology**. Drill into the same phrase.
3. Compare the two answers.

The Quantum Physics answer must describe the subatomic scale. The Microbiology answer must
describe cells and bacteria. If the two answers are the same, the context isolation has failed and
you must stop and report it.

You can also confirm this in the database. The two explanations must have different `_id` values.

---

## Phase 4 — Make the system observable. Do this before real traffic.

Today nobody can see a failure. The application has no metrics and no alerts. The log output goes
to the console only, and the timestamp has no date.

The code already writes three tokens. A person sees them today only by chance.

### Step 4.1 — Add a log drain and three alerts

1. Connect a log service to the fly.io application. Use `fly logs` shipping, or add a Logtail,
   Datadog or Better Stack drain.
2. Make an alert for each of these strings. Send each alert to a channel that a person reads.

| String | What it means | Urgency |
|---|---|---|
| `SPEND_UNRECORDED` | The application spent money but did not record it. The ledger understates the true cost. | High |
| `CORRUPT_SESSION` | A session document is damaged. No retry helps. A person must look at the document. | High |
| `unrecognised stop reason` | Anthropic returned a stop reason that the validator does not know. The validator rejects every such answer. | High |
| `BOOTSTRAP_FAILED` | The application started but did not create the indexes. | Medium |

The third string is important. If Anthropic adds a new stop reason that means success, the
validator rejects every answer until a person updates the list. The application looks broken and
nothing says why.

### Step 4.2 — Add a Cloudflare rate limit rule

**Why.** This is the second bound on the explain endpoint. Section 6 gives the numbers.

**How.**

1. Open the Cloudflare dashboard for `mytetz.com`. Go to **Security → WAF → Rate limiting rules**.
2. Make a rule with this shape:
   - Match: `URI Path` contains `/api/sessions/` and `URI Path` ends with `/explain`
   - Rate: 60 requests in 1 minute for each IP address
   - Action: Block for 10 minutes
3. Make a second rule for the whole API:
   - Match: `URI Path` starts with `/api/`
   - Rate: 300 requests in 1 minute for each IP address
   - Action: Managed challenge

This step needs the `Zone → Rate Limit → Edit` permission from step 1.1.

### Step 4.3 — Turn on Bot Fight Mode

Go to **Security → Bots**. Turn on **Bot Fight Mode**. This is one switch and it costs nothing.

---

## Phase 5 — Decisions for the owner

Each item below is a decision by omission today. The code works. Nobody chose the behaviour.

### Decision 5.1 — Topic pages

**The state.** The design specification asks for "catalogue and topic pages" and for a
topic-detail endpoint that carries a resolved seed. No task in the plan creates one. The
specification also puts server-side rendering out of scope.

**Why it matters.** The plan for user growth is organic search. A crawler sees very little of a
page that a browser renders. Today the site has two pages: the catalogue and the reader. Neither
is a page that a search engine can index well.

**The options.**

| Option | Effect |
|---|---|
| Add topic pages in slice 2 | The pages exist sooner. You build them twice if the rendering method changes. |
| Wait, and do the pages with the search work | You decide the pages and the rendering method together. The site is not findable until then. |
| Drop the pages | The reader is the product. You find users by another method. |

**My recommendation.** Wait. A page that a crawler cannot read gives no benefit, and the rendering
method decides the page structure. Build both at one time.

### Decision 5.2 — When does a session end?

**The state.** A session has a `COMPLETED` state. The wire format works and a test pins it. No
code makes the transition. Every session stays `ACTIVE` for ever.

**The options.** End a session after a period of inactivity. End it when the learner presses a
button. Or delete the state and accept that a session never ends.

**Why it matters.** The `sessions` collection grows and never shrinks. A session document holds
pointers only, so the growth is slow. The Atlas M0 cluster has a 512 MB limit.

### Decision 5.3 — Does a repeated question use the node budget?

**The state.** A session has a ceiling of 200 nodes. A learner who asks the same question again
adds a node each time. The second answer is a cache hit and costs nothing, but it still uses the
budget.

**The effect.** A learner who reads their own trail again can end their session on answers that
cost nothing.

**The options.** Move the focus to the node that exists, instead of adding a new one. Or raise the
ceiling. Or accept the behaviour, because a repeated question is a real step in a history.

### Decision 5.4 — What happens to a session when a curator unpublishes a topic?

**The state.** A curator who unpublishes a topic stops new sessions. A session that runs
continues.

**The reason.** The explanation store has no delete path, so a check on the explain endpoint would
stop new generations and leave every existing answer reachable. That is a half measure.

**The options.** Accept the behaviour. Or add a delete path for the content, and then stop the
running sessions too.

**Note.** This becomes urgent only if you must withdraw content that is wrong or harmful. Today
you cannot do that.

---

## Phase 6 — Hardening. Do these after the site works.

### Step 6.1 — Tighten the explain endpoint bound

**The numbers.** The explain endpoint allows 30 requests for each address in 10 minutes. An honest
learner meets the daily quota of 20 explanations first and never meets this limit. But the window
is fixed, so one day holds 144 windows:

- 30 × 144 = **4,320 explanations for one address each day**
- One generation costs about $0.01. The maximum is about $0.11.
- One address can therefore spend **$43 to $475 each day**.
- The global daily ceiling is $50, and **it cannot see this spend**, because a cancelled
  generation records no cost.

Two conditions make the bound weaker. The counters stay in the process, and fly.io stops an idle
machine, so a cold start gives a full allowance again. A caller that reaches `mytetz.fly.dev`
directly can also forge the `CF-Connecting-IP` header, and each value makes a new bucket.

**The options.**

1. Record an estimated cost when a stream stops early. Use the input tokens and the text that
   arrived. This closes the accounting gap. It is the correct fix and it is the harder one.
2. Add a per-address daily cap that all machines share. Store it in MongoDB with a time-to-live
   index. The code comment already proposes this.
3. Do step 4.2 and accept the bound. Cloudflare then blocks the caller before the application
   sees it.

Step 4.2 gives most of the benefit for the least work. Do that first.

### Step 6.2 — Add eviction to the explanation store

The `explanations` collection grows and never shrinks. The `topicRequests` collection has a cap;
this one does not.

The final fix wave changed the content key, so every document from before the change is now
unreachable. Those documents stay in the collection. Delete them, or add a rule that removes a
document with a low `requestCount` and an old `createdAtEpochMillis`.

### Step 6.3 — Verify the Gradle wrapper checksum

The build now pins `distributionSha256Sum`. Nobody compared the value against the published
checksum, because the review had no network access.

```
curl -fsSL https://services.gradle.org/distributions/gradle-9.6.1-bin.zip.sha256
grep distributionSha256Sum gradle/wrapper/gradle-wrapper.properties
```

The two values must match.

### Step 6.4 — Raise the memory of the local Docker VM

The Docker VM has about 2 GB. Testcontainers failed 19 times during this work. Every failure was
on the first test run of a session, and every retry succeeded.

This affects a developer only. It does not affect the deployment.

---

## Summary table

| Phase | Steps | Blocks what |
|---|---|---|
| 1. Security | 1 | Nothing. Do it now. |
| 2. Start the application | 4 | Everything below. |
| 3. Verify | 2 | Confidence that the product works. |
| 4. Observability | 3 | Real traffic. |
| 5. Decisions | 4 | Slice 2 planning. |
| 6. Hardening | 4 | Nothing. Do it when you can. |
