package com.mytetz.api

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

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
    installErrorMapping()

    bootstrap(components)

    routing {
        healthRoutes(mongoPing = { components.mongo.ping() })
        catalogRoutes(
            catalog = components.catalog,
            topicRequests = components.topicRequests,
            cookies = components.cookies,
        )
        staticResources("/", "static") {
            default("index.html")
        }
    }
}

/**
 * Index creation and catalogue seeding, run once at startup.
 *
 * ## Why a failure here does not stop the server
 *
 * `runBlocking` on the startup path is deliberate — this must finish before the first request — but
 * the `catch` is the decision worth recording, because letting the failure propagate is the more
 * obvious choice and it is the wrong one *for this deployment*:
 *
 * - `fly.toml` sets `min_machines_running = 0` with `auto_start_machines`, so the machine boots on
 *   the first request after an idle period. A throw here exits the process, so every arriving
 *   request would trigger a boot, a crash, and nothing served — a traffic-triggered crash loop
 *   during precisely the incident (Mongo unreachable) when someone needs the site to say so.
 * - `/api/health` already answers that question honestly: it pings Mongo per request and reports
 *   503 `degraded`, and the health check in `fly.toml` reads it. Refusing to start would make the
 *   endpoint that exists to report a database outage unreachable during a database outage.
 *
 * The cost, stated plainly rather than glossed: an instance that fails here serves an **empty
 * catalogue with 200 OK**, and runs without indexes until its next boot. That is why it is logged at
 * ERROR with a greppable token and the failure attached rather than swallowed — a silent version of
 * this trade-off would be indefensible, and missing indexes going unnoticed for ten tasks is the
 * very defect this function was added to close.
 */
private fun Application.bootstrap(components: Components) {
    try {
        runBlocking { components.bootstrap() }
    } catch (e: Exception) {
        log.error(
            "BOOTSTRAP_FAILED — indexes and the catalogue seed did not complete. This instance is " +
                "serving without them; /api/health reports the database state.",
            e,
        )
    }
}
