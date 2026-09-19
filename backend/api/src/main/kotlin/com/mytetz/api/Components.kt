package com.mytetz.api

import com.mytetz.account.AccountRepository
import com.mytetz.account.AccountService
import com.mytetz.account.GoogleConfig
import com.mytetz.account.GoogleOAuth
import com.mytetz.account.LoggingMailSender
import com.mytetz.account.MagicLinkService
import com.mytetz.account.MailConfig
import com.mytetz.account.MailSender
import com.mytetz.account.ResendMailSender
import com.mytetz.assess.QuizRepository
import com.mytetz.assess.QuizService
import com.mytetz.assess.QuizValidator
import com.mytetz.billing.BillingRepository
import com.mytetz.billing.BillingService
import com.mytetz.billing.FreemiusConfig
import com.mytetz.billing.Reconciliation
import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicRequestRepository
import com.mytetz.graph.Explanation
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.mytetz.api.Components")

private const val DAY_MILLIS = 86_400_000L

/**
 * The ERROR token a failed eviction run is logged under. [Components.bootstrap]'s own guard
 * around [Components.evictExplanations] logs it, and so does the daily loop in `Application.kt`
 * that calls the same method — one token for an operator to grep either source under, stated in
 * `docs/deploy.md`'s "Operator alert tokens" table.
 */
internal const val EVICTION_LOOP_FAILED_TOKEN: String = "EVICTION_LOOP_FAILED"

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
    /**
     * The model family a public, no-model-client page uses to compute a
     * [com.mytetz.graph.ContentKey] seed key. Resolved the same way [AnthropicLlmClient.modelFamily]
     * resolves it in production, but as a plain `String`: this never builds an [AnthropicLlmClient]
     * and never demands `ANTHROPIC_API_KEY`, which is exactly why `TopicPageRoutes.kt` and every
     * later public-page route in this project read this value and never `llm.modelFamily`.
     *
     * A test that wires its own [llmFactory] with a [com.mytetz.llm.FakeLlmClient] of a different
     * `modelFamily` must pass the same family here, or a page a route renders through this value,
     * and a page a pre-warm run generates through [sessions]/[graph], compute two different content
     * keys for the one seed. `ComponentsTest`'s own `a test can set modelFamily to agree with its
     * own FakeLlmClient` pins this.
     */
    val modelFamily: String = AnthropicLlmClient.resolveModel(System.getenv(AnthropicLlmClient.MODEL_FAMILY_ENV)),
    llmFactory: () -> LlmClient = { AnthropicLlmClient() },
    // Each factory default reads its own credential from the environment, and each throws when the
    // credential is absent. Neither runs at construction: both sit inside a `by lazy` below, on the
    // same reasoning [llmFactory] carries — a deployment with no mail key and no Google client must
    // still serve the catalogue. See [defaultMailSender] and [defaultGoogleOAuth].
    mailSenderFactory: () -> MailSender = { defaultMailSender() },
    // A factory, and not a plain `String`, for the same reason as the two above: the production
    // default throws when the credential is absent, and it must not do that until `magicLink` is
    // actually forced. A test overrides this to exercise a real sign-in without setting the real
    // environment variable process-wide. Declared before [googleOAuthFactory] so that factory's own
    // default can pass this one through — one base url feeds both the magic link and the Google
    // redirect, and an override here must reach both rather than only [magicLink].
    publicBaseUrl: () -> String = { resolvePublicBaseUrl(System.getenv(PUBLIC_BASE_URL_ENV)) },
    googleOAuthFactory: () -> GoogleOAuth = { defaultGoogleOAuth(publicBaseUrl) },
    val migrateOnBoot: Boolean = resolveMigrateOnBoot(System.getenv(MIGRATE_ON_BOOT_ENV)),
    val reconcileOnBoot: Boolean = Reconciliation.resolveReconcileOnBoot(System.getenv(Reconciliation.RECONCILE_ON_BOOT_ENV)),
    // A factory, for the same reason `mailSenderFactory` and `googleOAuthFactory` are: the
    // production default throws when a credential is absent, and it must not do that until
    // [reconcile] actually needs it. A test overrides this to simulate a missing credential
    // directly, rather than depending on whether the environment the test happens to run in sets
    // FREEMIUS_API_KEY or FREEMIUS_PRODUCT_ID — a developer's own shell must not change what a
    // test asserts. See `ComponentsTest`'s `bootstrap runs reconciliation with no Freemius
    // credential and does not throw`.
    freemiusApiClientFactory: () -> FreemiusApiClient = { FreemiusApiClient(HttpClient(CIO), FreemiusApiConfig()) },
    // A factory, for the same testability reason `mailSenderFactory`, `googleOAuthFactory` and
    // `freemiusApiClientFactory` are — but unlike those three, [Turnstile]'s construction never
    // throws: [TurnstileConfig.secretKey] is nullable, and an unset `MYTETZ_TURNSTILE_SECRET` is a
    // supported deployment state, not a missing credential. So [turnstile] below is built eagerly,
    // the same as [account].
    turnstileFactory: () -> Turnstile = { Turnstile(HttpClient(CIO), TurnstileConfig().secretKey) },
) {

    private val topics = TopicRepository(mongo.database)

    /** `internal`, and not `private`, so `TopicPageRoutes.kt`'s pure-read topic page can read it
     * directly and never go through the lazy [graph]/[llm] chain. See [modelFamily]'s own KDoc. */
    internal val explanations = ExplanationRepository(mongo.database)
    private val sessionRepository = SessionRepository(mongo.database)
    private val quizRepository = QuizRepository(mongo.database)

    /** The two settings [evictExplanations] reads: how quiet, and how old, a document must be. */
    private val evictionConfig = EvictionConfig()

    /**
     * Where the last call to [evictExplanations] stopped reading, or `null` when the next call
     * must start from the single oldest candidate — either because no call has run yet, or
     * because the last call read all the way to the end of the candidate set. See
     * [evictExplanations]'s own KDoc for why this lives here, in memory, and nowhere else.
     */
    private var evictionCursor: Explanation? = null

    /** Public so `Application.kt` can pass it to `authRoutes`, which reads a counter for `GET /api/account`. */
    val quotaRepository = QuotaRepository(mongo.database)

    /** `bootstrap()` calls `ensureIndexes()` on it, the same as every other repository here. */
    val billingRepository = BillingRepository(mongo.database)
    private val accountRepository = AccountRepository(mongo.database)

    val catalog = CatalogService(topics)

    /** Cheap to build and needs no credential, so — unlike [magicLink] and [googleOAuth] — this is not lazy. */
    val account: AccountService = AccountService(accountRepository)

    /** Cheap to build and needs no credential, so — like [account] and unlike [magicLink] — this is not lazy. */
    val turnstile: Turnstile = turnstileFactory()

    /**
     * The public Cloudflare Turnstile site key this deployment holds, or null.
     *
     * `authRoutes`' `GET /api/auth/config` route reports it to the browser. The browser then
     * decides whether to load the widget script at all. This property reads the environment
     * directly, apart from [turnstileFactory]'s own [TurnstileConfig]. That factory lets a test
     * inject a [Turnstile] built on a secret with no matching real environment variable. This
     * property must still answer from the real deployment either way.
     *
     * [logIfMismatched] runs here too, once, at boot. It does not run inside one of the lazy
     * chains the credentialed services above use. An unset site key is not a missing credential on
     * its own. It is a supported "Turnstile is off" state. Only the *combination* of a secret with
     * no site key is worth a WARN. [logIfMismatched]'s own KDoc gives the reason.
     */
    val turnstileSiteKey: String? = TurnstileConfig().also { logIfMismatched(it) }.siteKey

    private val mail: MailSender by lazy(mailSenderFactory)

    val magicLink: MagicLinkService by lazy {
        MagicLinkService(accountRepository, mail, publicBaseUrl())
    }

    val googleOAuth: GoogleOAuth by lazy(googleOAuthFactory)

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

    val billing: BillingService = BillingService(billingRepository)

    /**
     * The three Freemius identifiers the checkout route and the webhook route need, read from
     * the environment.
     *
     * `by lazy`, on the same reasoning [mail] and [googleOAuth] carry: [FreemiusConfig]'s default
     * constructor throws when a variable is missing, and it must not do that until the checkout
     * route or the webhook route actually needs it. A deployment with no Freemius account must
     * still boot and still serve the catalogue.
     *
     * [reconcile] does **not** read this. It reads [freemiusApiClient] instead, on its own
     * credential and its own `by lazy` chain — see that property's KDoc for why the two chains
     * stay apart.
     */
    val freemiusConfig: FreemiusConfig by lazy { FreemiusConfig() }

    /**
     * The client [reconcile] asks for a subscription's real state at Freemius.
     *
     * `by lazy`, for the same reason [freemiusConfig] is: [FreemiusApiConfig]'s default
     * constructor throws when [FreemiusApiConfig.API_KEY_ENV] or
     * [FreemiusApiConfig.PRODUCT_ID_ENV] is missing, and that must not happen until [reconcile]
     * actually runs — which itself only happens when [reconcileOnBoot] is true. A deployment that
     * never sets `MYTETZ_RECONCILE_ON_BOOT` never builds this, the same way one that never signs
     * a learner in never builds [googleOAuth].
     *
     * A **separate** `by lazy` from [freemiusConfig], and not a reuse of its `productId`: the two
     * configs need two different credentials — see [FreemiusApiConfig]'s own KDoc — and keeping
     * them on separate chains means turning reconciliation on, or off, never touches whether the
     * checkout and webhook routes can build.
     */
    private val freemiusApiClient: FreemiusApiClient by lazy(freemiusApiClientFactory)

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

    val quizzes: QuizService by lazy {
        QuizService(quizRepository, llm, QuizValidator())
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
     * [migrate] runs before [prewarm], and the order is load-bearing: [migrate] deletes a stranded
     * explanation, behind its own flag, and [prewarm] must not generate a fresh seed under the
     * same slug only for the delete to remove it. [prewarm] itself carries no flag — see its own
     * KDoc for why a published topic's seed must not wait on one.
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
        quizRepository.ensureIndexes()
        quotaRepository.ensureIndexes()
        billingRepository.ensureIndexes()
        accountRepository.ensureIndexes()
        catalog.seedFromResource()
        migrate()
        prewarm()
        reconcile()

        // Guarded, unlike the six calls above: eviction is housekeeping, not correctness. Every
        // ensureIndexes() and seedFromResource() above must stop the boot on failure — a missing
        // index or an unseeded catalogue is a real incident. A missed eviction run is not: the
        // daily loop in `Application.kt` retries tomorrow, and nothing else in this class depends
        // on the collection shrinking. Letting it fail loudly here would instead take
        // `ready` down for the life of the machine — see `Application.bootstrap`'s own KDoc for
        // what that flag guards.
        try {
            evictExplanations()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "$EVICTION_LOOP_FAILED_TOKEN — the boot-time eviction run did not complete; the " +
                    "daily loop retries on its own schedule",
                e,
            )
        }
    }

    /**
     * The one-time deletion for slice B0 of the monetization specification.
     *
     * It runs only when [migrateOnBoot] is true. An operator sets that flag for one deployment and
     * then removes it.
     *
     * This is not an ordinary boot step. It deletes documents, and it needs the model client to
     * name the family it keeps. An unconditional version would therefore make catalogue browsing
     * need `ANTHROPIC_API_KEY`.
     *
     * The delete is idempotent. A second run finds nothing stranded and deletes nothing.
     *
     * [prewarm] used to run inside this method, after the delete. It now runs on every boot, with
     * no flag — see [prewarm]'s own KDoc for the reason a published topic must not wait for an
     * operator to set [MIGRATE_ON_BOOT_ENV]. [Components.bootstrap] still calls this method first
     * and [prewarm] second, so a migrated deployment still deletes a stranded explanation before
     * [prewarm] can generate a fresh one under the same slug.
     */
    suspend fun migrate() {
        if (!migrateOnBoot) return

        val deleted = explanations.deleteWhereModelFamilyIsNot(llm.modelFamily)
        log.info(
            "MIGRATION removed {} explanation(s) stranded by a model family change; kept family '{}'",
            deleted,
            llm.modelFamily,
        )
    }

    /**
     * Generates the seed explanation for every published topic that does not have one yet.
     *
     * ## This runs on every boot, with no flag
     *
     * [migrate] only pre-warmed behind [migrateOnBoot], so an operator who added a topic to
     * `topics.json` and deployed, without also setting that flag, left the new topic published
     * with no seed — and the first visitor who opened it paid, in money and in wait time, for a
     * live generation. A published topic must never cost a visitor a cold generation, so this
     * method now closes that gap on its own, on every boot, rather than on an operator's memory.
     *
     * [com.mytetz.session.SessionService.prewarmSeed] itself checks the store before it calls the
     * model, so a topic that already has a seed costs one cheap read here and no model call. This
     * loop's real cost, on an ordinary boot, is therefore the count of *new* topics, not the size
     * of the catalogue.
     *
     * ## The credential guard mirrors [reconcile]
     *
     * The first read of [sessions] is the read that can throw: [sessions] forces [graph], which
     * forces the lazy model client, and that client's default construction demands
     * `ANTHROPIC_API_KEY`. A deployment with no key must still serve the catalogue, so that read
     * sits inside its own `try`/`catch`, logs one WARN line, and returns. Every later boot retries
     * — `by lazy`'s failure is not cached — the same as [reconcile]'s own guard.
     *
     * ## The loop shape matches [migrate]'s old one
     *
     * The spend-breaker check before each topic, the quota record inside the `onSpend` callback,
     * and one failure logged and skipped rather than stopping the whole run: this is the same
     * shape [migrate] used for the same reason. The seeds cost real money, and one topic's failure
     * must not cost the rest of the catalogue its seed.
     */
    suspend fun prewarm() {
        val sessionService = try {
            sessions
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "PREWARM_SKIPPED the model client did not build; boot continues with the catalogue " +
                    "served as it stands, and no missing seed generated this run",
                e,
            )
            return
        }

        val maintenance = PrincipalId.user("maintenance")
        val budget = Allowance(generations = 10_000, windowMillis = 86_400_000)

        var generated = 0
        var failed = 0
        var spentMicros = 0L
        for (topic in catalog.listPublished(category = null, query = null)) {
            if (quota.checkGeneration(maintenance, budget) != QuotaDecision.Allowed) {
                log.warn("PREWARM stopped early: the spend breaker refused before '{}'", topic.slug)
                break
            }
            try {
                val didGenerate = sessionService.prewarmSeed(topic.slug) { cost ->
                    spentMicros += cost
                    quota.recordGeneration(maintenance, cost, budget)
                }
                if (didGenerate) generated++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                log.warn("PREWARM failed to pre-warm the seed for '{}'", topic.slug, e)
            }
        }

        log.info(
            "PREWARM pre-warmed {} seed(s), {} failed, at a cost of {} micro-dollars",
            generated,
            failed,
            spentMicros,
        )
    }

    /**
     * Corrects a subscription mirror that has drifted from Freemius, when [reconcileOnBoot] is
     * true.
     *
     * Unlike [migrate], this is safe to leave on: it only reads, and [RECONCILE_LIMIT] bounds one
     * run so a cold start under load cannot flood the Freemius API. See `docs/deploy.md` for the
     * full argument, and why the same claim does not hold for [migrateOnBoot].
     *
     * ## The credential is guarded here, not inside [FreemiusApiClient]
     *
     * [freemiusApiClient] is `by lazy`, so building it — and therefore resolving
     * [FreemiusApiConfig] — happens on the **first** line inside this method that reads it, not
     * at [Components] construction. That first read is wrapped here: a deployment that turns
     * [reconcileOnBoot] on before an operator has set `FREEMIUS_API_KEY` and
     * `FREEMIUS_PRODUCT_ID` logs `RECONCILE_SKIPPED` and returns, rather than taking the whole
     * boot down. Every later boot retries — `by lazy`'s failure is not cached — so setting the
     * credential later needs no code change and no extra flag.
     */
    suspend fun reconcile() {
        if (!reconcileOnBoot) return

        val client = try {
            freemiusApiClient
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "RECONCILE_SKIPPED reconciliation is on but the Freemius API credential is not " +
                    "configured; boot continues with no subscription corrected this run",
                e,
            )
            return
        }

        val corrected = Reconciliation.reconcile(billingRepository, limit = RECONCILE_LIMIT) { subscription ->
            client.fetchState(subscription)
        }
        log.info("RECONCILE corrected {} drifted subscription(s)", corrected)
    }

    /**
     * Removes what it can of the explanations that nothing needs any more, across one or more
     * pages within one run. Logs `EVICTION removed={} scanned={}` at INFO, where `scanned` is the
     * total read across every page this call made.
     *
     * The rule: a document is removable when it is not a seed, when its `requestCount` is at or
     * below [EvictionConfig.maxRequestCount], and when it is older than
     * [EvictionConfig.maxAgeDays]. A removable document is still not deleted while a session node
     * points at it — deleting the target of a live node would break that session's trail, the
     * fault [CorruptSessionException] exists to catch. See `SessionService.hydrate`'s own KDoc.
     *
     * ## Why the reference check sits here, and not in `ExplanationRepository`
     *
     * `:backend:graph` does not depend on `:backend:session`, so `ExplanationRepository` cannot
     * ask whether a session still references a key. `Components` sees both repositories, so the
     * two-step rule lives here: read one page of candidates, remove the keys a session still
     * points at, and delete the rest.
     *
     * ## The bound of one run
     *
     * A session belonging to a signed-in learner is not deleted until account deletion, so the
     * oldest candidates are, in practice, disproportionately the ones a session still references.
     * A single page ordered oldest-first can therefore be entirely referenced, while an
     * unreferenced document waits just behind it. This method walks forward with [pageSize]-sized
     * pages, using [ExplanationRepository.findEvictionCandidates]'s `after` cursor, until one page
     * comes back short — meaning there is nothing left to read — or it has read [maxPagesPerRun]
     * pages, whichever comes first. **[maxPagesPerRun] pages of [pageSize] each is the fixed bound
     * for one run**; it is not a full sweep of the collection, and one page is one `find`, one
     * `distinct` and at most one `deleteMany`, so one run is a small, predictable number of
     * database round trips.
     *
     * ## The position carries over, so every candidate gets a turn
     *
     * A single run's bound is not, on its own, enough: a collection can hold far more than
     * [maxPagesPerRun] × [pageSize] contiguous referenced candidates ahead of the next removable
     * one, and a job that started over from the oldest candidate on every call would then never
     * reach anything behind them, on any later call either. So [evictionCursor] remembers, across
     * calls, the last candidate a run actually read. The next call resumes strictly past it — see
     * [ExplanationRepository.findEvictionCandidates]'s own KDoc on the `after` parameter — rather
     * than reading the same oldest page again. When a run's last page comes back short, the scan
     * has reached the end of the whole candidate set, and [evictionCursor] is cleared, so the next
     * call wraps around and starts again from the single oldest candidate — the same as if no call
     * had ever run.
     *
     * [evictionCursor] lives in memory only, on this instance, and needs no persistence and no new
     * failure handling: a position that names a document another run already deleted is not a
     * fault, because [ExplanationRepository.findEvictionCandidates]'s cursor filter compares
     * `createdAtEpochMillis` and `_id` values and needs no document to exist at them. A process
     * restart clears it, which is correct — the position is a resume point for an unfinished
     * sweep, not a record that must survive one.
     *
     * A full pass of a collection of `N` candidates therefore takes
     * `ceil(N / (maxPagesPerRun × pageSize))` calls to this method — with the daily loop in
     * `Application.kt`, that many days — after which every candidate that was ever unreferenced
     * and stayed that way has had its turn, however many referenced candidates sit ahead of it.
     *
     * ## The check-then-delete race, and how far the delete closes it
     *
     * A learner can turn a candidate into a cache hit between the reference check below and the
     * delete that follows it. `ExplanationGraph.getOrGenerate` raises `requestCount` on a cache
     * hit, and it does this *before* `SessionService` appends the node that then references the
     * key. [ExplanationRepository.deleteEvictable] repeats the `requestCount` filter, so Mongo
     * checks that filter against the document as it stands at delete time — a key raised in that
     * window no longer matches, and it survives. This closes most of the window. It does not
     * close every case; `SessionService.hydrate`'s own KDoc names the residual one.
     *
     * `open` only so a test can shrink [pageSize] and [maxPagesPerRun], or replace this method
     * outright to prove a caller survives its failure — see [bootstrap] and the daily loop in
     * `Application.kt`. There is no production subclass.
     */
    open suspend fun evictExplanations(
        pageSize: Int = EVICTION_PAGE_SIZE,
        maxPagesPerRun: Int = EVICTION_MAX_PAGES_PER_RUN,
    ) {
        var removed = 0L
        var scanned = 0
        var cursor: Explanation? = evictionCursor
        var pagesRead = 0
        var reachedEnd = false

        while (pagesRead < maxPagesPerRun) {
            val page = explanations.findEvictionCandidates(
                maxRequestCount = evictionConfig.maxRequestCount,
                olderThanEpochMillis = System.currentTimeMillis() - evictionConfig.maxAgeDays * DAY_MILLIS,
                pageSize = pageSize,
                after = cursor,
            )
            pagesRead++
            if (page.isEmpty()) {
                reachedEnd = true
                break
            }

            scanned += page.size
            val keys = page.map { it.key }
            val referenced = sessionRepository.referencedExplanationKeys(keys)
            val evictable = keys.filterNot { it in referenced }
            if (evictable.isNotEmpty()) {
                removed += explanations.deleteEvictable(evictable, evictionConfig.maxRequestCount)
            }

            cursor = page.last()
            if (page.size < pageSize) {
                reachedEnd = true // a short page: nothing is left behind it
                break
            }
        }

        // A run that reached the end wraps around; a run that only hit its page bound resumes
        // from here next time, so the candidates behind it still get their turn.
        evictionCursor = if (reachedEnd) null else cursor

        log.info("EVICTION removed={} scanned={}", removed, scanned)
    }

    companion object {

        /**
         * How many non-terminal subscriptions [reconcile] asks Freemius about in one run.
         *
         * This is the bound `docs/deploy.md` argues keeps a cold start under load from flooding
         * the Freemius API. Five hundred is generous for this product's expected scale.
         */
        const val RECONCILE_LIMIT: Int = 500

        /** The default page size [evictExplanations] reads with, absent an override. */
        const val EVICTION_PAGE_SIZE: Int = 250

        /**
         * How many pages [evictExplanations] reads in one run, at most.
         *
         * [EVICTION_PAGE_SIZE] × [EVICTION_MAX_PAGES_PER_RUN] is therefore the most documents one
         * run scans — 5,000 by default, the same reasoning [RECONCILE_LIMIT] states: a bound
         * keeps one run predictable, rather than scanning the whole collection in one sweep. Each
         * page costs one `find`, one `distinct` and at most one `deleteMany`, so a full run of
         * twenty pages is at most sixty small database round trips — cheap enough that this bound
         * is about predictability, not about protecting the database from one run.
         *
         * Reading that bound in pages, rather than in one query, is what lets a run walk past a
         * page that held nothing removable. `evictionCursor` is what lets the *next* run resume
         * where this one stopped, rather than reading the same oldest page again — see
         * [evictExplanations]'s own KDoc for both.
         */
        const val EVICTION_MAX_PAGES_PER_RUN: Int = 20

        const val MIGRATE_ON_BOOT_ENV: String = "MYTETZ_MIGRATE_ON_BOOT"
        const val PUBLIC_BASE_URL_ENV: String = "MYTETZ_PUBLIC_BASE_URL"
        const val GOOGLE_CLIENT_ID_ENV: String = "GOOGLE_CLIENT_ID"
        const val GOOGLE_CLIENT_SECRET_ENV: String = "GOOGLE_CLIENT_SECRET"

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

        /**
         * Resolves [PUBLIC_BASE_URL_ENV], and throws when it is unset.
         *
         * A magic link and a Google redirect both need an absolute URL of this deployment. There is
         * no safe default for one, on the same reasoning `PrincipalCookieConfig.resolveSigningKey`
         * gives for the cookie key: a wrong guess here mails a broken link to every learner, so the
         * safe failure is to refuse rather than to guess.
         */
        internal fun resolvePublicBaseUrl(raw: String?): String {
            val url = raw?.trim().orEmpty()
            check(url.isNotEmpty()) {
                "$PUBLIC_BASE_URL_ENV is not set. A magic link and a Google redirect both need an " +
                    "absolute base url for this deployment, and there is no safe default."
            }
            return url
        }

        private fun requireEnv(name: String): String {
            val value = System.getenv(name)?.trim().orEmpty()
            check(value.isNotEmpty()) { "$name is not set, and Google sign-in needs it." }
            return value
        }

        /**
         * Builds the production [MailSender] from [MailConfig], read from the environment.
         *
         * `MailConfig`'s own default constructor throws when `MYTETZ_MAIL_MODE` is unset or
         * unrecognised — see its KDoc. This function runs only inside [mail]'s `by lazy`, so that
         * throw happens the first time a route actually sends a magic link, and never while
         * [Components] is being constructed.
         */
        private fun defaultMailSender(): MailSender {
            val config = MailConfig()
            return when (config.mode) {
                "resend" -> ResendMailSender(
                    apiKey = config.apiKey?.takeIf { it.isNotBlank() }
                        ?: error("${MailConfig.API_KEY_ENV} is not set, and MYTETZ_MAIL_MODE=resend needs it"),
                    from = config.from?.takeIf { it.isNotBlank() }
                        ?: error("${MailConfig.FROM_ENV} is not set, and MYTETZ_MAIL_MODE=resend needs it"),
                    httpClient = HttpClient(CIO) { defaultRequest { url("https://api.resend.com") } },
                )
                else -> LoggingMailSender()
            }
        }

        /**
         * Builds the production [GoogleOAuth] from [GOOGLE_CLIENT_ID_ENV], [GOOGLE_CLIENT_SECRET_ENV]
         * and [baseUrl]. Runs only inside [googleOAuth]'s `by lazy`, for the same reason
         * [defaultMailSender] gives.
         *
         * [baseUrl] is the constructor's own `publicBaseUrl` factory, passed through rather than
         * read from the environment a second time here — a fix-round correction. Reading
         * [PUBLIC_BASE_URL_ENV] directly meant a test, or a future caller, that overrode
         * `publicBaseUrl` to point [magicLink] somewhere other than the real environment still got
         * the real environment's value on the Google redirect, so the two could name two different
         * deployments.
         */
        private fun defaultGoogleOAuth(baseUrl: () -> String): GoogleOAuth {
            val url = baseUrl()
            return GoogleOAuth(
                config = GoogleConfig(
                    clientId = requireEnv(GOOGLE_CLIENT_ID_ENV),
                    clientSecret = requireEnv(GOOGLE_CLIENT_SECRET_ENV),
                    redirectUri = "$url/api/auth/google/callback",
                ),
                httpClient = HttpClient(CIO),
            )
        }
    }
}
