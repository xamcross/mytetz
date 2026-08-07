package com.mytetz.graph

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull

private const val DUPLICATE_KEY = 11000

/**
 * `open`, and [findByKey] with it, only so that a test can make a key appear between two reads.
 *
 * That window is the whole subject of `SessionService.prepare`'s staleness contract — a plan that
 * missed can be a hit by the time it is executed, and an API layer that turned the miss into a quota
 * refusal has refused a request that had become free. Nothing else in the system can create that
 * interleaving on demand: it needs two callers landing either side of one insert. Same reasoning,
 * and the same note, as `QuotaRepository.incrementCounter` and `Components.bootstrap`. There is no
 * production subclass.
 */
open class ExplanationRepository(database: MongoDatabase) {

    private val collection = database.getCollection<Explanation>("explanations")

    suspend fun ensureIndexes() {
        collection.createIndex(
            Indexes.compoundIndex(Indexes.ascending("topicSlug"), Indexes.descending("requestCount")),
            IndexOptions().name("topic_demand"),
        )
        collection.createIndex(Indexes.ascending("createdAtEpochMillis"), IndexOptions().name("created_at"))
    }

    open suspend fun findByKey(key: String): Explanation? =
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

    /**
     * Removes every explanation that a change of model family stranded. Reports how many it removed.
     *
     * The content key holds `modelFamily`. A document written under a different family is therefore
     * unreachable. No key that a caller can compute finds it. The predicate is exact and not a
     * heuristic. The operation loses nothing that the system can serve.
     *
     * You can run this method twice. The second run matches nothing.
     *
     * **Do not run it while two application versions are live on different families.** Each version
     * deletes the other's documents. `fly.toml` runs one machine. The caller in
     * `Components.bootstrap` also sits behind an explicit flag. A mistake here costs a regeneration
     * and not a corruption. The system can reproduce every deleted document from its inputs.
     */
    suspend fun deleteWhereModelFamilyIsNot(modelFamily: String): Long =
        collection.deleteMany(Filters.ne("modelFamily", modelFamily)).deletedCount
}
