package com.mytetz.catalog

import kotlinx.serialization.json.Json

class CatalogService(private val repository: TopicRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Idempotent: upserts by slug, so re-running only refreshes existing rows.
     *
     * Runs on **every boot** since Task 1.11 wired it into `Components.bootstrap`, and on this
     * deployment a boot happens on any request that arrives after the machine has scaled to zero.
     * That is why it writes through [TopicRepository.upsertPreservingStatus] rather than
     * [TopicRepository.upsert]: re-running must refresh a topic's content and must never resurrect
     * one an operator withdrew. See that method for the division of authority.
     */
    suspend fun seedFromResource(resourcePath: String = "/topics.json") {
        val text = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "catalogue seed resource $resourcePath not found"
        }.bufferedReader().use { it.readText() }

        json.decodeFromString<List<Topic>>(text).forEach { repository.upsertPreservingStatus(it) }
    }

    suspend fun findBySlug(slug: String): Topic? = repository.findBySlug(slug)

    suspend fun listPublished(category: String?, query: String?): List<Topic> =
        repository.listPublished(category, query)
}
