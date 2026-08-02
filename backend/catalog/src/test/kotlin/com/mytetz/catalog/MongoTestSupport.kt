package com.mytetz.catalog

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.testcontainers.containers.MongoDBContainer

/**
 * One Mongo container for the whole module, following `:backend:graph`'s and `:backend:session`'s
 * copies of this object.
 *
 * The pattern it replaces is a `companion object { val container = MongoDBContainer(...).start() }`
 * in every suite, which starts one container **per test class**. This module gained a second suite
 * with `TopicRequestRepositoryTest`, and on this machine — with a ~2 GB Docker VM and thirteen
 * recorded Testcontainers failures — a second container is a cost worth not paying.
 *
 * Convention, inherited verbatim: every consumer must pass a `name` that is unique across the whole
 * module. All suites share one container and one [MongoClient], so two classes passing the same
 * `name` would silently share a database and its collections. There is no runtime check for this.
 */
object MongoTestSupport {
    private val container = MongoDBContainer("mongo:7").apply { start() }
    private val client = MongoClient.create(container.connectionString)

    fun database(name: String): MongoDatabase = client.getDatabase("test_$name")
}
