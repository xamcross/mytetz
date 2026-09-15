package com.mytetz.assess

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuizRepositoryTest {

    private val database: MongoDatabase = MongoTestSupport.database("repository")
    private val repository = QuizRepository(database)

    private fun template(key: String = "k1") = QuizTemplate(
        key = key,
        kind = QuizKind.TEST_ME,
        scopeKeys = listOf("scope-1"),
        questions = listOf(QuizQuestion("q1", "stem", listOf("a", "b", "c", "d"), 0, "scope-1", "why")),
        promptVersion = "v1",
        modelFamily = "family",
        modelId = "model",
        inputTokens = 10,
        outputTokens = 5,
        costMicros = 100,
        requestCount = 0,
        createdAtEpochMillis = 0,
    )

    // MongoTestSupport gives every test method the same Mongo database. Drop
    // both collections before each test, the same way ExplanationRepositoryTest
    // does. This stops one test's documents from leaking into the next test.
    @BeforeTest
    fun reset(): Unit = runBlocking {
        database.getCollection<QuizTemplate>("quizTemplates").drop()
        database.getCollection<QuizAttempt>("quizAttempts").drop()
        repository.ensureIndexes()
    }

    @Test
    fun `findByKey answers null for an absent key`(): Unit = runBlocking {
        assertNull(repository.findByKey("missing"))
    }

    @Test
    fun `insertIfAbsent stores and returns the template`(): Unit = runBlocking {
        val stored = repository.insertIfAbsent(template())
        assertEquals("k1", stored.key)
        assertNotNull(repository.findByKey("k1"))
    }

    @Test
    fun `insertIfAbsent on a duplicate key returns the existing winner rather than throwing`(): Unit = runBlocking {
        repository.insertIfAbsent(template())
        val second = repository.insertIfAbsent(template().copy(costMicros = 999))
        // The stored document is the first insert's document, not the caller's own copy. This is
        // the same contract as ExplanationRepository.insertIfAbsent.
        assertEquals(100, second.costMicros)
    }

    @Test
    fun `incrementRequestCount increments`(): Unit = runBlocking {
        repository.insertIfAbsent(template())
        repository.incrementRequestCount("k1")
        repository.incrementRequestCount("k1")
        assertEquals(2, repository.findByKey("k1")?.requestCount)
    }

    @Test
    fun `attempts round-trip through upsert and find, both as a fresh insert and as an overwrite`(): Unit = runBlocking {
        val attempt = QuizAttempt(
            id = "a1",
            principalId = "user:1",
            sessionId = "s1",
            templateId = "k1",
            answers = emptyList(),
            score = null,
            total = 1,
            createdAtEpochMillis = 0,
            submittedAtEpochMillis = null,
        )
        repository.upsertAttempt(attempt)
        assertNotNull(repository.findAttempt("a1"))

        val scored = attempt.copy(
            answers = listOf(AnsweredQuestion("q1", 0)),
            score = 1,
            submittedAtEpochMillis = 123,
        )
        repository.upsertAttempt(scored)
        assertEquals(1, repository.findAttempt("a1")?.score)
    }
}
