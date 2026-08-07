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
import com.mytetz.quota.Allowance
import com.mytetz.quota.PrincipalId
import com.mytetz.quota.QuotaDecision
import com.mytetz.quota.QuotaRepository
import com.mytetz.quota.QuotaService
import com.mytetz.session.SessionRepository
import com.mytetz.session.SessionService
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

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
    val migrateOnBoot: Boolean = resolveMigrateOnBoot(System.getenv(MIGRATE_ON_BOOT_ENV)),
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
        migrate()
    }

    /**
     * The one-time migration for slice B0 of the monetization specification.
     *
     * It runs only when [migrateOnBoot] is true. An operator sets that flag for one deployment and
     * then removes it.
     *
     * This is not an ordinary boot step. It deletes documents. It calls a metered API. It also
     * builds the lazy model client. An unconditional version would therefore make catalogue
     * browsing need `ANTHROPIC_API_KEY`.
     *
     * The order is load-bearing. The delete runs first. The first half then cannot delete a seed
     * that the second half generates.
     *
     * Both halves are idempotent. A second run is therefore safe. The seeds cost real money. The
     * loop asks the quota gate before each seed. It stops when the global spend breaker trips.
     * The allowance it names holds a whole catalogue. The allowance is not the real bound: ten
     * thousand generations cost $105 at $0.0105 each. That figure is above the $50 daily breaker.
     * The breaker is therefore the only effective bound.
     *
     * One topic's failure does not stop the loop. The loop catches the failure, logs the topic's
     * slug at WARN, and moves to the next topic. It re-throws a cancellation, because a swallowed
     * cancellation breaks structured concurrency — see `SessionRoutes.kt` for the same shape. The
     * summary line at the end of this method names the failure count.
     */
    suspend fun migrate() {
        if (!migrateOnBoot) return

        val deleted = explanations.deleteWhereModelFamilyIsNot(llm.modelFamily)
        log.info(
            "MIGRATION removed {} explanation(s) stranded by a model family change; kept family '{}'",
            deleted,
            llm.modelFamily,
        )

        val maintenance = PrincipalId.user("maintenance")
        val budget = Allowance(generations = 10_000, windowMillis = 86_400_000)

        var generated = 0
        var failed = 0
        var spentMicros = 0L
        for (topic in catalog.listPublished(category = null, query = null)) {
            if (quota.checkGeneration(maintenance, budget) != QuotaDecision.Allowed) {
                log.warn("MIGRATION stopped early: the spend breaker refused before '{}'", topic.slug)
                break
            }
            try {
                val didGenerate = sessions.prewarmSeed(topic.slug) { cost ->
                    spentMicros += cost
                    quota.recordGeneration(maintenance, cost, budget)
                }
                if (didGenerate) generated++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                log.warn("MIGRATION failed to pre-warm the seed for '{}'", topic.slug, e)
            }
        }

        log.info(
            "MIGRATION pre-warmed {} seed(s), {} failed, at a cost of {} micro-dollars; remove {} now",
            generated,
            failed,
            spentMicros,
            MIGRATE_ON_BOOT_ENV,
        )
    }

    companion object {

        const val MIGRATE_ON_BOOT_ENV: String = "MYTETZ_MIGRATE_ON_BOOT"

        /**
         * Only the exact word `true` turns the migration on.
         *
         * This polarity is the opposite of `PrincipalCookieConfig.resolveSecure`. The safe value is
         * also the opposite one. There, an unrecognised value keeps a protection. Here, an
         * unrecognised value keeps the migration off. The migration deletes documents. It also
         * calls a metered API. It must never start by accident.
         */
        internal fun resolveMigrateOnBoot(raw: String?): Boolean =
            raw?.trim()?.equals("true", ignoreCase = true) == true
    }
}
