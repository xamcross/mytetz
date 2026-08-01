package com.mytetz.persistence

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MongoDBContainer
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
}
