package com.mytetz.api

import com.mytetz.account.AccountLinkConflictException
import com.mytetz.account.AccountService
import com.mytetz.account.GoogleOAuth
import com.mytetz.account.MagicLinkService
import com.mytetz.account.User
import com.mytetz.billing.BillingService
import com.mytetz.billing.EntitlementDecision
import com.mytetz.quota.PrincipalId
import com.mytetz.quota.QuotaRepository
import com.mytetz.session.SessionService
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.mytetz.api.AuthRoutes")

/** How many magic-link requests one IP bucket may make in [MAGIC_LINK_WINDOW_MILLIS]. */
const val MAGIC_LINK_PER_IP: Int = 10

/** How many magic-link requests one address may make in [MAGIC_LINK_WINDOW_MILLIS]. */
const val MAGIC_LINK_PER_ADDRESS: Int = 3

const val MAGIC_LINK_WINDOW_MILLIS: Long = 10L * 60 * 1000

/** The largest body `POST /api/auth/magic-link` reads. One email address needs far less than this. */
const val MAX_AUTH_BODY_BYTES: Long = 1_024

/** Ten minutes. A Google sign-in that takes longer than this must start over. */
private const val OAUTH_COOKIE_MAX_AGE_SECONDS = 600

/** Carries the Google OAuth anti-CSRF state for the length of one sign-in attempt. */
private const val GOOGLE_STATE_COOKIE = "mytetz_g_state"

/** Carries the Google OAuth PKCE verifier for the length of one sign-in attempt. */
private const val GOOGLE_VERIFIER_COOKIE = "mytetz_g_verifier"

@Serializable
data class MagicLinkRequest(val email: String)

/**
 * The view `GET /api/account` answers.
 *
 * [status], [trialEndsAtEpochMillis] and [currentPeriodEndsAtEpochMillis] come from the caller's own
 * subscription row. [allowance] and [remaining] come from the caller's resolved entitlement and
 * their own counter — see [accountViewFor].
 */
@Serializable
data class AccountView(
    val email: String,
    val status: String = "TRIALING",
    val trialEndsAtEpochMillis: Long? = null,
    val currentPeriodEndsAtEpochMillis: Long? = null,
    val allowance: Int,
    val remaining: Int,
    val resetsAtEpochMillis: Long? = null,
)

/**
 * `POST /api/auth/magic-link`, `GET /api/auth/magic-link/{token}`, `GET /api/auth/google`,
 * `GET /api/auth/google/callback`, `POST /api/auth/sign-out`, `POST /api/auth/sign-out-all`, and
 * `GET /api/account`.
 *
 * ## Sign-in carries the anonymous trail
 *
 * A visitor reads a seed anonymously, highlights a phrase, and signs in. Every route below that
 * completes a sign-in reads the caller's anonymous principal first, opens the session, sets the
 * cookie, and then calls [SessionService.reassignPrincipal] to move that principal's sessions onto
 * the new user. [completeSignIn] does this in one place, so the order cannot drift between the
 * magic-link route and the Google route.
 *
 * ## Every session route reads the same identity this file writes
 *
 * `SessionRoutes.kt`'s `effectivePrincipal` prefers the signed-in user's principal over the caller's
 * anonymous cookie whenever a session cookie resolves to one. This has to be true, and not merely
 * usually true, because [completeSignIn] calls [SessionService.reassignPrincipal] unconditionally on
 * every sign-in: the moment it runs, every session the caller already holds is re-keyed onto
 * `user:<id>`, whether or not the caller ever reads or explains into it again. A route that kept
 * resolving the caller's anonymous principal after that point would be asking for a principal that
 * no longer owns the session it just moved — which is exactly the defect a fix round caught here: a
 * learner who reads a topic anonymously, highlights, meets the wall and signs in got `404 NOT_FOUND`
 * on the very session they were reading, because the write agreed with this file and the read did
 * not.
 *
 * [sessions], [magicLink] and [google] are factories and not the built services, for the reason
 * `SessionRoutes.kt` gives at length for its own `sessions` parameter: `Components.magicLink` and
 * `Components.googleOAuth` are each `by lazy` on a chain that can throw when a credential is
 * missing, and passing the built value would force that chain while `Application.module()` is still
 * being configured — taking down the catalogue for a deployment that never uses sign-in at all.
 */
fun Route.authRoutes(
    account: AccountService,
    sessions: () -> SessionService,
    magicLink: () -> MagicLinkService,
    google: () -> GoogleOAuth,
    cookies: PrincipalCookieConfig,
    quotaRepository: QuotaRepository,
    billing: BillingService,
    clientAddresses: ClientAddressConfig = ClientAddressConfig(),
    clock: () -> Long = System::currentTimeMillis,
    magicLinkIpLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        limit = MAGIC_LINK_PER_IP,
        windowMillis = MAGIC_LINK_WINDOW_MILLIS,
    ),
    magicLinkAddressLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        limit = MAGIC_LINK_PER_ADDRESS,
        windowMillis = MAGIC_LINK_WINDOW_MILLIS,
    ),
) {

    post("/api/auth/magic-link") {
        if (!call.authBodyIsSmallEnough()) return@post

        val caller = ClientAddress.of(call, clientAddresses)
        val request = call.receive<MagicLinkRequest>()
        val addressKey = request.email.trim().lowercase()

        // Both limiters are checked, not short-circuited, so a caller past one limit still spends
        // no allowance on the other. `tryAcquire` itself is cheap; the order carries no meaning.
        val underIpLimit = magicLinkIpLimiter.tryAcquire(caller)
        val underAddressLimit = magicLinkAddressLimiter.tryAcquire(addressKey)
        if (!underIpLimit || !underAddressLimit) {
            log.info("rate limited a magic-link request from {}", caller)
            call.response.headers.append(HttpHeaders.RetryAfter, (MAGIC_LINK_WINDOW_MILLIS / 1000).toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiError(
                    code = "RATE_LIMITED",
                    message = "too many sign-in requests; try again later",
                    retryAfter = MAGIC_LINK_WINDOW_MILLIS / 1000,
                ),
            )
            return@post
        }

        // Always 204, for a known address and an unknown one. `MagicLinkService.request` already
        // holds this guarantee; this route adds nothing that could tell the two apart.
        magicLink().request(request.email)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/auth/magic-link/{token}") {
        val token = call.parameters["token"].orEmpty()
        val email = magicLink().consume(token)
        if (email == null) {
            call.respondRedirect("/auth?auth=expired")
            return@get
        }

        val user = account.findOrCreateByEmail(email)
        call.completeSignIn(account, sessions, cookies, billing, user)
        call.respondRedirect("/")
    }

    get("/api/auth/google") {
        val state = randomUrlSafeToken()
        val verifier = randomUrlSafeToken()
        val challenge = pkceChallenge(verifier)
        call.response.cookies.append(signedOauthCookie(GOOGLE_STATE_COOKIE, state, cookies))
        call.response.cookies.append(signedOauthCookie(GOOGLE_VERIFIER_COOKIE, verifier, cookies))
        call.respondRedirect(google().authorizationUrl(state, challenge))
    }

    get("/api/auth/google/callback") {
        val queryState = call.request.queryParameters["state"]
        val code = call.request.queryParameters["code"]
        val cookieState = call.verifiedOauthCookie(GOOGLE_STATE_COOKIE, cookies)
        val verifier = call.verifiedOauthCookie(GOOGLE_VERIFIER_COOKIE, cookies)
        call.clearOauthCookie(GOOGLE_STATE_COOKIE, cookies)
        call.clearOauthCookie(GOOGLE_VERIFIER_COOKIE, cookies)

        val stateMatches = queryState != null && cookieState != null &&
            MessageDigest.isEqual(
                queryState.toByteArray(Charsets.UTF_8),
                cookieState.toByteArray(Charsets.UTF_8),
            )

        if (!stateMatches || verifier == null || code == null) {
            log.info("google sign-in refused a missing or mismatched state")
            call.respondRedirect("/auth?auth=failed")
            return@get
        }

        try {
            val identity = google().exchange(code, verifier)
            val user = account.linkGoogle(identity)
            call.completeSignIn(account, sessions, cookies, billing, user)
            call.respondRedirect("/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountLinkConflictException) {
            log.warn("ACCOUNT_LINK_CONFLICT {}", e.message)
            call.respondRedirect("/auth?auth=failed")
        } catch (e: Exception) {
            log.info("google sign-in failed", e)
            call.respondRedirect("/auth?auth=failed")
        }
    }

    post("/api/auth/sign-out") {
        Principals.readSessionId(call, cookies)?.let { account.closeSession(it) }
        Principals.clearSessionCookie(call, cookies)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/api/auth/sign-out-all") {
        val user = Principals.readSessionId(call, cookies)?.let { account.resolveSession(it) }
        if (user != null) account.closeAllSessions(user.id)
        Principals.clearSessionCookie(call, cookies)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/account") {
        val user = Principals.readSessionId(call, cookies)?.let { account.resolveSession(it) }
        if (user == null) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiError("SIGN_IN_REQUIRED", "sign in to view your account"),
            )
            return@get
        }
        call.respond(accountViewFor(user, quotaRepository, billing, clock()))
    }
}

/**
 * Finishes a sign-in for [user]: reads the caller's anonymous principal, opens a session, sets the
 * session cookie, moves the anonymous principal's sessions onto the user, and starts a trial for the
 * user when one has not already started. See the class KDoc on [authRoutes] for why the order of the
 * first three is fixed.
 *
 * [BillingService.startTrialIfAbsent] is called last and unconditionally, because both callers of
 * this function — the magic-link route and the Google route — are the two places a sign-in
 * completes, and a trial that started only on one of them would leave a learner without one who
 * happened to choose the other.
 */
private suspend fun ApplicationCall.completeSignIn(
    account: AccountService,
    sessions: () -> SessionService,
    cookies: PrincipalCookieConfig,
    billing: BillingService,
    user: User,
) {
    val anonymousPrincipal = Principals.resolve(this, cookies)
    val sessionId = account.openSession(user.id)
    Principals.setSessionCookie(this, cookies, sessionId)
    sessions().reassignPrincipal(anonymousPrincipal.value, PrincipalId.user(user.id).value)
    billing.startTrialIfAbsent(user.id)
}

/**
 * The account view for [user].
 *
 * [BillingService.startTrialIfAbsent] is called here as a read, not a write: it returns the stored
 * row untouched when one already exists, which is always true by the time a signed-in caller can
 * reach this route, and it is the one place [BillingService] hands back the raw row this view needs
 * for [AccountView.trialEndsAtEpochMillis] and [AccountView.currentPeriodEndsAtEpochMillis].
 *
 * [AccountView.allowance] and [AccountView.remaining] come from the caller's resolved entitlement
 * and their own counter, not from the quota module's own daily default. A caller with
 * [EntitlementDecision.SubscriptionRequired] reports zero for both — there is no allowance to count
 * against. An absent counter, or one whose window has already ended, reports the full allowance and
 * no reset time — a fresh window has not started yet.
 */
private suspend fun accountViewFor(
    user: User,
    quotaRepository: QuotaRepository,
    billing: BillingService,
    now: Long,
): AccountView {
    val subscription = billing.startTrialIfAbsent(user.id)
    val entitlement = billing.entitlementFor(user.id)

    val allowance = when (entitlement) {
        is EntitlementDecision.Allowed -> entitlement.allowance.generations
        EntitlementDecision.SubscriptionRequired -> 0
    }
    val status = when (entitlement) {
        is EntitlementDecision.Allowed -> entitlement.status.name
        EntitlementDecision.SubscriptionRequired -> "NONE"
    }

    if (entitlement is EntitlementDecision.SubscriptionRequired) {
        return AccountView(
            email = user.email,
            status = status,
            trialEndsAtEpochMillis = subscription.trialEndsAtEpochMillis,
            currentPeriodEndsAtEpochMillis = subscription.currentPeriodEndsAtEpochMillis,
            allowance = 0,
            remaining = 0,
        )
    }

    val counter = quotaRepository.findCounter(PrincipalId.user(user.id).value)
    return if (counter == null || now >= counter.windowExpiresAtEpochMillis) {
        AccountView(
            email = user.email,
            status = status,
            trialEndsAtEpochMillis = subscription.trialEndsAtEpochMillis,
            currentPeriodEndsAtEpochMillis = subscription.currentPeriodEndsAtEpochMillis,
            allowance = allowance,
            remaining = allowance,
        )
    } else {
        AccountView(
            email = user.email,
            status = status,
            trialEndsAtEpochMillis = subscription.trialEndsAtEpochMillis,
            currentPeriodEndsAtEpochMillis = subscription.currentPeriodEndsAtEpochMillis,
            allowance = allowance,
            remaining = (allowance - counter.explainCount).coerceAtLeast(0),
            resetsAtEpochMillis = counter.windowExpiresAtEpochMillis,
        )
    }
}

/** False once a refusal has been sent. See [MAX_AUTH_BODY_BYTES]. */
private suspend fun ApplicationCall.authBodyIsSmallEnough(): Boolean {
    val declared = request.contentLength()
    if (declared != null && declared <= MAX_AUTH_BODY_BYTES) return true
    respond(
        HttpStatusCode.PayloadTooLarge,
        ApiError("PAYLOAD_TOO_LARGE", "a sign-in request must be under $MAX_AUTH_BODY_BYTES bytes"),
    )
    return false
}

// ------------------------------------------------------------------ Google OAuth cookies

/**
 * Signs and appends a short-lived cookie named [name] holding [value].
 *
 * This is a second, small signing scheme, separate from [Principals]: those helpers sign only the
 * anonymous principal and the session id, and both are `private` inside `object Principals`, so
 * this file cannot reuse them. The scheme is the same HMAC-SHA256-over-base64url shape.
 */
private fun signedOauthCookie(name: String, value: String, cookies: PrincipalCookieConfig): Cookie = Cookie(
    name = name,
    value = "$value.${oauthHmac(value, cookies.signingKey)}",
    httpOnly = true,
    secure = cookies.secure,
    path = "/",
    maxAge = OAUTH_COOKIE_MAX_AGE_SECONDS,
    extensions = mapOf("SameSite" to "Lax"),
    encoding = CookieEncoding.RAW,
)

/** Clears the cookie named [name]. */
private fun ApplicationCall.clearOauthCookie(name: String, cookies: PrincipalCookieConfig) {
    response.cookies.append(
        Cookie(
            name = name,
            value = "",
            httpOnly = true,
            secure = cookies.secure,
            path = "/",
            maxAge = 0,
            extensions = mapOf("SameSite" to "Lax"),
            encoding = CookieEncoding.RAW,
        ),
    )
}

/** Reads and verifies the cookie named [name], or returns null for an absent or tampered one. */
private fun ApplicationCall.verifiedOauthCookie(name: String, cookies: PrincipalCookieConfig): String? {
    val raw = request.cookies[name, CookieEncoding.RAW] ?: return null
    val separator = raw.lastIndexOf('.')
    if (separator <= 0) return null
    val value = raw.substring(0, separator)
    val signature = raw.substring(separator + 1)
    val expected = oauthHmac(value, cookies.signingKey)
    if (!MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8),
        )
    ) {
        return null
    }
    return value
}

private fun oauthHmac(value: String, key: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
}

private val secureRandom = SecureRandom()

/** 32 random bytes, base64url, no padding. Used for the OAuth `state` and the PKCE verifier. */
private fun randomUrlSafeToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * The PKCE `S256` code challenge for [verifier]: the SHA-256 hash of its UTF-8 bytes, base64url, no
 * padding. This duplicates `GoogleOAuth.challengeOf`, which is `internal` to `:backend:account` and
 * so is not visible here.
 */
private fun pkceChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
