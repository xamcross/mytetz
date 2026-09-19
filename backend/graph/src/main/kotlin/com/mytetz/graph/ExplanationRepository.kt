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
     * Finds one page of up to [pageSize] eviction candidates, oldest first: not a seed, with
     * [Explanation.requestCount] at or below [maxRequestCount], and with
     * [Explanation.createdAtEpochMillis] below [olderThanEpochMillis].
     *
     * `createdAtEpochMillis` is a BSON Int64, not a BSON date. A TTL index expires a document a
     * fixed time after a stored date; it cannot compare a field against a threshold that moves
     * with every call. The `created_at` index still serves this query, because a plain ascending
     * index serves a range filter and a sort on the same field.
     *
     * ## [after], and why the sort carries a second key
     *
     * A caller that must page past a first page of candidates — `Components.evictExplanations`
     * does this when a page holds only referenced documents — passes the last document the
     * previous page returned as [after]. The next page then starts strictly past it: every
     * document with a later `createdAtEpochMillis`, plus every document at the same
     * `createdAtEpochMillis` with a later `_id`. The `_id` tie-break is load-bearing and not
     * decoration: two documents can share one `createdAtEpochMillis`, and a cursor keyed on the
     * date alone would either skip both on one side of a tie or repeat both on the other,
     * depending on which way the comparison leans.
     *
     * A candidate here is not yet safe to delete. `Components.evictExplanations` is the only
     * caller, and it still has to remove every key that a session references before it deletes
     * anything — see that method's own KDoc.
     */
    suspend fun findEvictionCandidates(
        maxRequestCount: Long,
        olderThanEpochMillis: Long,
        pageSize: Int,
        after: Explanation? = null,
    ): List<Explanation> {
        val filters = mutableListOf(
            Filters.ne("verb", Verb.SEED.name),
            Filters.lte("requestCount", maxRequestCount),
            Filters.lt("createdAtEpochMillis", olderThanEpochMillis),
        )
        if (after != null) {
            filters += Filters.or(
                Filters.gt("createdAtEpochMillis", after.createdAtEpochMillis),
                Filters.and(
                    Filters.eq("createdAtEpochMillis", after.createdAtEpochMillis),
                    Filters.gt("_id", after.key),
                ),
            )
        }
        return collection.find(Filters.and(filters))
            .sort(Indexes.compoundIndex(Indexes.ascending("createdAtEpochMillis"), Indexes.ascending("_id")))
            .limit(pageSize)
            .toList()
    }

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

    // ------------------------------------------------------------------ published (issue #48)

    /**
     * Sets [Explanation.published]. The only writer of this field anywhere in this project — see
     * that field's own KDoc. A curator calls this through the review script
     * (`scripts/PublishTopExplanations.kt`), never a request-time route.
     */
    suspend fun setPublished(key: String, published: Boolean) {
        collection.updateOne(Filters.eq("_id", key), Updates.set("published", published))
    }

    /**
     * Every published `EXPLAIN` node, for the glossary and for the sitemap. A `SEED` node names no
     * phrase, so it never belongs in a glossary, and a `VISUALIZE` node carries an SVG this project
     * does not render on a public page in this issue — see [Explanation.media]'s own KDoc — so
     * both are excluded by the `verb` filter, not by a second check downstream.
     */
    suspend fun findPublished(): List<Explanation> =
        collection.find(Filters.and(Filters.eq("verb", Verb.EXPLAIN.name), Filters.eq("published", true))).toList()

    /**
     * Finds every document whose `_id` starts with [prefix] (12 lowercase hex characters, per spec
     * section 7.1 of `docs/superpowers/specs/2026-09-19-public-surface-design.md`). Zero results is
     * an ordinary miss. More than one result is a short-key collision — the caller
     * (`ExplanationPageRoutes.kt`) answers `404` for both and logs `EXPLANATION_KEY_COLLISION` at
     * `WARN` with both keys, per spec section 7.2.
     *
     * MongoDB's own default index on `_id` (named `_id_`, created automatically for every
     * collection) already serves this range query in sorted order, so this method adds no new
     * index. `ComponentsTest`'s own index-listing test asserts that `_id_` exists on this
     * collection, so a later change cannot drop it without a test noticing.
     */
    suspend fun findByShortKeyPrefix(prefix: String): List<Explanation> {
        val upperBound = prefix.dropLast(1) + (prefix.last() + 1)
        return collection.find(Filters.and(Filters.gte("_id", prefix), Filters.lt("_id", upperBound))).toList()
    }

    /**
     * The top [limit] `EXPLAIN` nodes by [Explanation.requestCount], for the review script
     * (`scripts/PublishTopExplanations.kt`) to consider for publication. A `SEED` node is excluded
     * for the same reason [findPublished] excludes it: a seed names no phrase, so publishing one
     * would give the glossary an entry with nothing to define. The existing `topic_demand` index
     * (ascending `topicSlug`, descending `requestCount`) does not cover this query, because this
     * query has no `topicSlug` filter; a store at today's scale (well under the 512 MB Atlas M0
     * limit — see spec section 7.2's own store-size table) makes a full sort of the `EXPLAIN`
     * subset a bounded and infrequent cost, and this method runs only when a curator runs the
     * review script by hand, never on a request path.
     */
    suspend fun findTopByRequestCount(limit: Int): List<Explanation> =
        collection.find(Filters.eq("verb", Verb.EXPLAIN.name))
            .sort(Indexes.descending("requestCount"))
            .limit(limit)
            .toList()

    /**
     * The top [limit] published `EXPLAIN` nodes of [topicSlug], by [Explanation.requestCount]
     * descending — the "Popular questions" list `TopicPageHtml.kt` renders under a topic's seed,
     * per spec section 6.3. Empty while no explanation of this topic is published yet, which is
     * why `TopicPageHtml.kt` renders no such list in that case.
     *
     * The existing `topic_demand` index (ascending `topicSlug`, descending `requestCount`) serves
     * this query's filter and its sort together; no new index is added.
     */
    suspend fun findPublishedByTopic(topicSlug: String, limit: Int): List<Explanation> =
        collection.find(
            Filters.and(
                Filters.eq("topicSlug", topicSlug),
                Filters.eq("verb", Verb.EXPLAIN.name),
                Filters.eq("published", true),
            ),
        )
            .sort(Indexes.descending("requestCount"))
            .limit(limit)
            .toList()
}
