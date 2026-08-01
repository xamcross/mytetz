package com.mytetz.persistence

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import org.bson.BsonDocument
import org.bson.BsonInt32

class Mongo(config: MongoConfig) {
    private val client = MongoClient.create(config.uri)
    val database: MongoDatabase = client.getDatabase(config.databaseName)

    suspend fun ping(): Boolean = runCatching {
        database.runCommand(BsonDocument("ping", BsonInt32(1)))
        true
    }.getOrDefault(false)

    fun close() = client.close()
}
