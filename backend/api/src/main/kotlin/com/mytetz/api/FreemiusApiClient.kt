package com.mytetz.api

import com.mytetz.billing.FreemiusSubscriptionState
import com.mytetz.billing.Reconciliation
import com.mytetz.billing.Subscription
import com.mytetz.billing.SubscriptionStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 * Derives a [SubscriptionStatus] from [resource], since the vendor's schema carries no status
 * field of its own.
 *
 * This mapping is a best effort, not a confirmed one, and [Reconciliation.reconcile]'s own
 * fail-safe rule is what keeps a wrong guess here from being able to downgrade a paying learner:
 * only a derived [SubscriptionStatus.ACTIVE] is ever written automatically. A derived
 * [SubscriptionStatus.CANCELLED], [SubscriptionStatus.PAST_DUE] or [SubscriptionStatus.EXPIRED]
 * is logged under `BILLING_DRIFT` for an operator, never applied on this function's word alone.
 */
internal fun deriveState(resource: FreemiusSubscriptionResource): FreemiusSubscriptionState {
    val status = when {
        resource.canceledAt != null -> SubscriptionStatus.CANCELLED
        (resource.failedPayments ?: 0) > 0 -> SubscriptionStatus.PAST_DUE
        resource.nextPayment != null -> SubscriptionStatus.ACTIVE
        else -> SubscriptionStatus.EXPIRED
    }
    return FreemiusSubscriptionState(
        status = status,
        currentPeriodEndsAtEpochMillis = parseFreemiusDateTime(resource.nextPayment),
    )
}

private val json = Json { ignoreUnknownKeys = true }

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
 * this class never re-reads the environment itself.
 */
class FreemiusApiClient(
    private val httpClient: HttpClient,
    private val apiConfig: FreemiusApiConfig,
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

            deriveState(json.decodeFromString<FreemiusSubscriptionResource>(response.bodyAsText()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never the response body: a Freemius error page can echo request details back, the
            // same reasoning ResendMailSender's own KDoc gives for withholding one.
            log.warn("the Freemius subscription lookup for user {} did not complete", subscription.userId, e)
            null
        }
    }

    companion object {
        internal const val BASE_URL: String = "https://fast-api.freemius.com"
    }
}
