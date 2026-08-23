package com.mytetz.account

import org.bson.types.ObjectId
import java.security.SecureRandom
import java.util.Base64

/**
 * Raised when an incoming Google subject would overwrite a different, already-stored subject on
 * one account.
 *
 * This is a real conflict, not a bug in this module. Two different Google accounts share one email
 * address only through a mistake or an attack. Silently overwriting the stored subject would let
 * a second Google account take over the first account's session history.
 */
class AccountLinkConflictException(message: String) : Exception(message)

/**
 * Signs a learner up, signs a learner in, and manages the session that keeps them signed in.
 *
 * [clock], [ids] and [sessionIds] each carry a default for production use. A test overrides one or
 * more of them to fix the current time, the minted user id, or the minted session id.
 */
class AccountService(
    private val repository: AccountRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { ObjectId().toHexString() },
    private val sessionIds: () -> String = { newSessionId() },
) {

    /**
     * Finds the user whose stored email is exactly [email], or null. Creates nothing.
     *
     * This is the read half [findOrCreateByEmail] does not offer on its own: a caller that must
     * not create an account for an address it does not recognise — the billing webhook route is
     * the one that needs it — calls this instead. [email] must already be normalised; this
     * method does no normalisation of its own, the same division of labour
     * [findOrCreateByEmail] already keeps with [MagicLinkService].
     */
    suspend fun findByEmail(email: String): User? = repository.findUserByEmail(email)

    /** Finds the user with [email], or creates one. */
    suspend fun findOrCreateByEmail(email: String): User {
        repository.findUserByEmail(email)?.let { return it }
        val now = clock()
        return repository.insertUser(
            User(
                id = ids(),
                email = email,
                googleSub = null,
                createdAtEpochMillis = now,
                lastSeenAtEpochMillis = now,
            ),
        )
    }

    /**
     * Finds or creates the user for [identity], and links the Google subject to it.
     *
     * The method matches on [GoogleIdentity.sub] first. It matches on [GoogleIdentity.email] next,
     * when no user carries that subject yet. On an email match with no stored subject, the method
     * stores the incoming subject. On an email match whose stored subject differs from the
     * incoming one, the method raises [AccountLinkConflictException]. When no user matches either
     * field, the method creates one, with the subject already set.
     */
    suspend fun linkGoogle(identity: GoogleIdentity): User {
        repository.findUserByGoogleSub(identity.sub)?.let { return it }

        val byEmail = repository.findUserByEmail(identity.email)
        if (byEmail != null) {
            val storedSub = byEmail.googleSub
            if (storedSub == identity.sub) return byEmail
            if (storedSub != null) {
                throw AccountLinkConflictException(
                    "the account for ${identity.email} is already linked to a different Google account",
                )
            }
            repository.setGoogleSub(byEmail.id, identity.sub)
            return byEmail.copy(googleSub = identity.sub)
        }

        val now = clock()
        return repository.insertUser(
            User(
                id = ids(),
                email = identity.email,
                googleSub = identity.sub,
                createdAtEpochMillis = now,
                lastSeenAtEpochMillis = now,
            ),
        )
    }

    /** Opens a session for [userId] and returns the new session id. */
    suspend fun openSession(userId: String): String {
        val now = clock()
        val sessionId = sessionIds()
        repository.insertSession(
            AuthSession(
                sessionId = sessionId,
                userId = userId,
                createdAtEpochMillis = now,
                lastSeenAtEpochMillis = now,
                expiresAtEpochMillis = now + SESSION_TTL_MILLIS,
            ),
        )
        return sessionId
    }

    /**
     * Returns the user behind [sessionId], or null.
     *
     * The method returns null for an unknown session, for a session whose expiry is at or before
     * the current time, and for a session whose user has been deleted.
     *
     * The method slides the session's expiry forward only when the last slide is at least
     * [SLIDE_INTERVAL_MILLIS] old. A busy reader must not cost a database write on every request.
     */
    suspend fun resolveSession(sessionId: String): User? {
        val session = repository.findSession(sessionId) ?: return null
        val now = clock()
        if (now >= session.expiresAtEpochMillis) return null

        val user = repository.findUserById(session.userId) ?: return null

        if (now - session.lastSeenAtEpochMillis >= SLIDE_INTERVAL_MILLIS) {
            repository.touchSession(sessionId, now, now + SESSION_TTL_MILLIS)
        }

        return user
    }

    /** Removes [sessionId]. A caller signs a learner out through this method. */
    suspend fun closeSession(sessionId: String) {
        repository.deleteSession(sessionId)
    }

    /** Removes every session that belongs to [userId]. Reports how many it removed. */
    suspend fun closeAllSessions(userId: String): Long = repository.deleteSessionsForUser(userId)

    companion object {

        /** How long a session lasts from its last slide. 30 days. */
        const val SESSION_TTL_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /** The minimum gap between two slides of one session's expiry. One hour. */
        const val SLIDE_INTERVAL_MILLIS: Long = 60 * 60 * 1000

        /** The number of random bytes behind every minted session id. */
        private const val SESSION_ID_BYTE_LENGTH = 16

        /** The one [SecureRandom] instance every call to [newSessionId] draws from. */
        private val secureRandom = SecureRandom()

        /**
         * Mints a session id from [SESSION_ID_BYTE_LENGTH] random bytes, encoded as base64url
         * without padding.
         *
         * The method draws from [SecureRandom]. A predictable session id is an account takeover.
         */
        internal fun newSessionId(): String {
            val bytes = ByteArray(SESSION_ID_BYTE_LENGTH)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
