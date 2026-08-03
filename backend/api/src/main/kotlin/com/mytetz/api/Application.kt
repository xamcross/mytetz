package com.mytetz.api

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

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }
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
            cookies = components.cookies,
            clientAddresses = components.clientAddresses,
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
