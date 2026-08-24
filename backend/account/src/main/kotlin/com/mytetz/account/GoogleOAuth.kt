package com.mytetz.account

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/** Encodes [value] the way an `application/x-www-form-urlencoded` body or query string requires. */
private fun encodeFormValue(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

/**
 * Parses [text] as a JSON object.
 *
 * The function raises [GoogleAuthException] with [onFailure]'s message when [text] is not valid
 * JSON, or when it is valid JSON that is not an object. [GoogleOAuth.exchange] and
 * [GoogleOAuth.parseIdToken] both call this function, so one place states the failure shape for
 * both a token-endpoint answer and an ID token payload.
 */
private fun parseJsonObject(text: String, onFailure: () -> String): JsonObject =
    try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw GoogleAuthException(onFailure())
    }

/**
 * The settings one Google OAuth client needs.
 *
 * [clientId] and [clientSecret] come from Google Cloud Console, through the `GOOGLE_CLIENT_ID`
 * and `GOOGLE_CLIENT_SECRET` variables. Neither field carries a default. A missing value must
 * stop the server at boot, and must not silently disable Google sign-in. [redirectUri] is the
 * callback path the composition root builds from the deployment's own base URL, in the shape
 * `$baseUrl/api/auth/google/callback`.
 */
data class GoogleConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
)

/**
 * The claims [GoogleOAuth.exchange] reads out of a verified Google ID token.
 *
 * [sub] is Google's stable subject identifier for the account. [email] is lower-case, so it
 * matches the address [MagicLinkService.normaliseEmail] produces for the same person. Two
 * spellings of one address must resolve to one account, and not to two. [emailVerified] is true
 * only when Google itself has confirmed the address. [GoogleOAuth.exchange] refuses to return an
 * identity whose [emailVerified] is false.
 */
data class GoogleIdentity(val sub: String, val email: String, val emailVerified: Boolean)

/**
 * Raised when a step of the Google sign-in flow cannot complete.
 *
 * The message never carries a [GoogleConfig.clientSecret] value, and never carries a raw
 * response body. A failing provider response can echo a request field back, so
 * [GoogleOAuth.exchange] reports only the fact of a failure. [ResendMailSender] withholds a
 * failing response body from [MailSendFailedException] for the same reason.
 */
class GoogleAuthException(message: String) : Exception(message)

/**
 * Drives the Google OAuth authorization-code flow, with PKCE, for sign-in.
 *
 * [authorizationUrl] builds the link a browser follows to Google. [exchange] then trades the
 * code Google sends back, together with the matching PKCE verifier, for a verified identity.
 * [httpClient] carries its own transport. Production code injects a client that reaches the real
 * Google endpoints, and a test injects a client that reaches a loopback server instead. This is
 * the same division of labour [ResendMailSender] uses for the Resend API.
 *
 * [tokenEndpoint] defaults to the real Google token endpoint. A test overrides this constructor
 * argument to point [exchange] at a loopback server, without needing a URL-rewriting client.
 * [ResendMailSender]'s test redirects the class instead through the injected client's own base
 * URL, because that class calls one host only. This class calls two different hosts, one from
 * [authorizationUrl] and a different one from [exchange], so only the token endpoint takes a
 * constructor override here.
 */
class GoogleOAuth(
    private val config: GoogleConfig,
    private val httpClient: HttpClient,
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
) {

    /**
     * Builds the URL a browser follows to start Google sign-in.
     *
     * The URL always names `https://accounts.google.com/o/oauth2/v2/auth` and the `S256`
     * challenge method. It carries [state] and [codeChallenge] as opaque query values. The
     * caller mints [state] and a PKCE verifier, computes [codeChallenge] with [challengeOf], and
     * stores the state and the verifier against the caller's own session. [exchange] later needs
     * the same verifier back.
     */
    fun authorizationUrl(state: String, codeChallenge: String): String {
        val query = listOf(
            "response_type" to "code",
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "scope" to "openid email",
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        ).joinToString("&") { (name, value) -> "$name=${encodeFormValue(value)}" }
        return "$AUTHORIZATION_ENDPOINT?$query"
    }

    /**
     * Trades [code] and the matching PKCE [codeVerifier] for a verified Google identity.
     *
     * The method posts to [tokenEndpoint] and reads the `id_token` field out of the answer. It
     * raises [GoogleAuthException] when the request does not complete, when the endpoint answers
     * with a status outside the 2xx range, when the answer carries no `id_token` field, or when
     * [parseIdToken] refuses the token. It also raises when the token's `email_verified` claim is
     * false. A Google account can hold an address that Google has not confirmed belongs to the
     * account holder, and returning that identity would let its holder claim a magic-link
     * account that a different person owns.
     *
     * The response body is read only after a 2xx status. On every other status, the body stays
     * unread, because the request that produced it carried [GoogleConfig.clientSecret], and a
     * failing provider response can echo a request field back into its body.
     */
    suspend fun exchange(code: String, codeVerifier: String): GoogleIdentity {
        val payload = listOf(
            "code" to code,
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "redirect_uri" to config.redirectUri,
            "grant_type" to "authorization_code",
            "code_verifier" to codeVerifier,
        ).joinToString("&") { (name, value) -> "$name=${encodeFormValue(value)}" }

        val response = try {
            httpClient.post(tokenEndpoint) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No response exists yet at this point, so there is no body to withhold: the cause
            // is the network failure itself, not anything the provider sent back.
            throw GoogleAuthException("the request to the Google token endpoint did not complete")
        }

        if (!response.status.isSuccess()) {
            throw GoogleAuthException(
                "the Google token endpoint answered with status ${response.status.value}",
            )
        }

        val root = parseJsonObject(response.bodyAsText()) {
            "the Google token endpoint answered with a body that is not a JSON object"
        }
        val idToken = root["id_token"]?.jsonPrimitive?.contentOrNull
            ?: throw GoogleAuthException("the Google token endpoint answered with no id_token field")

        val identity = parseIdToken(idToken)
        if (!identity.emailVerified) {
            throw GoogleAuthException("the Google account's email address is not verified")
        }
        return identity
    }

    companion object {

        private const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"

        /** The number of random bytes behind every minted PKCE verifier. */
        private const val VERIFIER_BYTE_LENGTH = 32

        /** The one [SecureRandom] instance every call to [newVerifier] draws from. */
        private val secureRandom = SecureRandom()

        /**
         * Mints a fresh PKCE code verifier from [VERIFIER_BYTE_LENGTH] random bytes, encoded as
         * base64url without padding.
         *
         * The method draws from [SecureRandom], the generator [MagicLinkService.newToken] also
         * uses, and for the same reason: a predictable verifier defeats the point of PKCE.
         */
        internal fun newVerifier(): String {
            val bytes = ByteArray(VERIFIER_BYTE_LENGTH)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /**
         * Computes the PKCE `S256` code challenge for [verifier].
         *
         * The result is the SHA-256 hash of the verifier's UTF-8 bytes, encoded as base64url
         * without padding.
         */
        internal fun challengeOf(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        /**
         * Reads the subject, the email address and the verified flag out of [jwt].
         *
         * The method splits [jwt] on `.`, base64url-decodes the middle segment, and reads the
         * `sub`, `email` and `email_verified` claims from the decoded JSON object. It raises
         * [GoogleAuthException] when [jwt] does not have three dot-separated segments, when the
         * middle segment is not valid base64url, when the decoded segment is not a JSON object,
         * or when the `sub` or the `email` claim is absent. The returned [GoogleIdentity.email]
         * is lower-case, so it matches the address [MagicLinkService.normaliseEmail] produces for
         * the same person.
         *
         * ## Why the method does not check the token's signature
         *
         * A signature check defends against a token an attacker supplies directly. This token
         * never arrives that way. [exchange] receives it over TLS, straight from Google's own
         * token endpoint, as the answer to a request that this class made with the account's own
         * client secret. A party that cannot pass Google's client authentication cannot place a
         * token inside that answer. A signature check here would confirm a fact that the TLS
         * channel and Google's own endpoint already guarantee, so the check would add code
         * without adding safety. This reasoning holds only for a token that arrives through
         * [exchange]. It does not extend to a token an HTTP request supplies from outside this
         * class.
         */
        internal fun parseIdToken(jwt: String): GoogleIdentity {
            val segments = jwt.split(".")
            if (segments.size != 3) {
                throw GoogleAuthException("the ID token does not have three dot-separated segments")
            }

            val payloadBytes = try {
                Base64.getUrlDecoder().decode(segments[1])
            } catch (e: IllegalArgumentException) {
                throw GoogleAuthException("the ID token payload is not valid base64url")
            }

            val payload = parseJsonObject(payloadBytes.decodeToString()) {
                "the ID token payload is not a JSON object"
            }

            val sub = payload["sub"]?.jsonPrimitive?.contentOrNull
                ?: throw GoogleAuthException("the ID token carries no sub claim")
            val email = payload["email"]?.jsonPrimitive?.contentOrNull
                ?: throw GoogleAuthException("the ID token carries no email claim")
            val emailVerified = payload["email_verified"]?.jsonPrimitive?.booleanOrNull ?: false

            return GoogleIdentity(sub = sub, email = email.lowercase(), emailVerified = emailVerified)
        }
    }
}
