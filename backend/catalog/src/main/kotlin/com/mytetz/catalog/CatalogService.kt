package com.mytetz.catalog

import kotlinx.serialization.json.Json

class CatalogService(private val repository: TopicRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Idempotent: upserts by slug, so re-running only refreshes existing rows. */
    suspend fun seedFromResource(resourcePath: String = "/topics.json") {
        val text = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "catalogue seed resource $resourcePath not found"
        }.bufferedReader().use { it.readText() }

        json.decodeFromString<List<Topic>>(text).forEach { repository.upsert(it) }
    }

    suspend fun findBySlug(slug: String): Topic? = repository.findBySlug(slug)

    suspend fun listPublished(category: String?, query: String?): List<Topic> =
        repository.listPublished(category, query)
}
