package com.mytetz.billing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
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
 * [userReference] is our own user id. Task 12 sends it to Freemius as the checkout reference, and
 * Freemius returns it unchanged on every later event for that same checkout. A null value, or a
 * value that names no stored user, means [BillingService.apply] has no row to change.
 */
data class FreemiusEvent(
    val id: String,
    val type: String,
    val userReference: String?,
    val freemiusUserId: String?,
    val freemiusSubscriptionId: String?,
    val periodEndsAtEpochMillis: Long?,
    val occurredAtEpochMillis: Long,
)

/**
 * The wire shape of one Freemius webhook payload.
 *
 * Freemius does not publish this schema anywhere this task could confirm it against. Every
 * [SerialName] below is a best guess, and every one of them lives in this one type so an operator
 * has a single place to check. **Before this product takes a real payment, an operator must
 * capture one real webhook from the Freemius sandbox and confirm every name here against it.** A
 * wrong guess on an optional field does not raise an error: the field silently decodes to null.
 * [id], [type] and [occurredAtEpochMillis] carry no default, so a wrong guess on one of those
 * three raises instead, which is why [FreemiusWebhook.parse] treats only those three as required.
 */
@Serializable
internal data class FreemiusWebhookPayload(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("created") val occurredAtEpochMillis: Long,
    @SerialName("custom") val userReference: String? = null,
    @SerialName("user_id") val freemiusUserId: String? = null,
    @SerialName("subscription_id") val freemiusSubscriptionId: String? = null,
    @SerialName("period_end") val periodEndsAtEpochMillis: Long? = null,
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
     * [FreemiusWebhookPayload.occurredAtEpochMillis] carry no default, so a payload missing any of
     * them raises a [kotlinx.serialization.SerializationException] here rather than handing back
     * an event with a null id — the change that [BillingService.apply] would then never record and
     * never complain about.
     */
    fun parse(rawBody: ByteArray): FreemiusEvent {
        val payload = json.decodeFromString<FreemiusWebhookPayload>(rawBody.toString(Charsets.UTF_8))
        return FreemiusEvent(
            id = payload.id,
            type = payload.type,
            userReference = payload.userReference,
            freemiusUserId = payload.freemiusUserId,
            freemiusSubscriptionId = payload.freemiusSubscriptionId,
            periodEndsAtEpochMillis = payload.periodEndsAtEpochMillis,
            occurredAtEpochMillis = payload.occurredAtEpochMillis,
        )
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
