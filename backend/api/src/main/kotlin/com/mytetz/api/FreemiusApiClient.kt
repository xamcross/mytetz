package com.mytetz.api

import com.mytetz.billing.FreemiusSubscriptionState
import com.mytetz.billing.Reconciliation
import com.mytetz.billing.Subscription
import com.mytetz.billing.SubscriptionStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.mytetz.api.FreemiusApiClient")

/**
 * The credential the Freemius Developer API needs for a query request. Distinct from
 * [com.mytetz.billing.FreemiusConfig.secretKey], which signs a webhook and nothing else.
 *
 * This is not a guess. The vendor publishes its own backend SDK at
 * `github.com/Freemius/freemius-js`, and `packages/sdk/src/Freemius.ts` there declares a config
 * type carrying `apiKey` and `secretKey` as two separate fields:
 * `this.api = new ApiService(productId, apiKey, secretKey, publicKey)`. `ApiService` signs every
 * REST request `Authorization: Bearer ${apiKey}` (`packages/sdk/src/api/client.ts`), and a
 * *different* class, `WebhookService`, takes `secretKey` to verify a webhook. So a deployment that
 * wants reconciliation to make a real request needs a second credential, from the same product's
 * dashboard "API Token" tab, and `FREEMIUS_SECRET_KEY` cannot stand in for it.
 */
data class FreemiusApiConfig(
    val apiKey: String = resolveRequired(API_KEY_ENV, System.getenv(API_KEY_ENV)),
    val productId: String = resolveRequired(PRODUCT_ID_ENV, System.getenv(PRODUCT_ID_ENV)),
) {

    /** See `FreemiusConfig.toString` in `:backend:billing` for why this override exists. */
    override fun toString(): String = "FreemiusApiConfig(apiKey=REDACTED, productId=$productId)"

    companion object {

        const val API_KEY_ENV: String = "FREEMIUS_API_KEY"

        /**
         * The same variable name `com.mytetz.billing.FreemiusConfig.PRODUCT_ID_ENV` reads. One
         * product has one id; an operator sets `FREEMIUS_PRODUCT_ID` once, and this class reads
         * it again through its own [System.getenv] call rather than sharing a value with
         * `FreemiusConfig` — the two configs stay on separate `by lazy` chains in [Components],
         * so a deployment can turn reconciliation on, or off, without touching the checkout and
         * webhook routes' own credential at all.
         */
        const val PRODUCT_ID_ENV: String = "FREEMIUS_PRODUCT_ID"

        internal fun resolveRequired(name: String, raw: String?): String =
            raw?.trim()?.takeIf { it.isNotEmpty() } ?: error("$name is not set")
    }
}

/**
 * The wire shape of a Freemius subscription resource, decoded into only the fields
 * [deriveState] reads.
 *
 * Confirmed against the vendor's own generated OpenAPI type declarations in
 * `packages/sdk/src/api/schema.d.ts` (`github.com/Freemius/freemius-js`), not guessed. That file
 * documents no explicit subscription "status" field at all — [deriveState] derives one from
 * [canceledAt], [failedPayments] and [nextPayment], the three fields that actually exist.
 */
@Serializable
internal data class FreemiusSubscriptionResource(
    @SerialName("next_payment") val nextPayment: String? = null,
    @SerialName("canceled_at") val canceledAt: String? = null,
    @SerialName("failed_payments") val failedPayments: Int? = null,
)

/**
 * The `date-time` format every timestamp on [FreemiusSubscriptionResource] uses — confirmed from
 * the schema's own `@example 2025-01-01 00:00:00`: no `T` separator and no timezone offset. This
 * project assumes UTC, the same assumption `EpochMillisAsBsonDateTime` and every other clock
 * reading in this codebase already make.
 */
private val FREEMIUS_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Parses one Freemius timestamp, or answers null for a blank or an unparseable one. */
internal fun parseFreemiusDateTime(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
        LocalDateTime.parse(raw, FREEMIUS_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * Derives a [SubscriptionStatus] from [resource] as it stands at [nowEpochMillis], since the
 * vendor's schema carries no status field of its own.
 *
 * This mapping is a best effort, not a confirmed one, and [Reconciliation.reconcile]'s own
 * fail-safe rule is what keeps a wrong guess here from being able to downgrade a paying learner:
 * only a derived [SubscriptionStatus.ACTIVE] is ever written automatically. A derived
 * [SubscriptionStatus.CANCELLED], [SubscriptionStatus.PAST_DUE] or [SubscriptionStatus.EXPIRED]
 * is logged under `BILLING_DRIFT` for an operator, never applied on this function's word alone.
 *
 * ## The order is deliberate, and it favours a grant over a denial
 *
 * A future [FreemiusSubscriptionResource.nextPayment] derives [SubscriptionStatus.ACTIVE]
 * **whatever [FreemiusSubscriptionResource.failedPayments] holds.** [failedPayments] is
 * documented only as "Number of failed payments associated with the subscription", with no
 * stated reset rule, so this project cannot confirm it clears on a successful renewal. Checking
 * it first would then read as a **cumulative** count: a learner who failed one payment and later
 * renewed would derive [SubscriptionStatus.PAST_DUE] for ever, and the fail-safe rule above would
 * never let the correction that restores their access — a fetched `ACTIVE` — apply. That is the
 * exact case reconciliation exists to fix, so it is the one order this function must not take.
 *
 * The cost is accepted, not hidden: a subscription inside a dunning retry window — one failed
 * payment, one future retry date already scheduled — derives `ACTIVE` and keeps access it has not
 * yet paid for, until an operator reads a later `BILLING_DRIFT` line. That is the same direction
 * every part of the fail-safe rule already runs: toward a grant, never toward a denial.
 *
 * That `BILLING_DRIFT` line is why [resource]'s [FreemiusSubscriptionResource.failedPayments]
 * rides along on the returned [FreemiusSubscriptionState] even though it plays no further part in
 * this function's own decision: a dunning-window `ACTIVE` and a genuine renewal `ACTIVE` look
 * identical without it, and an operator reading the log needs to tell the two apart.
 */
internal fun deriveState(resource: FreemiusSubscriptionResource, nowEpochMillis: Long): FreemiusSubscriptionState {
    val periodEnd = parseFreemiusDateTime(resource.nextPayment)
    val status = when {
        resource.canceledAt != null -> SubscriptionStatus.CANCELLED
        periodEnd != null && periodEnd > nowEpochMillis -> SubscriptionStatus.ACTIVE
        (resource.failedPayments ?: 0) > 0 -> SubscriptionStatus.PAST_DUE
        else -> SubscriptionStatus.EXPIRED
    }
    return FreemiusSubscriptionState(
        status = status,
        currentPeriodEndsAtEpochMillis = periodEnd,
        failedPayments = resource.failedPayments,
    )
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * The request body of `POST /v1/products/{productId}/portal/login.json`.
 *
 * The SDK source confirms this shape: `packages/sdk/src/api/User.ts`
 * (`github.com/Freemius/freemius-js`), lines 221-234. There,
 * `retrieveHostedCustomerPortalByEmail(email: string)` sends a body with one field, `email`, to
 * the same path this class calls. The generated type `packages/sdk/src/api/schema.d.ts`, lines
 * 10733-10748, also allows an `id` field, as the other way to name a learner. This class never
 * sends an id. [FreemiusApiClient.fetchPortalLink] resolves a learner only by the email from the
 * signed-in session.
 */
@Serializable
private data class FreemiusPortalLoginRequest(val email: String)

/**
 * The `201` response body of `POST /v1/products/{productId}/portal/login.json`. This class
 * decodes only the field [FreemiusApiClient.fetchPortalLink] reads.
 *
 * The generated type `packages/sdk/src/api/schema.d.ts`, lines 10749-10766, confirms this shape.
 * The `products/generate-portal-login-link` operation states one success status, `201`. Its body
 * carries two optional fields, `token` and `link`. [link] defaults to null for that reason. This
 * default is not the defect issue #89 fixed in `AccountView`: that fix covers a field this
 * application's own route encodes back to the browser. This class only decodes a vendor body. It
 * never re-encodes this type.
 */
@Serializable
internal data class FreemiusPortalLoginResponse(val link: String? = null)

/**
 * Calls Freemius's own subscription-retrieve endpoint, for [Reconciliation.reconcile]'s
 * `fetchState` seam.
 *
 * Every part of this call is confirmed against the vendor's own published SDK source
 * (`github.com/Freemius/freemius-js`), not assumed:
 *
 * - The base url, `https://fast-api.freemius.com`, and the path,
 *   `/v1/products/{productId}/subscriptions/{subscriptionId}.json` — both from
 *   `packages/sdk/src/services/ApiService.ts` and `packages/sdk/src/api/Subscription.ts`.
 * - The `Authorization: Bearer {apiKey}` scheme, from `packages/sdk/src/api/client.ts`.
 * - A 2xx status is the only success signal the vendor's own SDK checks
 *   (`ApiBase.isGoodResponse`); this class follows the same rule.
 *
 * [httpClient] is injected, the same division of labour `ResendMailSender` and `GoogleOAuth`
 * already use in `:backend:account`: a production caller passes a real engine, and a test passes
 * a `MockEngine`. [apiConfig] is resolved once, by [Components], before this class is built —
 * this class never re-reads the environment itself. [clock] carries the same default every other
 * clock reading in this codebase uses, and a test overrides it so [deriveState]'s "future" check
 * does not depend on when the test happens to run.
 */
class FreemiusApiClient(
    private val httpClient: HttpClient,
    private val apiConfig: FreemiusApiConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * [subscription]'s current state at Freemius, or null.
     *
     * Answers null, and makes no request, for a row with no [Subscription.freemiusSubscriptionId]
     * — there is nothing to ask about. Answers null for a request that does not complete, for a
     * non-2xx status, and for a body this class cannot decode; every one of those is logged, and
     * none of them raises, so one row's failure cannot stop
     * [Reconciliation.reconcile]'s own per-row error handling from doing its job for the rest of
     * the sweep.
     */
    suspend fun fetchState(subscription: Subscription): FreemiusSubscriptionState? {
        val subscriptionId = subscription.freemiusSubscriptionId ?: return null

        return try {
            val response = httpClient.get(
                "$BASE_URL/v1/products/${apiConfig.productId}/subscriptions/$subscriptionId.json",
            ) {
                header(HttpHeaders.Authorization, "Bearer ${apiConfig.apiKey}")
            }

            if (!response.status.isSuccess()) {
                log.warn(
                    "the Freemius subscription lookup for user {} answered with status {}",
                    subscription.userId,
                    response.status.value,
                )
                return null
            }

            deriveState(json.decodeFromString<FreemiusSubscriptionResource>(response.bodyAsText()), clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Neither the response body nor the caught exception's own message ever reaches this
            // line — only the exception's class name does. A decode failure's message can quote a
            // snippet of the body it failed to parse, and the vendor's schema holds personal data
            // (ip, zip_postal_code, vat_id) that snippet could carry. Passing [e] itself to log.warn
            // would print that message and a stack trace built from it, so this logs a string we
            // built instead of the exception. `e.javaClass.name` and not `e::class.simpleName`:
            // the latter is nullable and answers null for an anonymous class, which would erase
            // the one diagnostic this line exists to keep.
            log.warn(
                "the Freemius subscription lookup for user {} did not complete: {}",
                subscription.userId,
                e.javaClass.name,
            )
            null
        }
    }

    /**
     * The hosted Freemius customer portal link for the learner at [email], or null.
     *
     * `BillingRoutes.kt`'s `POST /api/billing/portal` calls this function. The portal lets a
     * signed-in learner cancel a subscription, change a payment method, or read an invoice. The
     * portal page belongs to Freemius. This project builds no such page itself.
     *
     * The vendor's own published SDK source confirms every part of this call
     * (`github.com/Freemius/freemius-js`). This function guesses nothing:
     *
     * - The method, `POST`, the path, `/v1/products/{productId}/portal/login.json`, and the body,
     *   `{ email }`. The source: `packages/sdk/src/api/User.ts`, the function
     *   `retrieveHostedCustomerPortalByEmail`, lines 221-234.
     * - The base url, `https://fast-api.freemius.com`. The source: the same file [fetchState]
     *   already cites for [BASE_URL], `packages/sdk/src/services/ApiService.ts`, line 13.
     * - The `Authorization: Bearer {apiKey}` scheme. The source: `packages/sdk/src/api/client.ts`,
     *   the same source [fetchState] cites.
     * - The success status, `201`, and the response field, `link`. The source: the generated type
     *   `packages/sdk/src/api/schema.d.ts`, the `products/generate-portal-login-link` operation,
     *   lines 10749-10766. This function still checks with [io.ktor.http.isSuccess], the same 2xx
     *   range [fetchState] checks. It does not pin the exact code `201`. The vendor's own SDK does
     *   the same: `ApiBase.isGoodResponse` also checks a 2xx range, not one exact code.
     *
     * ## The vendor's documentation page names a different host
     *
     * The page `freemius.com/help/documentation/saas/saas-integration/` shows this same call
     * against `api.freemius.com`, not `fast-api.freemius.com`. This function follows the SDK
     * source instead. [fetchState] makes the same choice, for the same reason: the SDK is the one
     * artefact this project can read line by line. This deployment's [FreemiusApiClient] already
     * calls `fast-api.freemius.com` for every other request.
     *
     * ## The link never reaches the log
     *
     * The returned link signs [email]'s learner straight in to the vendor portal. No further
     * check follows. This function, and each of its failure paths, never pass the link, the
     * request body, or a caught exception's own message to [log]. Only a status code or an
     * exception's class name reaches the log, the same rule [fetchState] states at length.
     * [isAcceptableLink] is the one gate a fetched link passes before this function returns it.
     *
     * This function answers null for a request that does not complete, for a non-2xx status, for
     * a body it cannot decode, for a body with no `link`, and for a `link` that does not start
     * with `https://`. A learner who has no subscription to manage is the expected case behind
     * more than one of these. `BillingRoutes.kt` answers each of them the same way: `404
     * NO_SUBSCRIPTION`.
     */
    suspend fun fetchPortalLink(email: String): String? {
        return try {
            val response = httpClient.post(
                "$BASE_URL/v1/products/${apiConfig.productId}/portal/login.json",
            ) {
                header(HttpHeaders.Authorization, "Bearer ${apiConfig.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(FreemiusPortalLoginRequest(email)))
            }

            if (!response.status.isSuccess()) {
                log.warn("the Freemius portal login request answered with status {}", response.status.value)
                return null
            }

            val link = json.decodeFromString<FreemiusPortalLoginResponse>(response.bodyAsText()).link
            isAcceptableLink(link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // fetchState's own comment states the same rule for this line shape. Only the
            // exception's class name reaches the log. The message and the stack trace never do.
            log.warn("the Freemius portal login request did not complete: {}", e.javaClass.name)
            null
        }
    }

    companion object {
        internal const val BASE_URL: String = "https://fast-api.freemius.com"

        /**
         * [link], when it starts with `https://`. Null for each other value.
         *
         * The vendor's schema documents [link] as `Format: uri`. It gives no promise of a scheme.
         * This application redirects a learner's browser to the link. The link must not come back
         * `http://`, or as any value that is not a real, secure vendor url. Issue #90, step 4,
         * states this rule.
         */
        internal fun isAcceptableLink(link: String?): String? = link?.takeIf { it.startsWith("https://") }
    }
}
