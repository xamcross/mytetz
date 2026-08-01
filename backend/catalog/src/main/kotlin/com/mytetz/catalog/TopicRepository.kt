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

    suspend fun upsert(topic: Topic) {
        collection.replaceOne(
            Filters.eq("_id", topic.slug),
            topic,
            ReplaceOptions().upsert(true),
        )
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
