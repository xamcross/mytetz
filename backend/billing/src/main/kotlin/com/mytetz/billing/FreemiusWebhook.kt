package com.mytetz.billing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory

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
 * The two fields of a Freemius event an operator needs, to find the event in the Freemius
 * dashboard.
 *
 * [FreemiusWebhook.identifyOrNull] reads these two fields best-effort. It reads them even from a
 * body [FreemiusWebhook.parse] cannot decode. Both fields are vendor values. Neither field is
 * personal data. A caller may log both fields. A caller must never log any other part of a
 * webhook body.
 */
data class FreemiusEventIdentity(val type: String?, val id: String?)

/**
 * Verifies a Freemius webhook's signature, and decodes its body.
 *
 * Both functions take the request's raw bytes, and never a re-encoded string. Freemius signs the
 * exact bytes it sent. A JSON parser is free to reorder fields or to change whitespace when it
 * writes a value back out, so verifying anything but the original bytes verifies a message
 * Freemius never signed.
 *
 * ## The wire shape [parse] reads
 *
 * The source is the vendor SDK, at `github.com/Freemius/freemius-js`.
 * `packages/sdk/src/webhook/events.ts` gives the three top-level fields: `id`, `type` and
 * `created`. `subscription.events.ts` and `license.events.ts` give the shape of `objects` and
 * `data`.
 *
 * [parse] reads the body as a loose JSON tree, and not as one fixed type. Three facts about the
 * vendor shape force that choice.
 *
 * - `schema.d.ts` types `EventLog.data` as `unknown`. `data` can be a string, an object, or an
 *   array. Three event types [BillingService.apply] maps — `payment.refund`,
 *   `payment.dispute.lost` and `subscription.renewal.retry` — carry no vendor type for `data` at
 *   all. `data` can then take any of the three forms on one of those events.
 * - `license.events.ts` types `'license.deleted'` with `objects: { license: false }`. An entity
 *   under `objects` is not always an object.
 * - `schema.d.ts` gives every id the type `string`, with `Format: int64`. The real JSON is not
 *   confirmed before #73. [parse] reads a JSON string or a JSON number for an id, and gives back
 *   its text either way.
 *
 * [parse] treats [FreemiusEvent.id], [FreemiusEvent.type] and
 * [FreemiusEvent.occurredAtEpochMillis] as required. It raises a [SerializationException] when
 * one of the three is absent, or is not a type it accepts. Every other field reads as absent when
 * the vendor shape does not match, and [parse] never raises for that reason. An unmapped event
 * type, a deleted license, and a field #73 has not yet confirmed must never turn a signed,
 * genuine event into a `400`.
 */
object FreemiusWebhook {

    private val log = LoggerFactory.getLogger(FreemiusWebhook::class.java)

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
     * [rawBody] must decode to a JSON object. That object must carry a readable [FreemiusEvent.id],
     * a readable [FreemiusEvent.type], and a readable `created` field. Each of these three checks
     * raises a [SerializationException] on failure. A null id would let [BillingService.apply]
     * never record the change, and never report it. The caller of [parse] sees one exception type
     * for every failure, including a `created` value in neither date form [parseFreemiusDate]
     * accepts.
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
     * [FreemiusEvent.periodEndsAtEpochMillis] comes from [resolvePeriodEnd]. Every field this
     * function reads past [FreemiusEvent.id], [FreemiusEvent.type] and
     * [FreemiusEvent.occurredAtEpochMillis] reads as absent, and never raises, when the vendor
     * shape does not match: see this object's own KDoc for the three reasons that matters.
     */
    fun parse(rawBody: ByteArray): FreemiusEvent {
        val root = Json.parseToJsonElement(rawBody.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw SerializationException("a Freemius webhook body must be a JSON object")

        val id = root.idTextOrNull("id")
            ?: throw SerializationException("a Freemius webhook body must carry a string or a number \"id\"")
        val type = root.stringOrNull("type")
            ?: throw SerializationException("a Freemius webhook body must carry a string \"type\"")
        val createdRaw = root.stringOrNull("created")
            ?: throw SerializationException("a Freemius webhook body must carry a string \"created\"")

        val objects = root["objects"] as? JsonObject
        val user = objects?.get("user") as? JsonObject
        val subscription = objects?.get("subscription") as? JsonObject
        val license = objects?.get("license") as? JsonObject
        // schema.d.ts types EventLog.data as unknown. Only its object form has a subscription_id
        // to read. A string or an array form — see this object's own KDoc — reads as absent here.
        val data = root["data"] as? JsonObject

        return FreemiusEvent(
            id = id,
            type = type,
            userReference = null,
            email = user.stringOrNull("email"),
            freemiusUserId = user?.idTextOrNull("id"),
            freemiusSubscriptionId = data?.idTextOrNull("subscription_id") ?: subscription?.idTextOrNull("id"),
            periodEndsAtEpochMillis = resolvePeriodEnd(license, subscription, type = type, id = id),
            occurredAtEpochMillis = parseFreemiusDate(createdRaw),
        )
    }

    /**
     * Reads [rawBody] best-effort, for [FreemiusEventIdentity] alone.
     *
     * A body a [parse] call already rejected reaches this function next, in `BillingRoutes.kt`'s
     * own `catch`. This function never raises. It reads what it can, and gives back null for a
     * field it cannot read — a body that is not even a JSON object, for one example, gives back
     * [FreemiusEventIdentity] with both fields null.
     */
    fun identifyOrNull(rawBody: ByteArray): FreemiusEventIdentity {
        val root = runCatching {
            Json.parseToJsonElement(rawBody.toString(Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
        return FreemiusEventIdentity(type = root?.stringOrNull("type"), id = root?.idTextOrNull("id"))
    }

    /**
     * Resolves the period end for one event, from [license] and [subscription].
     *
     * `objects.license.expiration` is the first choice: the license controls the learner's
     * entitlement. `objects.subscription.next_payment` is a close estimate of the same date, and
     * this function reads it only when the event carries no readable expiration.
     *
     * An expiration that does not parse is not the same case as an absent one. A broken date
     * must not reject a signed, genuine event. See `Entitlement.resolveActive`'s own KDoc for the
     * reason a missing period end must never lock out a learner who has just paid. So this
     * function logs one `BILLING_UNREADABLE_PERIOD_END` line for the broken date. That line names
     * [type] and [id], and no other part of the body. This function then falls back to
     * `next_payment`, the same way an absent expiration would.
     */
    private fun resolvePeriodEnd(license: JsonObject?, subscription: JsonObject?, type: String, id: String): Long? {
        val expirationRaw = license.stringOrNull("expiration")
        if (expirationRaw != null) {
            val expiration = runCatching { parseFreemiusDate(expirationRaw) }.getOrNull()
            if (expiration != null) return expiration
            log.warn("BILLING_UNREADABLE_PERIOD_END type={} id={}", type, id)
        }
        val nextPaymentRaw = subscription.stringOrNull("next_payment") ?: return null
        return runCatching { parseFreemiusDate(nextPaymentRaw) }.getOrNull()
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

    /**
     * Reads [key] from this object as a Freemius id: a JSON string or a JSON number, given back
     * as text. `schema.d.ts` gives every id the type `string`, with `Format: int64`, but the real
     * JSON is not confirmed before #73 — see this object's own KDoc. A boolean, an object, an
     * array, or a JSON null under [key] is not an id, and this function reads none of them.
     */
    private fun JsonObject?.idTextOrNull(key: String): String? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        // A JsonPrimitive that is not a JSON string is a number, a boolean, or a bare null
        // literal. JsonNull is already ruled out above, so only a boolean is left to refuse.
        if (!primitive.isString && (primitive.content == "true" || primitive.content == "false")) return null
        return primitive.content
    }

    /**
     * Reads [key] from this object as plain text, or null when [key] is absent, is a JSON null,
     * or does not hold a string, a number, or a boolean.
     */
    private fun JsonObject?.stringOrNull(key: String): String? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content
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
