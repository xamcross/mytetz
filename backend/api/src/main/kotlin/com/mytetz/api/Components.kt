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
import com.mytetz.billing.BillingRepository
import com.mytetz.billing.BillingService
import com.mytetz.billing.FreemiusConfig
import com.mytetz.billing.Reconciliation
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
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
) {

    private val topics = TopicRepository(mongo.database)
    private val explanations = ExplanationRepository(mongo.database)
    private val sessionRepository = SessionRepository(mongo.database)

    /** Public so `Application.kt` can pass it to `authRoutes`, which reads a counter for `GET /api/account`. */
    val quotaRepository = QuotaRepository(mongo.database)

    /** `bootstrap()` calls `ensureIndexes()` on it, the same as every other repository here. */
    val billingRepository = BillingRepository(mongo.database)
    private val accountRepository = AccountRepository(mongo.database)

    val catalog = CatalogService(topics)

    /** Cheap to build and needs no credential, so — unlike [magicLink] and [googleOAuth] — this is not lazy. */
    val account: AccountService = AccountService(accountRepository)

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
    private val freemiusApiClient: FreemiusApiClient by lazy {
        FreemiusApiClient(HttpClient(CIO), FreemiusApiConfig())
    }

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
        billingRepository.ensureIndexes()
        accountRepository.ensureIndexes()
        catalog.seedFromResource()
        migrate()
        reconcile()
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

    companion object {

        /**
         * How many non-terminal subscriptions [reconcile] asks Freemius about in one run.
         *
         * This is the bound `docs/deploy.md` argues keeps a cold start under load from flooding
         * the Freemius API. Five hundred is generous for this product's expected scale.
         */
        const val RECONCILE_LIMIT: Int = 500

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
