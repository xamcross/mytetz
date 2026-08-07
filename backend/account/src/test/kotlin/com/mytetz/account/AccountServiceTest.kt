package com.mytetz.account

import kotlinx.coroutines.test.runTest
import org.bson.Document
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Drives [AccountService] against a real database, with an injected clock, user id factory and
 * session id factory.
 */
class AccountServiceTest {

    private companion object {
        /** 2100-01-01T00:00:00Z. Every test in this class anchors its timestamps here or later. */
        const val FUTURE = 4_102_444_800_000L
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 60 * 60 * 1000L
    }

    private val database = MongoTestSupport.database("account_service")
    private val repository = AccountRepository(database)

    private var userIdCounter = 0
    private var sessionIdCounter = 0

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Document>("users").drop()
        database.getCollection<Document>("magicLinkTokens").drop()
        database.getCollection<Document>("authSessions").drop()
        repository.ensureIndexes()
        userIdCounter = 0
        sessionIdCounter = 0
    }

    /** A service wired to the shared [repository], with a fixed clock and deterministic ids. */
    private fun service(now: Long = FUTURE): AccountService = AccountService(
        repository = repository,
        clock = { now },
        ids = { "u${userIdCounter++}" },
        sessionIds = { "s${sessionIdCounter++}" },
    )

    // ------------------------------------------------------------------ findOrCreateByEmail

    @Test
    fun `a first sign-in creates the user`() = runTest {
        val user = service().findOrCreateByEmail("alice@example.com")

        assertEquals("alice@example.com", user.email)
        assertEquals(user, repository.findUserByEmail("alice@example.com"))
    }

    @Test
    fun `a second sign-in finds the same user`() = runTest {
        val first = service().findOrCreateByEmail("alice@example.com")

        val second = service().findOrCreateByEmail("alice@example.com")

        assertEquals(first.id, second.id)
    }

    // ------------------------------------------------------------------ linkGoogle

    @Test
    fun `google links by subject when the subject is stored`() = runTest {
        val existing = service().findOrCreateByEmail("alice@example.com")
        repository.setGoogleSub(existing.id, "google-sub-1")

        val linked = service().linkGoogle(
            GoogleIdentity(sub = "google-sub-1", email = "different-address@example.com", emailVerified = true),
        )

        assertEquals(existing.id, linked.id, "a stored subject must win over the incoming email")
    }

    @Test
    fun `google links by email and stores the subject the first time`() = runTest {
        val existing = service().findOrCreateByEmail("alice@example.com")

        val linked = service().linkGoogle(
            GoogleIdentity(sub = "google-sub-2", email = "alice@example.com", emailVerified = true),
        )

        assertEquals(existing.id, linked.id)
        assertEquals("google-sub-2", linked.googleSub)
        assertEquals(
            "google-sub-2",
            repository.findUserById(existing.id)?.googleSub,
            "setGoogleSub must have taken effect on the stored user",
        )
    }

    @Test
    fun `a subject that collides with another account is refused`(): Unit = runTest {
        val existing = service().findOrCreateByEmail("alice@example.com")
        repository.setGoogleSub(existing.id, "google-sub-original")

        assertFailsWith<AccountLinkConflictException> {
            service().linkGoogle(
                GoogleIdentity(sub = "google-sub-different", email = "alice@example.com", emailVerified = true),
            )
        }
    }

    @Test
    fun `google creates a user when no account matches`(): Unit = runTest {
        val linked = service().linkGoogle(
            GoogleIdentity(sub = "google-sub-3", email = "brand-new@example.com", emailVerified = true),
        )

        assertEquals("brand-new@example.com", linked.email)
        assertEquals("google-sub-3", linked.googleSub)
        assertNotNull(repository.findUserByEmail("brand-new@example.com"))
    }

    // ------------------------------------------------------------------ resolveSession

    @Test
    fun `an unknown session resolves to null`() = runTest {
        assertNull(service().resolveSession("no-such-session"))
    }

    @Test
    fun `an expired session resolves to null`() = runTest {
        val user = service(now = FUTURE).findOrCreateByEmail("alice@example.com")
        val sessionId = service(now = FUTURE).openSession(user.id)
        val expiresAt = repository.findSession(sessionId)!!.expiresAtEpochMillis

        assertNull(service(now = expiresAt).resolveSession(sessionId), "must be null exactly at the expiry")
        assertNull(service(now = expiresAt + 1).resolveSession(sessionId), "must be null one millisecond after")
    }

    @Test
    fun `a session whose user is gone resolves to null`() = runTest {
        val user = service(now = FUTURE).findOrCreateByEmail("alice@example.com")
        val sessionId = service(now = FUTURE).openSession(user.id)
        repository.deleteUser(user.id)

        assertNull(service(now = FUTURE).resolveSession(sessionId))
    }

    @Test
    fun `resolving slides the expiry after an hour`() = runTest {
        val user = service(now = FUTURE).findOrCreateByEmail("alice@example.com")
        val sessionId = service(now = FUTURE).openSession(user.id)
        val before = repository.findSession(sessionId)!!.lastSeenAtEpochMillis

        val anHourLater = FUTURE + HOUR_MILLIS
        service(now = anHourLater).resolveSession(sessionId)

        val after = repository.findSession(sessionId)!!.lastSeenAtEpochMillis
        assertEquals(FUTURE, before)
        assertEquals(anHourLater, after, "an hour or more must slide the expiry")
    }

    @Test
    fun `resolving inside the hour writes nothing`() = runTest {
        val user = service(now = FUTURE).findOrCreateByEmail("alice@example.com")
        val sessionId = service(now = FUTURE).openSession(user.id)

        service(now = FUTURE + MINUTE_MILLIS).resolveSession(sessionId)

        assertEquals(
            FUTURE,
            repository.findSession(sessionId)!!.lastSeenAtEpochMillis,
            "a busy reader must not cost a write on every request",
        )
    }

    // ------------------------------------------------------------------ closeSession / closeAllSessions

    @Test
    fun `closing a session removes it`() = runTest {
        val user = service(now = FUTURE).findOrCreateByEmail("alice@example.com")
        val sessionId = service(now = FUTURE).openSession(user.id)

        service().closeSession(sessionId)

        assertNull(repository.findSession(sessionId))
    }

    @Test
    fun `closing every session for a user leaves another user's`(): Unit = runTest {
        val svc = service(now = FUTURE)
        val alice = svc.findOrCreateByEmail("alice@example.com")
        val bob = svc.findOrCreateByEmail("bob@example.com")
        val aliceSession1 = svc.openSession(alice.id)
        val aliceSession2 = svc.openSession(alice.id)
        val bobSession = svc.openSession(bob.id)

        val closed = svc.closeAllSessions(alice.id)

        assertEquals(2, closed)
        assertNull(repository.findSession(aliceSession1))
        assertNull(repository.findSession(aliceSession2))
        assertNotNull(repository.findSession(bobSession), "another user's session must survive")
    }

    // ------------------------------------------------------------------ newSessionId

    @Test
    fun `two session ids never match`() = runTest {
        val generated = (1..100).map { AccountService.newSessionId() }.toSet()
        assertEquals(100, generated.size, "every one of 100 generated session ids must be distinct")
    }
}
