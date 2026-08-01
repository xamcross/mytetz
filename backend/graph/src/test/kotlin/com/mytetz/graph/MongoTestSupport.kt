package com.mytetz.graph

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.testcontainers.containers.MongoDBContainer

object MongoTestSupport {
    private val container = MongoDBContainer("mongo:7").apply { start() }
    private val client = MongoClient.create(container.connectionString)

    /** A fresh, isolated database per test class. */
    fun database(name: String): MongoDatabase = client.getDatabase("test_$name")
}
