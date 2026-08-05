package com.mytetz.api

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicRequestRepository
import com.mongodb.client.model.Filters
import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationGraph
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.ExplanationValidator
import com.mytetz.llm.FakeLlmClient
import com.mytetz.quota.QuotaConfig
import com.mytetz.quota.QuotaRepository
import com.mytetz.quota.QuotaService
import com.mytetz.session.SessionRepository
import com.mytetz.session.SessionService
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.testcontainers.containers.MongoDBContainer
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * The seed body every session in this module opens on. Keyed on the phrase `PromptBuilder` puts
     * in a SEED prompt, so a seed and a child explanation are distinguishable.
     */
    const val SEED_BODY: String = "Quantum mechanics is the fundamental physical theory that " +
        "describes the behavior of matter and of light at and below the scale of atoms."

    const val CHILD_BODY: String = "The microscopic realm studied by quantum theory is the " +
        "subatomic scale, a universe smaller than 0.1 nanometers where the traditional laws of " +
        "physics collapse."

    private val databaseCounter = AtomicInteger()

    /**
     * A whole session stack — store, quota ledger and model — wired **per test**.
     *
     * Sharing it would be cheaper and wrong in three separate ways, each of which produces a green
     * suite that pins nothing:
     *
     * - the **cost ledger** is one document per UTC day, so a test that trips the breaker trips it
     *   for everything that runs after it;
     * - the **explanation store** is content addressed, so a test that expects to *generate* is
     *   served from cache the moment another test has asked for the same span — and a test whose
     *   generation never happens cannot observe a gate that stops generation;
     * - the **principal counter** is per principal and the fixture's client returns its cookie.
     *
     * The Mongo container is still shared; only the databases are new.
     */
    fun sessionApp(
        dailyExplains: Int = QuotaConfig.DEFAULT_DAILY_EXPLAINS,
        costCeilingMicros: Long = QuotaConfig.DEFAULT_COST_CEILING_MICROS,
        sessionsPerCaller: Int = SESSIONS_PER_CALLER,
        explainsPerCaller: Int = EXPLAINS_PER_CALLER,
    ): SessionStack {
        val database = database("session_app_${databaseCounter.incrementAndGet()}")
        val explanations = SeamedExplanationRepository(database)
        val sessionRepository = SessionRepository(database)
        val quotaRepository = QuotaRepository(database)
        runBlocking {
            explanations.ensureIndexes()
            sessionRepository.ensureIndexes()
            quotaRepository.ensureIndexes()
        }

        val llm = FakeLlmClient().apply {
            bodyByPromptSubstring["opening paragraph"] = SEED_BODY
            nextBody = CHILD_BODY
        }

        return SessionStack(
            database = database,
            llm = llm,
            explanations = explanations,
            sessions = SessionService(
                sessions = sessionRepository,
                catalog = catalog,
                graph = ExplanationGraph(
                    repository = explanations,
                    llm = llm,
                    validator = ExplanationValidator(),
                ),
                explanations = explanations,
            ),
            quota = QuotaService(
                quotaRepository,
                QuotaConfig(
                    dailyExplains = dailyExplains,
                    globalDailyCostCeilingMicros = costCeilingMicros,
                ),
            ),
            quotaRepository = quotaRepository,
            limiter = FixedWindowRateLimiter(limit = sessionsPerCaller, windowMillis = SESSION_WINDOW_MILLIS),
            explainLimiter = FixedWindowRateLimiter(
                limit = explainsPerCaller,
                windowMillis = EXPLAIN_WINDOW_MILLIS,
            ),
        )
    }

    class SessionStack(
        val database: MongoDatabase,
        val llm: FakeLlmClient,
        val explanations: SeamedExplanationRepository,
        val sessions: SessionService,
        val quota: QuotaService,
        val quotaRepository: QuotaRepository,
        val limiter: FixedWindowRateLimiter,
        val explainLimiter: FixedWindowRateLimiter,
    ) {
        /** How many model calls have been made. The only honest answer to "did that generate?". */
        val generations: Int get() = llm.calls.size

        /**
         * The first lookup of [key] answers "absent" — which is the truth on this path — and the
         * **second** raises.
         *
         * The second lookup is the quota re-check's own `prepare`, which is the read this is about.
         * Arming only the failure would blow up the *first* `prepare` instead, before the gate has
         * run at all, and test nothing.
         */
        fun failTheRecheckLookupOf(key: String) {
            explanations.hideOnce = key
            explanations.failOnce = key
        }

        /**
         * Removes the session document out from under an in-flight request.
         *
         * There is no delete path in `SessionRepository` and there should not be; this reaches past
         * it deliberately, to produce the one state the API layer has to survive — `appendNode`
         * raising **after** `insertIfAbsent` has already bought and stored an explanation.
         */
        suspend fun deleteSession(sessionId: String) {
            database.getCollection<Document>("sessions").deleteOne(Filters.eq("_id", sessionId))
        }
    }

    /**
     * Makes one key disappear for exactly one read.
     *
     * That interleaving — a key that is absent when `prepare` looks and present when the request is
     * finally served — is the one `SessionService` warns about at length and the one an API layer
     * gets wrong by refusing a request that had become free. It needs two callers landing either
     * side of a single insert, which no amount of test sequencing can arrange deterministically, so
     * it is arranged here instead. See `ExplanationRepository`, which is `open` for this.
     */
    class SeamedExplanationRepository(database: MongoDatabase) : ExplanationRepository(database) {

        /** The next `findByKey` for this key answers null, once, and then clears itself. */
        @Volatile
        var hideOnce: String? = null

        /**
         * The next `findByKey` for this key raises, once.
         *
         * For the other half of the staleness contract: the quota re-check is a second `prepare`, and
         * a `prepare` reads Mongo. If that read fails, the re-check must not turn a clean refusal
         * into a 500 on a request the cache might have served.
         */
        @Volatile
        var failOnce: String? = null

        override suspend fun findByKey(key: String): Explanation? {
            if (key == hideOnce) {
                hideOnce = null
                return null
            }
            if (key == failOnce) {
                failOnce = null
                throw IllegalStateException("simulated read failure for $key")
            }
            return super.findByKey(key)
        }
    }
}
