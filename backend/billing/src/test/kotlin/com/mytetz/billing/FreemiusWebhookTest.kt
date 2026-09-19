package com.mytetz.billing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FreemiusWebhook.verify] and [FreemiusWebhook.parse] are pure functions of their byte input, so
 * every test here runs with no database and no coroutine.
 *
 * [hmacLowerHex] is this file's own, independent computation of the signature the Freemius
 * documentation describes: HMAC-SHA256 over the raw body, keyed by the secret, encoded as
 * lowercase hexadecimal. A test that instead asked [FreemiusWebhook] to sign its own fixture
 * would only prove the production code agrees with itself, never that it agrees with the vendor.
 */
class FreemiusWebhookTest {

    companion object {
        private const val SECRET_KEY = "a-test-secret-key"

        /** `2025-01-01 00:00:00` UTC, computed independently with `date -u -d ... +%s`. */
        private const val JAN_1_2025_UTC_EPOCH_MILLIS = 1_735_689_600_000L

        /** `2025-02-01 00:00:00` UTC, computed the same independent way. */
        private const val FEB_1_2025_UTC_EPOCH_MILLIS = 1_738_368_000_000L

        /** `2025-12-31 23:59:59` UTC, computed the same independent way. */
        private const val DEC_31_2025_UTC_EPOCH_MILLIS = 1_767_225_599_000L

        /**
         * One `subscription.created` event in the exact shape
         * `packages/sdk/src/webhook/subscription.events.ts` declares. See
         * [FreemiusWebhookPayload]'s own KDoc for the three source files this shape comes from.
         */
        private val VENDOR_SHAPED_BODY = """
            {
              "id": "evt-1",
              "type": "subscription.created",
              "created": "2025-01-01 00:00:00",
              "objects": {
                "user": { "id": "1001", "email": "learner@example.com" },
                "subscription": { "id": "2001", "next_payment": "2025-02-01 00:00:00" },
                "license": { "id": "3001", "expiration": "2025-12-31 23:59:59" }
              },
              "data": { "subscription_id": "2001", "license_id": "3001" }
            }
        """.trimIndent()
    }

    private fun hmacLowerHex(rawBody: ByteArray, secretKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(rawBody).joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ verify

    @Test
    fun `a correct signature verifies`() {
        val body = VENDOR_SHAPED_BODY.toByteArray(Charsets.UTF_8)
        val signature = hmacLowerHex(body, SECRET_KEY)

        assertTrue(FreemiusWebhook.verify(body, signature, SECRET_KEY))
    }

    @Test
    fun `an absent signature header is refused`() {
        val body = "{}".toByteArray(Charsets.UTF_8)

        assertFalse(FreemiusWebhook.verify(body, null, SECRET_KEY))
    }

    @Test
    fun `a blank signature header is refused`() {
        val body = "{}".toByteArray(Charsets.UTF_8)

        assertFalse(FreemiusWebhook.verify(body, "   ", SECRET_KEY))
    }

    @Test
    fun `a wrong signature is refused`() {
        val body = VENDOR_SHAPED_BODY.toByteArray(Charsets.UTF_8)
        val signedUnderAWrongKey = hmacLowerHex(body, "a-different-secret-key")

        assertFalse(FreemiusWebhook.verify(body, signedUnderAWrongKey, SECRET_KEY))
    }

    @Test
    fun `an uppercase hex signature is refused`() {
        val body = VENDOR_SHAPED_BODY.toByteArray(Charsets.UTF_8)
        val signature = hmacLowerHex(body, SECRET_KEY)
        // A sanity check on the fixture: an all-digit or already-uppercase-invariant hex string
        // would make the assertion below pass for a reason that has nothing to do with case.
        assertTrue(signature.any { it.isLetter() }, "fixture error: the signature has no letters to case-flip")

        assertFalse(FreemiusWebhook.verify(body, signature.uppercase(), SECRET_KEY))
    }

    @Test
    fun `a signature of the wrong length is refused`() {
        val body = VENDOR_SHAPED_BODY.toByteArray(Charsets.UTF_8)
        val signature = hmacLowerHex(body, SECRET_KEY)

        assertFalse(FreemiusWebhook.verify(body, signature.dropLast(2), SECRET_KEY), "a shorter header must be refused")
        assertFalse(FreemiusWebhook.verify(body, signature + "ab", SECRET_KEY), "a longer header must be refused")
    }

    @Test
    fun `an empty secret key refuses rather than raises`() {
        // verify is public and it takes a raw String. SecretKeySpec raises IllegalArgumentException
        // on an empty key. A refusal is the correct answer for a caller that holds no key.
        val body = VENDOR_SHAPED_BODY.toByteArray(Charsets.UTF_8)
        val signature = hmacLowerHex(body, SECRET_KEY)

        assertFalse(FreemiusWebhook.verify(body, signature, ""))
    }

    @Test
    fun `a signature over a re-serialized body is refused`() {
        val original = """{ "id": "evt-1", "type": "subscription.created", "created": "2025-01-01 00:00:00" }"""
            .toByteArray(Charsets.UTF_8)
        val signature = hmacLowerHex(original, SECRET_KEY)
        // The signature must verify against the exact bytes it was computed over, or the
        // assertion below would prove nothing about re-serialization at all.
        assertTrue(FreemiusWebhook.verify(original, signature, SECRET_KEY))

        val reserialized = Json.encodeToString(
            JsonElement.serializer(),
            Json.parseToJsonElement(original.toString(Charsets.UTF_8)),
        ).toByteArray(Charsets.UTF_8)
        assertFalse(
            original.contentEquals(reserialized),
            "fixture error: re-serializing must change the bytes, or this test proves nothing",
        )

        assertFalse(FreemiusWebhook.verify(reserialized, signature, SECRET_KEY))
    }

    // ------------------------------------------------------------------ parse

    @Test
    fun `a payload missing its id raises`() {
        val body = """{"type":"subscription.created","created":"2025-01-01 00:00:00"}"""
            .toByteArray(Charsets.UTF_8)

        assertFailsWith<SerializationException> { FreemiusWebhook.parse(body) }
    }

    @Test
    fun `an unknown field in the payload is ignored`() {
        val body = (
            """{"id":"evt-1","type":"subscription.created","created":"2025-01-01 00:00:00",""" +
                """"a_field_freemius_adds_later":true}"""
            ).toByteArray(Charsets.UTF_8)

        val event = FreemiusWebhook.parse(body)

        assertEquals("evt-1", event.id)
        assertEquals("subscription.created", event.type)
        assertEquals(JAN_1_2025_UTC_EPOCH_MILLIS, event.occurredAtEpochMillis)
    }

    // ------------------------------------------------------------------ the vendor shape
    //
    // Each body here is a `subscription.created` event. Each has the exact shape
    // `packages/sdk/src/webhook/subscription.events.ts` declares: a top-level `id`, `type` and
    // `created`, one `objects` object, and one `data` object. See `FreemiusWebhookPayload`'s own
    // KDoc for the three source files this shape comes from.

    @Test
    fun `a vendor-shaped payload fills every field from its real location`() {
        val body = VENDOR_SHAPED_BODY.toByteArray(Charsets.UTF_8)

        val event = FreemiusWebhook.parse(body)

        assertEquals("evt-1", event.id)
        assertEquals("subscription.created", event.type)
        assertEquals(JAN_1_2025_UTC_EPOCH_MILLIS, event.occurredAtEpochMillis)
        assertEquals("learner@example.com", event.email)
        assertEquals("1001", event.freemiusUserId)
        assertEquals("2001", event.freemiusSubscriptionId)
        assertEquals(DEC_31_2025_UTC_EPOCH_MILLIS, event.periodEndsAtEpochMillis)
    }

    @Test
    fun `the ISO 8601 form of created gives the same instant as the space form`() {
        val spaceForm = FreemiusWebhook.parse(VENDOR_SHAPED_BODY.toByteArray(Charsets.UTF_8))
        val isoBody = VENDOR_SHAPED_BODY.replace(
            """"created":"2025-01-01 00:00:00"""",
            """"created":"2025-01-01T00:00:00Z"""",
        )

        val isoForm = FreemiusWebhook.parse(isoBody.toByteArray(Charsets.UTF_8))

        assertEquals(spaceForm.occurredAtEpochMillis, isoForm.occurredAtEpochMillis)
        assertEquals(JAN_1_2025_UTC_EPOCH_MILLIS, isoForm.occurredAtEpochMillis)
    }

    @Test
    fun `freemiusSubscriptionId falls back to objects subscription id when data carries none`() {
        val body = """
            {"id":"evt-2","type":"subscription.created","created":"2025-01-01 00:00:00",
            "objects":{"subscription":{"id":"2002"}}}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val event = FreemiusWebhook.parse(body)

        assertEquals("2002", event.freemiusSubscriptionId)
    }

    @Test
    fun `periodEndsAtEpochMillis falls back to subscription next_payment when the license has no expiration`() {
        val body = """
            {"id":"evt-3","type":"subscription.created","created":"2025-01-01 00:00:00",
            "objects":{"subscription":{"next_payment":"2025-02-01 00:00:00"},"license":{}}}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val event = FreemiusWebhook.parse(body)

        assertEquals(FEB_1_2025_UTC_EPOCH_MILLIS, event.periodEndsAtEpochMillis)
    }

    @Test
    fun `a created value in neither known date form raises`() {
        val body = """{"id":"evt-4","type":"subscription.created","created":"not a date"}"""
            .toByteArray(Charsets.UTF_8)

        assertFailsWith<SerializationException> { FreemiusWebhook.parse(body) }
    }

    // ------------------------------------------------------------------ FreemiusConfig
    //
    // Not named in the task brief's required list. Added because FreemiusConfig is new production
    // code this task creates, and its two stated rules — no field defaults silently, and no
    // message may carry a secret — are exactly the kind of rule this task's own risk section warns
    // a missing test lets slip through.

    // Each of the three tests below constructs a FreemiusConfig. A test that only called
    // resolveRequired proved the resolver alone, and left the constructor default free to hand
    // back a placeholder. These three run in an environment that sets no FREEMIUS_ variable, so
    // each default reaches the resolver. Kotlin evaluates a default argument only for a parameter
    // the caller omits, and it evaluates them in declaration order. Each test therefore passes the
    // fields ahead of the one it tests.

    @Test
    fun `a missing Freemius secret key fails construction and names the variable`() {
        val error = assertFailsWith<IllegalStateException> { FreemiusConfig() }

        assertTrue(error.message.orEmpty().contains(FreemiusConfig.SECRET_KEY_ENV))
    }

    @Test
    fun `a missing Freemius plan id fails construction and names the variable`() {
        val error = assertFailsWith<IllegalStateException> {
            FreemiusConfig(secretKey = SECRET_KEY, productId = "a-test-product-id")
        }

        assertTrue(error.message.orEmpty().contains(FreemiusConfig.PLAN_ID_ENV))
    }

    @Test
    fun `a blank Freemius value fails construction the same way a missing one does`() {
        // Both halves call the resolver directly. A shell may export FREEMIUS_PRODUCT_ID — this
        // task's own credentialed verification run does — and FreemiusConfig's own productId
        // default reads that exact variable. `FreemiusConfig(secretKey = SECRET_KEY)` would then
        // build a working config and throw nothing, and the assertion below would fail for a
        // reason that has nothing to do with a blank value. A test must not depend on the
        // developer's shell. See `FreemiusApiClientTest` and `ComponentsTest` for the same fix.
        assertFailsWith<IllegalStateException> {
            FreemiusConfig.resolveRequired(FreemiusConfig.PRODUCT_ID_ENV, "   ")
        }

        val error = assertFailsWith<IllegalStateException> {
            FreemiusConfig.resolveRequired(FreemiusConfig.PRODUCT_ID_ENV, null)
        }

        assertTrue(error.message.orEmpty().contains(FreemiusConfig.PRODUCT_ID_ENV))
    }

    @Test
    fun `resolveRequired's failure names the variable before anything else in its own message`() {
        // `com.mytetz.api.ConfigGate.buildConfiguredOrNull`, in the `:backend:api` module, reads
        // this exact message across a module boundary, by a regex that takes the first
        // variable-shaped name it finds. It never sees this test, or this class. This test is the
        // guard on its side of that boundary: it pins the one property that regex depends on, so
        // a future edit to this message fails here first.
        val message = assertFailsWith<IllegalStateException> {
            FreemiusConfig.resolveRequired(FreemiusConfig.SECRET_KEY_ENV, null)
        }.message.orEmpty()

        assertTrue(
            message.startsWith(FreemiusConfig.SECRET_KEY_ENV),
            "the variable name must lead the message: $message",
        )
    }

    @Test
    fun `a FreemiusConfig never prints its secret key`() {
        val config = FreemiusConfig(secretKey = SECRET_KEY, productId = "a-test-product-id", planId = "a-test-plan-id")

        val printed = config.toString()

        assertFalse(printed.contains(SECRET_KEY), "the secret key reached toString")
        assertTrue(printed.contains("a-test-product-id"), "toString must still name the fields that are not secret")
        assertTrue(printed.contains("a-test-plan-id"))
    }

    @Test
    fun `a present Freemius value is trimmed and used`() {
        assertEquals("abc123", FreemiusConfig.resolveRequired(FreemiusConfig.PRODUCT_ID_ENV, "  abc123\n"))
    }
}
