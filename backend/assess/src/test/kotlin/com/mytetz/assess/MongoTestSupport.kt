package com.mytetz.assess

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.testcontainers.containers.MongoDBContainer

object MongoTestSupport {
    private val container = MongoDBContainer("mongo:7").apply { start() }
    private val client = MongoClient.create(container.connectionString)

    /**
     * A fresh, isolated database for one test class.
     *
     * Rule: give a `name` that is unique across the whole module. Use the
     * feature under test as the name, for example "attempts". Do not use a
     * generic term. Every test class shares one Testcontainers instance and
     * one [MongoClient]. Two classes with the same `name` share one database
     * and its collections. State from one class then leaks into the other
     * class, or into a concurrent run. No check in the code stops this. Pick
     * a name that does not collide with any other
     * `MongoTestSupport.database(...)` call in this module.
     */
    fun database(name: String): MongoDatabase = client.getDatabase("test_$name")
}
