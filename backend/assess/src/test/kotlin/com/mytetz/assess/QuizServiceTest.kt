package com.mytetz.assess

import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.mytetz.llm.FakeLlmClient
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuizServiceTest {

    private val database: MongoDatabase = MongoTestSupport.database("service")

    // MongoTestSupport gives every test method in this class the same Mongo database.
    // Most tests here share one scope key and one quiz kind. Without a reset, they would
    // collide on the same content key. Drop both collections before each test, the same
    // way QuizRepositoryTest does. Then one test's stored template cannot answer another
    // test's cache lookup.
    @BeforeTest
    fun reset(): Unit = runBlocking {
        database.getCollection<QuizTemplate>("quizTemplates").drop()
        database.getCollection<QuizAttempt>("quizAttempts").drop()
    }

    private fun repository() = QuizRepository(database)

    private val sources = listOf(QuizSource("key-1", "The sky is blue because of Rayleigh scattering."))

    private fun validQuestionJson(sourceKey: String = "key-1") = """
        {"questions":[{"stem":"Why is the sky blue?","options":["a","b","c","d"],"correctIndex":1,"sourceKey":"$sourceKey","rationale":"Rayleigh scattering."}]}
    """.trimIndent()

    @Test
    fun `generates, validates and persists a template, and reports its cost`() = runBlocking {
        val llm = FakeLlmClient().apply { nextStructuredJson = validQuestionJson() }
        val service = QuizService(repository(), llm, QuizValidator())
        var spent = 0L

        val template = service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) { spent += it }

        assertEquals(1, template.questions.size)
        assertEquals("key-1", template.questions.single().sourceKey)
        assertTrue(spent > 0, "a real generation must report a positive cost")
        assertEquals(1, llm.structuredCalls.size)
    }

    @Test
    fun `two calls with the same scope hit one template document`() = runBlocking {
        val llm = FakeLlmClient().apply { nextStructuredJson = validQuestionJson() }
        val repo = repository()
        val service = QuizService(repo, llm, QuizValidator())

        val first = service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}
        val second = service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}

        assertEquals(first.key, second.key)
        assertEquals(1, llm.structuredCalls.size, "the second call must be a cache hit and call no model")
    }

    @Test
    fun `an out-of-scope sourceKey is dropped, and a retry with a nudge can still succeed`() = runBlocking {
        val llm = FakeLlmClient().apply {
            nextStructuredJson = """{"questions":[{"stem":"x","options":["a","b","c","d"],"correctIndex":0,"sourceKey":"not-in-scope","rationale":"r"}]}"""
            structuredJsonByPromptSubstring["previous attempt"] = validQuestionJson()
        }
        val service = QuizService(repository(), llm, QuizValidator())

        val template = service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}

        assertEquals(1, template.questions.size)
        assertEquals(2, llm.structuredCalls.size, "the first attempt is invalid, so a nudged retry must follow")
    }

    @Test
    fun `nothing valid on either attempt raises QuizUnavailableException`() = runBlocking {
        val llm = FakeLlmClient().apply { nextStructuredJson = """{"questions":[]}""" }
        val service = QuizService(repository(), llm, QuizValidator())

        assertFailsWith<QuizUnavailableException> {
            service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}
        }
        assertEquals(2, llm.structuredCalls.size, "both the first attempt and the one retry must have run")
    }

    @Test
    fun `malformed json from the model is treated as no valid questions rather than a crash`(): Unit = runBlocking {
        val llm = FakeLlmClient().apply { nextStructuredJson = "not json" }
        val service = QuizService(repository(), llm, QuizValidator())

        assertFailsWith<QuizUnavailableException> {
            service.getOrGenerate(listOf("key-1"), QuizKind.TEST_ME, sources) {}
        }
    }

    @Test
    fun `scoring counts each correct answer and records the submission time`() = runBlocking {
        val service = QuizService(repository(), FakeLlmClient(), QuizValidator())
        val template = QuizTemplate(
            key = "k1", kind = QuizKind.TEST_ME, scopeKeys = listOf("key-1"),
            questions = listOf(
                QuizQuestion("q1", "stem1", listOf("a", "b", "c", "d"), correctIndex = 0, sourceKey = "key-1", rationale = "r1"),
                QuizQuestion("q2", "stem2", listOf("a", "b", "c", "d"), correctIndex = 2, sourceKey = "key-1", rationale = "r2"),
            ),
            promptVersion = "v1", modelFamily = "f", modelId = "m",
            inputTokens = 0, outputTokens = 0, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
        )
        val attempt = QuizAttempt(
            id = "a1", principalId = "user:1", sessionId = "s1", templateId = "k1",
            answers = emptyList(), score = null, total = 2, createdAtEpochMillis = 0, submittedAtEpochMillis = null,
        )

        val scored = service.score(
            attempt,
            template,
            answers = listOf(AnsweredQuestion("q1", chosenIndex = 0), AnsweredQuestion("q2", chosenIndex = 1)),
            clock = { 999 },
        )

        assertEquals(1, scored.score, "only q1 was answered correctly")
        assertEquals(999, scored.submittedAtEpochMillis)
    }

    @Test
    fun `an unanswered question counts as wrong rather than throwing`() = runBlocking {
        val service = QuizService(repository(), FakeLlmClient(), QuizValidator())
        val template = QuizTemplate(
            key = "k1", kind = QuizKind.TEST_ME, scopeKeys = listOf("key-1"),
            questions = listOf(QuizQuestion("q1", "stem1", listOf("a", "b", "c", "d"), correctIndex = 0, sourceKey = "key-1", rationale = "r1")),
            promptVersion = "v1", modelFamily = "f", modelId = "m",
            inputTokens = 0, outputTokens = 0, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
        )
        val attempt = QuizAttempt(
            id = "a1", principalId = "user:1", sessionId = "s1", templateId = "k1",
            answers = emptyList(), score = null, total = 1, createdAtEpochMillis = 0, submittedAtEpochMillis = null,
        )

        val scored = service.score(attempt, template, answers = emptyList())

        assertEquals(0, scored.score)
    }

    @Test
    fun `an answer for a question id outside the template is dropped, not scored or stored`() = runBlocking {
        val service = QuizService(repository(), FakeLlmClient(), QuizValidator())
        val template = QuizTemplate(
            key = "k1", kind = QuizKind.TEST_ME, scopeKeys = listOf("key-1"),
            questions = listOf(QuizQuestion("q1", "stem1", listOf("a", "b", "c", "d"), correctIndex = 0, sourceKey = "key-1", rationale = "r1")),
            promptVersion = "v1", modelFamily = "f", modelId = "m",
            inputTokens = 0, outputTokens = 0, costMicros = 0, requestCount = 0, createdAtEpochMillis = 0,
        )
        val attempt = QuizAttempt(
            id = "a1", principalId = "user:1", sessionId = "s1", templateId = "k1",
            answers = emptyList(), score = null, total = 1, createdAtEpochMillis = 0, submittedAtEpochMillis = null,
        )

        val scored = service.score(
            attempt,
            template,
            answers = listOf(
                AnsweredQuestion("q1", chosenIndex = 0),
                AnsweredQuestion("not-a-real-question-id", chosenIndex = 3),
            ),
        )

        assertEquals(1, scored.score, "q1 is still scored correctly")
        assertEquals(
            listOf(AnsweredQuestion("q1", chosenIndex = 0)),
            scored.answers,
            "the answer for the invented question id must not be stored",
        )
    }
}
