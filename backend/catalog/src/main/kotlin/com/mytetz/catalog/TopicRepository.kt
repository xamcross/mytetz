package com.mytetz.catalog

import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

class TopicRepository(database: MongoDatabase) {

    private val collection = database.getCollection<Topic>("topics")

    suspend fun ensureIndexes() {
        collection.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending("status"),
                Indexes.ascending("category"),
                Indexes.ascending("sortWeight"),
            ),
            IndexOptions().name("browse"),
        )
    }

    /** Writes [topic] whole, publication status included. The admin-shaped write. */
    suspend fun upsert(topic: Topic) {
        collection.replaceOne(
            Filters.eq("_id", topic.slug),
            topic,
            ReplaceOptions().upsert(true),
        )
    }

    /**
     * [upsert], except that an existing row keeps the [Topic.status] it already has.
     *
     * This is the write `CatalogService.seedFromResource` uses, and the difference matters because
     * Task 1.11 made seeding run on **every boot** — including every scale-from-zero wake, which on
     * this deployment is any request after an idle period. A plain [upsert] takes `status` straight
     * back from `topics.json`, so an operator who withdrew a topic would find it republished by the
     * next request to arrive, with nothing in the logs to say so and no admin API to do it again.
     *
     * The division of authority it establishes: **`topics.json` owns a topic's content, the stored
     * row owns its publication.** So a typo in a summary is still fixed by editing the seed file and
     * redeploying, and withdrawing a topic is still done once. Adding a *new* topic naturally takes
     * the file's status, because there is no stored row to preserve.
     *
     * Read-then-write, and deliberately unguarded. The alternative — a field-by-field `$set` with
     * `$setOnInsert` on `status` — has to list every property of [Topic] and silently stops writing
     * any property added later. The race it leaves needs an operator to change a topic's status in
     * the microseconds between this read and its write, during a boot; the same operator can simply
     * do it again. Nothing else in the system writes `status`.
     */
    suspend fun upsertPreservingStatus(topic: Topic) {
        val storedStatus = findBySlug(topic.slug)?.status
        upsert(if (storedStatus == null) topic else topic.copy(status = storedStatus))
    }

    suspend fun findBySlug(slug: String): Topic? =
        collection.find(Filters.eq("_id", slug)).firstOrNull()

    suspend fun listPublished(category: String?, query: String?): List<Topic> {
        val filters = buildList {
            add(Filters.eq("status", TopicStatus.PUBLISHED.name))
            category?.takeIf { it.isNotBlank() }?.let { add(Filters.eq("category", it)) }
            query?.takeIf { it.isNotBlank() }?.let { q ->
                // `q` arrives from a URL parameter. Interpolating it into a $regex unescaped is a
                // denial-of-service vector: a crafted pattern can pin a CPU on every matched
                // document. Pattern.quote wraps it in \Q…\E so it can only ever match literally.
                val escaped = java.util.regex.Pattern.quote(q)
                add(
                    Filters.or(
                        Filters.regex("title", escaped, "i"),
                        Filters.regex("aliases", escaped, "i"),
                        Filters.regex("summary", escaped, "i"),
                    )
                )
            }
        }
        return collection.find(Filters.and(filters))
            .sort(Indexes.compoundIndex(Indexes.ascending("sortWeight"), Indexes.ascending("title")))
            .toList()
    }
}
