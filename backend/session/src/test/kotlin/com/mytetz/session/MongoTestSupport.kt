package com.mytetz.session

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.testcontainers.containers.MongoDBContainer

/**
 * One Mongo container for the whole module, following `:backend:graph`'s copy of this object.
 *
 * The pattern it replaces is a `companion object { val container = MongoDBContainer(...).start() }`
 * in every suite, which starts one container **per test class**. On this machine, with a ~2 GB
 * Docker VM, that is the difference between one container and three — and Task 1.9's review round
 * recorded the eleventh Testcontainers failure here, surfacing as `ExceptionInInitializerError` on
 * one test and `NoClassDefFoundError: Could not initialize class …` on the rest, with the real
 * cause two frames down. Concentrating the initialisation here does not make Docker more reliable,
 * but it gives that failure one named place to happen rather than one per suite.
 *
 * Convention, inherited verbatim from graph's copy: every consumer must pass a `name` that is
 * unique across the whole module. All suites share one container and one [MongoClient], so two
 * classes passing the same `name` would silently share a database and its collections, letting one
 * class's leftover state bleed into another's. There is no runtime check for this.
 */
object MongoTestSupport {
    private val container = MongoDBContainer("mongo:7").apply { start() }
    private val client = MongoClient.create(container.connectionString)

    fun database(name: String): MongoDatabase = client.getDatabase("test_$name")
}
