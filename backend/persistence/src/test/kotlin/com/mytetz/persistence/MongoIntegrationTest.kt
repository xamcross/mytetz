package com.mytetz.persistence

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MongoDBContainer
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MongoIntegrationTest {
    companion object {
        private val container = MongoDBContainer("mongo:7")

        @JvmStatic
        @BeforeAll
        fun start() = container.start()

        @JvmStatic
        @AfterAll
        fun stop() = container.stop()
    }

    @Test
    fun `ping succeeds against a live server`() = runTest {
        val mongo = Mongo(MongoConfig(container.connectionString, "mytetz_test"))
        try {
            assertTrue(mongo.ping())
        } finally {
            mongo.close()
        }
    }

    /**
     * Issue #175's own proof: [Mongo.close] must close the real client, and not merely exist.
     * [Mongo.ping] catches every exception, and returns `false` for one — see its own KDoc. A read
     * after [Mongo.close] must then return `false`, where the same read returned `true` before it.
     */
    @Test
    fun `a read after close fails, where the same read succeeded before close`() = runTest {
        val mongo = Mongo(MongoConfig(container.connectionString, "mytetz_test"))
        assertTrue(mongo.ping(), "the read must succeed before close")

        mongo.close()

        assertFalse(mongo.ping(), "the read must fail after close")
    }
}
