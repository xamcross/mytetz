package com.mytetz.api

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicRequestRepository
import com.mytetz.graph.ExplanationGraph
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.ExplanationValidator
import com.mytetz.llm.FakeLlmClient
import com.mytetz.session.SessionRepository
import com.mytetz.session.SessionService
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.MongoDBContainer

/**
 * One Mongo container for the whole `:backend:api` module, following the `MongoTestSupport` objects
 * in `:backend:graph`, `:backend:session` and `:backend:catalog`.
 *
 * The pattern it replaces is a `companion object { val container = MongoDBContainer(...).start() }`
 * per suite, which starts one container per test class. On this machine that has been the
 * difference between one container and several, and Testcontainers has failed here thirteen times.
 *
 * Everything below is built once and shared. Suites that mutate shared state must therefore choose
 * inputs that cannot collide with another suite's — [topicRequests] in particular is a single
 * collection, so each test uses text nothing else submits.
 *
 * **No `AnthropicLlmClient` is constructed anywhere in this file or reachable from it.** The
 * account has no credit, `AnthropicOkHttpClient.fromEnv()` demands a key at construction time, and
 * a fixture that quietly acquired one would put a paid API call one mistake away from every test in
 * the module. [sessions] takes a [FakeLlmClient] for that reason and not merely for speed.
 */
object TestFixtures {

    private val container = MongoDBContainer("mongo:7").apply { start() }
    private val client = MongoClient.create(container.connectionString)

    /** For the one test that must build a real [com.mytetz.persistence.Mongo] of its own. */
    val connectionString: String get() = container.connectionString

    /** Every consumer must pass a name unique across the module; nothing checks it at runtime. */
    fun database(name: String): MongoDatabase = client.getDatabase("test_api_$name")

    /**
     * A key of exactly [PrincipalCookieConfig.MIN_SIGNING_KEY_LENGTH] characters, and `secure=false`
     * so a test client speaking plain HTTP behaves the way a browser would.
     */
    val cookieConfig = PrincipalCookieConfig(
        signingKey = "0123456789abcdef0123456789abcdef",
        secure = false,
    )

    private val topicRepository: TopicRepository by lazy { TopicRepository(database("catalog")) }

    private val catalog: CatalogService by lazy {
        CatalogService(topicRepository).also {
            runBlocking {
                topicRepository.ensureIndexes()
                it.seedFromResource()
            }
        }
    }

    /** The real catalogue, seeded from `topics.json` exactly as production seeds it. */
    fun seededCatalog(): CatalogService = catalog

    /** The repository behind [seededCatalog], for a test that needs to plant an unpublished row. */
    fun topics(): TopicRepository = topicRepository.also { catalog }

    private val topicRequestRepository: TopicRequestRepository by lazy {
        TopicRequestRepository(database("topic_requests")).also { runBlocking { it.ensureIndexes() } }
    }

    fun topicRequests(): TopicRequestRepository = topicRequestRepository

    private val sessionService: SessionService by lazy {
        val database = database("sessions")
        val explanations = ExplanationRepository(database)
        val sessionRepository = SessionRepository(database)
        runBlocking {
            explanations.ensureIndexes()
            sessionRepository.ensureIndexes()
        }
        SessionService(
            sessions = sessionRepository,
            catalog = catalog,
            graph = ExplanationGraph(
                repository = explanations,
                llm = FakeLlmClient(),
                validator = ExplanationValidator(),
            ),
            explanations = explanations,
        )
    }

    /** A fully wired `SessionService` over a fake model. Task 1.12's SSE endpoint reuses this. */
    fun sessions(): SessionService = sessionService
}
