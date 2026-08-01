package com.mytetz.api

import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.default
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }
        .start(wait = true)
}

fun Application.module() {
    val mongo = Mongo(MongoConfig.fromEnv())
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(CallLogging)
    routing {
        healthRoutes(mongoPing = { mongo.ping() })
        staticResources("/", "static") {
            default("index.html")
        }
    }
}
