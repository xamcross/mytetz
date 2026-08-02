package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicRequestRepository
import com.mytetz.graph.ExplanationGraph
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.ExplanationValidator
import com.mytetz.graph.GraphConfig
import com.mytetz.llm.AnthropicLlmClient
import com.mytetz.llm.LlmClient
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import com.mytetz.quota.QuotaRepository
import com.mytetz.quota.QuotaService
import com.mytetz.session.SessionRepository
import com.mytetz.session.SessionService

/**
 * The whole object graph, wired by hand.
 *
 * No DI framework, deliberately: this is the one place where the shape of the system is visible at a
 * glance, and a container would replace forty readable lines with annotations scattered across nine
 * modules. There are eleven objects here and they are constructed in dependency order.
 *
 * ## Everything is a constructor parameter with a production default
 *
 * [mongo], [cookies] and [llm] all default to the production thing and can all be replaced. [llm] in
 * particular: `AnthropicLlmClient()` calls `AnthropicOkHttpClient.fromEnv()` **during construction**,
 * which demands `ANTHROPIC_API_KEY` and, once built, is one call away from real spend. A default
 * parameter is not evaluated when the caller supplies one, so a test that passes `FakeLlmClient()`
 * never constructs it — which is the only reason `ComponentsTest` can exercise this class at all.
 */
class Components(
    val mongo: Mongo = Mongo(MongoConfig.fromEnv()),
    val cookies: PrincipalCookieConfig = PrincipalCookieConfig(),
    llm: LlmClient = AnthropicLlmClient(),
) {

    private val topics = TopicRepository(mongo.database)
    private val explanations = ExplanationRepository(mongo.database)
    private val sessionRepository = SessionRepository(mongo.database)
    private val quotaRepository = QuotaRepository(mongo.database)

    val catalog = CatalogService(topics)
    val topicRequests = TopicRequestRepository(mongo.database)
    val quota = QuotaService(quotaRepository)

    private val graph = ExplanationGraph(
        repository = explanations,
        llm = llm,
        validator = ExplanationValidator(),
        config = GraphConfig(),
    )

    val sessions = SessionService(sessionRepository, catalog, graph, explanations)

    /**
     * Creates every index in the system and seeds the catalogue.
     *
     * **This is the first production caller of `ensureIndexes()` anywhere in this codebase.** Each
     * repository grew one over Tasks 1.2–1.10 and every one of them was, until now, called only from
     * a test — so a deployed instance had no quota TTL index (and therefore a `principals` collection
     * that grew by one document per anonymous visitor for ever), no explanation demand indexes, no
     * session indexes and no catalogue browse index. A repository added later without a line here
     * repeats that silently: `ComponentsTest` asserts one index per repository for that reason.
     *
     * Every call is idempotent. `createIndex` on an index that already exists with the same
     * specification is a no-op, and `seedFromResource` upserts by slug while preserving each topic's
     * stored publication status — see `TopicRepository.upsertPreservingStatus`, which exists
     * precisely because wiring seeding into every boot is what this task did.
     */
    suspend fun bootstrap() {
        topics.ensureIndexes()
        topicRequests.ensureIndexes()
        explanations.ensureIndexes()
        sessionRepository.ensureIndexes()
        quotaRepository.ensureIndexes()
        catalog.seedFromResource()
    }
}
