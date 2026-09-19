package com.mytetz.billing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Freemius signs a webhook body with this algorithm. Confirmed from the vendor documentation. */
private const val HMAC_ALGORITHM: String = "HmacSHA256"

private val HEX_DIGITS: CharArray = "0123456789abcdef".toCharArray()

/**
 * The three Freemius identifiers a deployment needs to accept a webhook and to build a checkout
 * link, read from the environment with no fallback value.
 *
 * A missing value fails construction rather than defaulting to a placeholder. A defaulted secret
 * key would verify no real webhook. A defaulted product or plan id would send a learner to the
 * wrong checkout. The failure message names the missing variable and never a value, so a log of
 * this failure can never carry the secret.
 */
data class FreemiusConfig(
    val secretKey: String = resolveRequired(SECRET_KEY_ENV, System.getenv(SECRET_KEY_ENV)),
    val productId: String = resolveRequired(PRODUCT_ID_ENV, System.getenv(PRODUCT_ID_ENV)),
    val planId: String = resolveRequired(PLAN_ID_ENV, System.getenv(PLAN_ID_ENV)),
) {

    /**
     * Names each field and prints only two of the three values.
     *
     * A `data class` generates a `toString()` that prints every field, and [secretKey] is a secret.
     * One log line, one exception message, or one debugger view then carries the key that signs
     * every webhook. This override is what keeps that key out of all three.
     */
    override fun toString(): String =
        "FreemiusConfig(secretKey=REDACTED, productId=$productId, planId=$planId)"

    companion object {

        const val SECRET_KEY_ENV: String = "FREEMIUS_SECRET_KEY"
        const val PRODUCT_ID_ENV: String = "FREEMIUS_PRODUCT_ID"
        const val PLAN_ID_ENV: String = "FREEMIUS_PLAN_ID"

        /**
         * Reads [raw] as the value of [name], or fails construction.
         *
         * The message names [name] and never [raw]. [raw] is either absent, in which case there
         * is nothing to print, or it is a secret, in which case printing it is the one thing this
         * function must never do.
         */
        internal fun resolveRequired(name: String, raw: String?): String =
            raw?.trim()?.takeIf { it.isNotEmpty() } ?: error("$name is not set")
    }
}

/**
 * One Freemius webhook event, decoded into the fields [BillingService.apply] needs.
 *
 * [userReference] is our own user id. The checkout link sends no such reference — Freemius
 * documents no arbitrary metadata parameter, only `affiliate_user_id` — so [userReference] is
 * null on every event today. It stays in this type for the field an operator may yet confirm,
 * and [BillingService.apply] keeps it as its first choice for that reason.
 *
 * [email] is the join key the checkout link actually uses: the learner's own address, sent as
 * `user_email` with `readonly_user=true` so Freemius cannot change it. The API layer resolves
 * [email] to a user id when [userReference] is absent — see `BillingRoutes.kt` — because
 * `:backend:billing` does not depend on `:backend:account` and cannot do that lookup itself.
 */
data class FreemiusEvent(
    val id: String,
    val type: String,
    val userReference: String?,
    val email: String?,
    val freemiusUserId: String?,
    val freemiusSubscriptionId: String?,
    val periodEndsAtEpochMillis: Long?,
    val occurredAtEpochMillis: Long,
)

/**
 * The wire shape of one Freemius webhook payload.
 *
 * The source is the vendor SDK, at `github.com/Freemius/freemius-js`.
 * `packages/sdk/src/webhook/events.ts` gives the three top-level fields.
 * `subscription.events.ts` and `license.events.ts` give the shape of [objects] and [data].
 *
 * [id], [type] and [createdRaw] carry no default value. A payload without one of these three
 * fields raises an error in [FreemiusWebhook.parse]. [FreemiusWebhook.parse] treats only these
 * three fields as required.
 *
 * Every field of [objects] and [data] is optional. The event type in [type] decides which fields
 * Freemius sends. For example, a `subscription.created` event carries a `subscription` object,
 * and a `license.created` event does not. This one type reads every event type this deployment
 * receives. A field this deployment does not use yet is never a reason for
 * [FreemiusWebhook.parse] to raise an error.
 */
@Serializable
internal data class FreemiusWebhookPayload(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("created") val createdRaw: String,
    @SerialName("objects") val objects: FreemiusWebhookObjects? = null,
    @SerialName("data") val data: FreemiusWebhookData? = null,
)

/**
 * The `objects` half of a Freemius webhook payload. It names the entities the event is about.
 *
 * Every field is optional. `packages/sdk/src/webhook/subscription.events.ts` and
 * `license.events.ts` each name a different subset of these three fields for each event type.
 * Neither file names `install` for an event this deployment reads today.
 */
@Serializable
internal data class FreemiusWebhookObjects(
    @SerialName("user") val user: FreemiusWebhookUser? = null,
    @SerialName("subscription") val subscription: FreemiusWebhookSubscription? = null,
    @SerialName("license") val license: FreemiusWebhookLicense? = null,
)

/** The one entity under `objects.user` this deployment reads. See `schema.d.ts`, `User`. */
@Serializable
internal data class FreemiusWebhookUser(
    @SerialName("id") val id: String? = null,
    @SerialName("email") val email: String? = null,
)

/** The one entity under `objects.subscription` this deployment reads. See `schema.d.ts`, `Subscription`. */
@Serializable
internal data class FreemiusWebhookSubscription(
    @SerialName("id") val id: String? = null,
    @SerialName("next_payment") val nextPayment: String? = null,
)

/** The one entity under `objects.license` this deployment reads. See `schema.d.ts`, `License`. */
@Serializable
internal data class FreemiusWebhookLicense(
    @SerialName("expiration") val expiration: String? = null,
)

/**
 * The `data` half of a Freemius webhook payload. It names the event-specific fields
 * `packages/sdk/src/webhook/subscription.events.ts` declares outside `objects`.
 */
@Serializable
internal data class FreemiusWebhookData(
    @SerialName("subscription_id") val subscriptionId: String? = null,
)

/**
 * Verifies a Freemius webhook's signature, and decodes its body.
 *
 * Both functions take the request's raw bytes, and never a re-encoded string. Freemius signs the
 * exact bytes it sent. A JSON parser is free to reorder fields or to change whitespace when it
 * writes a value back out, so verifying anything but the original bytes verifies a message
 * Freemius never signed.
 */
object FreemiusWebhook {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reports whether [signatureHeader] is the HMAC-SHA256 of [rawBody], keyed by [secretKey] and
     * encoded as lowercase hexadecimal — the exact scheme the Freemius documentation states.
     *
     * A null or a blank header is refused before any comparison runs. Every other header, whether
     * it holds the wrong number of characters or the right characters in the wrong case, reaches
     * the same [constantTimeEquals] comparison a correct header would reach. A length check ahead
     * of that comparison would let a caller learn the true signature's length one guess at a time;
     * there is no such check here.
     *
     * An empty [secretKey] is refused too. This function is public and it takes a raw `String`, so
     * a caller can reach it with a key [FreemiusConfig] never built. `SecretKeySpec` raises
     * `IllegalArgumentException` on an empty key. A refusal is the correct answer here, and it also
     * keeps the key out of an exception this function would otherwise let through.
     */
    fun verify(rawBody: ByteArray, signatureHeader: String?, secretKey: String): Boolean {
        if (signatureHeader.isNullOrBlank()) return false
        if (secretKey.isEmpty()) return false

        val expected = hmacLowerHex(rawBody, secretKey)
        return constantTimeEquals(expected, signatureHeader)
    }

    /**
     * Decodes [rawBody] into a [FreemiusEvent].
     *
     * [FreemiusWebhookPayload.id], [FreemiusWebhookPayload.type] and
     * [FreemiusWebhookPayload.createdRaw] carry no default value. A payload without one of these
     * three fields raises a [SerializationException] here. A null id would let
     * [BillingService.apply] never record the change, and never report it.
     * [parseFreemiusDate] raises the same [SerializationException] for a `created` value in
     * neither date form it accepts. An unreadable date is not a usable event. The caller of
     * [parse] sees one exception type for both failures.
     *
     * [FreemiusEvent.userReference] is always null here. Freemius documents no field that carries
     * it — see [FreemiusEvent]'s own KDoc. This deployment's only source for it is the email
     * lookup `BillingRoutes.kt` runs after [parse] returns.
     *
     * [FreemiusEvent.email] and [FreemiusEvent.freemiusUserId] come from `objects.user`.
     * [FreemiusEvent.freemiusSubscriptionId] prefers `data.subscription_id`. It falls back to
     * `objects.subscription.id` when the event carries no `data.subscription_id`. A
     * `subscription.renewal.failed` event, for one example, carries no `data` object. That event
     * still names its subscription under `objects`.
     *
     * [FreemiusEvent.periodEndsAtEpochMillis] prefers `objects.license.expiration` over
     * `objects.subscription.next_payment`. The license controls the learner's entitlement. The
     * subscription's own next payment date is a close estimate of the same date. [parse] reads
     * it only when the event carries no license.
     */
    fun parse(rawBody: ByteArray): FreemiusEvent {
        val payload = json.decodeFromString<FreemiusWebhookPayload>(rawBody.toString(Charsets.UTF_8))
        val user = payload.objects?.user
        val subscription = payload.objects?.subscription
        val license = payload.objects?.license

        val subscriptionId = payload.data?.subscriptionId ?: subscription?.id
        val periodEnd = license?.expiration ?: subscription?.nextPayment

        return FreemiusEvent(
            id = payload.id,
            type = payload.type,
            userReference = null,
            email = user?.email,
            freemiusUserId = user?.id,
            freemiusSubscriptionId = subscriptionId,
            periodEndsAtEpochMillis = periodEnd?.let(::parseFreemiusDate),
            occurredAtEpochMillis = parseFreemiusDate(payload.createdRaw),
        )
    }

    /**
     * Reads [raw] as a UTC instant, in one of two date forms.
     *
     * The vendor sources give two forms for one date: the schema example form,
     * `2025-01-01 00:00:00`, and ISO 8601, `2025-01-01T00:00:00Z`. The schema example form names
     * no zone. `CommonProperties.created`, in `schema.d.ts`, states that this form is already
     * UTC. So this function reads a value with no zone as UTC, and never as the server's own
     * zone.
     *
     * [parseFreemiusDate] tries ISO 8601 first, through [Instant.parse]. That form carries its
     * own zone, so it needs no assumption. The schema example form carries no zone, so
     * [parseFreemiusDate] reads it as a [LocalDateTime] and pairs it with [ZoneOffset.UTC] by
     * hand. A [raw] value in neither form raises a [SerializationException]. A date from Freemius
     * in some third form this task did not confirm then fails the same way an unparseable field
     * always has. It never lands silently on the wrong instant.
     */
    private fun parseFreemiusDate(raw: String): Long {
        try {
            return Instant.parse(raw).toEpochMilli()
        } catch (isoFailure: DateTimeParseException) {
            try {
                return LocalDateTime.parse(raw.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (schemaExampleFailure: DateTimeParseException) {
                throw SerializationException(
                    "a Freemius date must have the form \"yyyy-MM-dd HH:mm:ss\" or ISO 8601; " +
                        "this event's date has neither form",
                    schemaExampleFailure,
                )
            }
        }
    }

    private fun hmacLowerHex(rawBody: ByteArray, secretKey: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(rawBody).toLowerHex()
    }

    private fun ByteArray.toLowerHex(): String {
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = HEX_DIGITS[value ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(chars)
    }

    /**
     * A second, deliberate copy of the compare `Principals.constantTimeEquals` already performs
     * in `:backend:api`, with that copy's own reasoning still true here: [MessageDigest.isEqual]
     * is specified to take time independent of the number of matching bytes, which `==` on a
     * `String` is not, and [b] always comes from the caller of [verify].
     *
     * The two copies do not merge into one shared function. `Principals.constantTimeEquals` is
     * `private` inside `Principals` in `:backend:api`, and `:backend:api` already depends on
     * `:backend:billing`. Importing it here would make the two modules depend on each other in a
     * circle.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
