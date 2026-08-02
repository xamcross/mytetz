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
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.mytetz.api.Components")

/**
 * The whole object graph, wired by hand.
 *
 * No DI framework, deliberately: this is the one place where the shape of the system is visible at a
 * glance, and a container would replace forty readable lines with annotations scattered across nine
 * modules. There are eleven objects here and they are constructed in dependency order.
 *
 * ## Everything is a constructor parameter with a production default
 *
 * [mongo], [cookies], [clientAddresses] and [llmFactory] all default to the production thing and can
 * all be replaced.
 *
 * ## The model client is a factory, and is built lazily
 *
 * `AnthropicLlmClient()` calls `AnthropicOkHttpClient.fromEnv()` **during construction**, which
 * demands `ANTHROPIC_API_KEY` there and then. Taking it as a `LlmClient` parameter — even a defaulted
 * one — therefore builds it while `Application.module()` is evaluating its own default argument, so
 * a missing or freshly-rotated key takes down **topic browsing**, which needs no model at all.
 * Nothing this slice registers uses it: [sessions], [graph] and [quota] are wired for Task 1.12.
 *
 * A factory plus `by lazy` makes the coupling match the dependency: the client is built the first
 * time something actually needs a model, and a catalogue-only deployment never builds one. It also
 * removes structurally, rather than by convention, the "one mistake away from a paid call" hazard
 * that otherwise has to be policed in every test.
 */
open class Components(
    val mongo: Mongo = Mongo(MongoConfig.fromEnv()),
    val cookies: PrincipalCookieConfig = PrincipalCookieConfig(),
    val clientAddresses: ClientAddressConfig = ClientAddressConfig(),
    llmFactory: () -> LlmClient = { AnthropicLlmClient() },
) {

    private val topics = TopicRepository(mongo.database)
    private val explanations = ExplanationRepository(mongo.database)
    private val sessionRepository = SessionRepository(mongo.database)
    private val quotaRepository = QuotaRepository(mongo.database)

    val catalog = CatalogService(topics)

    val topicRequests = TopicRequestRepository(
        database = mongo.database,
        // The repository recycles its own oldest, least-wanted rows once full. That is deliberate
        // (see its KDoc) but it is still data disappearing, so it is visible rather than silent.
        evictionListener = { evicted ->
            log.info(
                "evicted topic request '{}' (count={}) to make room; the backlog is at its cap",
                evicted.normalizedText,
                evicted.count,
            )
        },
    )

    val quota = QuotaService(quotaRepository)

    private val llm: LlmClient by lazy(llmFactory)

    private val graph by lazy {
        ExplanationGraph(
            repository = explanations,
            llm = llm,
            validator = ExplanationValidator(),
            config = GraphConfig(),
        )
    }

    val sessions: SessionService by lazy {
        SessionService(sessionRepository, catalog, graph, explanations)
    }

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
     *
     * `open` — with the class — only so a test can hold this method open on a latch and prove that
     * `/api/health` answers while it is still running. There is no production subclass. Same
     * reasoning, and the same note, as `QuotaRepository.incrementCounter`.
     */
    open suspend fun bootstrap() {
        topics.ensureIndexes()
        topicRequests.ensureIndexes()
        explanations.ensureIndexes()
        sessionRepository.ensureIndexes()
        quotaRepository.ensureIndexes()
        catalog.seedFromResource()
    }
}
