package com.mytetz.catalog

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndDeleteOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a learner asked for that the catalogue does not have, and how many people asked.
 *
 * The normalised text is the `_id`, so "Organic  Chemistry" and "organic chemistry" are one row and
 * one vote each rather than two topics nobody has heard of. [rawText] keeps the most recent spelling
 * a human actually typed, because the normalised form is for grouping and reads badly on a list an
 * editor is working through.
 */
@Serializable
data class TopicRequest(
    @SerialName("_id") val normalizedText: String,
    val rawText: String,
    val count: Long,
    val firstSeenAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
)

/** What [TopicRequestRepository.record] did. The caller turns this into a status code. */
enum class RecordOutcome {
    RECORDED,

    /** Empty after normalisation, or longer than [TopicRequestRepository.MAX_TEXT_LENGTH]. */
    INVALID_TEXT,
}

/**
 * The demand signal for a curated-only catalogue: the queue of topics somebody wanted and we do not
 * have yet.
 *
 * ## Everything here is written by an endpoint anyone can call
 *
 * `POST /api/topic-requests` needs no account and is the only write path into this collection, so
 * every bound this collection has is a bound enforced in this class. That is deliberate rather than
 * incidental: a check that lives in the route is a check the next route to be added does not have.
 *
 * Two bounds, and they do different jobs:
 *
 * 1. **[MAX_TEXT_LENGTH]** caps one row. A topic request is a phrase, not an essay, and the value
 *    becomes a Mongo `_id` — which has a hard 1024-byte limit of its own that a caller must not be
 *    able to discover by hitting it.
 * 2. **[maxDistinctRequests]** caps the *collection*. This is the one that matters. Without it,
 *    every distinct normalised string mints a new document for ever, so a script sending random
 *    words fills the database — the same unbounded-growth class as the missing TTL index found in
 *    Task 1.8, arriving through a different door.
 *
 * The cap bounds growth and not interest: once it is reached, a request for text already in the
 * collection still increments. Freezing the counts at the moment the cap is hit would be worst
 * exactly when the signal starts to matter, and it would hand a spammer a way to silence everyone
 * else's votes as well as their own.
 *
 * ### Full means evict the weakest, not refuse the newest
 *
 * A permanent ceiling bounds storage and then hands a stranger a second lever: ~[maxDistinctRequests]
 * junk phrases fill every slot, and from then on every genuine request is refused until a human
 * triages the backlog by hand. For the *only* demand signal a curated catalogue has, "switchable off
 * by anyone" is not an acceptable resting state.
 *
 * So a full collection evicts its weakest row — **lowest [TopicRequest.count] first, oldest
 * [TopicRequest.lastSeenAtEpochMillis] to break the tie** — and admits the new one. That ordering is
 * the point: the row everybody asked for outlives a flood of one-shot rows, so what survives is a
 * bounded top-N by demand, which is what this collection wanted to be anyway. It self-heals the
 * moment the flood stops, with no operator involved.
 *
 * The cost, stated rather than glossed: a sustained flood does erase genuine requests that only ever
 * had one vote. Nothing here can prevent that — the storage is finite — and preferring to discard
 * count-1 rows is the least-bad rule available.
 *
 * ### What the cap does not promise
 *
 * The count-then-insert in [record] is several round trips with no transaction between them, so N
 * concurrent first-sightings of N distinct new strings can each see room and each insert, leaving
 * the collection up to N over. The overshoot is bounded by the API layer's concurrency and not by
 * this class — the same honest bound `QuotaService` documents for its own check-then-record pair.
 * A hard guarantee would need a transaction on every request to a public endpoint, which costs more
 * than the few rows it saves.
 *
 * Rate limiting is **not** here. It is per-principal, and this class knows nothing about principals;
 * see `FixedWindowRateLimiter` in `:backend:api`.
 */
class TopicRequestRepository(
    database: MongoDatabase,
    private val maxDistinctRequests: Long = resolveMaxDistinctRequests(System.getenv(MAX_DISTINCT_ENV)),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Called with each row [record] discards to make room. This module has no logger of its own, and
     * a collection quietly recycling itself is something an operator should be able to see — the API
     * layer supplies one that logs.
     */
    private val evictionListener: (TopicRequest) -> Unit = {},
) {

    private val collection = database.getCollection<TopicRequest>("topicRequests")

    /**
     * `demand` serves "what is most asked for", the only question anyone asks of this collection.
     * `weakest` serves [record]'s eviction sort, which runs on every new row once the collection is
     * full — that is, under exactly the flood that makes it run, where a collection scan per request
     * is the difference between a bounded cost and a self-inflicted outage.
     */
    suspend fun ensureIndexes() {
        collection.createIndex(Indexes.descending("count"), IndexOptions().name("demand"))
        collection.createIndex(
            Indexes.compoundIndex(Indexes.ascending("count"), Indexes.ascending("lastSeenAtEpochMillis")),
            IndexOptions().name("weakest"),
        )
    }

    /**
     * Records one request for [rawText], subject to both bounds.
     *
     * Validation is here rather than at the route so that it cannot be bypassed, and it runs
     * **after** normalisation: a length checked before trimming is a length that can be padded past,
     * and it is not the length of the string that actually gets stored.
     */
    suspend fun record(rawText: String): RecordOutcome {
        val normalized = normalize(rawText)
        if (normalized.isEmpty() || normalized.length > MAX_TEXT_LENGTH) return RecordOutcome.INVALID_TEXT

        val now = clock()
        val update = Updates.combine(
            Updates.inc("count", 1L),
            // The spelling a human typed, for the editor working through the list. Capped
            // independently of the normalised form: collapsing a run of whitespace can take a
            // 10_000-character submission down to a valid three-character `_id`, so the raw text
            // being short is not implied by the normalised text being short.
            Updates.set("rawText", rawText.trim().take(MAX_TEXT_LENGTH)),
            Updates.set("lastSeenAtEpochMillis", now),
        )

        // Existing row first, with upsert deliberately OFF: an increment adds no document, so it
        // must not be made to wait behind a capacity check it can never fail.
        val matched = collection.updateOne(Filters.eq("_id", normalized), update).matchedCount
        if (matched > 0L) return RecordOutcome.RECORDED

        // Only a genuinely new row costs capacity. Evict rather than refuse — see "Full means evict
        // the weakest" above — and loop, because a concurrent writer can have taken the slot this
        // one just freed. Bounded by construction: each pass deletes a row.
        while (collection.countDocuments() >= maxDistinctRequests) {
            val evicted = collection.findOneAndDelete(
                Filters.empty(),
                FindOneAndDeleteOptions().sort(
                    Indexes.compoundIndex(
                        Indexes.ascending("count"),
                        Indexes.ascending("lastSeenAtEpochMillis"),
                    )
                ),
            ) ?: break // Nothing left to evict; the cap must be 0-ish. Admit rather than spin.
            evictionListener(evicted)
        }

        collection.updateOne(
            Filters.eq("_id", normalized),
            Updates.combine(update, Updates.setOnInsert("firstSeenAtEpochMillis", now)),
            UpdateOptions().upsert(true),
        )
        return RecordOutcome.RECORDED
    }

    /** How many distinct requests are stored. The cap is on this number. */
    suspend fun countDistinct(): Long = collection.countDocuments()

    /**
     * How many times [text] has been asked for, in **any** spelling.
     *
     * Takes raw text and normalises it here. The shape this replaces took an already-normalised
     * string while `normalize` was public, which made "the caller forgot to normalise" and "nobody
     * has ever asked for this" the same answer — 0 — with nothing to distinguish them. Normalisation
     * is idempotent, so passing an already-normalised string is still correct.
     */
    suspend fun countFor(text: String): Long = find(text)?.count ?: 0

    /** The stored row for [text] in any spelling, or null. */
    suspend fun find(text: String): TopicRequest? =
        collection.find(Filters.eq("_id", normalize(text))).firstOrNull()

    companion object {

        const val MAX_DISTINCT_ENV: String = "MYTETZ_MAX_TOPIC_REQUESTS"

        /** A phrase, not an essay. Also keeps the `_id` far below Mongo's 1024-byte index limit. */
        const val MAX_TEXT_LENGTH: Int = 200

        /**
         * Room for a large multiple of anything a curated catalogue would ever absorb — the seeded
         * catalogue holds a few dozen topics — while still being a number.
         */
        const val DEFAULT_MAX_DISTINCT_REQUESTS: Long = 5_000

        /**
         * Idempotent by construction: trimming, lowercasing and collapsing runs of whitespace all
         * reach a fixed point on the first pass. [countFor] depends on that.
         */
        fun normalize(text: String): String = text.trim().lowercase().replace(WHITESPACE_RUN, " ")

        /**
         * A missing, unparseable or non-positive override falls back to the default rather than
         * throwing — the same reasoning, and the same shape, as `GraphConfig.resolveMaxOutputTokens`
         * and `QuotaConfig.resolveDailyExplains`. Here the default really is the safe value: it is
         * the bound, and a `0` taken literally would refuse every request while a negative would be
         * meaningless.
         */
        internal fun resolveMaxDistinctRequests(raw: String?): Long =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_DISTINCT_REQUESTS

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
