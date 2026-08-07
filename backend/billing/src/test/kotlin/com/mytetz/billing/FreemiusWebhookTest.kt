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
    }

    private fun hmacLowerHex(rawBody: ByteArray, secretKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(rawBody).joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ verify

    @Test
    fun `a correct signature verifies`() {
        val body = """{"id":"evt-1","type":"subscription.created","created":1000}""".toByteArray(Charsets.UTF_8)
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
        val body = """{"id":"evt-1","type":"subscription.created","created":1000}""".toByteArray(Charsets.UTF_8)
        val signedUnderAWrongKey = hmacLowerHex(body, "a-different-secret-key")

        assertFalse(FreemiusWebhook.verify(body, signedUnderAWrongKey, SECRET_KEY))
    }

    @Test
    fun `an uppercase hex signature is refused`() {
        val body = """{"id":"evt-1","type":"subscription.created","created":1000}""".toByteArray(Charsets.UTF_8)
        val signature = hmacLowerHex(body, SECRET_KEY)
        // A sanity check on the fixture: an all-digit or already-uppercase-invariant hex string
        // would make the assertion below pass for a reason that has nothing to do with case.
        assertTrue(signature.any { it.isLetter() }, "fixture error: the signature has no letters to case-flip")

        assertFalse(FreemiusWebhook.verify(body, signature.uppercase(), SECRET_KEY))
    }

    @Test
    fun `a signature of the wrong length is refused`() {
        val body = """{"id":"evt-1","type":"subscription.created","created":1000}""".toByteArray(Charsets.UTF_8)
        val signature = hmacLowerHex(body, SECRET_KEY)

        assertFalse(FreemiusWebhook.verify(body, signature.dropLast(2), SECRET_KEY), "a shorter header must be refused")
        assertFalse(FreemiusWebhook.verify(body, signature + "ab", SECRET_KEY), "a longer header must be refused")
    }

    @Test
    fun `a signature over a re-serialized body is refused`() {
        val original = """{ "id": "evt-1", "type": "subscription.created", "created": 1000 }"""
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
        val body = """{"type":"subscription.created","created":1000}""".toByteArray(Charsets.UTF_8)

        assertFailsWith<SerializationException> { FreemiusWebhook.parse(body) }
    }

    @Test
    fun `an unknown field in the payload is ignored`() {
        val body =
            """{"id":"evt-1","type":"subscription.created","created":1000,"a_field_freemius_adds_later":true}"""
                .toByteArray(Charsets.UTF_8)

        val event = FreemiusWebhook.parse(body)

        assertEquals("evt-1", event.id)
        assertEquals("subscription.created", event.type)
        assertEquals(1000L, event.occurredAtEpochMillis)
    }

    // ------------------------------------------------------------------ FreemiusConfig
    //
    // Not named in the task brief's required list. Added because FreemiusConfig is new production
    // code this task creates, and its two stated rules — no field defaults silently, and no
    // message may carry a secret — are exactly the kind of rule this task's own risk section warns
    // a missing test lets slip through.

    @Test
    fun `a missing Freemius secret key fails construction and names the variable`() {
        val error = assertFailsWith<IllegalStateException> {
            FreemiusConfig.resolveRequired(FreemiusConfig.SECRET_KEY_ENV, null)
        }

        assertTrue(error.message.orEmpty().contains(FreemiusConfig.SECRET_KEY_ENV))
    }

    @Test
    fun `a missing Freemius plan id fails construction and names the variable`() {
        val error = assertFailsWith<IllegalStateException> {
            FreemiusConfig.resolveRequired(FreemiusConfig.PLAN_ID_ENV, null)
        }

        assertTrue(error.message.orEmpty().contains(FreemiusConfig.PLAN_ID_ENV))
    }

    @Test
    fun `a blank Freemius value fails construction the same way a missing one does`() {
        assertFailsWith<IllegalStateException> {
            FreemiusConfig.resolveRequired(FreemiusConfig.PRODUCT_ID_ENV, "   ")
        }
    }

    @Test
    fun `a present Freemius value is trimmed and used`() {
        assertEquals("abc123", FreemiusConfig.resolveRequired(FreemiusConfig.PRODUCT_ID_ENV, "  abc123\n"))
    }
}
