package com.mytetz.graph

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExplanationRepositoryTest {

    private val database = MongoTestSupport.database("explanations")
    private val repository = ExplanationRepository(database)

    private fun explanation(key: String, body: String) = Explanation(
        key = key,
        topicSlug = "quantum-physics",
        parentKey = null,
        span = null,
        spanSentence = null,
        verb = Verb.SEED,
        variant = 0,
        depth = 0,
        body = body,
        grounded = false,
        sources = emptyList(),
        promptVersion = "v1",
        modelFamily = "claude-opus-5",
        modelId = "claude-opus-5",
        inputTokens = 10,
        outputTokens = 20,
        costMicros = 550,
        requestCount = 0,
        createdAtEpochMillis = 1_700_000_000_000,
    )

    @BeforeTest
    fun reset() = runTest {
        database.getCollection<Explanation>("explanations").drop()
        repository.ensureIndexes()
    }

    @Test
    fun `findByKey returns null when absent`() = runTest {
        assertNull(repository.findByKey("missing"))
    }

    @Test
    fun `insertIfAbsent stores and findByKey reads it back`() = runTest {
        repository.insertIfAbsent(explanation("k1", "Quantum mechanics is…"))

        val found = repository.findByKey("k1")

        assertEquals("Quantum mechanics is…", found?.body)
        assertEquals(Verb.SEED, found?.verb)
    }

    @Test
    fun `insertIfAbsent returns the winner and never overwrites`() = runTest {
        repository.insertIfAbsent(explanation("k2", "first"))

        val returned = repository.insertIfAbsent(explanation("k2", "second"))

        assertEquals("first", returned.body, "loser must receive the stored document")
        assertEquals("first", repository.findByKey("k2")?.body, "stored body must be immutable")
    }

    @Test
    fun `concurrent inserts of the same key leave exactly one document`() = runTest {
        coroutineScope {
            (1..12).map { i ->
                async { repository.insertIfAbsent(explanation("race", "body-$i")) }
            }.awaitAll()
        }

        val stored = database.getCollection<Explanation>("explanations")
            .find(com.mongodb.client.model.Filters.eq("_id", "race"))
            .count()

        assertEquals(1, stored)
    }

    @Test
    fun `incrementRequestCount is additive`() = runTest {
        repository.insertIfAbsent(explanation("k3", "body"))

        repeat(3) { repository.incrementRequestCount("k3") }

        assertEquals(3, repository.findByKey("k3")?.requestCount)
    }
}
