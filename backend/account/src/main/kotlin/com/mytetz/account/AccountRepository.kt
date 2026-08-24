package com.mytetz.account

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import java.util.Date
import java.util.concurrent.TimeUnit

/** The MongoDB error code for a duplicate key on a unique index. */
private const val DUPLICATE_KEY = 11000

/**
 * The store for identity: a user, a magic-link token and a session.
 *
 * Every write here targets one document. A client does not need a transaction to stay consistent.
 *
 * [consumeToken] is the one method with a race to defend. Two callers can present the same raw
 * token at nearly the same instant. Exactly one of them must receive the stored token. See its
 * own KDoc for the atomic filter that enforces this.
 */
class AccountRepository(database: MongoDatabase) {

    private val users = database.getCollection<User>("users")
    private val tokens = database.getCollection<MagicLinkToken>("magicLinkTokens")
    private val sessions = database.getCollection<AuthSession>("authSessions")

    /**
     * Creates every index this repository needs.
     *
     * The unique index on `email` turns a duplicate signup into a duplicate-key error.
     * [insertUser] relies on that error to find the winner. The sparse unique index on
     * `googleSub` allows many users to hold a null value there. A learner adds a Google account
     * only by choice.
     *
     * Both TTL indexes expire a document at the instant the `expiresAt` field stores. Each field
     * must hold a BSON Date for the TTL monitor to act on it.
     */
    suspend fun ensureIndexes() {
        users.createIndex(Indexes.ascending("email"), IndexOptions().name("email_unique").unique(true))
        users.createIndex(
            Indexes.ascending("googleSub"),
            IndexOptions().name("google_sub_unique").unique(true).sparse(true),
        )
        tokens.createIndex(
            Indexes.ascending("expiresAt"),
            IndexOptions().name("token_ttl").expireAfter(0, TimeUnit.SECONDS),
        )
        sessions.createIndex(
            Indexes.ascending("expiresAt"),
            IndexOptions().name("session_ttl").expireAfter(0, TimeUnit.SECONDS),
        )
        sessions.createIndex(Indexes.ascending("userId"), IndexOptions().name("by_user"))
    }

    suspend fun findUserByEmail(email: String): User? =
        users.find(Filters.eq("email", email)).firstOrNull()

    suspend fun findUserByGoogleSub(sub: String): User? =
        users.find(Filters.eq("googleSub", sub)).firstOrNull()

    suspend fun findUserById(id: String): User? =
        users.find(Filters.eq("_id", id)).firstOrNull()

    /**
     * Inserts [user], or reads back the stored user when the email is already taken.
     *
     * A duplicate-key error means another caller won the race for this email. This method then
     * reads the stored user and returns it in place of the caller's own copy. The pattern is
     * `ExplanationRepository.insertIfAbsent` in `:backend:graph`.
     */
    suspend fun insertUser(user: User): User =
        try {
            users.insertOne(user)
            user
        } catch (e: MongoWriteException) {
            if (e.error.code != DUPLICATE_KEY) throw e
            findUserByEmail(user.email)
                ?: error("duplicate email reported for ${user.email} but the stored user is missing")
        }

    suspend fun setGoogleSub(userId: String, sub: String) {
        users.updateOne(Filters.eq("_id", userId), Updates.set("googleSub", sub))
    }

    suspend fun touchUser(userId: String, nowEpochMillis: Long) {
        users.updateOne(Filters.eq("_id", userId), Updates.set("lastSeenAtEpochMillis", nowEpochMillis))
    }

    suspend fun insertToken(token: MagicLinkToken) {
        tokens.insertOne(token)
    }

    /**
     * Removes the token named by [tokenHash] and returns it.
     *
     * Returns null once the token has expired, or once another caller has already consumed it.
     *
     * The filter carries two conditions together: `_id` equality, and an expiry after
     * [nowEpochMillis]. `findOneAndDelete` applies both conditions in one atomic operation. A
     * separate read followed by a separate delete would let two concurrent callers each read the
     * token before either deletes it. A magic link that two people can redeem is an account
     * takeover.
     *
     * `findOneAndDelete` removes at most one document. Of any number of concurrent callers,
     * exactly one receives the token.
     *
     * An expired token stays in place for the TTL index to reap. This method never deletes an
     * expired token itself. It therefore never reports a consumption that did not happen.
     */
    suspend fun consumeToken(tokenHash: String, nowEpochMillis: Long): MagicLinkToken? =
        tokens.findOneAndDelete(
            Filters.and(Filters.eq("_id", tokenHash), Filters.gt("expiresAt", Date(nowEpochMillis))),
        )

    suspend fun insertSession(session: AuthSession) {
        sessions.insertOne(session)
    }

    suspend fun findSession(sessionId: String): AuthSession? =
        sessions.find(Filters.eq("_id", sessionId)).firstOrNull()

    suspend fun touchSession(sessionId: String, nowEpochMillis: Long, expiresAtEpochMillis: Long) {
        sessions.updateOne(
            Filters.eq("_id", sessionId),
            Updates.combine(
                Updates.set("lastSeenAtEpochMillis", nowEpochMillis),
                Updates.set("expiresAt", Date(expiresAtEpochMillis)),
            ),
        )
    }

    suspend fun deleteSession(sessionId: String) {
        sessions.deleteOne(Filters.eq("_id", sessionId))
    }

    /** Removes every session that belongs to [userId]. Reports how many it removed. */
    suspend fun deleteSessionsForUser(userId: String): Long =
        sessions.deleteMany(Filters.eq("userId", userId)).deletedCount

    suspend fun deleteUser(userId: String) {
        users.deleteOne(Filters.eq("_id", userId))
    }
}
