package com.mytetz.account

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * The logger both adapters in this file share, named after the file rather than either class.
 *
 * The name is public so that a test can attach an appender to this exact logger. [MailSenderTest]
 * reads both `MAGIC_LINK_LOGGED` and `MAIL_SEND_FAILED` back through it.
 */
internal const val MAIL_SENDER_LOGGER: String = "com.mytetz.account.MailSender"

private val log = LoggerFactory.getLogger(MAIL_SENDER_LOGGER)

/**
 * A port for the magic-link sign-in email.
 *
 * The product offers two sign-in methods: a magic link by email, and Google. Mail delivery is
 * the least reliable part of this design. This port exists so that a mail failure stays inside
 * the mail path, and never stops sign-in through Google.
 *
 * [LoggingMailSender] writes the link to the log. A developer uses it for local work.
 * [ResendMailSender] calls the Resend API. A deployment uses it in production.
 */
interface MailSender {

    /**
     * Sends [link] to [email] as a magic-link sign-in message.
     *
     * The method throws [MailSendFailedException] when the send fails. The caller decides the
     * response to that failure. This method does not retry a failed send on its own.
     */
    suspend fun sendMagicLink(email: String, link: String)
}

/**
 * Writes the magic link to the log at INFO, under the token `MAGIC_LINK_LOGGED`.
 *
 * A developer uses this adapter for local work. No email leaves the process. The developer reads
 * the link from the log and opens it directly.
 */
class LoggingMailSender : MailSender {

    override suspend fun sendMagicLink(email: String, link: String) {
        try {
            log.info("MAGIC_LINK_LOGGED to={} link={}", email, link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("MAIL_SEND_FAILED the log write for {} failed", email, e)
            throw MailSendFailedException("the logging sender could not write the magic link", e)
        }
    }
}

/** The wire shape of a Resend `POST /emails` request body. */
@Serializable
private data class ResendEmailRequest(
    val from: String,
    val to: List<String>,
    val subject: String,
    val html: String,
)

/**
 * Sends the magic link through the Resend transactional email API.
 *
 * [httpClient] carries its own base URL. A production caller configures that URL as
 * `https://api.resend.com`. A test configures it as a local server instead. This class always
 * calls the relative path [EMAILS_PATH], so the injected client alone decides the real
 * destination — the same division of labour as `AnthropicLlmClient`, whose base URL lives on the
 * injected `AnthropicClient` and never inside the adapter.
 *
 * ## Why a failure never carries the response body
 *
 * A failure response from a provider can echo a request header back inside its body. The
 * `Authorization` header carries the API key here. A response body placed inside the exception
 * message would then leak the key by a second route, even though the message never names the key
 * itself. [sendMagicLink] therefore reports only the HTTP status code on a failure. It never
 * reports the response body, and it never reports the key.
 */
class ResendMailSender(
    private val apiKey: String,
    private val from: String,
    private val httpClient: HttpClient,
) : MailSender {

    override suspend fun sendMagicLink(email: String, link: String) {
        val payload = Json.encodeToString(
            ResendEmailRequest.serializer(),
            ResendEmailRequest(from = from, to = listOf(email), subject = SUBJECT, html = htmlBody(link)),
        )

        val response = try {
            httpClient.post(EMAILS_PATH) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No response exists yet at this point, so there is no body or key to withhold: the
            // cause is the network failure itself, not anything the provider sent back.
            log.error("MAIL_SEND_FAILED the request to Resend did not complete", e)
            throw MailSendFailedException("the request to the Resend API did not complete", e)
        }

        if (!response.status.isSuccess()) {
            val status = response.status.value
            log.error("MAIL_SEND_FAILED the Resend API answered with status {}", status)
            throw MailSendFailedException(
                "the Resend API answered with status $status; its response is withheld because " +
                    "a provider error body can echo the request's Authorization header",
            )
        }
    }

    private fun htmlBody(link: String): String =
        "<p>Select this link to sign in to Mytetz:</p><p><a href=\"$link\">$link</a></p>"

    companion object {
        /** Resolved against [httpClient]'s own base URL. See the class KDoc for why. */
        internal const val EMAILS_PATH: String = "/emails"
        private const val SUBJECT: String = "Your Mytetz sign-in link"
    }
}

/**
 * Raised when [MailSender.sendMagicLink] cannot deliver the message.
 *
 * The message names only what is safe to show: an HTTP status, or a short description of a
 * network failure. It never carries an API key or a provider's response body.
 */
class MailSendFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The mail settings a deployment selects through its environment.
 *
 * [mode] is `resend` or `log`, from [MODE_ENV], and is resolved by [resolveMode]. [apiKey] is the
 * Resend API key, from [API_KEY_ENV]. [from] is the sender address, from [FROM_ENV].
 *
 * Neither [apiKey] nor [from] is checked here. The value of [mode] decides which adapter is
 * meaningful, and building that adapter from these three fields is the composition root's task,
 * not this class's.
 */
class MailConfig(
    val mode: String = resolveMode(System.getenv(MODE_ENV)),
    val apiKey: String? = System.getenv(API_KEY_ENV),
    val from: String? = System.getenv(FROM_ENV),
) {

    companion object {

        const val MODE_ENV: String = "MYTETZ_MAIL_MODE"
        const val API_KEY_ENV: String = "MYTETZ_MAIL_API_KEY"
        const val FROM_ENV: String = "MYTETZ_MAIL_FROM"

        private const val MODE_RESEND: String = "resend"
        private const val MODE_LOG: String = "log"

        /**
         * Resolves [MODE_ENV] to `resend` or `log`, and throws on every other value.
         *
         * ## Why this resolver throws instead of falling back to a default
         *
         * `GraphConfig.resolveMaxOutputTokens` and `QuotaConfig.resolveDailyExplains` each fall
         * back to a default value on a missing or bad override. `PrincipalCookieConfig
         * .resolveSigningKey` does not follow that rule. It throws instead, because no default
         * signing key is safe.
         *
         * This resolver follows `PrincipalCookieConfig.resolveSigningKey`, and for the same
         * reason: no default mail mode is safe either. A default of `log` in production writes
         * every magic link to a production log, and any reader of that log can then sign in as
         * the linked learner. A default of `resend` with no configured API key breaks every
         * sign-in silently, with no message that names the cause.
         *
         * The safe failure is a refusal to start. An operator sees a boot failure at once. An
         * operator does not see a silent leak, and does not see a silent outage.
         *
         * The value is trimmed and lower-cased before the check. A hand-edited `.env` file, and
         * `fly secrets set`, both routinely leave a stray space or a stray case around a value.
         */
        internal fun resolveMode(raw: String?): String {
            val mode = raw?.trim()?.lowercase().orEmpty()
            check(mode == MODE_RESEND || mode == MODE_LOG) {
                "$MODE_ENV must be '$MODE_RESEND' or '$MODE_LOG', was " +
                    (raw?.let { "'$it'" } ?: "unset") +
                    ". There is no default value: a wrong value must stop the server, rather " +
                    "than silently drop sign-in mail or silently print a magic link to a " +
                    "production log."
            }
            return mode
        }
    }
}
