package com.mytetz.graph.scripts

import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.MAX_PUBLISHED_EXPLANATIONS
import com.mytetz.graph.MongoTestSupport
import com.mytetz.graph.Verb
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every test here runs against a real Mongo container through [MongoTestSupport], the project's
 * own Testcontainers fixture — never against a live database. This file is the proof this issue's
 * safety rule asks for: the review path is built and tested against Testcontainers only.
 */
class PublishTopExplanationsTest {

    private val database = MongoTestSupport.database("publish_top_explanations")
    private val repository = ExplanationRepository(database)

    private fun explanation(key: String, requestCount: Long, published: Boolean = false) = Explanation(
        key = key, topicSlug = "quantum-physics", parentKey = "p", span = "span-$key", spanSentence = "s",
        verb = Verb.EXPLAIN, variant = 0, depth = 1, body = "b", grounded = false, sources = emptyList(),
        promptVersion = "v1", modelFamily = "fake-model", modelId = "fake-model",
        inputTokens = 1, outputTokens = 1, costMicros = 0, requestCount = requestCount,
        createdAtEpochMillis = 0, published = published,
    )

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Explanation>("explanations").drop()
        repository.ensureIndexes()
    }

    @Test
    fun `a dry run lists the top nodes and writes nothing`() = runTest {
        repository.insertIfAbsent(explanation("low", requestCount = 1))
        repository.insertIfAbsent(explanation("high", requestCount = 9))

        val selected = publishTopExplanations(repository, dryRun = true)

        assertEquals(listOf("high", "low"), selected.map { it.key })
        assertFalse(repository.findByKey("high")!!.published, "a dry run must write nothing")
        assertFalse(repository.findByKey("low")!!.published, "a dry run must write nothing")
    }

    @Test
    fun `a live run sets published on each selected node`() = runTest {
        repository.insertIfAbsent(explanation("low", requestCount = 1))
        repository.insertIfAbsent(explanation("high", requestCount = 9))

        val selected = publishTopExplanations(repository, dryRun = false)

        assertEquals(listOf("high", "low"), selected.map { it.key })
        assertTrue(repository.findByKey("high")!!.published)
        assertTrue(repository.findByKey("low")!!.published)
    }

    @Test
    fun `an already-published node is not selected again`() = runTest {
        repository.insertIfAbsent(explanation("already", requestCount = 9))
        repository.setPublished("already", true)
        repository.insertIfAbsent(explanation("new", requestCount = 5))

        val selected = publishTopExplanations(repository, dryRun = false)

        assertEquals(listOf("new"), selected.map { it.key })
    }

    @Test
    fun `the cap is never crossed — publishing stops at exactly the room the cap still allows`() = runTest {
        // 99 already published, one under the cap of 100. Two more candidates compete for that
        // one remaining slot; only the higher-demand one may be selected.
        repeat(99) { i ->
            repository.insertIfAbsent(explanation("already-$i", requestCount = 1000L - i))
            repository.setPublished("already-$i", true)
        }
        repository.insertIfAbsent(explanation("candidate-low", requestCount = 1))
        repository.insertIfAbsent(explanation("candidate-high", requestCount = 2))

        val selected = publishTopExplanations(repository, dryRun = false, cap = MAX_PUBLISHED_EXPLANATIONS)

        assertEquals(listOf("candidate-high"), selected.map { it.key })
        assertEquals(MAX_PUBLISHED_EXPLANATIONS, repository.findPublished().size)
    }

    @Test
    fun `at the cap already, a run selects nothing and writes nothing`() = runTest {
        repeat(100) { i ->
            repository.insertIfAbsent(explanation("already-$i", requestCount = 1))
            repository.setPublished("already-$i", true)
        }
        repository.insertIfAbsent(explanation("never", requestCount = 1000))

        val selected = publishTopExplanations(repository, dryRun = false, cap = 100)

        assertEquals(emptyList(), selected)
        assertNull(repository.findByKey("never")?.published?.takeIf { it }, "the 101st node stays unpublished")
        assertEquals(100, repository.findPublished().size)
    }

    @Test
    fun `a custom, smaller cap is honoured too`() = runTest {
        repository.insertIfAbsent(explanation("a", requestCount = 3))
        repository.insertIfAbsent(explanation("b", requestCount = 2))
        repository.insertIfAbsent(explanation("c", requestCount = 1))

        val selected = publishTopExplanations(repository, dryRun = false, cap = 2)

        assertEquals(listOf("a", "b"), selected.map { it.key })
        assertNotNull(repository.findByKey("c"))
        assertFalse(repository.findByKey("c")!!.published)
    }
}
