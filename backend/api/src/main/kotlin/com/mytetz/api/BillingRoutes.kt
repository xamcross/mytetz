package com.mytetz.api

import com.mytetz.account.AccountService
import com.mytetz.account.MagicLinkService
import com.mytetz.billing.BillingService
import com.mytetz.billing.FreemiusConfig
import com.mytetz.billing.FreemiusEvent
import com.mytetz.billing.FreemiusWebhook
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URLEncoder

private val log = LoggerFactory.getLogger("com.mytetz.api.BillingRoutes")

/** Freemius signs a webhook request under this header. Confirmed from the vendor documentation. */
private const val SIGNATURE_HEADER: String = "X-Signature"

/**
 * The largest body `POST /api/billing/webhook` reads.
 *
 * A Freemius event is a handful of short fields — smaller than an explain span — so this is
 * generous. Enforced on `Content-Length` **before** the raw body is read, for the reason
 * `MAX_SESSION_BODY_BYTES` gives: by the time anything downstream can object, the body has
 * already been buffered.
 */
const val MAX_WEBHOOK_BODY_BYTES: Long = 16_384

@Serializable
data class CheckoutResponse(val url: String)

/**
 * `POST /api/billing/checkout` and `POST /api/billing/webhook`.
 *
 * ## The checkout link carries an email, and nothing else
 *
 * Freemius documents no arbitrary metadata parameter on a checkout link — only
 * `affiliate_user_id`, which is not this. So this route builds the plain URL the class brief
 * gives verbatim, with the signed-in learner's own email as `user_email` and `readonly_user=true`
 * so the learner cannot change that address at the till. No API call opens a session; the string
 * is the whole answer.
 *
 * ## The webhook route reads the raw body before anything parses it
 *
 * `call.receiveChannel().toByteArray()` runs before [FreemiusWebhook.parse]. A route that let
 * content negotiation deserialise the body first would verify a signature against bytes Ktor's
 * own JSON converter re-encoded, which is not what Freemius signed — see [FreemiusWebhook]'s own
 * KDoc on exactly this hazard.
 *
 * ## Nothing the browser sends back is trusted
 *
 * There is no `GET /api/billing/return` here and none is needed: the frontend's own return-url
 * handling only tells the app to re-fetch `GET /api/account`. The webhook, verified against
 * [FreemiusConfig.secretKey], is the only source of truth this route ever writes from.
 *
 * ## The email-to-user resolver
 *
 * `BillingService.apply` keeps a Freemius event's own `userReference` as its first choice, since
 * the field may yet exist under a name an operator confirms. When it is absent, this route
 * resolves the event's email to a user id itself — `:backend:billing` must not depend on
 * `:backend:account`, so that lookup cannot live there. [MagicLinkService.normaliseEmail] runs on
 * both sides of the join: on the checkout route, against the signed-in learner's own stored
 * address, and on the webhook route, against the address the event carries.
 * [AccountService.findByEmail] itself does no normalisation; both calls happen here, so the two
 * sides cannot drift apart. An event whose resolved id names no stored subscription still reaches
 * [BillingService.apply] unchanged, which logs `BILLING_UNKNOWN_USER` and changes nothing — the
 * correct answer for a learner who paid with an address they never signed in with.
 *
 * [freemiusConfig] is a factory, and not the built value, for the reason `sessions` carries in
 * `SessionRoutes.kt`: `Components.freemiusConfig` is `by lazy` on a chain that throws when a
 * Freemius variable is missing, and reading it here would force that chain while
 * `Application.module()` is still being configured — taking the catalogue down for a deployment
 * that has no Freemius account yet.
 */
fun Route.billingRoutes(
    account: AccountService,
    billing: BillingService,
    freemiusConfig: () -> FreemiusConfig,
    cookies: PrincipalCookieConfig,
) {

    post("/api/billing/checkout") {
        val user = Principals.readSessionId(call, cookies)?.let { account.resolveSession(it) }
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("SIGN_IN_REQUIRED", "sign in to subscribe"))
            return@post
        }

        val config = freemiusConfig()
        // Falls back to the stored address on the rare row a normalisation refuses — an account
        // created before this rule existed, for instance — rather than answering an error for a
        // learner who is trying to pay.
        val email = MagicLinkService.normaliseEmail(user.email) ?: user.email
        val encodedEmail = URLEncoder.encode(email, Charsets.UTF_8)
        val url = "https://checkout.freemius.com/product/${config.productId}/plan/${config.planId}/" +
            "?user_email=$encodedEmail&readonly_user=true"

        call.respond(CheckoutResponse(url))
    }

    post("/api/billing/webhook") {
        if (!call.webhookBodyIsSmallEnough()) return@post

        val rawBody = call.receiveChannel().toByteArray()
        val signature = call.request.headers[SIGNATURE_HEADER]
        val config = freemiusConfig()

        if (!FreemiusWebhook.verify(rawBody, signature, config.secretKey)) {
            // Never the header, the body or the key: a signature that fails to verify is not
            // proof of anything about the bytes that produced it, and logging them would put an
            // attacker-controlled payload straight into the log.
            log.warn("WEBHOOK_SIGNATURE_MISMATCH")
            call.respond(HttpStatusCode.Unauthorized, ApiError("SIGNATURE_INVALID", "the webhook signature did not verify"))
            return@post
        }

        val event = FreemiusWebhook.parse(rawBody)
        val resolved = resolveUserReference(event) { email ->
            MagicLinkService.normaliseEmail(email)?.let { account.findByEmail(it)?.id }
        }
        billing.apply(resolved)

        // Always 204 once the signature verifies — for a first payment, for a duplicate id
        // BillingService.apply's own de-duplication refuses, and for an event BillingService.apply
        // refuses for any other reason. Freemius gets one answer either way; the log line and the
        // operator alert tokens BillingService.apply already carries are where the difference
        // shows up.
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Resolves [event] to the event [BillingService.apply] should actually see.
 *
 * [event] is returned unchanged when it already carries a [FreemiusEvent.userReference], and when
 * it carries no email to resolve. Otherwise [resolveUserId] — a `suspend (String) -> String?` the
 * caller supplies — is asked to turn [FreemiusEvent.email] into a user id. A null answer, meaning
 * no stored account matches, is not corrected here: the unresolved [event] still reaches
 * [BillingService.apply], which logs `BILLING_UNKNOWN_USER` for a null `userReference` and changes
 * nothing — the one place that rule needs to live.
 */
private suspend fun resolveUserReference(
    event: FreemiusEvent,
    resolveUserId: suspend (String) -> String?,
): FreemiusEvent {
    if (event.userReference != null) return event
    val email = event.email ?: return event
    val userId = resolveUserId(email) ?: return event
    return event.copy(userReference = userId)
}

/** False once a refusal has been sent. See [MAX_WEBHOOK_BODY_BYTES]. */
private suspend fun ApplicationCall.webhookBodyIsSmallEnough(): Boolean {
    val declared = request.contentLength()
    if (declared != null && declared <= MAX_WEBHOOK_BODY_BYTES) return true
    respond(
        HttpStatusCode.PayloadTooLarge,
        ApiError("PAYLOAD_TOO_LARGE", "a webhook body must be under $MAX_WEBHOOK_BODY_BYTES bytes"),
    )
    return false
}
