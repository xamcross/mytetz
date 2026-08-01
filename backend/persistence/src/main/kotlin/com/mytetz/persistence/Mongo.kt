package com.mytetz.persistence

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlin.coroutines.cancellation.CancellationException
import org.bson.BsonDocument
import org.bson.BsonInt32

class Mongo(config: MongoConfig) {
    private val client = MongoClient.create(config.uri)
    val database: MongoDatabase = client.getDatabase(config.databaseName)

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
