package com.mytetz.account

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.testcontainers.containers.MongoDBContainer

/** One MongoDB container for every test class in this module. */
object MongoTestSupport {
    private val container = MongoDBContainer("mongo:7").apply { start() }
    private val client = MongoClient.create(container.connectionString)

    /**
     * A database for one test class.
     *
     * Every caller must pass a [name] that is unique across this module. All test classes share
     * one container and one client. Two classes that pass the same [name] would share a database
     * and its collections. One class's leftover state could then bleed into another class's test.
     */
    fun database(name: String): MongoDatabase = client.getDatabase("test_$name")
}
