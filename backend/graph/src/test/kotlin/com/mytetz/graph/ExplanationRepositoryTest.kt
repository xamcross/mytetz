package com.mytetz.graph

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExplanationRepositoryTest {

    private val database = MongoTestSupport.database("explanations")
    private val repository = ExplanationRepository(database)

    private fun explanation(
        key: String,
        body: String,
        modelFamily: String = "claude-opus-5",
    ) = Explanation(
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
        modelFamily = modelFamily,
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
        val results = coroutineScope {
            (1..12).map { i ->
                async { repository.insertIfAbsent(explanation("race", "body-$i")) }
            }.awaitAll()
        }

        // Counting documents alone is nearly free once insertOne (not upsert) is used: Mongo's
        // own _id uniqueness already guarantees exactly one document regardless of whether
        // insertIfAbsent's contract is honoured. What actually needs proving is the contract
        // itself — "returns the stored document, which is the existing one if another writer
        // won" — so every one of the 12 callers must have received the SAME body (the winner's),
        // not a mix of stale reads or their own losing copy.
        assertEquals(1, results.map { it.body }.toSet().size, "callers disagreed on the stored body")

        val stored = database.getCollection<Explanation>("explanations")
            .find(com.mongodb.client.model.Filters.eq("_id", "race"))
            .count()

        assertEquals(1, stored)
        assertEquals(repository.findByKey("race")?.body, results.first().body, "agreed body must be what actually persisted")
    }

    @Test
    fun `incrementRequestCount is additive`() = runTest {
        repository.insertIfAbsent(explanation("k3", "body"))

        repeat(3) { repository.incrementRequestCount("k3") }

        assertEquals(3, repository.findByKey("k3")?.requestCount)
    }

    @Test
    fun `deleting by family removes every other family and reports the count`() = runTest {
        repository.insertIfAbsent(explanation("a", "an old body", modelFamily = "claude-opus-5"))
        repository.insertIfAbsent(explanation("b", "another old body", modelFamily = "claude-opus-5"))
        repository.insertIfAbsent(explanation("c", "a current body", modelFamily = "claude-sonnet-5"))

        val deleted = repository.deleteWhereModelFamilyIsNot("claude-sonnet-5")

        assertEquals(2, deleted)
        assertNull(repository.findByKey("a"))
        assertNull(repository.findByKey("b"))
        assertNotNull(repository.findByKey("c"), "the current family survives")
    }

    @Test
    fun `deleting by family is idempotent`() = runTest {
        repository.insertIfAbsent(explanation("a", "an old body", modelFamily = "claude-opus-5"))

        assertEquals(1, repository.deleteWhereModelFamilyIsNot("claude-sonnet-5"))
        assertEquals(
            0,
            repository.deleteWhereModelFamilyIsNot("claude-sonnet-5"),
            "a second run finds nothing",
        )
    }

    @Test
    fun `deleting by family on an empty store reports zero`() = runTest {
        assertEquals(0, repository.deleteWhereModelFamilyIsNot("claude-sonnet-5"))
    }
}
