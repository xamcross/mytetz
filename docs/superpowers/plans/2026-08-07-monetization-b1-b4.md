# Monetization Slices B1 to B4 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take money. Add accounts, a 7-day trial, a Freemius subscription, and a gate in front of every model call.

**Architecture:** Two new Gradle modules join the eight that exist. `account` owns identity: users, magic-link tokens, the Google code exchange, and revocable server-side sessions. `billing` owns money: the subscription mirror Freemius writes through a webhook, and one pure function that resolves a user id to an allowance. `quota` already accepts an `Allowance` from slice B0 and gains one reset. `api` gains the auth routes, the billing routes, and two new checks in front of the explain pipeline. The Angular app gains a sign-in panel, an account store, and an interceptor that turns 401, 402 and 403 into the right panel.

**Tech Stack:** Kotlin 2.1, Ktor, MongoDB Kotlin coroutine driver, kotlinx.serialization, JUnit 5 with `kotlin.test`, Testcontainers (`mongo:7`), Angular standalone components with signals, Vitest, Playwright, Gradle multi-module.

## Global Constraints

- Read the specification first: `docs/superpowers/specs/2026-08-07-monetization-design.md`. Sections 5 to 12 cover these slices.
- **No credential exists yet.** Every secret is read from the environment and every one of them has no default. The build must be complete and fully tested without a Freemius account, a Google OAuth client, or a mail provider. Nothing goes live until an operator sets the secrets.
- **Write no ASD-STE100 prose into this plan's code blocks.** This plan specifies behaviour, signatures and test names. You write the KDoc and the comments yourself, in ASD-STE100, against the test below. Slice B0 lost seven fix rounds to prose copied out of a plan.
- **The language test, applied to everything you author:** the subject performs the verb, and a sentence holds one statement. A sentence must not carry two subjects that each have their own verb, whether joined by a comma plus "and" or "so", or by a semicolon. A compound predicate on one subject is fine. A log line and a test name are exempt.
- **Language findings do not block a task.** A reviewer records them and one sweep clears them at the end. A correctness, security or money-handling finding blocks.
- **No secret may reach a log, an error message, or the browser.** Not a token, not a signature, not an API key, not a session id.
- Every TTL field is written through `EpochMillisAsBsonDateTime` from the `quota` module, or through an identical serializer in the new module. A `Long` makes a TTL index do nothing and the collection grows for ever.
- **Do not add a unique index to `principals` or `costLedger`.** `QuotaRepository.ensureIndexes` explains why.
- Backend tests: `./gradlew build` from the repository root. The baseline is **352 tests**. Frontend: `npm test -- --watch=false` in `frontend/`, baseline **155**. End to end: `npx playwright test`, baseline **21**.
- Testcontainers needs Docker. The first container start of a session sometimes fails on this machine. Retry one time before you report a failure.
- Work on the branch `spec-b-monetization-b1-b4`, branched from `main`.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `backend/account/.../Account.kt` | `User`, `MagicLinkToken`, `AuthSession` documents and their serializers | 1 |
| `backend/account/.../AccountRepository.kt` | All three collections, their indexes, and the atomic token consume | 1 |
| `backend/account/.../MailSender.kt` | The port, plus a logging adapter and a Resend adapter | 2 |
| `backend/account/.../MagicLinkService.kt` | Mint, hash, send, consume | 3 |
| `backend/account/.../GoogleOAuth.kt` | Authorization URL, PKCE, code exchange, ID token claims | 4 |
| `backend/account/.../AccountService.kt` | Find-or-create a user, link by subject, open and close a session | 5 |
| `backend/api/.../Principal.kt` | Extend to resolve a signed session id to `user:<id>` | 5 |
| `backend/session/.../SessionService.kt` | `reassignPrincipal` | 5 |
| `backend/api/.../AuthRoutes.kt` | Every `/api/auth/*` route | 6 |
| `backend/api/.../SessionRoutes.kt` | The gate, in front of the existing pipeline | 6, 9, 12 |
| `frontend/src/app/auth/*` | Sign-in panel, sent state, callback landings | 7 |
| `frontend/src/app/core/account.store.ts` | The account signal and the HTTP interceptor | 7 |
| `backend/billing/.../Subscription.kt` | The document, the states, and `BillingEvent` | 8 |
| `backend/billing/.../BillingRepository.kt` | Both collections, indexes, idempotent event insert | 8 |
| `backend/billing/.../Entitlement.kt` | The pure resolver | 8 |
| `backend/billing/.../BillingService.kt` | Start a trial, apply an event, resolve an allowance | 9, 11 |
| `backend/quota/.../QuotaRepository.kt` | `resetCounter` | 9 |
| `backend/billing/.../FreemiusWebhook.kt` | Signature verification and payload parsing | 11 |
| `backend/api/.../BillingRoutes.kt` | Checkout and the webhook route | 12 |
| `backend/billing/.../Reconciliation.kt` | The nightly drift job | 12 |
| `frontend/src/app/account/*` | Allowance meter, wall panels, checkout link | 10, 13 |

---

# Slice B1 — Accounts

## Task 1: The `account` module and its three collections

**Files:**
- Create: `backend/account/build.gradle.kts`
- Create: `backend/account/src/main/kotlin/com/mytetz/account/Account.kt`
- Create: `backend/account/src/main/kotlin/com/mytetz/account/AccountRepository.kt`
- Create: `backend/account/src/test/kotlin/com/mytetz/account/AccountRepositoryTest.kt`
- Create: `backend/account/src/test/kotlin/com/mytetz/account/MongoTestSupport.kt`
- Modify: `settings.gradle.kts`

**Interfaces produced:**

```kotlin
@Serializable data class User(
    @SerialName("_id") val id: String,          // ObjectId hex
    val email: String,                           // normalised
    val googleSub: String? = null,
    val createdAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
)

@Serializable data class MagicLinkToken(
    @SerialName("_id") val tokenHash: String,    // sha256 hex of the raw token
    val email: String,
    @SerialName("expiresAt") @Serializable(with = EpochMillisAsBsonDateTime::class)
    val expiresAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

@Serializable data class AuthSession(
    @SerialName("_id") val sessionId: String,    // 16 random bytes, base64url, no padding
    val userId: String,
    val createdAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
    @SerialName("expiresAt") @Serializable(with = EpochMillisAsBsonDateTime::class)
    val expiresAtEpochMillis: Long,
)

class AccountRepository(database: MongoDatabase) {
    suspend fun ensureIndexes()
    suspend fun findUserByEmail(email: String): User?
    suspend fun findUserByGoogleSub(sub: String): User?
    suspend fun findUserById(id: String): User?
    suspend fun insertUser(user: User): User          // duplicate email -> read the winner and return it
    suspend fun setGoogleSub(userId: String, sub: String)
    suspend fun touchUser(userId: String, nowEpochMillis: Long)
    suspend fun insertToken(token: MagicLinkToken)
    suspend fun consumeToken(tokenHash: String, nowEpochMillis: Long): MagicLinkToken?
    suspend fun insertSession(session: AuthSession)
    suspend fun findSession(sessionId: String): AuthSession?
    suspend fun touchSession(sessionId: String, nowEpochMillis: Long, expiresAtEpochMillis: Long)
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteSessionsForUser(userId: String): Long
    suspend fun deleteUser(userId: String)
}
```

Indexes: `users` `{email:1}` unique and `{googleSub:1}` unique sparse; `magicLinkTokens` TTL on `{expiresAt:1}` with `expireAfter(0)`; `authSessions` TTL on `{expiresAt:1}` with `expireAfter(0)` and `{userId:1}`.

Copy `EpochMillisAsBsonDateTime` into this module rather than depending on `quota`. `account` must not depend on `quota`.

**Behaviour that the tests must pin:**

- `consumeToken` uses `findOneAndDelete` with a filter of `_id` equality **and** `expiresAt` greater than now. It returns the document it removed, or null. Two concurrent consumes of one token: exactly one gets the document.
- `insertUser` on a duplicate email catches the duplicate-key error, reads the stored user, and returns it. The pattern is `ExplanationRepository.insertIfAbsent`.
- Every TTL field stores a BSON Date. Assert it by reading the raw `Document` and checking the field is a `java.util.Date`, exactly as `QuotaServiceTest` does.

- [ ] **Step 1: Register the module**

`settings.gradle.kts`: add `":backend:account"` after `":backend:quota"`.

`backend/account/build.gradle.kts`: copy `backend/quota/build.gradle.kts` and change nothing except what the module name requires. Its dependency is `:backend:persistence` only.

- [ ] **Step 2: Write `MongoTestSupport.kt`**

Copy `backend/quota/src/test/kotlin/com/mytetz/quota/` test container setup, or `backend/graph/src/test/kotlin/com/mytetz/graph/MongoTestSupport.kt` if that file is the cleaner one. Do not invent a third pattern.

- [ ] **Step 3: Write the failing tests**

`AccountRepositoryTest.kt`. Required test names, one per behaviour:

```
`a user round-trips by email`
`inserting a duplicate email returns the stored user`
`a user is findable by google sub`
`an unknown email gives null`
`a token round-trips and consume removes it`
`consuming a token twice gives null the second time`
`consuming an expired token gives null and leaves nothing behind`
`two concurrent consumes of one token give exactly one document`
`the token expiry is stored as a BSON Date`
`a session round-trips and delete removes it`
`the session expiry is stored as a BSON Date`
`deleting every session for a user leaves another user's sessions`
`ensureIndexes creates the unique email index`
```

The concurrency test uses the shape in `QuotaServiceTest`'s `CONCURRENCY` block: 16 coroutines on `Dispatchers.IO`, `awaitAll`, then assert exactly one non-null result.

- [ ] **Step 4: Run them and watch them fail**

```
./gradlew :backend:account:test
```

- [ ] **Step 5: Implement `Account.kt` and `AccountRepository.kt` until they pass**

- [ ] **Step 6: Run the whole build**

```
./gradlew build
```

Expected: 352 baseline plus the new account tests, 0 failures.

- [ ] **Step 7: Commit**

Subject: `feat(account): add the account module and its three collections`

---

## Task 2: The mail port and its two adapters

**Files:**
- Create: `backend/account/src/main/kotlin/com/mytetz/account/MailSender.kt`
- Create: `backend/account/src/test/kotlin/com/mytetz/account/MailSenderTest.kt`

**Interfaces produced:**

```kotlin
interface MailSender {
    suspend fun sendMagicLink(email: String, link: String)
}

class LoggingMailSender : MailSender                     // writes the link at INFO, for local work
class ResendMailSender(apiKey: String, from: String, httpClient: HttpClient) : MailSender

class MailConfig(
    val mode: String,        // "resend" or "log", from MYTETZ_MAIL_MODE, no default
    val apiKey: String?,     // MYTETZ_MAIL_API_KEY
    val from: String?,       // MYTETZ_MAIL_FROM
) {
    companion object {
        const val MODE_ENV = "MYTETZ_MAIL_MODE"
        const val API_KEY_ENV = "MYTETZ_MAIL_API_KEY"
        const val FROM_ENV = "MYTETZ_MAIL_FROM"
        internal fun resolveMode(raw: String?): String   // throws when unset or unrecognised
    }
}

class MailSendFailedException(message: String, cause: Throwable? = null) : Exception(message)
```

**Behaviour:**

- `resolveMode` accepts `resend` and `log` only, after trimming and lower-casing. Anything else throws, including null and empty. There is no default: a defaulted mail mode silently breaks sign-in or silently prints a link.
- `ResendMailSender` posts to `https://api.resend.com/emails` with `Authorization: Bearer <key>`. A non-2xx answer raises `MailSendFailedException`. **The exception message must not contain the API key or the response body**, because a body can echo a header.
- `LoggingMailSender` writes the link at INFO under the token `MAGIC_LINK_LOGGED`.
- Both adapters log `MAIL_SEND_FAILED` on failure.

**Tests:** drive `ResendMailSender` against a local `HttpServer` on 127.0.0.1, the same way `AnthropicLlmClientTest` drives the Anthropic SDK. Cover a 200, a 401, and a 500. Assert the raised message holds neither the key nor the body.

Required test names:

```
`an unset mail mode is refused`
`an unrecognised mail mode is refused`
`resend and log are accepted in any case`
`a 200 from the provider sends without raising`
`a 401 from the provider raises and the message holds no key`
`a 500 from the provider raises`
`the logging sender writes the link`
```

- [ ] Steps: write the tests, watch them fail, implement, run `./gradlew :backend:account:test`, run `./gradlew build`, commit.

Subject: `feat(account): add the mail port with a logging and a Resend adapter`

---

## Task 3: The magic link

**Files:**
- Create: `backend/account/src/main/kotlin/com/mytetz/account/MagicLinkService.kt`
- Create: `backend/account/src/test/kotlin/com/mytetz/account/MagicLinkServiceTest.kt`

**Interfaces produced:**

```kotlin
class MagicLinkService(
    private val repository: AccountRepository,
    private val mail: MailSender,
    private val baseUrl: String,                 // MYTETZ_PUBLIC_BASE_URL, no default
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokens: () -> String = { newToken() },
) {
    suspend fun request(rawEmail: String)                       // always returns; never says whether the user exists
    suspend fun consume(rawToken: String): String?              // returns the normalised email, or null

    companion object {
        const val TTL_MILLIS: Long = 15 * 60 * 1000
        internal fun normaliseEmail(raw: String): String?       // null when it is not a plausible address
        internal fun hash(rawToken: String): String             // sha256 hex
        internal fun newToken(): String                         // 32 random bytes, base64url, no padding
    }
}
```

**Behaviour that the tests must pin:**

- `normaliseEmail` trims and lower-cases. It **must not** remove a dot or a plus-tag. It returns null for a value with no `@`, with more than one `@`, with no dot after the `@`, longer than 254 characters, or holding whitespace.
- `request` stores `hash(token)` and never the token. Assert by reading the collection and checking no stored `_id` equals the raw token.
- The link is `"$baseUrl/api/auth/magic-link/$rawToken"`.
- `consume` hashes, calls `AccountRepository.consumeToken`, and returns the email. A second call with the same token returns null.
- `newToken` draws from `SecureRandom`. Two calls never match.

Required test names:

```
`an address is trimmed and lower-cased`
`a dot and a plus tag survive normalisation`
`a malformed address is refused`
`request stores the hash and never the token`
`request mails a link that carries the raw token`
`consume returns the email and removes the token`
`consume a second time gives null`
`consume an expired token gives null`
`consume an unknown token gives null`
`two tokens never match`
```

- [ ] Steps: tests, fail, implement, module tests, `./gradlew build`, commit.

Subject: `feat(account): add the magic link with hashed single-use tokens`

---

## Task 4: Google sign-in

**Files:**
- Create: `backend/account/src/main/kotlin/com/mytetz/account/GoogleOAuth.kt`
- Create: `backend/account/src/test/kotlin/com/mytetz/account/GoogleOAuthTest.kt`

**Interfaces produced:**

```kotlin
data class GoogleConfig(
    val clientId: String,       // GOOGLE_CLIENT_ID, no default
    val clientSecret: String,   // GOOGLE_CLIENT_SECRET, no default
    val redirectUri: String,    // "$baseUrl/api/auth/google/callback"
)

data class GoogleIdentity(val sub: String, val email: String, val emailVerified: Boolean)

class GoogleOAuth(private val config: GoogleConfig, private val httpClient: HttpClient) {
    fun authorizationUrl(state: String, codeChallenge: String): String
    suspend fun exchange(code: String, codeVerifier: String): GoogleIdentity

    companion object {
        internal fun newVerifier(): String                 // 32 random bytes, base64url, no padding
        internal fun challengeOf(verifier: String): String // base64url(sha256(verifier)), no padding
        internal fun parseIdToken(jwt: String): GoogleIdentity
    }
}

class GoogleAuthException(message: String) : Exception(message)
```

**Behaviour that the tests must pin:**

- `authorizationUrl` targets `https://accounts.google.com/o/oauth2/v2/auth` and carries `response_type=code`, `scope=openid email`, `code_challenge_method=S256`, the challenge, the state, the client id and the redirect uri.
- `exchange` posts to `https://oauth2.googleapis.com/token` with the verifier. It reads `id_token` from the answer.
- `parseIdToken` splits on `.`, base64url-decodes the payload, and reads `sub`, `email` and `email_verified`. It raises `GoogleAuthException` when a claim is absent or the shape is wrong. **It does not verify the JWT signature**, and its KDoc must say why that is sound here: the token arrived over TLS directly from Google's token endpoint in response to our own authenticated request, so it is not attacker-supplied. Write that reasoning yourself.
- **`exchange` raises when `email_verified` is false.** This is the check that stops a Google account holding an unverified address from claiming an address that belongs to a magic-link user.

**Tests:** drive against a local `HttpServer`, as in Task 2.

Required test names:

```
`the authorization url carries the challenge the state and S256`
`a verifier and its challenge match the S256 rule`
`two verifiers never match`
`an id token yields the subject the email and the verified flag`
`an id token with no email claim is refused`
`an id token with a malformed payload is refused`
`an exchange with email_verified false is refused`
`an exchange with email_verified true yields the identity`
`a non-2xx from the token endpoint is refused`
```

- [ ] Steps: tests, fail, implement, module tests, `./gradlew build`, commit.

Subject: `feat(account): add Google sign-in with PKCE and a verified-email check`

---

## Task 5: Sessions, the cookie, and the trail that survives sign-in

**Files:**
- Create: `backend/account/src/main/kotlin/com/mytetz/account/AccountService.kt`
- Create: `backend/account/src/test/kotlin/com/mytetz/account/AccountServiceTest.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Principal.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/PrincipalTest.kt`
- Modify: `backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt`
- Modify: `backend/session/src/test/kotlin/com/mytetz/session/SessionServiceTest.kt`
- Modify: `backend/session/src/main/kotlin/com/mytetz/session/SessionRepository.kt`

**Interfaces produced:**

```kotlin
class AccountService(
    private val repository: AccountRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { ObjectId().toHexString() },
    private val sessionIds: () -> String = { newSessionId() },
) {
    suspend fun findOrCreateByEmail(email: String): User
    suspend fun linkGoogle(identity: GoogleIdentity): User      // raises AccountLinkConflictException
    suspend fun openSession(userId: String): String             // returns the session id
    suspend fun resolveSession(sessionId: String): User?        // slides the expiry at most hourly
    suspend fun closeSession(sessionId: String)
    suspend fun closeAllSessions(userId: String): Long

    companion object {
        const val SESSION_TTL_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
        const val SLIDE_INTERVAL_MILLIS: Long = 60 * 60 * 1000
        internal fun newSessionId(): String                     // 16 random bytes, base64url, no padding
    }
}

class AccountLinkConflictException(message: String) : Exception(message)
```

`Principals` in `api` gains:

```kotlin
const val SESSION_COOKIE_NAME = "mytetz_sid"
private const val SESSION_DOMAIN_SEPARATOR = "sid"

fun Principals.readSessionId(call: ApplicationCall, config: PrincipalCookieConfig): String?
fun Principals.setSessionCookie(call: ApplicationCall, config: PrincipalCookieConfig, sessionId: String)
fun Principals.clearSessionCookie(call: ApplicationCall, config: PrincipalCookieConfig)
```

`SessionService` gains:

```kotlin
suspend fun reassignPrincipal(fromPrincipalId: String, toPrincipalId: String): Long
```

`SessionRepository` gains the update that backs it.

**Behaviour that the tests must pin:**

- The session cookie signs `"sid:" + sessionId` with the existing HMAC and the existing key. The domain separator means a signed `anon:<uuid>` can never be replayed as a session id, and the reverse is also impossible. Assert both directions.
- The cookie is `HttpOnly`, `SameSite=Lax`, `Secure` per `PrincipalCookieConfig.secure`, path `/`, `MAX_AGE_SECONDS`.
- `resolveSession` returns null for an unknown id, an expired row, and a row whose user is gone.
- `resolveSession` rewrites the expiry at most once an hour. Assert that a second call inside the hour issues no write.
- `linkGoogle` matches on `googleSub` first, then on the normalised email. On an email match with no stored subject it stores the subject. **When the stored subject differs from the incoming subject it raises `AccountLinkConflictException`.**
- `reassignPrincipal` re-keys every session document from the anonymous principal to the user principal and returns the count.

Required test names:

```
`a session id round-trips through the signed cookie`
`a signed anonymous principal is refused as a session id`
`a signed session id is refused as an anonymous principal`
`an unsigned session cookie is refused`
`a session cookie signed under another key is refused`
`the session cookie is http-only and same-site lax`
`an unknown session resolves to null`
`an expired session resolves to null`
`resolving slides the expiry at most once an hour`
`closing a session removes it`
`closing every session for a user leaves another user's`
`a first sign-in creates the user`
`a second sign-in finds the same user`
`google links by subject when the subject is stored`
`google links by email and stores the subject the first time`
`a subject that collides with another account is refused`
`reassignPrincipal moves every session of one principal`
`reassignPrincipal leaves another principal's sessions`
```

- [ ] Steps: tests, fail, implement, `./gradlew build`, commit.

Subject: `feat(account): add revocable sessions and carry the trail through sign-in`

---

## Task 6: The auth routes and the sign-in gate

**Files:**
- Create: `backend/api/src/main/kotlin/com/mytetz/api/AuthRoutes.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/AuthRoutesTest.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Components.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Application.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/SessionRoutesTest.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/ComponentsTest.kt`
- Modify: `.env.example`

**Routes:**

| Method | Path | Answer |
|---|---|---|
| `POST` | `/api/auth/magic-link` | Always `204`, for a known address and an unknown one |
| `GET` | `/api/auth/magic-link/{token}` | `302` to `/` on success, `302` to `/?auth=expired` on failure |
| `GET` | `/api/auth/google` | `302` to Google, with the state and the verifier in short-lived signed cookies |
| `GET` | `/api/auth/google/callback` | `302` to `/` on success, `302` to `/?auth=failed` on failure |
| `POST` | `/api/auth/sign-out` | `204`, clears the cookie |
| `POST` | `/api/auth/sign-out-all` | `204`, deletes every session for the user |
| `GET` | `/api/account` | `200` with the account view, or `401 SIGN_IN_REQUIRED` |

**The account view:**

```kotlin
@Serializable data class AccountView(
    val email: String,
    val status: String,               // "TRIALING" until slice B2 fills it
    val trialEndsAtEpochMillis: Long? = null,
    val currentPeriodEndsAtEpochMillis: Long? = null,
    val allowance: Int,
    val remaining: Int,
    val resetsAtEpochMillis: Long?,
)
```

**The gate.** In `SessionRoutes`, in front of the existing pipeline on `POST /api/sessions/{id}/explain` and on `POST /api/sessions/{id}/quizzes` when that route exists:

| # | Check | Failure |
|---|---|---|
| 1 | The session cookie resolves to a user | `401 SIGN_IN_REQUIRED` |
| 2 | *(slice B2 inserts entitlement here)* | |
| 3 | Span validation, unchanged | `400 SPAN_MISMATCH` |
| 4 | Quota, unchanged for now | `429 QUOTA_EXCEEDED` |
| 5 | Breaker, unchanged | `503 SPEND_LIMIT` |

**On a successful sign-in, before the redirect:** read the anonymous principal from the request, open the session, and call `SessionService.reassignPrincipal(anonPrincipal, userPrincipal)`. A visitor who read a seed anonymously and then signed in keeps their trail.

**Rate limits.** `POST /api/auth/magic-link` is limited by address and by IP bucket through the existing `FixedWindowRateLimiter`. Ten requests for each IP bucket in ten minutes, and three for each address in ten minutes.

**Behaviour that the tests must pin:**

- `POST /api/auth/magic-link` answers `204` for a known address and an unknown one, and the two answers are byte-identical.
- An anonymous caller on explain gets `401 SIGN_IN_REQUIRED` and **never** `400 SPAN_MISMATCH`, even when the span is wrong. This is a leak test.
- A signed-in caller reaches the existing pipeline unchanged.
- The Google callback refuses a state that does not match the cookie.
- Sign-out clears the cookie and the next explain answers `401`.
- The catalogue, topic detail, `GET /api/sessions/{id}` and `POST /api/sessions` stay open to an anonymous caller.

Required test names:

```
`a magic link request answers 204 for an unknown address`
`a magic link request answers 204 for a known address`
`the two magic link answers are identical`
`consuming a link opens a session and redirects`
`consuming a link twice redirects to the expired landing`
`the google callback refuses a mismatched state`
`the google callback opens a session on success`
`signing in carries an anonymous trail to the user`
`an anonymous explain answers SIGN_IN_REQUIRED`
`an anonymous explain with a bad span still answers SIGN_IN_REQUIRED`
`a signed-in explain reaches the pipeline`
`signing out clears the cookie and the next explain answers 401`
`the catalogue stays open to an anonymous caller`
`creating a session stays open to an anonymous caller`
`the account route answers 401 when signed out`
`the account route answers the view when signed in`
```

`.env.example` gains, each with no default and a comment saying the server refuses to start without it: `MYTETZ_PUBLIC_BASE_URL`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `MYTETZ_MAIL_MODE`, `MYTETZ_MAIL_API_KEY`, `MYTETZ_MAIL_FROM`.

**Components must stay startable without them for the catalogue.** Follow the `llmFactory` precedent: build `MagicLinkService` and `GoogleOAuth` lazily, so a deployment with no mail key still serves topics. Prove it with a test in `ComponentsTest` that counts factory invocations, exactly as the B0 migration test does.

- [ ] Steps: tests, fail, implement, `./gradlew build`, commit.

Subject: `feat(api): add the auth routes and gate the explain endpoint`

---

## Task 7: The sign-in surface

**Files:**
- Create: `frontend/src/app/auth/sign-in-panel.component.ts` and its spec
- Create: `frontend/src/app/auth/auth-landing.component.ts` and its spec
- Create: `frontend/src/app/core/account.store.ts` and its spec
- Create: `frontend/src/app/core/auth.interceptor.ts` and its spec
- Modify: `frontend/src/app/core/api.service.ts`
- Modify: `frontend/src/app/app.config.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/reader/reader-page.component.ts`

**Behaviour:**

- `AccountStore` is a signal holding `AccountView | null`. It loads from `GET /api/account` and treats `401` as signed out rather than as an error.
- `auth.interceptor` catches `401 SIGN_IN_REQUIRED` and opens the sign-in panel in place. It leaves every other error to the existing handler.
- The sign-in panel offers the email field and the Google button. After a request it shows the sent state and does not say whether the address was known.
- The reader renders the panel where the explanation would appear. **The breadcrumb and the trail stay on screen.**
- `/auth` renders the landing that reads `?auth=expired` and `?auth=failed`.

**The Angular rule from specification A holds:** the reader must not touch `window`, `document` or `localStorage` on its render path. The session is an HttpOnly cookie, so nothing needs to.

Required spec names:

```
`the store treats a 401 as signed out`
`the store holds the view when signed in`
`the interceptor opens the panel on SIGN_IN_REQUIRED`
`the interceptor leaves an unrelated error alone`
`the panel shows the sent state after a request`
`the panel never says whether the address was known`
`the reader keeps the trail while the panel is open`
`the landing reads the expired reason`
```

- [ ] Steps: specs, fail, implement, `npm test -- --watch=false`, commit.

Subject: `feat(ui): add the sign-in panel and the account store`

---

# Slice B2 — The trial

## Task 8: The `billing` module and the entitlement resolver

**Files:**
- Create: `backend/billing/build.gradle.kts`
- Create: `backend/billing/src/main/kotlin/com/mytetz/billing/Subscription.kt`
- Create: `backend/billing/src/main/kotlin/com/mytetz/billing/BillingRepository.kt`
- Create: `backend/billing/src/main/kotlin/com/mytetz/billing/Entitlement.kt`
- Create: `backend/billing/src/test/kotlin/com/mytetz/billing/EntitlementTest.kt`
- Create: `backend/billing/src/test/kotlin/com/mytetz/billing/BillingRepositoryTest.kt`
- Create: `backend/billing/src/test/kotlin/com/mytetz/billing/MongoTestSupport.kt`
- Modify: `settings.gradle.kts`

**Interfaces produced:**

```kotlin
enum class SubscriptionStatus { TRIALING, ACTIVE, PAST_DUE, CANCELLED, EXPIRED }

@Serializable data class Subscription(
    @SerialName("_id") val userId: String,
    val status: SubscriptionStatus,
    val trialEndsAtEpochMillis: Long? = null,
    val currentPeriodEndsAtEpochMillis: Long? = null,
    val graceEndsAtEpochMillis: Long? = null,
    val freemiusUserId: String? = null,
    val freemiusSubscriptionId: String? = null,
    val updatedAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

@Serializable data class BillingEvent(
    @SerialName("_id") val eventId: String,
    @SerialName("receivedAt") @Serializable(with = EpochMillisAsBsonDateTime::class)
    val receivedAtEpochMillis: Long,
)

data class BillingConfig(
    val trialGenerations: Int = 40,      // MYTETZ_TRIAL_GENERATIONS
    val trialDays: Int = 7,              // MYTETZ_TRIAL_DAYS
    val graceDays: Int = 3,              // MYTETZ_GRACE_DAYS
    val subscriberDailyExplains: Int = 25, // MYTETZ_SUBSCRIBER_DAILY_EXPLAINS
)

sealed interface EntitlementDecision {
    data class Allowed(val allowance: Allowance) : EntitlementDecision
    data object TrialExhausted : EntitlementDecision      // only when the pool is spent while TRIALING
    data object SubscriptionRequired : EntitlementDecision
}

object Entitlement {
    fun resolve(subscription: Subscription?, nowEpochMillis: Long, config: BillingConfig): EntitlementDecision
}
```

`billing` depends on `:backend:persistence` and `:backend:quota`, because it returns an `Allowance`. It must not depend on `account`, `session` or `graph`.

**The resolver, exactly:**

| Status | Condition | Result |
|---|---|---|
| `TRIALING` | `now < trialEndsAt` | `Allowed(Allowance(trialGenerations, trialEndsAt - createdAt))` |
| `ACTIVE` | — | `Allowed(Allowance(subscriberDailyExplains, 86_400_000))` |
| `CANCELLED` | `now < currentPeriodEndsAt` | `Allowed(Allowance(subscriberDailyExplains, 86_400_000))` |
| `PAST_DUE` | `now < graceEndsAt` | `Allowed(Allowance(subscriberDailyExplains, 86_400_000))` |
| null, or any other | — | `SubscriptionRequired` |

`Entitlement.resolve` is a pure function of its three arguments. It holds no clock and reads no database.

**The tests must cross every boundary at one millisecond before and one millisecond after.** This function decides who pays, so prove it rather than sample it.

Required test names:

```
`a null subscription requires a subscription`
`a trial one millisecond before its end is allowed`
`a trial exactly at its end requires a subscription`
`a trial one millisecond after its end requires a subscription`
`an active subscription gets the subscriber allowance`
`a cancelled subscription is allowed until its period ends`
`a cancelled subscription one millisecond after its period ends requires a subscription`
`a past-due subscription is allowed inside the grace`
`a past-due subscription one millisecond after the grace requires a subscription`
`an expired subscription requires a subscription`
`the trial window spans the whole trial and not a day`
`a configured trial size reaches the allowance`
```

`BillingRepository` needs: `find(userId)`, `upsert(subscription)`, `insertEventIfAbsent(eventId, now): Boolean`, `listNonTerminal(limit)`, `ensureIndexes()`. `insertEventIfAbsent` returns false on a duplicate, using the `insertIfAbsent` pattern.

- [ ] Steps: tests, fail, implement, `./gradlew build`, commit.

Subject: `feat(billing): add the billing module and the entitlement resolver`

---

## Task 9: Wire the trial into the gate

**Files:**
- Create: `backend/billing/src/main/kotlin/com/mytetz/billing/BillingService.kt`
- Create: `backend/billing/src/test/kotlin/com/mytetz/billing/BillingServiceTest.kt`
- Modify: `backend/quota/src/main/kotlin/com/mytetz/quota/QuotaRepository.kt`
- Modify: `backend/quota/src/test/kotlin/com/mytetz/quota/QuotaServiceTest.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/SessionRoutes.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/AuthRoutes.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Components.kt`
- Modify: `backend/api/src/test/kotlin/com/mytetz/api/SessionRoutesTest.kt`

**Interfaces produced:**

```kotlin
class BillingService(
    private val repository: BillingRepository,
    private val config: BillingConfig = BillingConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun startTrialIfAbsent(userId: String): Subscription
    suspend fun entitlementFor(userId: String): EntitlementDecision
}
```

`QuotaRepository` gains:

```kotlin
suspend fun resetCounter(principalId: String)
```

**Behaviour:**

- `startTrialIfAbsent` is called on every successful sign-in. It inserts a `TRIALING` row with `trialEndsAt = now + trialDays` when none exists, and leaves an existing row alone.
- The gate gains step 2, between the sign-in check and span validation:

| Decision | Answer |
|---|---|
| `Allowed(allowance)` | Continue, and pass the allowance to `checkGeneration` and `recordGeneration` |
| `SubscriptionRequired` | `403 SUBSCRIPTION_REQUIRED` |

- Step 4 splits. When `checkGeneration` refuses and the status is `TRIALING`, the answer is `403 TRIAL_EXHAUSTED` and not `429`. **A trial pool does not roll over, so a `Retry-After` would be a lie.** When the status is a paid one, the answer stays `429 QUOTA_EXCEEDED` with its real `retryAfter`.
- `Components` must call `resetCounter` when the resolved allowance's window differs from the stored counter's window. That happens on the trial-to-paid transition. Put the call on the write path and not the read path.

Required test names:

```
`signing in starts a trial`
`signing in twice leaves the first trial alone`
`a trial user reaches the pipeline`
`a user with no subscription answers SUBSCRIPTION_REQUIRED`
`an exhausted trial answers TRIAL_EXHAUSTED and not 429`
`an exhausted subscriber answers 429 with a retry-after`
`an expired trial answers SUBSCRIPTION_REQUIRED`
`the trial allowance bounds generation at the configured pool`
`resetCounter clears the count and the window`
`a window change resets the counter`
```

- [ ] Steps: tests, fail, implement, `./gradlew build`, commit.

Subject: `feat(billing): gate generation on the trial and split the exhausted answers`

---

## Task 10: The allowance meter and the walls

**Files:**
- Create: `frontend/src/app/account/allowance-meter.component.ts` and its spec
- Create: `frontend/src/app/account/wall-panel.component.ts` and its spec
- Modify: `frontend/src/app/core/account.store.ts`
- Modify: `frontend/src/app/core/auth.interceptor.ts`
- Modify: `frontend/src/app/reader/reader-page.component.ts`
- Modify: `frontend/src/app/ui/app-shell.component.ts`

**Behaviour:**

- The meter shows `remaining` of `allowance` and the reset time. In a trial it says how many of the pool are left and when the trial ends.
- The interceptor maps three codes to three panels: `SIGN_IN_REQUIRED` to the sign-in panel, `TRIAL_EXHAUSTED` to the subscribe panel, `SUBSCRIPTION_REQUIRED` to the subscribe panel. `QUOTA_EXCEEDED` keeps the existing wait message.
- **The subscribe panel and the wait message must not be the same component.** One says paying helps, the other says waiting helps. Getting that wrong tells a trial user to wait for a reset that never comes.
- The trail and the breadcrumb stay on screen behind every panel.

Required spec names:

```
`the meter shows remaining of allowance`
`the meter names the trial end in a trial`
`TRIAL_EXHAUSTED opens the subscribe panel`
`SUBSCRIPTION_REQUIRED opens the subscribe panel`
`QUOTA_EXCEEDED shows the wait message and not the subscribe panel`
`the trail stays visible behind every panel`
```

- [ ] Steps: specs, fail, implement, `npm test -- --watch=false`, commit.

Subject: `feat(ui): add the allowance meter and the subscribe wall`

---

# Slice B3 — Freemius

## Task 11: The webhook

**Files:**
- Create: `backend/billing/src/main/kotlin/com/mytetz/billing/FreemiusWebhook.kt`
- Create: `backend/billing/src/test/kotlin/com/mytetz/billing/FreemiusWebhookTest.kt`
- Modify: `backend/billing/src/main/kotlin/com/mytetz/billing/BillingService.kt`
- Modify: `backend/billing/src/test/kotlin/com/mytetz/billing/BillingServiceTest.kt`

**Interfaces produced:**

```kotlin
data class FreemiusConfig(
    val secretKey: String,     // FREEMIUS_SECRET_KEY, no default
    val productId: String,     // FREEMIUS_PRODUCT_ID
    val planId: String,        // FREEMIUS_PLAN_ID
)

object FreemiusWebhook {
    fun verify(rawBody: ByteArray, signatureHeader: String?, secretKey: String): Boolean
    fun parse(rawBody: ByteArray): FreemiusEvent
}

data class FreemiusEvent(
    val id: String,
    val type: String,
    val userReference: String?,          // our user id, sent as the checkout reference
    val freemiusUserId: String?,
    val freemiusSubscriptionId: String?,
    val periodEndsAtEpochMillis: Long?,
    val occurredAtEpochMillis: Long,
)
```

`BillingService` gains `suspend fun apply(event: FreemiusEvent): Boolean`.

**Verification, exactly.** Freemius signs with an HMAC-SHA256 of the **raw request body**, keyed by the product's secret, in the `x-signature` header. Compute over the bytes before any deserialization. Compare with `MessageDigest.isEqual`, the constant-time compare `Principal.kt` already uses. Reuse that helper rather than writing a second one.

**Three properties:**

- **Idempotent.** `apply` calls `insertEventIfAbsent`. A duplicate event id returns false and changes nothing.
- **Ordered.** A write whose `occurredAt` is older than the stored `updatedAt` is dropped. A late failure cannot overwrite a newer renewal.
- **Mapped.** Event type to state:

| Effect | New status | Fields |
|---|---|---|
| First payment, renewal | `ACTIVE` | `currentPeriodEndsAt` |
| Payment failed | `PAST_DUE` | `graceEndsAt = occurredAt + graceDays` |
| Cancellation | `CANCELLED` | keeps `currentPeriodEndsAt` |
| Refund, chargeback, expiry | `EXPIRED` | — |

The exact Freemius event-type strings come from their dashboard. Put them in one `private val` map so an operator can correct a name in one place. **When the type is unknown, log `BILLING_UNKNOWN_EVENT` and change nothing.** Do not guess a default.

Required test names:

```
`a correct signature verifies`
`an absent signature header is refused`
`a wrong signature is refused`
`a signature over a re-serialized body is refused`
`a replayed event id changes nothing`
`an event older than the stored state is dropped`
`a newer event overwrites`
`a first payment moves the state to active`
`a failed payment moves the state to past due with a grace`
`a cancellation keeps the period end`
`a refund expires the subscription`
`an unknown event type changes nothing`
```

The re-serialization test is the one that proves reading raw bytes was necessary. Sign a body, re-serialize it through the JSON parser, and assert the signature no longer verifies.

- [ ] Steps: tests, fail, implement, `./gradlew build`, commit.

Subject: `feat(billing): verify and apply Freemius webhook events`

---

## Task 12: Checkout, the subscription gate, and reconciliation

**Files:**
- Create: `backend/api/src/main/kotlin/com/mytetz/api/BillingRoutes.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/BillingRoutesTest.kt`
- Create: `backend/billing/src/main/kotlin/com/mytetz/billing/Reconciliation.kt`
- Create: `backend/billing/src/test/kotlin/com/mytetz/billing/ReconciliationTest.kt`
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/Components.kt`
- Modify: `.env.example`
- Modify: `docs/deploy.md`

**Routes:**

| Method | Path | Answer |
|---|---|---|
| `POST` | `/api/billing/checkout` | `200 {"url": "..."}` with the user id as the reference, or `401` |
| `POST` | `/api/billing/webhook` | `204` on success, `401` on a bad signature, `204` on a duplicate |

**The webhook route must read the raw body before any content negotiation touches it.** In Ktor, take `call.receiveChannel().toByteArray()` and parse afterwards. A route that accepts a deserialized object cannot verify the signature.

**Nothing the browser sends back is trusted.** The checkout return URL only tells the app to refresh the account view. The webhook is the only source of truth.

**Reconciliation** is a suspend function that reads every non-terminal subscription, asks Freemius for its current state, and corrects the mirror. It logs `BILLING_DRIFT` for each change. Wire it behind `MYTETZ_RECONCILE_ON_BOOT`, off by default, with the same polarity rule as `MYTETZ_MIGRATE_ON_BOOT`: only the exact word `true` turns it on.

**The alert tokens this slice adds:** `BILLING_DRIFT`, `BILLING_UNKNOWN_EVENT`, `WEBHOOK_SIGNATURE_MISMATCH`, `ACCOUNT_LINK_CONFLICT`, `MAIL_SEND_FAILED`. Add them to the table in `docs/deploy.md` beside the four that exist.

Required test names:

```
`checkout answers 401 when signed out`
`checkout returns a url carrying the user reference`
`the webhook route reads the raw body`
`the webhook refuses a bad signature with 401`
`the webhook answers 204 for a duplicate event`
`the webhook applies a first payment`
`an active subscriber reaches the pipeline`
`an expired subscriber answers SUBSCRIPTION_REQUIRED`
`reconciliation corrects a drifted mirror`
`reconciliation logs BILLING_DRIFT on a change`
`reconciliation is off unless the flag says true`
```

`.env.example` gains `FREEMIUS_SECRET_KEY`, `FREEMIUS_PRODUCT_ID`, `FREEMIUS_PLAN_ID`, `MYTETZ_SUBSCRIBER_DAILY_EXPLAINS`, `MYTETZ_TRIAL_GENERATIONS`, `MYTETZ_TRIAL_DAYS`, `MYTETZ_GRACE_DAYS`, `MYTETZ_RECONCILE_ON_BOOT`.

- [ ] Steps: tests, fail, implement, `./gradlew build`, commit.

Subject: `feat(api): add checkout, the webhook route, and reconciliation`

---

## Task 13: Checkout in the browser

**Files:**
- Modify: `frontend/src/app/account/wall-panel.component.ts`
- Create: `frontend/src/app/account/account-page.component.ts` and its spec
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/core/api.service.ts`

**Behaviour:**

- The subscribe panel calls `POST /api/billing/checkout` and sends the browser to the returned URL.
- `/account` shows the email, the status, the period end, the meter, a manage link, sign-out, sign-out-everywhere and delete.
- On return from checkout the app refreshes `GET /api/account` rather than trusting any query parameter.

Required spec names:

```
`the subscribe panel fetches a checkout url`
`the account page shows the status and the period end`
`returning from checkout refreshes the account view`
`the account page offers sign out everywhere`
```

- [ ] Steps: specs, fail, implement, `npm test -- --watch=false`, commit.

Subject: `feat(ui): add checkout and the account page`

---

# Slice B4 — Hardening

## Task 14: Deletion, Turnstile, and the trial cap

**Files:**
- Modify: `backend/api/src/main/kotlin/com/mytetz/api/AuthRoutes.kt`
- Modify: `backend/account/src/main/kotlin/com/mytetz/account/AccountService.kt`
- Create: `backend/api/src/main/kotlin/com/mytetz/api/Turnstile.kt`
- Create: `backend/api/src/test/kotlin/com/mytetz/api/TurnstileTest.kt`
- Modify: `backend/billing/src/main/kotlin/com/mytetz/billing/BillingService.kt`
- Modify: `docs/deploy.md`

**Behaviour:**

- `POST /api/account/delete` needs a fresh confirmation token, minted by a second magic link. It removes the user, every auth session, every learning session, every quiz attempt and the principal counter. **It removes no explanation**, because an explanation is user-independent and holds nothing personal.
- Turnstile verifies the token against `https://challenges.cloudflare.com/turnstile/v0/siteverify` in front of `POST /api/auth/magic-link` and the Google callback. It is skipped when `MYTETZ_TURNSTILE_SECRET` is unset, so local work needs no key.
- At most three new trials for each IP bucket in 24 hours. A blocked visitor is offered checkout at once and is not refused outright. Store the count on the existing `principals` document's `ipBucket`, or in a small TTL collection.

Required test names:

```
`deleting an account removes the user and every session`
`deleting an account leaves every explanation`
`deleting an account needs a fresh confirmation`
`a stale confirmation is refused`
`turnstile is skipped when no secret is set`
`turnstile refuses a bad token when a secret is set`
`a fourth trial from one ip bucket in a day is refused`
`a refused trial is offered checkout`
```

- [ ] Steps: tests, fail, implement, `./gradlew build`, `npm test`, `npx playwright test`, commit.

Subject: `feat(api): add account deletion, Turnstile, and the trial cap`

---

## Final language sweep

After Task 14, one task sweeps every language finding the reviewers deferred. Read the ledger's `minor (deferred)` lines, fix each one, and run the full suite. This is the one place prose blocks.

---

## Verification before deployment

| # | Check | Pass condition |
|---|---|---|
| 1 | Open the catalogue signed out | Topics list. A topic opens and shows its seed. |
| 2 | Highlight and press Explain signed out | The sign-in panel appears. The trail stays. |
| 3 | Request a magic link | The mail arrives. The link signs you in. The trail is still there. |
| 4 | Sign in with Google | The same account, matched by email |
| 5 | Read the meter | 40 of 40, and the trial end |
| 6 | Spend the trial | `TRIAL_EXHAUSTED`, and the subscribe panel, not the wait message |
| 7 | Check out in the Freemius sandbox | The webhook arrives. The status becomes `ACTIVE`. The meter shows 25 a day. |
| 8 | Replay the webhook | Nothing changes |
| 9 | Cancel in the Freemius dashboard | The status becomes `CANCELLED`. Generation still works until the period ends. |
| 10 | Refund in the Freemius dashboard | The status becomes `EXPIRED`. Generation answers `SUBSCRIPTION_REQUIRED`. The trail still reads. |
| 11 | Delete the account | The user is gone. The explanations are not. |

Checks 7 to 10 need a Freemius sandbox and cannot run before an operator creates the account.

## What an operator must set before this goes live

| Secret | Effect when unset |
|---|---|
| `MYTETZ_PUBLIC_BASE_URL` | The server refuses to start |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google sign-in is unavailable; the magic link still works |
| `MYTETZ_MAIL_MODE`, `MYTETZ_MAIL_API_KEY`, `MYTETZ_MAIL_FROM` | The magic link is unavailable; Google still works |
| `FREEMIUS_SECRET_KEY`, `FREEMIUS_PRODUCT_ID`, `FREEMIUS_PLAN_ID` | Checkout and the webhook are unavailable; the trial still works |
| `MYTETZ_TURNSTILE_SECRET` | Turnstile is skipped |

**Sign-in must survive one of the two methods being unavailable.** That is the whole reason the specification chose both.
