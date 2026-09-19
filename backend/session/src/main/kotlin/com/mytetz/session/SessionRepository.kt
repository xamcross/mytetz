package com.mytetz.session

import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.TimeUnit

/**
 * The session named by [sessionId] does not exist — it was never created, or it has been removed.
 *
 * A distinct type rather than [IllegalStateException] because this is an ordinary, expected outcome
 * of a stale client: Task 1.11's error mapping should turn it into a 404, and it cannot do that if
 * it is indistinguishable from a bug.
 *
 * That completes the three-way split this module hands Task 1.11, each keyed on type and none on
 * message text:
 *
 * - [SessionNotFoundException] — gone or never existed. 404.
 * - [IllegalArgumentException] from [ContextChain.pathTo] — a node id the caller made up. 400.
 * - [CorruptSessionException] — the stored tree is broken. 500, and somebody should be paged.
 */
class SessionNotFoundException(val sessionId: String) : NoSuchElementException("session $sessionId not found")

class SessionRepository(database: MongoDatabase) {

    private val collection = database.getCollection<LearningSession>("sessions")

    /**
     * `principal_recent` serves "my sessions, most recent first"; `by_topic` serves per-topic
     * lookups. Neither expires anything.
     *
     * `session_ttl` does. It expires a document the instant its `expiresAt` field's stored Date
     * passes, which is only ever true for an anonymous session — see [LearningSession]'s own KDoc on
     * [LearningSession.expiresAtEpochMillis]. A signed-in learner's sessions carry no such field and
     * this index never touches them; they are kept until account deletion removes them.
     */
    suspend fun ensureIndexes() {
        collection.createIndex(
            Indexes.compoundIndex(Indexes.ascending("principalId"), Indexes.descending("lastActiveAtEpochMillis")),
            IndexOptions().name("principal_recent"),
        )
        collection.createIndex(Indexes.ascending("topicSlug"), IndexOptions().name("by_topic"))
        collection.createIndex(
            Indexes.ascending("expiresAt"),
            IndexOptions().name("session_ttl").expireAfter(0, TimeUnit.SECONDS),
        )
    }

    /** Raises `MongoWriteException` on a duplicate id; the id is the caller's to make unique. */
    suspend fun insert(session: LearningSession) {
        collection.insertOne(session)
    }

    suspend fun findById(id: String): LearningSession? =
        collection.find(Filters.eq("_id", id)).firstOrNull()

    /**
     * Adds one step to the session and moves the cursor onto it.
     *
     * `$push` appends to the end of the array
     * (https://www.mongodb.com/docs/manual/reference/operator/update/push/), which is what keeps a
     * parent stored before its children. It enforces no uniqueness, so a caller that reuses a node
     * id lands two nodes under it; [ContextChain.pathTo] is where that is caught.
     *
     * Raises [SessionNotFoundException] when no session has that id. An `updateOne` that matches
     * nothing is not an error to MongoDB — `matchedCount` is 0, `modifiedCount` is 0, and the call
     * returns normally
     * (https://www.mongodb.com/docs/manual/reference/method/db.collection.updateOne/). Left
     * unchecked, the caller cannot tell "appended" from "that session does not exist": Task 1.10
     * reaches here only after it has already paid the model for the explanation this node points
     * at, so a silent no-op costs real money and then loses the learner's branch. The test is on
     * `matchedCount`, not `modifiedCount` — a matched document can report zero modifications when
     * an update is a no-op, and only the former answers "did the session exist?".
     *
     * No `upsert`, deliberately: a session invented here would have no principal, no topic and no
     * root, and [ContextChain.pathTo] would then raise on every read of it.
     *
     * Enforces none of [SessionLimits]. This is the write primitive, and a depth or node-count
     * check here would happen after generation has been billed — see the note on [SessionLimits].
     */
    suspend fun appendNode(sessionId: String, node: SessionNode, nowEpochMillis: Long) {
        val result = collection.updateOne(
            Filters.eq("_id", sessionId),
            Updates.combine(
                Updates.push("nodes", node),
                Updates.set("currentNodeId", node.nodeId),
                Updates.set("lastActiveAtEpochMillis", nowEpochMillis),
            ),
        )
        if (result.matchedCount == 0L) throw SessionNotFoundException(sessionId)
    }

    /**
     * Re-keys every session document that carries [from] as its `principalId` to [to]. Reports how
     * many it changed.
     *
     * Sign-in is the caller: an anonymous learner's sessions carry an `anon:` principal, and a
     * sign-in must move them onto the new `user:` principal, or the learner's history is orphaned
     * under an id nothing else ever presents again. A completed session moves too, exactly like an
     * active one — see "When a session completes" on [SessionService].
     *
     * Also removes the `expiresAt` field, on every session moved, completed or not. That field
     * exists only for an anonymous session; the moment sign-in claims one, it is a signed-in
     * learner's session and must be kept until account deletion, not reaped by `session_ttl` ninety
     * days after whatever anonymous activity it last saw.
     */
    suspend fun reassignPrincipal(from: String, to: String): Long {
        val result = collection.updateMany(
            Filters.eq("principalId", from),
            Updates.combine(Updates.set("principalId", to), Updates.unset("expiresAt")),
        )
        return result.modifiedCount
    }

    /**
     * Marks the session named by [sessionId] [SessionStatus.COMPLETED].
     *
     * Raises [SessionNotFoundException] when no session has that id, on the same `matchedCount`
     * check [appendNode] uses and for the same reason: an `updateOne` that matches nothing is not an
     * error to MongoDB, and a caller that could not tell "completed" from "no such session" would
     * report success for a request that changed nothing.
     *
     * The filter is on `_id` only, exactly as [appendNode]'s is — this method does not check
     * ownership, and neither does [appendNode]; see [SessionService]'s "Authorisation is the
     * caller's".
     */
    suspend fun complete(sessionId: String) {
        val result = collection.updateOne(
            Filters.eq("_id", sessionId),
            Updates.set("status", SessionStatus.COMPLETED.name),
        )
        if (result.matchedCount == 0L) throw SessionNotFoundException(sessionId)
    }

    /**
     * Removes every session document that carries [principalId]. Reports how many it removed.
     *
     * Account deletion is the caller. It touches only this collection: an explanation is
     * user-independent and holds nothing personal, so removing one here would destroy content
     * every other learner shares — see `com.mytetz.api.AuthRoutes`'s own KDoc on
     * `POST /api/account/delete` for the full scope of what an account deletion removes.
     */
    suspend fun deleteForPrincipal(principalId: String): Long =
        collection.deleteMany(Filters.eq("principalId", principalId)).deletedCount
}
