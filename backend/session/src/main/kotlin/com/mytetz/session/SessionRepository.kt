package com.mytetz.session

import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

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
     * lookups; `by_explanation_key` serves [referencedExplanationKeys]. None of the three is a
     * TTL index and nothing here expires — sessions are the learner's record of what they read,
     * and dropping them is a product decision nobody has made.
     */
    suspend fun ensureIndexes() {
        collection.createIndex(
            Indexes.compoundIndex(Indexes.ascending("principalId"), Indexes.descending("lastActiveAtEpochMillis")),
            IndexOptions().name("principal_recent"),
        )
        collection.createIndex(Indexes.ascending("topicSlug"), IndexOptions().name("by_topic"))
        collection.createIndex(Indexes.ascending("nodes.explanationKey"), IndexOptions().name("by_explanation_key"))
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
     * Of [candidates], reports which ones a session node still points at through
     * [SessionNode.explanationKey].
     *
     * `Components.evictExplanations` is the only caller. It reads one batch of eviction
     * candidates from `ExplanationRepository`, and must not delete a key that a session still
     * references — a deleted target of a live node breaks that session's trail. See
     * `SessionService.hydrate`'s own KDoc for what a broken trail looks like from that side.
     *
     * An empty [candidates] returns an empty set and asks Mongo nothing, because a caller with no
     * candidates has nothing to check.
     *
     * ## Why this reads a distinct field and not a decoded document
     *
     * The fly machine this runs on has 512 MB, and the JVM gets a fraction of that. A session
     * holds a `nodes` array that grows for as long as the learner keeps drilling, and decoding a
     * whole page of full [LearningSession] documents just to read one string field off each one
     * spends memory this rule exists to protect. `distinct` is a server-side operation
     * (https://www.mongodb.com/docs/manual/reference/method/db.collection.distinct/): the server
     * scans the field and returns only the distinct values it finds, so no full document, and no
     * `nodes` array, ever crosses the wire.
     *
     * The MongoDB Kotlin coroutine driver call is
     * `MongoCollection<T>.distinct<R>(fieldName, filter): DistinctFlow<R>`, generic in the result
     * type `R` and independent of the collection's own document type `T` — so this reads `String`
     * values off a `MongoCollection<LearningSession>` with no intermediate type. `DistinctFlow` is
     * a `Flow<R>`, so `.toList()` collects it the same way `collection.find(...)` is collected
     * elsewhere in this class.
     *
     * The extra `.filter` after `.toList()` is still needed, and is not redundant with the query
     * filter: per MongoDB's own documented rule, "if the value of the specified field is an
     * array, `distinct()` considers each element of the array as a separate value" — so a session
     * that matches the filter because *one* of its nodes carries a wanted key contributes *every*
     * one of its nodes' keys to the distinct result, not only the one that matched.
     */
    suspend fun referencedExplanationKeys(candidates: Collection<String>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val wanted = candidates.toSet()
        return collection.distinct<String>("nodes.explanationKey", Filters.`in`("nodes.explanationKey", wanted))
            .toList()
            .filter { it in wanted }
            .toSet()
    }

    /**
     * Re-keys every session document that carries [from] as its `principalId` to [to]. Reports how
     * many it changed.
     *
     * Sign-in is the caller: an anonymous learner's sessions carry an `anon:` principal, and a
     * sign-in must move them onto the new `user:` principal, or the learner's history is orphaned
     * under an id nothing else ever presents again.
     */
    suspend fun reassignPrincipal(from: String, to: String): Long {
        val result = collection.updateMany(Filters.eq("principalId", from), Updates.set("principalId", to))
        return result.modifiedCount
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
