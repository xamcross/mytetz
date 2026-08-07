package com.mytetz.account

import com.mongodb.client.model.Filters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bson.Document
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AccountRepositoryTest {

    companion object {
        /**
         * 2100-01-01T00:00:00Z. Every test in this class anchors its timestamps here or later.
         * The real container clock never reaches this instant, so the real-time TTL monitor never
         * reaps a document this suite calls "expired" against its own simulated `now`.
         */
        private const val FUTURE = 4_102_444_800_000L
        private const val MINUTE_MILLIS = 60_000L
        private const val CONCURRENCY = 16
    }

    private val database = MongoTestSupport.database("account")
    private val repository = AccountRepository(database)

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Document>("users").drop()
        database.getCollection<Document>("magicLinkTokens").drop()
        database.getCollection<Document>("authSessions").drop()
        repository.ensureIndexes()
    }

    private fun user(id: String, email: String, googleSub: String? = null) = User(
        id = id,
        email = email,
        googleSub = googleSub,
        createdAtEpochMillis = FUTURE,
        lastSeenAtEpochMillis = FUTURE,
    )

    private fun token(hash: String, email: String, expiresAt: Long = FUTURE + MINUTE_MILLIS) = MagicLinkToken(
        tokenHash = hash,
        email = email,
        expiresAtEpochMillis = expiresAt,
        createdAtEpochMillis = FUTURE,
    )

    private fun session(id: String, userId: String, expiresAt: Long = FUTURE + MINUTE_MILLIS) = AuthSession(
        sessionId = id,
        userId = userId,
        createdAtEpochMillis = FUTURE,
        lastSeenAtEpochMillis = FUTURE,
        expiresAtEpochMillis = expiresAt,
    )

    // ------------------------------------------------------------------ users

    @Test
    fun `a user round-trips by email`() = runTest {
        val inserted = repository.insertUser(user("u1", "alice@example.com"))

        val found = repository.findUserByEmail("alice@example.com")

        assertEquals(inserted, found)
    }

    @Test
    fun `inserting a duplicate email returns the stored user`() = runTest {
        val first = repository.insertUser(user("u1", "alice@example.com"))

        val second = repository.insertUser(user("u2", "alice@example.com"))

        assertEquals(first, second, "the loser must receive the winner's own document")
        assertNull(repository.findUserById("u2"), "the losing document must not exist under its own id")
    }

    @Test
    fun `a user is findable by google sub`() = runTest {
        repository.insertUser(user("u1", "alice@example.com", googleSub = "google-sub-1"))

        val found = repository.findUserByGoogleSub("google-sub-1")

        assertEquals("alice@example.com", found?.email)
    }

    @Test
    fun `an unknown email gives null`() = runTest {
        assertNull(repository.findUserByEmail("nobody@example.com"))
    }

    @Test
    fun `setGoogleSub links a google account to an existing user`() = runTest {
        repository.insertUser(user("u1", "alice@example.com"))

        repository.setGoogleSub("u1", "google-sub-9")

        assertEquals("google-sub-9", repository.findUserById("u1")?.googleSub)
    }

    @Test
    fun `touchUser advances lastSeenAtEpochMillis`() = runTest {
        repository.insertUser(user("u1", "alice@example.com"))

        repository.touchUser("u1", FUTURE + MINUTE_MILLIS)

        assertEquals(FUTURE + MINUTE_MILLIS, repository.findUserById("u1")?.lastSeenAtEpochMillis)
    }

    @Test
    fun `deleteUser removes the user`() = runTest {
        repository.insertUser(user("u1", "alice@example.com"))

        repository.deleteUser("u1")

        assertNull(repository.findUserById("u1"))
    }

    // ------------------------------------------------------------------ magic link tokens

    @Test
    fun `a token round-trips and consume removes it`() = runTest {
        repository.insertToken(token("hash-1", "alice@example.com"))

        val consumed = repository.consumeToken("hash-1", nowEpochMillis = FUTURE)

        assertEquals("alice@example.com", consumed?.email)
        assertEquals(0L, database.getCollection<Document>("magicLinkTokens").countDocuments())
    }

    @Test
    fun `consuming a token twice gives null the second time`() = runTest {
        repository.insertToken(token("hash-2", "alice@example.com"))
        repository.consumeToken("hash-2", nowEpochMillis = FUTURE)

        val secondAttempt = repository.consumeToken("hash-2", nowEpochMillis = FUTURE)

        assertNull(secondAttempt)
    }

    @Test
    fun `consuming an expired token gives null and leaves nothing behind`() = runTest {
        // expiresAt sits at FUTURE, still far ahead of the real container clock. The `now` this
        // call passes is a minute later still, so the token is expired for the repository's own
        // comparison without ever coming near the real-time TTL monitor's reach.
        repository.insertToken(token("hash-3", "alice@example.com", expiresAt = FUTURE))

        val consumed = repository.consumeToken("hash-3", nowEpochMillis = FUTURE + MINUTE_MILLIS)

        assertNull(consumed)
        // The filter's expiry condition did not match, so findOneAndDelete deleted nothing. The
        // document is left exactly as it was, for the TTL index to reap later.
        assertEquals(1L, database.getCollection<Document>("magicLinkTokens").countDocuments())
    }

    @Test
    fun `two concurrent consumes of one token give exactly one document`() = runTest {
        repository.insertToken(token("hash-4", "alice@example.com"))

        val results = coroutineScope {
            (1..CONCURRENCY).map {
                async(Dispatchers.IO) { repository.consumeToken("hash-4", nowEpochMillis = FUTURE) }
            }.awaitAll()
        }

        // A read-then-delete would let every caller see the token before any of them removes it.
        // Two winners here would mean a magic link is usable twice, which is an account takeover.
        assertEquals(1, results.count { it != null }, "exactly one caller must receive the token")
    }

    @Test
    fun `the token expiry is stored as a BSON Date`() = runTest {
        repository.insertToken(token("hash-5", "alice@example.com", expiresAt = FUTURE))

        val raw = database.getCollection<Document>("magicLinkTokens")
            .find(Filters.eq("_id", "hash-5")).firstOrNull()

        assertNotNull(raw)
        assertIs<Date>(raw["expiresAt"], "a non-Date here makes the TTL index a silent no-op")
        assertEquals(FUTURE, (raw["expiresAt"] as Date).time)
    }

    // ------------------------------------------------------------------ sessions

    @Test
    fun `a session round-trips and delete removes it`() = runTest {
        repository.insertSession(session("s1", "u1"))

        val found = repository.findSession("s1")
        assertEquals("u1", found?.userId)

        repository.deleteSession("s1")

        assertNull(repository.findSession("s1"))
    }

    @Test
    fun `the session expiry is stored as a BSON Date`() = runTest {
        repository.insertSession(session("s2", "u1", expiresAt = FUTURE))

        val raw = database.getCollection<Document>("authSessions")
            .find(Filters.eq("_id", "s2")).firstOrNull()

        assertNotNull(raw)
        assertIs<Date>(raw["expiresAt"], "a non-Date here makes the TTL index a silent no-op")
        assertEquals(FUTURE, (raw["expiresAt"] as Date).time)
    }

    @Test
    fun `touchSession advances lastSeenAtEpochMillis and expiresAt`() = runTest {
        repository.insertSession(session("s3", "u1", expiresAt = FUTURE))

        repository.touchSession("s3", nowEpochMillis = FUTURE + MINUTE_MILLIS, expiresAtEpochMillis = FUTURE + 2 * MINUTE_MILLIS)

        val found = repository.findSession("s3")
        assertEquals(FUTURE + MINUTE_MILLIS, found?.lastSeenAtEpochMillis)
        assertEquals(FUTURE + 2 * MINUTE_MILLIS, found?.expiresAtEpochMillis)
    }

    @Test
    fun `deleting every session for a user leaves another user's sessions`() = runTest {
        repository.insertSession(session("s4", "u1"))
        repository.insertSession(session("s5", "u1"))
        repository.insertSession(session("s6", "u2"))

        val deleted = repository.deleteSessionsForUser("u1")

        assertEquals(2, deleted)
        assertNull(repository.findSession("s4"))
        assertNull(repository.findSession("s5"))
        assertNotNull(repository.findSession("s6"), "another user's session must survive")
    }

    // ------------------------------------------------------------------ indexes

    @Test
    fun `ensureIndexes creates the unique email index`() = runTest {
        val index = database.getCollection<Document>("users").listIndexes().toList()
            .single { it.getString("name") == "email_unique" }

        assertEquals(setOf("email"), index.get("key", Document::class.java).keys)
        assertEquals(true, index.getBoolean("unique"))
    }
}
