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
 * Mongo access for two collections: `quizTemplates` and `quizAttempts`.
 *
 * A quiz template is content addressed and immutable. One document in
 * `quizAttempts` holds one learner's attempt at one template. This class
 * mirrors `com.mytetz.graph.ExplanationRepository` exactly, including the
 * duplicate-key handling in [insertIfAbsent]. On a race, the loser's own
 * copy is discarded. The winner's document is returned. This wastes one
 * insert, but the result is never wrong.
 *
 * The class is `open`, and [findByKey] with it, for the same reason as
 * `ExplanationRepository`. A test can make a key appear between two reads.
 * This lets a test create, on demand, a race that would otherwise need two
 * callers landing on either side of one insert by chance.
 */
open class QuizRepository(database: MongoDatabase) {

    private val templates = database.getCollection<QuizTemplate>("quizTemplates")
    private val attempts = database.getCollection<QuizAttempt>("quizAttempts")

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
     * It returns the document that is already stored. This is wasteful, but
     * it is never wrong.
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

    suspend fun insertAttempt(attempt: QuizAttempt) {
        attempts.insertOne(attempt)
    }

    suspend fun findAttempt(id: String): QuizAttempt? =
        attempts.find(Filters.eq("_id", id)).firstOrNull()

    suspend fun updateAttempt(attempt: QuizAttempt) {
        attempts.replaceOne(Filters.eq("_id", attempt.id), attempt)
    }
}
