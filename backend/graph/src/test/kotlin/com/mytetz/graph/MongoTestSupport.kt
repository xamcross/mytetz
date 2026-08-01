package com.mytetz.graph

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.testcontainers.containers.MongoDBContainer

object MongoTestSupport {
    private val container = MongoDBContainer("mongo:7").apply { start() }
    private val client = MongoClient.create(container.connectionString)

    /**
     * A fresh, isolated database per test class.
     *
     * Convention: every consumer must pass a `name` that is unique across the whole module —
     * e.g. the feature under test ("explanations"), not a generic term. All test classes share
     * one Testcontainers instance and one [MongoClient], so two classes passing the same `name`
     * would silently share a database and its collections, letting one class's leftover state
     * (or a concurrent run) bleed into another's. There is no runtime check for this — pick a
     * name that won't collide with any other `MongoTestSupport.database(...)` call in the module.
     */
    fun database(name: String): MongoDatabase = client.getDatabase("test_$name")
}
