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
| fly VM | 1 x `shared-cpu-1x`, 512 MB, scales to zero when idle |
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
| `FREEMIUS_API_KEY` | for reconciliation only | a Bearer token for the Freemius Developer API, distinct from `FREEMIUS_SECRET_KEY`. Built lazily; with `MYTETZ_RECONCILE_ON_BOOT` on and this unset, the boot logs `RECONCILE_SKIPPED` rather than failing |

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

**What the ceiling does not cover.** A request that ends before the model's own
stream completes records no cost, because no token count exists for it. Two
things do that: a learner who navigates away mid-answer, and a provider stream
that ends without a stop reason, which includes the 120-second client timeout.
`EXPLAINS_PER_CALLER` in `SessionRoutes.kt` — 30 explanations per address per
ten minutes — is what bounds that path. Its counters live in the process, so
they reset whenever the machine cold-starts.

### 2.2 Every variable the backend reads

20 variables, and each one is listed here and in `.env.example`. Everything
except the three secrets above has a default in code, and the defaults are the
values shown. **An unset, unparseable or non-positive value falls back to its
default rather than stopping the server**, because these are read while the
process is starting and a typo must not take the site down.
`MYTETZ_COOKIE_SIGNING_KEY` is the one deliberate exception: it fails closed.

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
| `MYTETZ_MIGRATE_ON_BOOT` | off | whether the B0 migration runs at boot. Only the exact word `true` turns it on. Section "The B0 model migration" explains it. |

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
5. **Rate limiting rule:** `/api/*`, 60 requests per minute per IP.
6. **Bot Fight Mode: on.**

Verify end to end:

```bash
curl -s https://mytetz.com/api/health         # {"status":"ok","mongo":true,"ready":true}
curl -sI https://mytetz.com/api/health | grep -i cf-cache-status   # expect BYPASS/DYNAMIC
```

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
model changes that key. Every stored explanation becomes unreachable when this image boots. This
is true whether or not `MYTETZ_MIGRATE_ON_BOOT` is set.

The migration below removes the orphaned documents. The migration then regenerates a seed for
each published topic. A learner may open a topic before the migration runs. That learner still
gets an explanation. The app generates it fresh, for that topic, at the ordinary cost of one
generation.

Do this one time, after the deployment that carries the `claude-sonnet-5` default.

1. Confirm the Anthropic account holds credit. Step 2 of the migration makes about 29 model calls.

2. Turn the migration on and deploy.

   ```
   fly secrets set MYTETZ_MIGRATE_ON_BOOT=true --app mytetz
   fly deploy --local-only --ha=false --app mytetz
   ```

3. Wait for the health check to report `ready`.

   ```
   curl -s https://mytetz.com/api/health
   ```

   The answer must be `{"status":"ok","mongo":true,"ready":true}`.

4. Read what the migration did.

   **In bash.**

   ```bash
   fly logs --app mytetz --no-tail | grep MIGRATION
   ```

   **In PowerShell.**

   ```powershell
   fly logs --app mytetz --no-tail | Select-String MIGRATION
   ```

   You must see two lines: one count of removed explanations and one count of pre-warmed seeds.
   The `--no-tail` flag is necessary, because the lines are already in the past.

5. Turn the migration off immediately after step 4. Do not wait until later.

   ```
   fly secrets unset MYTETZ_MIGRATE_ON_BOOT --app mytetz
   ```

   The flag is not run-once. `fly.toml` sets `auto_stop_machines = "off"`, so the machine no
   longer stops when it is idle. A deploy, a crash or a manual restart still boots it. Every
   boot between step 2 and this step re-runs the whole migration.

   A topic can fail after its model call. That topic still spent money. The migration does not
   persist a failed generation. The next cold start spends money on that topic again. The real
   ceiling on that cost is the $50 daily spend breaker in section 2.1. One full run costs about
   $0.30.

**If the second log line reports fewer seeds than the catalogue holds,** the spend breaker stopped
the loop. The existing seeds remain. Do the following:

1. Check the day's ledger.
2. Raise `MYTETZ_GLOBAL_DAILY_COST_CEILING_USD_MICROS`, only if that is the correct action.
3. Run the migration again tomorrow.

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

**This flag is safe to leave set. `MYTETZ_MIGRATE_ON_BOOT` is not.** The difference is in what
each flag does, not in how often either one runs. The migration deletes documents and calls a
metered model API. Every migration run costs real money, so section "The B0 model migration"
above tells you to turn that flag off again right after the run. Reconciliation only reads Mongo
and asks Freemius. It spends nothing, so there is nothing here to turn off in a hurry.

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

Each row below is a `log.warn` or a `log.error` line. Each line is greppable in `fly logs`. Each
line is for an operator, and no line ever reaches a learner.

| Token | Logged in | Meaning | Operator action |
| --- | --- | --- | --- |
| `BILLING_UNKNOWN_EVENT` | `BillingService.apply` | a webhook named a type this deployment does not map to a status | confirm the type against the Freemius dashboard; add it to `EVENT_TYPE_TO_STATUS` if it is real |
| `BILLING_UNKNOWN_USER` | `BillingService.apply` | a webhook's `userReference` was empty, or resolved to no stored row | a learner paid with an address they never signed in with; find the account by hand and correct it |
| `BILLING_STALE_EVENT` | `BillingService.apply` | an event arrived older than the row's last applied event, and was dropped | confirm the row's current state is still correct; the event id can never be replayed after this |
| `BILLING_NO_PERIOD_END` | `Entitlement.resolve` | an `ACTIVE` row carries no period end | check whether the first-payment webhook for that row ever carried one; the row is granted access regardless |
| `BILLING_DRIFT` | `Reconciliation.reconcile` | a subscription disagreed with what Freemius reports. `applied=true` means the row was corrected; `applied=false` means only a downgrade was proposed, and the row is untouched | for `applied=true`, read the before/after values — repeated drift on one row means its webhook is not arriving. For `applied=false`, decide by hand whether to apply the downgrade |
| `RECONCILE_SKIPPED` | `Components.reconcile` | `MYTETZ_RECONCILE_ON_BOOT` is on but `FREEMIUS_API_KEY` or `FREEMIUS_PRODUCT_ID` is not set | set the missing variable; nothing else needs to change, the next boot retries on its own |
| `WEBHOOK_SIGNATURE_MISMATCH` | `BillingRoutes` | `POST /api/billing/webhook` received a body whose signature did not verify | expected from scanners and mis-configured retries; investigate only if it is frequent, or if `FREEMIUS_SECRET_KEY` was just rotated |
| `ACCOUNT_LINK_CONFLICT` | `AuthRoutes` | a Google sign-in's email is already linked to a different Google account | a real conflict, not a bug; the learner needs the sign-in method their account already used |
| `MAIL_SEND_FAILED` | `MailSender` | a magic-link email could not be sent | check the mail provider's status and `MYTETZ_MAIL_API_KEY`; a learner is currently unable to sign in by email |
