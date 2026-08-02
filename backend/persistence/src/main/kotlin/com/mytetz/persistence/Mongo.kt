package com.mytetz.persistence

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import org.bson.BsonDocument
import org.bson.BsonInt32

class Mongo(config: MongoConfig) {

    /**
     * Built from settings rather than straight from the URI so that
     * [MongoConfig.serverSelectionTimeoutMillis] is applied. `applyConnectionString` first, then the
     * cluster override — so a `serverSelectionTimeoutMS` written into the URI is deliberately
     * superseded by the configured value, and there is exactly one place that decides it.
     */
    private val client = MongoClient.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(config.uri))
            .applyToClusterSettings {
                it.serverSelectionTimeout(config.serverSelectionTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            .build()
    )

    val database: MongoDatabase = client.getDatabase(config.databaseName)

    /**
     * Bounded by [MongoConfig.serverSelectionTimeoutMillis]. `/api/health` calls this on every
     * request and fly's health check allows 5 seconds, so an unbounded ping makes the endpoint that
     * reports a database outage unable to answer during one.
     */
    suspend fun ping(): Boolean = try {
        database.runCommand(BsonDocument("ping", BsonInt32(1)))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        false
    }

    fun close() = client.close()
}
