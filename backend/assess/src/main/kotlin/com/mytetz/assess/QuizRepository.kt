package com.mytetz.assess

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull

private const val DUPLICATE_KEY = 11000

/**
 * This class gives Mongo access to two collections: `quizTemplates` and `quizAttempts`.
 *
 * A quiz template is content-addressed. A quiz template is immutable. One document in
 * `quizAttempts` holds one learner's attempt at one template.
 *
 * This class copies the shape of `com.mytetz.graph.ExplanationRepository`. It also copies the
 * duplicate-key handling in [insertIfAbsent]. When two writers race for the same key, the class
 * drops the loser's own copy. The class returns the document that the winner stored. This wastes
 * one insert. The result is still always correct.
 *
 * The class is `open`. The method [findByKey] is `open` too, for the same reason as
 * `ExplanationRepository`. A test can use this to insert a key between two reads. This gives the
 * test a race on demand. Two real callers would otherwise create that race only by chance.
 */
open class QuizRepository(database: MongoDatabase) {

    private val templates = database.getCollection<QuizTemplate>("quizTemplates")
    private val attempts = database.getCollection<QuizAttempt>("quizAttempts")

    /**
     * `principal_recent` serves "my attempts, most recent first". `by_session` serves
     * per-session lookups. Neither is a TTL index, and nothing here expires.
     *
     * A quiz attempt is the learner's own record of how they did. `com.mytetz.session.
     * SessionRepository` once treated a session the same way, and no longer does: an anonymous
     * session now expires after 90 days of inactivity, and a learner can end one early. Neither
     * change touches this collection. Dropping a quiz attempt is still a product decision nobody
     * has made, so `quizAttempts` grows with no ceiling here, on purpose, and not by oversight.
     */
    suspend fun ensureIndexes() {
        attempts.createIndex(
            Indexes.compoundIndex(Indexes.ascending("principalId"), Indexes.descending("createdAtEpochMillis")),
            IndexOptions().name("principal_recent"),
        )
        attempts.createIndex(Indexes.ascending("sessionId"), IndexOptions().name("by_session"))
    }

    open suspend fun findByKey(key: String): QuizTemplate? =
        templates.find(Filters.eq("_id", key)).firstOrNull()

    /**
     * Inserts the template only if its key is free.
     *
     * On a duplicate-key race, this method discards the caller's own copy.
     * It returns the document that already exists. This wastes an insert.
     * The result is still always correct.
     */
    suspend fun insertIfAbsent(template: QuizTemplate): QuizTemplate =
        try {
            templates.insertOne(template)
            template
        } catch (e: MongoWriteException) {
            if (e.error.code != DUPLICATE_KEY) throw e
            findByKey(template.key) ?: error("duplicate key ${template.key} reported but document not found")
        }

    suspend fun incrementRequestCount(key: String) {
        templates.updateOne(Filters.eq("_id", key), Updates.inc("requestCount", 1L))
    }

    /**
     * Inserts a new attempt, or overwrites an existing one with the same id.
     *
     * One method covers both cases. `QuizRoutes.kt` calls this to create an attempt, and again
     * later to record its score. A single method removes the need to pick the right one at each
     * call site.
     */
    suspend fun upsertAttempt(attempt: QuizAttempt) {
        attempts.replaceOne(
            Filters.eq("_id", attempt.id),
            attempt,
            com.mongodb.client.model.ReplaceOptions().upsert(true),
        )
    }

    suspend fun findAttempt(id: String): QuizAttempt? =
        attempts.find(Filters.eq("_id", id)).firstOrNull()
}
