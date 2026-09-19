# Deploying mytetz

One artifact serves everything: the Kotlin/Ktor backend and the compiled Angular
bundle ship in the same container. There is no separate frontend host.

```
browser -> Cloudflare (proxied, Full (strict)) -> fly.io Anycast -> machine in fra -> Ktor :8080
                                                                                       |
                                                                          MongoDB Atlas M0, AWS eu-central-1
```

---

## 1. Components and where they live

| Thing | Value |
| --- | --- |
| fly app | `mytetz`, org `personal` |
| fly primary region | `fra` (Frankfurt) |
| fly VM | 1 x `shared-cpu-1x`, 512 MB, stays up — see section 4 |
| fly hostname | `https://mytetz.fly.dev` |
| fly IPv6 | `2a09:8280:1::15c:3d15:0` — **dedicated** |
| fly IPv4 | `66.241.125.121` — **shared** |
| Atlas cluster | `mytetz`, tier **M0** (free shared), provider **AWS**, region **EU_CENTRAL_1** (Frankfurt) |
| Atlas database | `mytetz` |
| Public hostname | `mytetz.com` (+ `www.mytetz.com`) via Cloudflare |

**The IPv4 is shared, and that is a Cloudflare constraint.** `66.241.125.121` is a
fly *shared* v4 address, so fly routes inbound TLS by **SNI**, not by address. A
request only reaches this app if the TLS handshake presents a hostname fly has a
certificate for. Consequences:

- `fly certs create mytetz.com` (and `www`) **must** be done before a proxied
  Cloudflare A record to that address will work, because Cloudflare's origin
  connection sets `SNI = mytetz.com`.
- The IPv6 address is dedicated, so an `AAAA` record works without SNI matching.
- Allocating a dedicated v4 (`fly ips allocate-v4`) removes the SNI dependency but
  **costs money** ($2/month at time of writing). Not done; the shared address is
  sufficient behind Cloudflare.

**Why `fra`.** The Atlas M0 cluster is in AWS `EU_CENTRAL_1`. `primary_region = "fra"`
in `fly.toml` puts the app in the same metro, which keeps every database round-trip
in single-digit milliseconds. **If you move one, move the other** — an M0 cluster's
region cannot be changed after creation, so moving the app alone means recreating
the cluster and re-seeding it.

---

## 2. Configuration

Non-sensitive values are checked in, in `fly.toml`'s `[env]` block:

```toml
[env]
  PORT = "8080"
  MONGODB_DATABASE = "mytetz"
  MYTETZ_CLIENT_IP_HEADER = "CF-Connecting-IP"
```

`MYTETZ_CLIENT_IP_HEADER` decides which header the rate limiters key on, and it
is a security setting rather than a tuning one. Three routes use them:
`POST /api/topic-requests`, `POST /api/sessions` and
`POST /api/sessions/{id}/explain`. Because
Cloudflare proxies this app (section 5), `CF-Connecting-IP` is the visitor's
real address and gives one bucket per visitor. The trade: anyone reaching
`mytetz.fly.dev` **directly**, bypassing Cloudflare, can set that header to
whatever they like and so escape their own rate limit. That evasion is bounded
by the limiter's key ceiling and by the eviction cap on the `topicRequests`
collection, and it costs honest visitors nothing.

The code default is `Fly-Client-IP`, which fly's proxy sets and a caller cannot
forge — but behind Cloudflare it resolves to the Cloudflare *edge*, so everyone
sharing an edge would share a single daily allowance. Set this to `none` only
for a deployment behind no proxy you control: with no trusted header the key
falls back to the socket peer, which on a proxied deployment is one address for
every visitor.

The server accepts only a name it knows: `Fly-Client-IP`, `CF-Connecting-IP`,
`True-Client-IP`, `X-Forwarded-For`, `X-Real-IP`, or `none`. Case does not
matter. Any other value is rejected with a WARN line, and `Fly-Client-IP`
applies. A misspelt name is never present on a request, so without this check
every visitor would share one bucket. The resolved source is logged once at
startup — grep the boot log for `rate limiting keys on`.

Sensitive values are fly secrets. `fly secrets list` shows names and digests only.

| Secret | Required | Notes |
| --- | --- | --- |
| `MONGODB_URI` | yes — the app calls `error("MONGODB_URI is not set")` and refuses to boot without it | full `mongodb+srv://` string including the database user's password |
| `MYTETZ_COOKIE_SIGNING_KEY` | yes — the app refuses to boot without it, deliberately | signs the anonymous principal cookie. There is no safe default: a known key lets anyone mint any principal. 32 characters minimum |
| `ANTHROPIC_API_KEY` | for explanation generation only | the model client is built lazily, so the catalogue serves without it |
| `FREEMIUS_SECRET_KEY` | for checkout and the webhook only | signs and verifies the Freemius webhook. Built lazily, alongside `FREEMIUS_PRODUCT_ID` and `FREEMIUS_PLAN_ID`; the catalogue, sign-in and reading all still serve with none of the three set |
| `FREEMIUS_API_KEY` | for reconciliation and the customer portal | a Bearer token for the Freemius Developer API, distinct from `FREEMIUS_SECRET_KEY`. Built lazily; with `MYTETZ_RECONCILE_ON_BOOT` on and this unset, the boot logs `RECONCILE_SKIPPED` rather than failing. `POST /api/billing/portal` also needs this key, and answers `503 BILLING_UNAVAILABLE` while it is unset |
| `MYTETZ_TURNSTILE_SECRET` | no | Turnstile checks a token in front of the magic-link request and the start of the Google flow. With this unset, the check is skipped and every sign-in still works — see section "Turnstile" below |

Set them from the git-ignored `.env` at the repo root, without ever echoing them:

```bash
fly secrets set MONGODB_URI="$(grep '^MONGODB_URI=' .env | cut -d= -f2- | tr -d '\r\n')" --app mytetz
fly secrets set MYTETZ_COOKIE_SIGNING_KEY="$(openssl rand -base64 32)" --app mytetz
```

Setting a secret triggers a rolling restart. Use `--stage` to queue it for the
next `fly deploy` instead.

`.env.example` lists every variable the app knows about, with placeholders.
Copy it to `.env` for local work. **`.env` is git-ignored and must never be
committed**, and `.dockerignore` excludes it so it cannot reach a layer either.

### 2.1 The spend ceiling — read this before an incident, not during one

`MYTETZ_GLOBAL_DAILY_COST_CEILING_USD_MICROS` is **the only spend brake in the
system**. Lower it to slow a runaway bill.

- The unit is USD micro-dollars. The default is `50000000`, which is **$50**.
- It is a ceiling on the cost of all generation in one **UTC day**, across every
  visitor. The ledger is one document in Mongo, so a restart does not reset it.
- At the ceiling the server refuses **new** generation and keeps serving every
  explanation it already holds. The site stays usable and stops costing money.
  A refused request answers `503` with the code `SPEND_LIMIT`.

To lower it mid-incident:

```bash
fly secrets set MYTETZ_GLOBAL_DAILY_COST_CEILING_USD_MICROS=5000000 --app mytetz   # $5
```

The machine restarts, and the new ceiling applies against the same day's ledger.
A ceiling below the day's recorded spend stops new generation at once.

**A stream that stops early now records an estimated cost.** Two things stop a
stream before the model's own answer completes: a learner who navigates away
mid-answer, and a provider stream that ends without a stop reason, which
includes the 120-second client timeout. Both used to record nothing. Each one
now records an estimate against this ceiling and against the learner's own
daily allowance, and the server logs one `SPEND_ESTIMATED` line for each
record. The estimate uses the real input token count when the stream reported
one, and the real output token count when the stream reported one. It falls
back to the length of the prompt and of the text the stream did deliver, each
divided by four, when a real count did not arrive in time.

One gap remains. A request that fails for any other reason — an upstream
fault that is neither of the two above — still records nothing, because
nothing says how much of an answer the model produced before it fell over.
`EXPLAINS_PER_CALLER` in `SessionRoutes.kt` — 30 explanations per address per
ten minutes — is what bounds a retry loop built on that path. Its counters
live in the process, so they reset whenever the machine cold-starts.

### 2.2 Every variable the backend reads

44 variables, and each one is listed here and in `.env.example`. The Default
column gives `none` for a name with no default in code, and the real default
for every other name.

**A name with a default falls back to it on an unset, unparseable or
non-positive value, rather than stopping the server.** These values are read
while the process starts, so a typo must not take the site down. A name with
no default states its own consequence in its own row.
`MONGODB_URI` and `MYTETZ_COOKIE_SIGNING_KEY` are the two names whose absence
stops the whole server. Each other `none` row stops only the one feature that
needs it, until an operator sets it.

| Variable | Default | What it decides |
| --- | --- | --- |
| `PORT` | `8080` | the port Ktor binds. `fly.toml` sets it. |
| `MONGODB_URI` | none — required | the Atlas connection string. The app refuses to boot without it. |
| `MONGODB_DATABASE` | `mytetz` | the database name. |
| `MYTETZ_MONGO_SERVER_SELECTION_TIMEOUT_MILLIS` | `3000` | how long the driver looks for a reachable server. Keep it under fly's 5s health-check timeout. |
| `ANTHROPIC_API_KEY` | none | the model key. The catalogue serves without it; explanation generation does not. |
| `MYTETZ_MODEL_ID` | `claude-sonnet-5` | the model that generates explanations. It also selects the price table. |
| `MYTETZ_MODEL_FAMILY` | `claude-sonnet-5` | part of every content key. Changing it regenerates the whole store. |
| `MYTETZ_LLM_TIMEOUT_SECONDS` | `120` | the ceiling on one streamed request, and on how long a stalled read holds a thread. |
| `MYTETZ_MAX_OUTPUT_TOKENS` | `4000` | caps thinking and response text together. |
| `MYTETZ_EFFORT` | `LOW` | thinking effort: `LOW`, `MEDIUM` or `HIGH`. An unknown name falls back to the cheapest. |
| `MYTETZ_MAX_DEPTH` | `12` | how deep one learner may drill in a session. |
| `MYTETZ_MAX_SESSION_NODES` | `200` | how many steps one session may hold. |
| `MYTETZ_MAX_VARIANTS` | `3` | how many times one span may be regenerated. |
| `MYTETZ_DAILY_EXPLAINS` | `20` | explanations per principal per day. A visitor that drops its cookie gets a new principal, so this bounds a polite client only. |
| `MYTETZ_GLOBAL_DAILY_COST_CEILING_USD_MICROS` | `50000000` ($50) | **the only spend brake.** See section 2.1. |
| `MYTETZ_MAX_TOPIC_REQUESTS` | `5000` | how many distinct rows `POST /api/topic-requests` may store. It then recycles the least-wanted row. |
| `MYTETZ_COOKIE_SIGNING_KEY` | none — required | signs the principal cookie. The app refuses to boot without it. 32 characters minimum. |
| `MYTETZ_COOKIE_SECURE` | `true` | whether the cookie carries `Secure`. Only an explicit `false`, `0`, `no` or `off` turns it off. |
| `MYTETZ_CLIENT_IP_HEADER` | `Fly-Client-IP` | which header the rate limiters key on. See section 2. |
| `MYTETZ_MIGRATE_ON_BOOT` | off | whether the app deletes an explanation stranded by a model family change, at boot. Only the exact word `true` turns it on. It does **not** control the pre-warm of a missing seed: that step runs on every boot, with no flag. Section "The B0 model migration" explains both. |
| `GOOGLE_CLIENT_ID` | none | the Google OAuth client ID. Sign-in with Google answers `503` until this and `GOOGLE_CLIENT_SECRET` are both set. |
| `GOOGLE_CLIENT_SECRET` | none | the Google OAuth client secret. Sign-in with Google answers `503` until this and `GOOGLE_CLIENT_ID` are both set. |
| `MYTETZ_MAIL_MODE` | none | selects the mail adapter: `resend` or `log`. Sign-in by email answers `503` until this holds one of the two words. |
| `MYTETZ_MAIL_FROM` | none | the sender address for a magic-link email. `MYTETZ_MAIL_MODE=resend` needs it. |
| `MYTETZ_TRIAL_GENERATIONS` | `40` | how many generations a new trial grants in total. |
| `MYTETZ_TRIAL_DAYS` | `7` | how many days a new trial lasts. |
| `MYTETZ_GRACE_DAYS` | `3` | extra days of access after an active subscription's period end, so a late renewal webhook still finds the learner allowed. A past-due row keeps access for this many days from the event that flagged it. A cancelled row gets no grace: it loses access exactly at its period end. |
| `MYTETZ_SUBSCRIBER_DAILY_EXPLAINS` | `25` | explanations per day for a paying subscriber. |
| `MYTETZ_QUIZ_EFFORT` | `LOW` | thinking effort for a quiz: `LOW`, `MEDIUM` or `HIGH`. An unknown name falls back to `LOW`. |
| `MYTETZ_QUIZ_MAX_OUTPUT_TOKENS` | `2000` | caps thinking and response text together, for one quiz generation call. |
| `MYTETZ_TEST_ME_MAX_QUESTIONS` | `3` | how many questions one "test me" quiz holds. |
| `MYTETZ_EXAM_MAX_QUESTIONS` | `8` | how many questions one exam holds. |
| `MYTETZ_EXAM_MAX_SOURCES` | `20` | how many of a session's most recent nodes an exam may draw from. |
| `FREEMIUS_SECRET_KEY` | none | signs and verifies the Freemius webhook. Checkout and the webhook route answer `503` until this, `FREEMIUS_PRODUCT_ID` and `FREEMIUS_PLAN_ID` are all set. See the secrets table above. |
| `FREEMIUS_PRODUCT_ID` | none | the Freemius product id. Checkout and the webhook route answer `503` until this, `FREEMIUS_SECRET_KEY` and `FREEMIUS_PLAN_ID` are all set. |
| `FREEMIUS_PLAN_ID` | none | the Freemius plan id. Checkout and the webhook route answer `503` until this, `FREEMIUS_SECRET_KEY` and `FREEMIUS_PRODUCT_ID` are all set. |
| `FREEMIUS_API_KEY` | none | a Bearer token for the Freemius Developer API. With `MYTETZ_RECONCILE_ON_BOOT` on and this unset, the boot logs `RECONCILE_SKIPPED` and reconciliation does nothing. See "Billing reconciliation" below. `POST /api/billing/portal` also needs this key, and answers `503 BILLING_UNAVAILABLE` while it is unset. |
| `MYTETZ_RECONCILE_ON_BOOT` | off | whether the reconciliation sweep runs at every boot. Only the exact word `true` turns it on. Section "Billing reconciliation" below explains it. |
| `MYTETZ_TURNSTILE_SECRET` | none | the Turnstile secret key. With this unset, the Turnstile check is skipped, and every sign-in still works. Section "Turnstile" below explains it. |
| `MYTETZ_TURNSTILE_SITE_KEY` | none | the Turnstile site key, reported to the browser at `GET /api/auth/config`. With this unset, the sign-in panel renders no widget. Section "Turnstile" below explains it. |
| `MYTETZ_MAIL_API_KEY` | none | the Resend API key. Needed only when `MYTETZ_MAIL_MODE` is `resend`; without it, email sign-in in that mode answers `503`. |
| `MYTETZ_PUBLIC_BASE_URL` | none | the absolute base url of this deployment, such as `https://mytetz.com`. Email sign-in and Google sign-in both answer `503` until this is set. |
| `MYTETZ_EVICTION_MAX_REQUEST_COUNT` | `0` | the most times a candidate document may have been read and still be evicted. `0` is a legal value, and it is also the default. |
| `MYTETZ_EVICTION_MAX_AGE_DAYS` | `90` | how old a document must be before it is a candidate. `0` falls back to the default, because it would mark every document as old enough at once. |

### Explanation-store eviction

The `explanations` collection grows and never shrinks on its own. The Atlas M0 cluster has a
512 MB limit. A full cluster refuses every write, and every new explanation then fails.
`Components.evictExplanations` removes a document that nothing needs any more, so the collection
stays bounded.

A document is a candidate for eviction when all three of these are true:

- It is not a seed. A seed keeps the pre-warm contract, so eviction never touches it.
- Its `requestCount` is at or below `MYTETZ_EVICTION_MAX_REQUEST_COUNT`.
- Its `createdAtEpochMillis` is older than `MYTETZ_EVICTION_MAX_AGE_DAYS`.

`createdAtEpochMillis` is a plain number, not a BSON date, so a TTL index cannot serve this rule.
The `created_at` index on `explanations` serves the age filter and the sort instead.

A candidate document still survives when a session node points at it, through
`nodes.explanationKey` in the `sessions` collection. Deleting the target of a live node would
break that session's trail, so the job checks every candidate against that collection before it
deletes anything.

The job runs once at boot, at the end of `Components.bootstrap`, and once a day after that, from a
loop in `Application.kt`. One run reads 5,000 candidates at most, in pages, and never scans the
whole collection. The next run continues after where the last one stopped, rather than reading the
same oldest candidates again, so a large collection of mostly-referenced candidates does not stall
the job for ever. A full pass of a large collection can therefore take more than one day. Grep the
boot log for `EVICTION` to see what one run removed and what it scanned.

### Atlas network access — known constraint

The Atlas project's IP access list contains `0.0.0.0/0`.

fly machines on the shared plan have **no stable egress IP**, so there is nothing
narrower to allow-list. The database is therefore protected by credentials and TLS
alone. Revisit this if the app moves to a dedicated egress IP (fly's
[static outbound IP](https://fly.io/docs/networking/static-ip-addresses/) feature)
or to Atlas private endpoints — at that point replace `0.0.0.0/0` with the fixed
address. Until then, treat `MONGODB_URI` as the only thing standing between the
public internet and the data, and rotate it if it is ever exposed.

---

## 3. The image

`Dockerfile` is three stages:

1. **`frontend`** — `node:24-slim`. Runs `npm ci` then `ng build`, producing
   `dist/frontend/browser`. It asserts the Node version satisfies Angular 21's
   `engines` field (`^20.19 || ^22.12 || >=24`) and fails the build if not.
2. **`backend`** — `eclipse-temurin:21-jdk`, invoking **`./gradlew`**, not a
   `gradle:*` image. The wrapper pins Gradle 9.6.1; a `gradle:*` base would pin a
   second, different Gradle and the two would drift. The stage 1 bundle is copied
   to `frontend/dist/frontend/browser`, which is exactly where
   `:backend:api:processResources` looks for it, so the npm-driven Gradle tasks
   are skipped with `-x installFrontend -x buildFrontend`.
3. **`runtime`** — `eclipse-temurin:21-jre-alpine`, non-root user `app`, holding
   only the `installDist` output. No JDK, no compiler, no sources.

Layer order matters: build scripts and `package*.json` are copied before sources,
so editing Kotlin or TypeScript does not re-download Gradle or re-run `npm ci`.

Build and run it locally against the real cluster:

```bash
docker build -t mytetz:local .
docker run --rm -p 8080:8080 \
  -e MONGODB_URI="$(grep '^MONGODB_URI=' .env | cut -d= -f2- | tr -d '\r\n')" \
  -e MONGODB_DATABASE=mytetz \
  mytetz:local
curl -s localhost:8080/api/health    # {"status":"ok","mongo":true,"ready":true}
```

---

## 4. Deploy

```bash
fly deploy --local-only --ha=false     # builds with the local Docker daemon, pushes to registry.fly.io
fly status --app mytetz
curl -s https://mytetz.fly.dev/api/health
```

`--local-only` is deliberate: it avoids provisioning a fly remote builder machine.
Drop it (plain `fly deploy`) if you have no local Docker; fly will start a builder
machine on demand.

**`--ha=false` is not optional if you want to stay on one machine.** On a first
deploy flyctl creates **two** machines for high availability and says the way to
stop that is `min_machines_running = 0` — that advice is wrong; this `fly.toml`
set it to 0 at the time and flyctl still made two. Only `--ha=false` prevents it.
If you end up with two anyway:

```bash
fly scale count 1 --app mytetz --yes
fly machine list --app mytetz          # confirm exactly one shared-cpu-1x:512MB
```

A deployed image is roughly **115 MB** compressed (~220 MB on disk: ~8 MB Alpine,
~165 MB Temurin JRE, ~46 MB application). If a deploy suddenly pushes far more
than that, the runtime stage has probably stopped being a JRE stage.

`fly.toml` declares an HTTP health check on `/api/health` every 30s. A deploy that
never passes that check is rolled back by fly automatically.

`auto_stop_machines = "off"` keeps the machine up, so no visitor waits for a cold
start. The machine boots only on a deploy, on a crash, or when you restart it by
hand. It scaled to zero until 2026-08-18, and the comment in `fly.toml` records the
outage that ended it. Read that comment before you set this back to `"stop"`.

### Rollback

```bash
fly releases --app mytetz --image      # lists every release with its image ref
fly deploy --app mytetz --image registry.fly.io/mytetz@sha256:<digest-of-a-good-release>
```

`fly deploy -i/--image` skips the build entirely and redeploys that exact image, so
a rollback is as fast as a machine restart. Note this does **not** roll back
secrets or `fly.toml` — if the bad release changed either, revert those too.

### Logs and shell

```bash
fly logs --app mytetz
fly ssh console --app mytetz
fly machine list --app mytetz
```

---

## Log drain, alert rules, and uptime check

No drain, alert, or uptime check exists yet. This section names the alert rule for each operator
token, the catch-all for an ERROR line with no token, and the uptime check. `<owner: provider>`,
`<owner: retention>`, and `<owner: channel>` mark the three choices below that only the owner can
make.

**A logged magic-link token cannot be replayed from the drain.** `GET /api/auth/magic-link/{token}`
consumes the token — `AccountRepository.consumeToken` deletes it from Mongo — before the route
responds. A second `consume` call on the same token returns null;
`MagicLinkServiceTest`'s own test `consume a second time gives null` pins this. So the token that
the access log carries for this request is already spent by the time the drain, or anyone else,
reads that line. This is true whether the drain provider logs the line before or after the
response completes.

**One caution stays with the owner.** `LoggingMailSender` writes a full sign-in link, not only a
token, under `MAGIC_LINK_LOGGED`, whenever `MYTETZ_MAIL_MODE` is `log`. An operator confirms the
value of `MYTETZ_MAIL_MODE` on fly, and sets it to `resend`, before the drain is attached.

### The drain

`<owner: provider>`. Set the retention to 30 days or more. Set the drain to keep a multi-line
event, such as a stack trace, as one event — several rows below span more than one line.

### The alert channel

`<owner: channel>`. Connect it to the drain provider.

### The alert rules

Each row is one alert rule. The match string is the exact text the log line holds.

`<owner: confirm or change this urgency policy>` — a `High` token pages the channel at once, and a
`Medium` token can wait for a person to read it on the next work day. This is a proposal, not a
decision the owner has made. An `Info` token needs no alert; the table lists it only so every
token the codebase writes has one row somewhere in this document.

| Token | Match string | Level | Urgency | Threshold |
| --- | --- | --- | --- | --- |
| `SPEND_UNRECORDED` | `SPEND_UNRECORDED` | ERROR | High | any occurrence |
| `CORRUPT_SESSION` | `CORRUPT_SESSION` | ERROR | High | any occurrence |
| unrecognised stop reason | `unrecognised stop reason` | WARN | High | any occurrence |
| `MAIL_SEND_FAILED` | `MAIL_SEND_FAILED` | ERROR | High | any occurrence |
| `ACCOUNT_LINK_CONFLICT` | `ACCOUNT_LINK_CONFLICT` | WARN | High | any occurrence |
| `CONFIG_MISSING` | `CONFIG_MISSING` | ERROR | High | any occurrence |
| `WEBHOOK_SIGNATURE_MISMATCH` | `WEBHOOK_SIGNATURE_MISMATCH` | WARN | High | more than 20 in one hour |
| `BOOTSTRAP_FAILED` | `BOOTSTRAP_FAILED` | ERROR | Medium | any occurrence |
| `BILLING_DRIFT` | `BILLING_DRIFT` | WARN | Medium | any occurrence |
| `BILLING_UNKNOWN_EVENT` | `BILLING_UNKNOWN_EVENT` | WARN | Medium | any occurrence |
| `BILLING_UNKNOWN_USER` | `BILLING_UNKNOWN_USER` | WARN | Medium | any occurrence |
| `BILLING_STALE_EVENT` | `BILLING_STALE_EVENT` | WARN | Medium | any occurrence |
| `BILLING_NO_PERIOD_END` | `BILLING_NO_PERIOD_END` | WARN | Medium | any occurrence |
| `BILLING_UNPARSEABLE_EVENT` | `BILLING_UNPARSEABLE_EVENT` | WARN | High | any occurrence; a signed payment event was rejected, and no row changed |
| `BILLING_UNREADABLE_PERIOD_END` | `BILLING_UNREADABLE_PERIOD_END` | WARN | Medium | any occurrence; the event was applied, with a fall-back period end |
| `RECONCILE_SKIPPED` | `RECONCILE_SKIPPED` | WARN | Medium | any occurrence |
| `EVICTION_LOOP_FAILED` | `EVICTION_LOOP_FAILED` | ERROR | Medium | any occurrence; one run of the eviction job failed, and the next run tries again |
| `TRIAL_CAP_REACHED` | `TRIAL_CAP_REACHED` | INFO | Info | no alert; a rate signal only |
| `MIGRATION` and `PREWARM`, the INFO lines | `MIGRATION removed` or `PREWARM pre-warmed` | INFO | Info | no alert; read by hand, see "The B0 model migration" |
| `PREWARM`, the WARN lines | `PREWARM_SKIPPED`, `PREWARM stopped early` or `PREWARM failed to pre-warm` | WARN | Medium | any occurrence; the pre-warm runs on each boot, so a line can appear on each boot |

### The catch-all for an ERROR line with no token

Three ERROR lines carry no token today. A rule that alerts on level ERROR, and excludes the
tokens above, also catches these three:

| Match string | Source |
| --- | --- |
| `unhandled error` | `ErrorMapping.kt:269`, the `Throwable` catch-all for a plain HTTP response |
| `unhandled error mid-stream` | `ErrorMapping.kt:388`, the same catch-all for a response that is already streaming |
| `the quota check could not be evaluated` | `SessionRoutes.kt:785`; the caller still gets an answer, from the cache only |

`<owner: confirm the provider can filter on log level>`. A provider that cannot filter on level
must match each of the three strings above by hand instead.

### The uptime check

Check `GET https://mytetz.com/api/health` every minute. Use `GET`, and not `HEAD` — a `HEAD`
answer has no body to check. Alert after two consecutive failures, or on a body that does not
contain `"ready":true`.

`<owner: confirm mytetz.com is not challenged by Bot Fight Mode>`. If it is, point the check at
`https://mytetz.fly.dev/api/health` instead. Issue #68 plans to refuse a request on
`mytetz.fly.dev` to every `/api/*` path that does not come through Cloudflare, but its own
implementation steps keep `/api/health` open on that host, so the fallback stays reachable after
that change lands.

**A note for the `fly machine stop` acceptance check.** `fly.toml:50` sets
`auto_start_machines = true`. A probe through the fly proxy can therefore start the stopped
machine again before the uptime alert fires. Record this in this document if it happens.

### What the drain receives

- **Every request path.** The call log writes the path of each request, so the drain receives it
  too. This is how a magic-link token reaches the drain, in the way described above.
- **A user id, in a `BILLING_*` line.** For example, `BILLING_DRIFT` logs `user={}` with
  `subscription.userId` — `backend/billing/src/main/kotlin/com/mytetz/billing/Reconciliation.kt:126-127`.
- **An email address, in an `ACCOUNT_LINK_CONFLICT` line.** `AccountService.linkGoogle` throws
  `AccountLinkConflictException` with the message `the account for <email> is already linked to a
  different Google account` at
  `backend/account/src/main/kotlin/com/mytetz/account/AccountService.kt:73-75`. `AuthRoutes.kt:332`
  logs `e.message` for that exception, so the `ACCOUNT_LINK_CONFLICT` line carries the caller's own
  email address.

**The consequence.** The drain provider receives personal data: an email address, and a user id.
The provider is therefore a data processor for this product, and the privacy policy must name it
once the owner selects it. Issue #24 holds the privacy policy text.

---

## 5. Cloudflare

DNS for `mytetz.com` is on Cloudflare. **These steps are done in the Cloudflare
dashboard / API and are not automated by this repo.**

1. **DNS.** `A` and `AAAA` records for `@` (and `www`) pointing at the fly
   addresses from `fly ips list --app mytetz`, **proxied** (orange cloud).
2. **SSL/TLS mode: Full (strict).** fly terminates TLS with a real certificate, so
   Flexible or Full (non-strict) would be a downgrade. Full (strict) requires the
   fly certificate to exist first — see step 3.
3. **Certificates.** `fly certs create mytetz.com` and
   `fly certs create www.mytetz.com`, then add the `_acme-challenge` CNAME records
   flyctl prints. `fly certs show mytetz.com` reports issuance status.
4. **Cache rule: bypass cache for `/api/*`.** Not optional. Without it Cloudflare
   buffers and caches `text/event-stream` responses and the SSE reader silently
   stops streaming — the single most likely cause of "explanations never arrive".
5. **Rate limiting rule: one rule, on the explain path.** Named `session-explain`.
   Match: `http.request.uri.path contains "/api/sessions/" and ends_with(http.request.uri.path, "/explain")`.
   Rate: **10 requests per 10 seconds**, per IP per datacenter. Action: block for
   10 seconds. A blocked caller gets `429` with Cloudflare error `1015`.

   **The zone is on the Free plan, and that decides the shape of this rule.**
   Free allows one rule, a counting period of 10 s only, a mitigation timeout of
   10 s only, and the IP characteristic only. So a one-minute window, a ten-minute
   block, and a second rule for all of `/api/*` are all impossible here. Pro raises
   the limits to two rules, a one-minute period and a one-hour timeout. The owner
   chose to stay on Free on 2026-09-19. See
   [rate limiting rules](https://developers.cloudflare.com/waf/rate-limiting-rules/).

   **What this rule bounds.** It brakes bursts, and it survives a restart of the
   app, which the in-process limiter does not. It is not a daily cap: 10 per 10 s
   is 60 per minute. `EXPLAINS_PER_CALLER` (30 per 10 minutes, in
   `SessionRoutes.kt`) is the tighter bound for a caller that comes through
   `mytetz.com`. **It bounds nothing for a caller that goes straight to
   `mytetz.fly.dev`,** because that host never reaches Cloudflare. Issue #68
   tracks that hole.
6. **Bot Fight Mode: on.** Security > Bots. The API token in `.env` cannot read or
   set this — `GET /zones/{zone}/bot_management` answers `403`. It is a dashboard
   step unless the token gains `Zone → Bot Management → Edit`.
7. **Redirect Rule: `www` to apex.** Rules > Redirect Rules > "Redirect www to
   apex (301)". Condition: `http.host eq "www.mytetz.com"`. Action: a dynamic
   redirect to `concat("https://mytetz.com", http.request.uri.path)`, status
   `301`, "Preserve query string" on. The rule runs at the edge, so the `www`
   DNS record stays proxied and the fly certificate for `www` stays in place.

Verify end to end:

```bash
curl -s https://mytetz.com/api/health         # {"status":"ok","mongo":true,"ready":true}
curl -sI https://mytetz.com/api/health | grep -i cf-cache-status   # expect BYPASS/DYNAMIC
curl -sS -o /dev/null -w "%{http_code} %{redirect_url}\n" "https://www.mytetz.com/privacy?x=1"   # expect 301 https://mytetz.com/privacy?x=1

# The rate limiting rule. Fourteen POSTs on one connection stay inside the 10 s window.
# Expect the app's 401 for the first ten or so, then 429 with "error code: 1015" from Cloudflare.
# The boundary moves by one either way: the counter is distributed and approximate.
U="https://mytetz.com/api/sessions/x/explain"
curl -s -o /dev/null -w '%{http_code} ' -X POST -H "content-type: application/json" \
  --data '{"verb":"EXPLAIN"}' $U $U $U $U $U $U $U $U $U $U $U $U $U $U; echo
```

The session id `x` does not exist, so the app answers `401` before it generates
anything. The check costs no money.

If `https://mytetz.fly.dev/api/health` is healthy but `https://mytetz.com/api/health`
is not, the fault is in Cloudflare (DNS record, proxy status, SSL mode, or a rule),
not in the app.

---

## 6. Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Machine boots then exits immediately | `MONGODB_URI` missing. `fly secrets list --app mytetz` — the app throws on startup without it. |
| `/api/health` returns `{"status":"degraded","mongo":false,"ready":true}` with HTTP 503 | The app is up but cannot reach Atlas: bad credentials, cluster paused (M0 clusters auto-pause after ~60 days idle), or the IP access list no longer has `0.0.0.0/0`. |
| SSE responses stall or arrive all at once | Cloudflare is caching/buffering `/api/*`. Check the cache rule from step 5.4. |
| `fly deploy` cannot find a builder | Use `fly deploy --local-only` with Docker running locally. |
| Health check fails only right after deploy | `grace_period` is 10s; JVM + Netty start well inside that, but a cold Atlas handshake on a loaded shared CPU can be slower. Raise `grace_period` before suspecting the app. |
| `curl -I https://mytetz.fly.dev/api/health` returns **404** | Not expected. `AutoHeadResponse` is installed, so `HEAD /api/health` answers **200** and an uptime monitor may use HEAD or GET. A 404 means the request did not reach this application. |
| Cloudflare origin errors while `mytetz.fly.dev` is healthy | Almost certainly the shared-IPv4/SNI issue above: no fly certificate for that hostname yet. |

---

## The B0 model migration

**Warning: this release orphans the store.** `modelFamily` is part of every content key. The new
model changes that key. Every stored explanation becomes unreachable when this image boots.

Two separate steps now run at boot, and only one of them needs a flag.

- **The pre-warm step runs on every boot, with no flag.** It generates a fresh seed for every
  published topic that has no seed under the current model family. It stops early if the $50
  daily spend breaker trips. A learner must never pay for a live generation because a topic's
  seed is missing, so this step does not wait for an operator.
- **The delete step runs only when `MYTETZ_MIGRATE_ON_BOOT` is `true`.** It removes an
  explanation stranded under the old model family. It is cleanup, not correctness: a stranded
  document costs storage, and nothing else, until an operator deletes it.

A learner may still open a topic in the short window before the pre-warm step finishes on the
very first boot after the deploy. That learner gets an explanation regardless: the app generates
it fresh, for that topic, at the ordinary cost of one generation.

Do the following one time, after the deployment that carries the `claude-sonnet-5` default.

1. Confirm the Anthropic account holds credit. The pre-warm step makes about 29 model calls, one
   for each published topic with no seed under the new family.

2. Deploy. The pre-warm step needs no flag.

   ```
   fly deploy --local-only --ha=false --app mytetz
   ```

3. Wait for the health check to report `ready`.

   ```
   curl -s https://mytetz.com/api/health
   ```

   The answer must be `{"status":"ok","mongo":true,"ready":true}`.

4. Read what the pre-warm step did.

   **In bash.**

   ```bash
   fly logs --app mytetz --no-tail | grep PREWARM
   ```

   **In PowerShell.**

   ```powershell
   fly logs --app mytetz --no-tail | Select-String PREWARM
   ```

   You must see one line: `PREWARM pre-warmed <count> seed(s), <count> failed, at a cost of
   <count> micro-dollars`. The `--no-tail` flag is necessary, because the line is already in the
   past.

5. Confirm every published topic has a seed under the current family.

   ```
   mongosh "$MONGODB_URI" --quiet --eval '
     const family = "claude-sonnet-5";
     const version = "v3";
     const published = db.topics.countDocuments({ status: "PUBLISHED" });
     const seeded = db.explanations.countDocuments({ verb: "SEED", modelFamily: family, promptVersion: version });
     print("published=" + published + " seeded=" + seeded);
   '
   ```

   Set `family` to the value of `MYTETZ_MODEL_FAMILY`, or to its default in section 2.2. Set
   `version` to `PromptBuilder.VERSION` in `backend/graph/src/main/kotlin/com/mytetz/graph/PromptBuilder.kt`.
   A seed key holds the two values, so a seed from an older prompt version does not count.

   The two counts must match. A lower `seeded` count means the spend breaker stopped the loop
   early; see below.

6. Only if you also want to remove the stranded documents from the old family now, rather than
   later, turn the delete on and deploy again.

   ```
   fly secrets set MYTETZ_MIGRATE_ON_BOOT=true --app mytetz
   fly deploy --local-only --ha=false --app mytetz
   fly logs --app mytetz --no-tail | grep MIGRATION
   fly secrets unset MYTETZ_MIGRATE_ON_BOOT --app mytetz
   ```

   Turn the flag off immediately after you read the `MIGRATION removed <count> explanation(s)`
   line. Do not wait until later. The flag is not run-once: `fly.toml` sets
   `auto_stop_machines = "off"`, so the machine no longer stops when it is idle, and a deploy, a
   crash or a manual restart still boots it. Every boot between setting the flag and clearing it
   deletes the same stranded family again — a no-op after the first run, but still a needless
   database round trip.

A topic can fail after its model call. That topic still spent money. The pre-warm step does not
persist a failed generation. The next boot spends money on that topic again. The real ceiling on
that cost is the $50 daily spend breaker in section 2.1. One full pre-warm run, from an empty
store, costs about $0.30.

**If the log line reports fewer seeds than the catalogue holds,** the spend breaker stopped the
loop. The existing seeds remain. Do the following:

1. Check the day's ledger.
2. Raise `MYTETZ_GLOBAL_DAILY_COST_CEILING_USD_MICROS`, only if that is the correct action.
3. Restart the machine, or wait for the next boot, so the pre-warm step runs again tomorrow.

---

## Freemius checkout — the post-purchase return

`POST /api/billing/checkout` builds a plain checkout URL. The URL sets no return address. A
return address is a Freemius dashboard setting, not a request parameter. An operator must set it
by hand, once, on the product's own checkout page in the Freemius dashboard.

**Set the post-purchase redirect to `{MYTETZ_PUBLIC_BASE_URL}/account`.** One example is
`https://mytetz.com/account`. The dashboard path is `Plans` > `Customization` > `Redirect
Checkout to a custom URL`.

`AccountPageComponent` reads a fresh `GET /api/account` on every mount. This is why the redirect
matters: the account page shows the learner's new allowance only after this mount. A wrong
redirect still lets the learner sign in. But the learner then does not see the new allowance
until they open `/account` by hand.

**Freemius may still be sending its webhook when the browser lands back on `/account`.** The
return and the webhook are two separate requests, and no vendor source states which one arrives
first. The return URL carries an `action` query parameter for exactly this case. The account page
reads `action` only as a hint, and starts no poll without it. When the hint is present, the page
reads `GET /api/account` again every 2 seconds, until the status or the period end changes, or 30
seconds pass. A learner who still sees the old allowance after that wait can load `/account`
again in a minute.

---

## Turnstile

`MYTETZ_TURNSTILE_SECRET` turns on a check. The check sits in front of
`POST /api/auth/magic-link`. The check also sits in front of the start of the Google sign-in
flow, `GET /api/auth/google`. Cloudflare Turnstile is the first of three defences against a flood
of throwaway trial accounts. The IP-bucket trial cap is the second defence. The global spend
ceiling is the third.

**The check is skipped when this variable is unset.** Every sign-in still works. No request to
Cloudflare is ever made. Local work and CI need no key for this reason.

**The sign-in panel renders the widget itself, from `GET /api/auth/config`.** That route reports
`MYTETZ_TURNSTILE_SITE_KEY` to the browser as `turnstileSiteKey`. A null value there means the site
key is unset. The panel then renders no widget and loads no script. A learner who sees the widget
solves it once. The resulting token then travels two ways: as `turnstileToken` in the magic-link
body, and as a `turnstileToken` query parameter on the Google link. Both are exactly where the
backend check already reads it.

**Set both variables together, from the same Cloudflare Turnstile widget, or set neither.**
`MYTETZ_TURNSTILE_SECRET` is the secret key. It checks a token on the server, and must never reach
the browser. `MYTETZ_TURNSTILE_SITE_KEY` is the site key. It is public by design. The widget
cannot render without it. A secret with the site key unset makes every sign-in fail. The widget
never renders. No token is then ever produced. The server refuses every caller on that missing
token. The boot log carries a WARN line naming both variables when a deployment is in that state.
Do not mistake that line for a Cloudflare account fault.

```
fly secrets set MYTETZ_TURNSTILE_SECRET="<secret key>" MYTETZ_TURNSTILE_SITE_KEY="<site key>" --app mytetz
```

Get both values from the Cloudflare dashboard, under Turnstile. Create one widget for the
`mytetz.com` hostname, and copy the pair it gives you.

---

## Billing reconciliation

`MYTETZ_RECONCILE_ON_BOOT` runs a sweep at every boot. The sweep reads every subscription that is
not `EXPIRED`. It asks Freemius for the real state of each one, through the Freemius Developer
API. It corrects a row that has drifted, and it logs the correction under `BILLING_DRIFT`. This
sweep is the second defence behind the webhook, for a delivery that never arrives.
`Components.RECONCILE_LIMIT` (500) bounds one run. The bound stops a restart under load from
flooding the Freemius API.

**The request, and the response, are confirmed against the vendor's own SDK source, not guessed.**
Freemius publishes a backend SDK at `github.com/Freemius/freemius-js`. Its code fixes the request:
the base url (`https://fast-api.freemius.com`), the path
(`/v1/products/{productId}/subscriptions/{subscriptionId}.json`), and the
`Authorization: Bearer {apiKey}` header — `FREEMIUS_API_KEY`, not `FREEMIUS_SECRET_KEY`, which
only signs a webhook. `packages/sdk/src/api/schema.d.ts` there fixes the response fields this
project reads: `next_payment`, `canceled_at` and `failed_payments`. That schema carries no
explicit subscription status; `deriveState` in `backend/api/.../FreemiusApiClient.kt` derives one
from those three fields, and its own KDoc states the rule.

**A wrong guess in that derivation cannot downgrade a paying learner.** The mapping above is a
best effort, not a confirmed one — unlike the request shape, which the SDK fixes exactly. So
`Reconciliation.reconcile` trusts a fetched status only when it is `ACTIVE`: the correction a
missed `subscription.created` or renewal webhook would have made. Every other disagreement — a
downgrade to `CANCELLED`, `PAST_DUE` or `EXPIRED` — is only logged under `BILLING_DRIFT`, for an
operator to read and apply by hand; the stored row is never touched on that word alone. A period
end never shortens either, for the same reason. `Reconciliation.reconcile`'s own KDoc states this
rule as "the fail-safe rule."

**This flag is safe to leave set. `MYTETZ_MIGRATE_ON_BOOT` is not — though the reason changed
with the pre-warm split above.** `migrate()` only deletes a document stranded under an old model
family now. It no longer calls the model itself, and a second run finds nothing to delete. So
leaving the flag on spends no money any more, only a needless Mongo round trip on every boot.
Turn it off anyway, right after you read the delete's log line, the same as section "The B0
model migration" above says: a flag with one job stays clearest when it runs only for that job.
Reconciliation only reads Mongo and asks Freemius. It spends nothing, so there is nothing here to
turn off in a hurry.

**How often "every boot" happens today.** `fly.toml` currently sets `auto_stop_machines = "off"`.
The machine never scales to zero, because of the 2026-08-16 outage section 4 above records. A boot
now happens only on a deploy, a crash, or a manual restart — never on a visitor after an idle
period. If `auto_stop_machines` goes back to `"stop"`, a boot again means a cold start, and
reconciliation then runs on every cold start too. Either way, the safety argument above still
holds: reconciliation is safe because of what it does, and the boot frequency only changes how
often it runs.

**With no `FREEMIUS_API_KEY` or `FREEMIUS_PRODUCT_ID` set, reconciliation logs and skips rather
than failing the boot.** `Components.reconcile` builds `FreemiusApiConfig` once, before the sweep
starts, inside a `try`. A missing variable logs `RECONCILE_SKIPPED` and returns; it does not throw
inside `bootstrap()`. Every later boot retries on its own, so setting the credential later needs
no code change.

### Operator alert tokens

Most rows below are a `log.warn` or a `log.error` line. Two rows, `TRIAL_CAP_REACHED` and
`MIGRATION`, are INFO lines, and the pre-warm also writes one INFO line, `PREWARM pre-warmed`. Each line is greppable in `fly logs`. Each line is for an
operator, and no line ever reaches a learner.

| Token | Logged in | Meaning | Operator action |
| --- | --- | --- | --- |
| `BILLING_UNKNOWN_EVENT` | `BillingService.apply` | a webhook named a type this deployment does not map to a status | confirm the type against the Freemius dashboard; add it to `EVENT_TYPE_TO_STATUS` if it is real |
| `BILLING_UNKNOWN_USER` | `BillingService.apply` | a webhook carried no `userReference` and no `freemiusUserId`, or neither one named a stored row | a learner paid with an address they never signed in with; find the account by hand and correct it |
| `BILLING_STALE_EVENT` | `BillingService.apply` | an event arrived older than the row's last applied event, and was dropped | confirm the row's current state is still correct; the event id can never be replayed after this |
| `BILLING_NO_PERIOD_END` | `Entitlement.resolve` | an `ACTIVE` row carries no period end | check whether the first-payment webhook for that row ever carried one; the row is granted access regardless |
| `BILLING_DRIFT` | `Reconciliation.reconcile` | a subscription disagreed with what Freemius reports. `applied=true` means the row was corrected; `applied=false` means only a downgrade was proposed, and the row is untouched | read the log line's own fields — see "Reading a `BILLING_DRIFT` line" below, right after this table |
| `RECONCILE_SKIPPED` | `Components.reconcile` | `MYTETZ_RECONCILE_ON_BOOT` is on but `FREEMIUS_API_KEY` or `FREEMIUS_PRODUCT_ID` is not set | set the missing variable; nothing else needs to change, the next boot retries on its own |
| `PREWARM_SKIPPED` | `Components.prewarm` | the model client did not build at boot, usually because `ANTHROPIC_API_KEY` is not set. The catalogue still serves. No missing seed was generated on this boot | set the key. The next boot runs the pre-warm again |
| `PREWARM stopped early` and `PREWARM failed to pre-warm` | `Components.prewarm` | the spend breaker refused a topic, or the generation of one seed failed. A published topic then has no seed, and the first visitor pays for a live generation | read the slug in the line. For the breaker, read section "The B0 model migration". For a failure, read the stack trace under the line. The next boot tries the topic again, and each try that reaches the model costs money |
| `WEBHOOK_SIGNATURE_MISMATCH` | `BillingRoutes` | `POST /api/billing/webhook` received a body whose signature did not verify | expected from scanners and mis-configured retries; investigate only if it is frequent, or if `FREEMIUS_SECRET_KEY` was just rotated |
| `BILLING_UNPARSEABLE_EVENT` | `BillingRoutes` | a signed webhook body had no readable `id`, `type` or `created`, or was not a JSON object. The route answers `400`, and no row changes | the line gives the event type and the event id when the body holds them. Find the event in the Freemius dashboard under Webhooks > Events, and compare it with `FreemiusWebhook.parse`. No other part of the body reaches the log line |
| `BILLING_UNREADABLE_PERIOD_END` | `FreemiusWebhook.parse` | `data.to` or `objects.license.expiration` of an event was not in one of the two date forms that the parser reads. The event is still applied, with the period end from the next choice in that order, or with no period end | the line gives the event type and the event id. Read the real value in the Freemius dashboard, and add its date form to `parseFreemiusDate`. An `ACTIVE` row with no period end also logs `BILLING_NO_PERIOD_END` |
| `ACCOUNT_LINK_CONFLICT` | `AuthRoutes` | a Google sign-in's email is already linked to a different Google account | a real conflict, not a bug; the learner needs the sign-in method their account already used |
| `MAIL_SEND_FAILED` | `MailSender` | a magic-link email could not be sent | check the mail provider's status and `MYTETZ_MAIL_API_KEY`; a learner is currently unable to sign in by email |
| `CONFIG_MISSING` | `ConfigGate`, from `AuthRoutes` and `BillingRoutes` | a route needs an environment variable that is not set, so it answers `503` rather than `500`. One line names only the first missing variable in that route's own chain; a second variable, if one is also missing, only appears after the first is set | set the named variable. The next request retries on its own. No restart is needed |
| `EVICTION_LOOP_FAILED` | `Components.bootstrap` (the boot-time run), and the daily loop in `Application.kt` | one explanation-eviction run raised an exception and did not complete | read the log line's own exception. Nothing else fails because of this: the boot still completes, and the next run — the next day, or the next boot — retries on its own |
| `SPEND_UNRECORDED` | `SessionRoutes.recordSpend` | a generation was paid for, but the quota ledger did not record the cost | read the principal and the cost in the line; the daily spend ceiling in section 2.1 is understating the true spend by that amount |
| `CORRUPT_SESSION` | `ErrorMapping.logCorruptSession` | a stored session no longer describes a tree; no retry fixes it | read the session id in the line; a person must read the document by hand |
| `unrecognised stop reason` | `ExplanationValidator.validate`, through `ErrorMapping.installErrorMapping` and `ErrorMapping.sseErrorFor` | the model answered with a stop reason the validator does not know; the validator rejects every such answer, and keeps rejecting until a person acts | read the exact reason in the line under `generation failed`; add the new reason to the validator's allowlist if it is a real success case |
| `BOOTSTRAP_FAILED` | `Application.bootstrap` | the boot did not finish the database indexes or the catalogue seed. `ready.set(true)` at `Application.kt:224` never runs on this path, so `/api/health` reports `"ready":false` for the whole life of the machine, not only during the cold-start window | read the stack trace under the line; the uptime check on `"ready":true` also alerts, because the field never turns `true`; restart the machine, or wait for the next boot to retry on its own |
| `TRIAL_CAP_REACHED` | `BillingService.startTrialIfAbsent` | one IP bucket already started the day's cap of trials; the caller still signs in, with checkout offered instead | no action; this is a rate signal, not a fault |
| `MIGRATION` | `Components.migrate` | one INFO line, `MIGRATION removed`, reports how many stranded explanations the delete removed. It appears only while `MYTETZ_MIGRATE_ON_BOOT` is set. The pre-warm has its own lines, in the `PREWARM` rows above | read section "The B0 model migration" above; remove the flag after you read the line |

### Reading a `BILLING_DRIFT` line

A `BILLING_DRIFT` line names six fields: `user`, the stored `status` and the fetched one, the
stored `periodEnd` and the fetched one, `failedPayments`, and `applied`.

**For `applied=true`,** the row is already corrected. Repeated drift on one row is the real
signal to act on: it means that row's webhook keeps failing to arrive.

**For `applied=false`,** the row is untouched, and an operator decides by hand whether to apply
the downgrade. `failedPayments` is the field that decides how confident that decision can be:

- `failedPayments=0` on a fetched `ACTIVE` is a genuine renewal. No failed payment sits behind it.
- `failedPayments>0` does **not** prove the learner is stuck in dunning. Freemius documents no
  reset rule for this count. The count may be cumulative across the whole subscription, so one
  old failure from months ago can still show a positive number on an account that has since paid
  normally, every time, since then.

**The `periodEnd` before/after pair is the more reliable signal in practice.** A subscription
still inside a dunning retry window carries a retry date only days out. A subscription that has
genuinely renewed carries a period end a full billing period out. Read both fields together, not
`failedPayments` alone.
