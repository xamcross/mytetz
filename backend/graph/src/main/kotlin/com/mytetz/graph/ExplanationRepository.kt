package com.mytetz.graph

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

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
     * deletes the other's documents. The deploy runbook's `--ha=false` flag, and not `fly.toml`,
     * keeps this app on one machine. The caller in `Components.bootstrap` also sits behind an
     * explicit flag. A mistake here costs a regeneration and not a corruption. The system can
     * reproduce every deleted document from its inputs.
     */
    suspend fun deleteWhereModelFamilyIsNot(modelFamily: String): Long =
        collection.deleteMany(Filters.ne("modelFamily", modelFamily)).deletedCount

    /**
     * Finds up to [limit] eviction candidates, oldest first: not a seed, with
     * [Explanation.requestCount] at or below [maxRequestCount], and with
     * [Explanation.createdAtEpochMillis] below [olderThanEpochMillis].
     *
     * `createdAtEpochMillis` is a BSON Int64, not a BSON date. A TTL index expires a document a
     * fixed time after a stored date; it cannot compare a field against a threshold that moves
     * with every call. The `created_at` index still serves this query, because a plain ascending
     * index serves a range filter and a sort on the same field.
     *
     * A candidate here is not yet safe to delete. `Components.evictExplanations` is the only
     * caller, and it still has to remove every key that a session references before it deletes
     * anything — see that method's own KDoc.
     */
    suspend fun findEvictionCandidates(maxRequestCount: Long, olderThanEpochMillis: Long, limit: Int): List<String> =
        collection.find(
            Filters.and(
                Filters.ne("verb", Verb.SEED.name),
                Filters.lte("requestCount", maxRequestCount),
                Filters.lt("createdAtEpochMillis", olderThanEpochMillis),
            ),
        )
            .sort(Indexes.ascending("createdAtEpochMillis"))
            .limit(limit)
            .toList()
            .map { it.key }

    /**
     * Deletes every one of [keys] whose [Explanation.verb] is still not [Verb.SEED] and whose
     * [Explanation.requestCount] is still at or below [maxRequestCount]. Reports how many it
     * removed.
     *
     * Both filters repeat [findEvictionCandidates]'s own filters on purpose. Mongo checks a
     * delete's filter against the document as it stands at delete time, not at candidate-scan
     * time. A cache hit between the two calls raises `requestCount` before
     * `SessionService` appends the session node that then references the key — see
     * `SessionService`'s own KDoc — so a key a learner reaches in that window no longer matches
     * this filter and survives.
     */
    suspend fun deleteEvictable(keys: Collection<String>, maxRequestCount: Long): Long {
        if (keys.isEmpty()) return 0
        return collection.deleteMany(
            Filters.and(
                Filters.`in`("_id", keys),
                Filters.ne("verb", Verb.SEED.name),
                Filters.lte("requestCount", maxRequestCount),
            ),
        ).deletedCount
    }
}
