package com.mytetz.api

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.mytetz.api.Turnstile")

private val json = Json { ignoreUnknownKeys = true }

/** Encodes [value] the way an `application/x-www-form-urlencoded` body requires. */
private fun encodeFormValue(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

/**
 * The Cloudflare Turnstile secret this deployment holds, or null.
 *
 * [secretKey] carries no default beyond "unset". Unlike [com.mytetz.billing.FreemiusConfig], a
 * missing value here does not throw: Turnstile is *skipped* when [SECRET_KEY_ENV] is unset, so
 * local work and CI need no key. A throwing config would force every credential-free test in this
 * module to inject one just to reach the routes it verifies.
 */
data class TurnstileConfig(
    val secretKey: String? = resolveSecretKey(System.getenv(SECRET_KEY_ENV)),
) {
    companion object {

        const val SECRET_KEY_ENV: String = "MYTETZ_TURNSTILE_SECRET"

        /** Trims [raw] and turns a blank value into null, the same as an unset one already is. */
        internal fun resolveSecretKey(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Verifies a Cloudflare Turnstile token against the vendor's own `siteverify` endpoint.
 *
 * `AuthRoutes.kt` calls [verify] in front of `POST /api/auth/magic-link`, and in front of
 * `GET /api/auth/google` — see that file's own KDoc on the Google route for why the check sits at
 * the start of the Google flow rather than at the callback. With [secretKey] unset, [verify]
 * always answers true and never opens a connection, so every existing test in this module, and a
 * deployment with no Cloudflare account, need no real key.
 *
 * [httpClient] is injected, the same division of labour [com.mytetz.account.GoogleOAuth] and
 * [FreemiusApiClient] already use in this codebase: production code injects a client that reaches
 * the real Cloudflare endpoint, and a test injects a `MockEngine`.
 *
 * Confirmed against Cloudflare's own server-side validation documentation
 * (`developers.cloudflare.com/turnstile/get-started/server-side-validation/`), not guessed: the
 * endpoint, the method, the `secret`/`response`/`remoteip` field names and the `success` response
 * field are all named there exactly as this class uses them.
 */
class Turnstile(
    private val httpClient: HttpClient,
    private val secretKey: String?,
) {

    /**
     * True when [token] is a token Cloudflare accepts for [secretKey], or when [secretKey] is
     * null.
     *
     * A null or blank [token] is refused without a network call whenever [secretKey] is set: an
     * absent token can never be a real answer to the challenge, so there is nothing to ask
     * Cloudflare about. Every other outcome that is not a confirmed `success: true` also refuses —
     * a non-2xx status, a body this class cannot parse, and a request that does not complete at
     * all. A verification step that fails open on an error is not a verification step.
     *
     * Never logs [token] or [secretKey]. A caught exception's own message is never logged either,
     * only its class name — the same rule [FreemiusApiClient.fetchState] states at length: the
     * caller-supplied token could yet appear inside a parser's own exception message.
     */
    suspend fun verify(token: String?, remoteIp: String? = null): Boolean {
        val secret = secretKey ?: return true
        if (token.isNullOrBlank()) return false

        return try {
            val fields = buildList {
                add("secret" to secret)
                add("response" to token)
                remoteIp?.let { add("remoteip" to it) }
            }
            val payload = fields.joinToString("&") { (name, value) -> "$name=${encodeFormValue(value)}" }

            val response = httpClient.post(SITEVERIFY_URL) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(payload)
            }

            if (!response.status.isSuccess()) {
                log.warn("the Turnstile siteverify endpoint answered with status {}", response.status.value)
                return false
            }

            json.parseToJsonElement(response.bodyAsText()).jsonObject["success"]
                ?.jsonPrimitive?.booleanOrNull ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("the Turnstile siteverify request did not complete: {}", e.javaClass.name)
            false
        }
    }

    companion object {
        internal const val SITEVERIFY_URL: String = "https://challenges.cloudflare.com/turnstile/v0/siteverify"
    }
}
