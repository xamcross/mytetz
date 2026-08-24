package com.mytetz.api

import com.mytetz.llm.AnthropicLlmClient
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

const val PORT_ENV: String = "PORT"

/** The port `fly.toml` and `.env.example` both name. */
const val DEFAULT_PORT: Int = 8080

/**
 * The port to bind, from the environment.
 *
 * A missing, unparseable or out-of-range value falls back to [DEFAULT_PORT] rather than throwing.
 * That is the rule `GraphConfig.resolveMaxOutputTokens`, `QuotaConfig.resolveDailyExplains`,
 * `SessionLimits.resolveMaxDepth` and `MongoConfig.resolveServerSelectionTimeoutMillis` all state:
 * a typo in a deployment variable must not stop the server. `PORT` was the one config that broke
 * it, because `"8O80".toInt()` throws and takes the boot with it.
 *
 * The range is the range of a TCP port. Zero is excluded on purpose: `embeddedServer` reads 0 as
 * "bind any free port", so a `PORT` of 0 would start a server on a port that fly's proxy does not
 * forward to, and the machine would look healthy while it served nobody.
 *
 * **`MYTETZ_COOKIE_SIGNING_KEY` does not follow this rule, and it must not be made to.**
 * `PrincipalCookieConfig` fails closed, and its KDoc holds the argument: the premise of the rule
 * above is that the default is the safe value, and there is no safe default signing key. A server
 * that refuses to start is an incident an operator sees in the first thirty seconds. A server that
 * started with a known key is an incident nobody sees at all.
 */
internal fun resolvePort(raw: String?): Int =
    raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT

/**
 * The model an operator would see if the lazy `Components.llm` were built, without building it.
 *
 * `AnthropicLlmClient.resolveModel` holds the one fallback rule this restates: a missing, empty
 * or blank value falls back to [AnthropicLlmClient.DEFAULT_MODEL]. That function is internal to
 * the `llm` module, so `Application.kt` cannot call it directly. Restating the one rule here costs
 * one line and keeps `Components.llm`'s guarantee intact: a deployment with no
 * `ANTHROPIC_API_KEY` must still serve the catalogue, and reading the lazy client here to log it
 * would build a real Anthropic client on every boot.
 */
internal fun resolveModelForLogging(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() } ?: AnthropicLlmClient.DEFAULT_MODEL

fun main() {
    embeddedServer(Netty, port = resolvePort(System.getenv(PORT_ENV)), host = "0.0.0.0") { module() }
        .start(wait = true)
}

/**
 * [components] is a parameter with a production default so that a test can drive the real module
 * against a real Mongo and a fake model. That is the only way to establish that
 * [Components.bootstrap] is actually reached — and a bootstrap nothing calls is indistinguishable
 * from no bootstrap at all, which is exactly the state this task found the system in.
 */
fun Application.module(components: Components = Components()) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(CallLogging)
    // Answers HEAD by running the matching GET and discarding the body. Without it `get { }` matches
    // GET only, so every HEAD falls through to the `/api/{...}` catch-all below and 404s — and HEAD
    // is what uptime monitors send at a health endpoint.
    install(AutoHeadResponse)
    installErrorMapping()

    // Which source the rate limiters key on, said once, at startup. It is a security setting and
    // not a tuning one: a name that no request carries puts every visitor in one bucket. Nothing
    // reported it before, and there is no metric here that would show it. See `ClientAddress`.
    log.info("rate limiting keys on {}", components.clientAddresses.source)

    // The resolved model, said once, at startup, so an operator can confirm which model this
    // deployment runs. This does not read `Components.llm`: that lazy is built on first use on
    // purpose, so that a deployment with no `ANTHROPIC_API_KEY` still serves the catalogue. See
    // `resolveModelForLogging`.
    log.info(
        "model resolves to modelId={} modelFamily={}",
        resolveModelForLogging(System.getenv(AnthropicLlmClient.MODEL_ID_ENV)),
        resolveModelForLogging(System.getenv(AnthropicLlmClient.MODEL_FAMILY_ENV)),
    )

    val ready = bootstrap(components)

    routing {
        healthRoutes(mongoPing = { components.mongo.ping() }, ready = { ready.get() })
        catalogRoutes(
            catalog = components.catalog,
            topicRequests = components.topicRequests,
            cookies = components.cookies,
            clientAddresses = components.clientAddresses,
        )
        // No `install(SSE)`. The explain endpoint is a POST and builds its own `SSEServerContent`;
        // the plugin is an empty marker for `Route.sse`, which registers GET only. See the note on
        // `sessionRoutes`.
        sessionRoutes(
            // A factory, not the service. `components.sessions` is a lazy that ends at
            // `AnthropicLlmClient()`, which demands ANTHROPIC_API_KEY in its constructor — reading it
            // here would build the model client while this module is still being configured, so a
            // missing key would stop the process booting and take /api/health and topic browsing
            // with it. See the note on `sessionRoutes` and `Components`' own KDoc.
            sessions = { components.sessions },
            quota = components.quota,
            billing = components.billing,
            account = components.account,
            cookies = components.cookies,
            clientAddresses = components.clientAddresses,
        )
        authRoutes(
            account = components.account,
            sessions = { components.sessions },
            // Factories, not the built services, for the reason `sessions` above is one: each of
            // `components.magicLink` and `components.googleOAuth` is `by lazy` on a chain that can
            // throw when a credential is missing, and reading either here would force it while this
            // module is still being configured. See `Components`' own KDoc.
            magicLink = { components.magicLink },
            google = { components.googleOAuth },
            cookies = components.cookies,
            quotaRepository = components.quotaRepository,
            billing = components.billing,
            clientAddresses = components.clientAddresses,
            turnstile = components.turnstile,
        )
        billingRoutes(
            account = components.account,
            billing = components.billing,
            // A factory, not the built config, for the same reason `magicLink` and `google` are
            // above: `Components.freemiusConfig` is `by lazy` on a chain that throws when a
            // Freemius variable is missing, and reading it here would force that chain while this
            // module is still being configured.
            freemiusConfig = { components.freemiusConfig },
            cookies = components.cookies,
        )

        // Every unmatched `/api/**` path, answered as JSON.
        //
        // Without this the static handler below catches them, because `default("index.html")` makes
        // it match everything — so a typo'd or withdrawn endpoint returns the SPA shell with
        // **200 OK**. A client parsing that as `ApiError` gets a syntax error rather than a 404, and
        // a monitor watching status codes sees a perfectly healthy API.
        route("/api/{...}") {
            handle {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("NOT_FOUND", "no such endpoint"),
                )
            }
        }

        staticResources("/", "static") {
            default("index.html")
        }
    }
}

/**
 * Starts index creation and catalogue seeding, and returns a flag that flips when they finish.
 *
 * ## Why this does not block startup
 *
 * `Application.module()` runs to completion **before** the engine accepts a single connection, so
 * anything blocking in here is time during which nothing is served — not even `/api/health`, whose
 * entire purpose is to report the state of the database.
 *
 * That matters twice, and the second is not an edge case:
 *
 * - **During a Mongo outage.** The first `ensureIndexes()` blocks until server selection gives up.
 *   `fly.toml`'s health check has a 10 s grace and a 5 s timeout, so a blocking bootstrap fails it
 *   throughout — the endpoint that exists to say "the database is unreachable" would be itself
 *   unreachable for exactly as long as the database is. Note that
 *   [com.mytetz.persistence.MongoConfig.serverSelectionTimeoutMillis] now bounds that wait to 3 s;
 *   it is the *second* half of the same fix, not a substitute for this one, because a blocking
 *   bootstrap also delays a healthy cold start by every round trip the seed makes.
 * - **On every cold start.** `seedFromResource` puts every seeded topic through
 *   `TopicRepository.upsertPreservingStatus`, which is a read *and* a write each: dozens of
 *   sequential round trips plus five `createIndex` calls before the first byte. With
 *   `min_machines_running = 0`, every idle period ends in a cold start that a real user waits for.
 *
 * So it runs on the application's own scope and the routes go up immediately.
 *
 * ## What that costs, stated rather than glossed
 *
 * There is a window — normally well under a second — in which the catalogue is queryable but not yet
 * seeded, so a request landing inside it can see an empty or partial topic list. That is why
 * `/api/health` reports [HealthResponse.ready]: the window is visible rather than mysterious. It is
 * the better trade, because the failure is transient and self-corrects on the next request, whereas
 * blocking makes every cold start slower and every outage silent.
 *
 * A failure is logged at ERROR with a greppable token and does **not** stop the server, for the same
 * reason: `/api/health` already reports the database state honestly, and a crash on a machine that
 * boots on demand turns every arriving request into a boot-crash loop serving nothing at all.
 */
private fun Application.bootstrap(components: Components): AtomicBoolean {
    val ready = AtomicBoolean(false)
    launch {
        try {
            components.bootstrap()
            ready.set(true)
        } catch (e: Exception) {
            log.error(
                "BOOTSTRAP_FAILED — indexes and the catalogue seed did not complete. This instance " +
                    "is serving without them; /api/health reports the database state and readiness.",
                e,
            )
        }
    }
    return ready
}
