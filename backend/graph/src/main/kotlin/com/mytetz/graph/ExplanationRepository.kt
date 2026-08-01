package com.mytetz.graph

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull

private const val DUPLICATE_KEY = 11000

class ExplanationRepository(database: MongoDatabase) {

    private val collection = database.getCollection<Explanation>("explanations")

    suspend fun ensureIndexes() {
        collection.createIndex(
            Indexes.compoundIndex(Indexes.ascending("topicSlug"), Indexes.descending("requestCount")),
            IndexOptions().name("topic_demand"),
        )
        collection.createIndex(Indexes.ascending("createdAtEpochMillis"), IndexOptions().name("created_at"))
    }

    suspend fun findByKey(key: String): Explanation? =
        collection.find(Filters.eq("_id", key)).firstOrNull()

    /**
     * Inserts only if the key is free. On a duplicate-key race the loser's copy is
     * discarded and the winner's document is returned — wasteful, never wrong.
     */
    suspend fun insertIfAbsent(explanation: Explanation): Explanation =
        try {
            collection.insertOne(explanation)
            explanation
        } catch (e: MongoWriteException) {
            if (e.error.code != DUPLICATE_KEY) throw e
            findByKey(explanation.key)
                ?: error("duplicate key ${explanation.key} reported but document not found")
        }

    suspend fun incrementRequestCount(key: String) {
        collection.updateOne(Filters.eq("_id", key), Updates.inc("requestCount", 1L))
    }
}
