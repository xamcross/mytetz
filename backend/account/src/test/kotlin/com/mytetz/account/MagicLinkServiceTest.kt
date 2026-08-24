package com.mytetz.account

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bson.Document
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [MagicLinkService] against a real database, a [RecordingMailSender], and an injected
 * clock and token factory.
 *
 * [RecordingMailSender] stands in for a real mail provider. It sends nothing over the network.
 * It only records what the service asked it to send, so a test can assert on the recorded
 * address and link.
 */
class MagicLinkServiceTest {

    private class RecordingMailSender : MailSender {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun sendMagicLink(email: String, link: String) { sent += email to link }
    }

    companion object {
        private const val BASE_URL = "https://mytetz.example"
        private const val FIXED_TOKEN = "fixed-test-token-value"

        /** 2100-01-01T00:00:00Z, the same anchor instant [AccountRepositoryTest] uses. */
        private const val FUTURE = 4_102_444_800_000L
    }

    private val database = MongoTestSupport.database("magiclink")
    private val repository = AccountRepository(database)
    private val mail = RecordingMailSender()

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Document>("users").drop()
        database.getCollection<Document>("magicLinkTokens").drop()
        database.getCollection<Document>("authSessions").drop()
        repository.ensureIndexes()
        mail.sent.clear()
    }

    /** A service wired to the shared [repository] and [mail], with a fixed clock and token. */
    private fun service(now: Long = FUTURE, token: String = FIXED_TOKEN): MagicLinkService =
        MagicLinkService(
            repository = repository,
            mail = mail,
            baseUrl = BASE_URL,
            clock = { now },
            tokens = { token },
        )

    // ------------------------------------------------------------------ normaliseEmail

    @Test
    fun `an address is trimmed and lower-cased`() {
        assertEquals("alice@example.com", MagicLinkService.normaliseEmail("  Alice@Example.COM "))
    }

    @Test
    fun `a dot and a plus tag survive normalisation`() {
        // A mail provider may fold "a.b+tag@example.com" into one mailbox. This service must
        // not fold the two together, or it silently merges two accounts into one.
        assertEquals("a.b+tag@example.com", MagicLinkService.normaliseEmail("a.b+tag@example.com"))
    }

    @Test
    fun `a malformed address is refused`() {
        assertNull(MagicLinkService.normaliseEmail("alice.example.com"), "an address with no @ must be refused")
        assertNull(MagicLinkService.normaliseEmail("alice@@example.com"), "an address with two @ must be refused")
        assertNull(
            MagicLinkService.normaliseEmail("alice@examplecom"),
            "an address with no dot after the @ must be refused",
        )
        assertNull(
            MagicLinkService.normaliseEmail("a".repeat(250) + "@example.com"),
            "an address over 254 characters must be refused",
        )
        assertNull(MagicLinkService.normaliseEmail("ali ce@example.com"), "an address holding a space must be refused")
    }

    // ------------------------------------------------------------------ request

    @Test
    fun `request stores the hash and never the token`() = runTest {
        service().request("alice@example.com")

        val stored = database.getCollection<Document>("magicLinkTokens").find().toList()
        assertEquals(1, stored.size)
        assertEquals(MagicLinkService.hash(FIXED_TOKEN), stored.single().getString("_id"))
        assertTrue(
            stored.none { it.getString("_id") == FIXED_TOKEN },
            "the raw token must never reach storage",
        )
    }

    @Test
    fun `request mails a link that carries the raw token`() = runTest {
        service().request("alice@example.com")

        assertEquals(1, mail.sent.size)
        val (email, link) = mail.sent.single()
        assertEquals("alice@example.com", email)
        assertEquals("$BASE_URL/api/auth/magic-link/$FIXED_TOKEN", link)
    }

    @Test
    fun `request on a malformed address mails nothing`() = runTest {
        service().request("not-an-address")

        assertTrue(mail.sent.isEmpty())
        assertEquals(0L, database.getCollection<Document>("magicLinkTokens").countDocuments())
    }

    // ------------------------------------------------------------------ consume

    @Test
    fun `consume returns the email and removes the token`() = runTest {
        service().request("alice@example.com")

        val email = service().consume(FIXED_TOKEN)

        assertEquals("alice@example.com", email)
        assertEquals(0L, database.getCollection<Document>("magicLinkTokens").countDocuments())
    }

    @Test
    fun `consume a second time gives null`() = runTest {
        service().request("alice@example.com")
        service().consume(FIXED_TOKEN)

        assertNull(service().consume(FIXED_TOKEN))
    }

    @Test
    fun `consume an expired token gives null`() = runTest {
        service(now = FUTURE).request("alice@example.com")

        val result = service(now = FUTURE + MagicLinkService.TTL_MILLIS + 1).consume(FIXED_TOKEN)

        assertNull(result)
    }

    @Test
    fun `consume an unknown token gives null`() = runTest {
        assertNull(service().consume("never-issued-token"))
    }

    // ------------------------------------------------------------------ newToken and hash

    @Test
    fun `two tokens never match`() {
        val generated = (1..100).map { MagicLinkService.newToken() }.toSet()
        assertEquals(100, generated.size, "every one of 100 generated tokens must be distinct")
    }

    @Test
    fun `a token is url-safe`() {
        val allowed = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('_', '-')).toSet()
        repeat(200) {
            val token = MagicLinkService.newToken()
            assertTrue(token.isNotEmpty())
            assertTrue(
                token.all { char -> char in allowed },
                "the token '$token' held a character outside A-Za-z0-9_-",
            )
        }
    }

    @Test
    fun `hash is stable and differs per token`() {
        val x = MagicLinkService.hash("token-x")
        val y = MagicLinkService.hash("token-y")

        assertEquals(x, MagicLinkService.hash("token-x"), "hashing the same token twice must give the same result")
        assertFalse(x == y, "hashing two different tokens must give two different results")
        assertEquals(64, x.length, "a SHA-256 hex digest is 64 characters long")
        assertTrue(x.all { char -> char in "0123456789abcdef" }, "the hash '$x' held a non-hex or upper-case character")
    }
}
