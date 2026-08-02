package com.mytetz.api

import com.mytetz.quota.PrincipalId
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * How the anonymous-principal cookie is signed and how it is scoped.
 *
 * ## Why this config behaves the opposite way to every other config in the project
 *
 * `GraphConfig.resolveMaxOutputTokens`, `QuotaConfig.resolveDailyExplains` and
 * `SessionLimits.resolveMaxDepth` all share one rule, stated in each of them: *a missing,
 * unparseable or non-positive override falls back to the default rather than throwing, because a
 * typo in a deployment environment variable must not take the server down.* Task 1.8 fixed a real
 * crash-on-missing-config defect on exactly that reasoning.
 *
 * **[signingKey] deliberately does not follow it, and must not be made to.** The premise of that
 * rule is that *the default is the safe value*. There is no safe default signing key. A key baked
 * into the source, or derived from anything an attacker can also derive, lets anyone mint any
 * principal they like — which is a free quota reset today and somebody else's account the moment
 * `PrincipalId.user(...)` is reachable. For this one value the safe failure is to refuse to start:
 * a server that will not boot is an incident an operator sees in the first thirty seconds, and a
 * server that booted with a known key is an incident nobody sees at all.
 *
 * This is written down here, and pinned by `PrincipalCookieConfigTest`, so that a later pass at
 * "making the configuration handling consistent" has to argue with it rather than quietly win.
 *
 * ## [secure], and why it is not derived from the request
 *
 * Whether this deployment is reachable over TLS is a fact about the deployment, and deployment
 * facts come from the environment — the same place the three configs above read theirs. It is
 * emphatically not a fact about the request: `call.request.host()` is the client-supplied `Host`
 * header, so deriving `secure` from it hands any client a switch for turning off the protection on
 * its own session cookie, on a production host, over plain HTTP.
 *
 * The polarity is inverted relative to the other resolvers because the safe value is inverted:
 * unset means `true`. A local HTTP development server must say so explicitly with
 * `MYTETZ_COOKIE_SECURE=false`, and the failure when it forgets is loud and immediate — the browser
 * declines to send the cookie back and every visit looks like a first visit.
 */
data class PrincipalCookieConfig(
    val signingKey: String = resolveSigningKey(System.getenv(SIGNING_KEY_ENV)),
    val secure: Boolean = resolveSecure(System.getenv(SECURE_ENV)),
) {

    init {
        // The resolver already rejects a missing or short key, but a caller constructing this
        // directly bypasses it — and a short key is not a typo to tolerate, it is a key that can be
        // searched. Same belt-and-braces shape as QuotaConfig and SessionLimits.
        require(signingKey.length >= MIN_SIGNING_KEY_LENGTH) {
            "$SIGNING_KEY_ENV must be at least $MIN_SIGNING_KEY_LENGTH characters, was ${signingKey.length}"
        }
    }

    companion object {

        const val SIGNING_KEY_ENV: String = "MYTETZ_COOKIE_SIGNING_KEY"
        const val SECURE_ENV: String = "MYTETZ_COOKIE_SECURE"

        /** 32 characters is 256 bits of base64url, i.e. the output width of the HMAC it keys. */
        const val MIN_SIGNING_KEY_LENGTH: Int = 32

        /** A year. Long enough that an anonymous learner's history survives; not a permanent id. */
        const val MAX_AGE_SECONDS: Int = 60 * 60 * 24 * 365

        /**
         * Fail closed. See the class KDoc for why this is the one resolver in the project that
         * throws instead of defaulting.
         *
         * Trimmed because `fly secrets set` and a hand-edited `.env` both routinely leave a
         * trailing newline on a value, and a key that changes shape between two pastes of the same
         * secret invalidates every cookie in circulation on the next restart.
         */
        internal fun resolveSigningKey(raw: String?): String {
            val key = raw?.trim().orEmpty()
            check(key.isNotEmpty()) {
                "$SIGNING_KEY_ENV is not set. It signs the anonymous principal cookie, so there is " +
                    "no safe default: generate one with `openssl rand -base64 32` and set it as a " +
                    "deployment secret."
            }
            return key
        }

        /**
         * `true` unless the value is recognisably a "no". An unset, empty or misspelt override
         * keeps the protection rather than dropping it — the inverse of the other resolvers,
         * because here the safe value is the strict one.
         */
        internal fun resolveSecure(raw: String?): Boolean =
            raw?.trim()?.lowercase() !in NEGATIVE
    }
}

private val NEGATIVE = setOf("false", "0", "no", "off")

/** The name is part of the wire contract with the browser; changing it logs every learner out. */
private const val COOKIE_NAME = "mytetz_pid"

/**
 * The cookie is written and read under **this** encoding on both sides.
 *
 * It is a named constant rather than two literals because Ktor's two sides do not default to the
 * same thing: `ResponseCookies.append` takes whatever the [Cookie] carries, while
 * `RequestCookies.get(name)` defaults to [CookieEncoding.URI_ENCODING]. Writing RAW and reading the
 * default round-trips only by the accident that base64url, a UUID and a colon contain no `%` — and
 * an accident that holds is still an accident. `PrincipalTest` pins both halves.
 */
private val COOKIE_ENCODING = CookieEncoding.RAW

/** The separator between the signed value and its signature. Absent from base64url by definition. */
private const val SIGNATURE_SEPARATOR = '.'

object Principals {

    /**
     * Returns a stable anonymous principal, minting and setting a signed cookie when one is absent,
     * unsigned, signed under a foreign key, or signed but not a principal this function mints.
     *
     * The signature stops a client from choosing its own principal id, which would otherwise be a
     * free quota reset once per request. It is **not** an authentication mechanism and confers no
     * authorisation: `SessionService` reads no principal at all, so holding a session id is still
     * enough to read and append to that session. See "Authorisation is the caller's" there.
     */
    fun resolve(call: ApplicationCall, config: PrincipalCookieConfig): PrincipalId {
        verified(call, config.signingKey)?.let { return it }

        val minted = PrincipalId.anonymous(UUID.randomUUID().toString())
        call.response.cookies.append(
            Cookie(
                name = COOKIE_NAME,
                value = sign(minted.value, config.signingKey),
                httpOnly = true,
                secure = config.secure,
                path = "/",
                maxAge = PrincipalCookieConfig.MAX_AGE_SECONDS,
                extensions = mapOf("SameSite" to "Lax"),
                encoding = COOKIE_ENCODING,
            )
        )
        return minted
    }

    private fun sign(value: String, key: String): String = "$value$SIGNATURE_SEPARATOR${hmac(value, key)}"

    private fun verified(call: ApplicationCall, key: String): PrincipalId? {
        val cookie = call.request.cookies[COOKIE_NAME, COOKIE_ENCODING] ?: return null

        val separator = cookie.lastIndexOf(SIGNATURE_SEPARATOR)
        if (separator <= 0) return null
        val value = cookie.substring(0, separator)
        val signature = cookie.substring(separator + 1)
        if (!constantTimeEquals(hmac(value, key), signature)) return null

        // Verifying the signature answers "did we sign this?" and never "is this a principal we
        // mint". Those come apart the moment a second code path holds the same key, and the string
        // returned here becomes a Mongo `_id` and a quota key: a signed `user:admin` would claim
        // the account namespace `PrincipalId.user(...)` already defines. So the shape is checked
        // too, and checked by RECONSTRUCTING the value through PrincipalId.anonymous rather than by
        // matching a prefix literal — the namespace is quota's to define, and a copy of it here
        // would be free to drift.
        val uuid = value.substringAfter(':', missingDelimiterValue = "")
        val parsed = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return null
        if (!parsed.toString().equals(uuid, ignoreCase = true)) return null

        return PrincipalId.anonymous(uuid).takeIf { it.value == value }
    }

    private fun hmac(value: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    /**
     * [MessageDigest.isEqual] is specified to take time independent of the number of matching
     * bytes, which `==` on a String is not. The comparison is between a value we computed and a
     * value the client sent, so a fast-fail comparison is a byte-at-a-time oracle on the signature.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
