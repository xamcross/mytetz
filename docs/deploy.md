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

`MYTETZ_CLIENT_IP_HEADER` decides which header the topic-request rate limiter
keys on, and it is a security setting rather than a tuning one. Because
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

Sensitive values are fly secrets. `fly secrets list` shows names and digests only.

| Secret | Required | Notes |
| --- | --- | --- |
| `MONGODB_URI` | yes — the app calls `error("MONGODB_URI is not set")` and refuses to boot without it | full `mongodb+srv://` string including the database user's password |
| `MYTETZ_COOKIE_SIGNING_KEY` | yes — the app refuses to boot without it, deliberately | signs the anonymous principal cookie. There is no safe default: a known key lets anyone mint any principal. 32 characters minimum |
| `ANTHROPIC_API_KEY` | for explanation generation only | the model client is built lazily, so the catalogue serves without it |

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
curl -s localhost:8080/api/health    # {"status":"ok","mongo":true}
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
already sets it and flyctl still made two. Only `--ha=false` prevents it. If you
end up with two anyway:

```bash
fly scale count 1 --app mytetz --yes
fly machine list --app mytetz          # confirm exactly one shared-cpu-1x:512MB
```

A deployed image is roughly **115 MB** compressed (~220 MB on disk: ~8 MB Alpine,
~165 MB Temurin JRE, ~46 MB application). If a deploy suddenly pushes far more
than that, the runtime stage has probably stopped being a JRE stage.

`fly.toml` declares an HTTP health check on `/api/health` every 30s. A deploy that
never passes that check is rolled back by fly automatically.

Because `min_machines_running = 0` and `auto_stop_machines = "stop"`, the machine
stops when idle and the fly proxy cold-starts it on the next request. The first
request after an idle period pays JVM startup plus the initial Atlas handshake.
Set `min_machines_running = 1` if that latency ever matters more than the cost.

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
curl -s https://mytetz.com/api/health         # {"status":"ok","mongo":true}
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
| `/api/health` returns `{"status":"degraded","mongo":false}` with HTTP 503 | The app is up but cannot reach Atlas: bad credentials, cluster paused (M0 clusters auto-pause after ~60 days idle), or the IP access list no longer has `0.0.0.0/0`. |
| SSE responses stall or arrive all at once | Cloudflare is caching/buffering `/api/*`. Check the cache rule from step 5.4. |
| `fly deploy` cannot find a builder | Use `fly deploy --local-only` with Docker running locally. |
| Health check fails only right after deploy | `grace_period` is 10s; JVM + Netty start well inside that, but a cold Atlas handshake on a loaded shared CPU can be slower. Raise `grace_period` before suspecting the app. |
| `curl -I https://mytetz.fly.dev/api/health` returns **405** | Expected, not a fault. The route is registered with `get(...)` only, so `HEAD` is unhandled. Use `curl -s` (GET). Anything you point at this endpoint — uptime monitors included — must use GET. |
| Cloudflare origin errors while `mytetz.fly.dev` is healthy | Almost certainly the shared-IPv4/SNI issue above: no fly certificate for that hostname yet. |
