package com.mytetz.persistence

import kotlinx.coroutines.test.runTest
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MongoConfigTest {

    @Test
    fun `an unparseable or non-positive server selection timeout falls back to the default`() {
        assertEquals(
            MongoConfig.DEFAULT_SERVER_SELECTION_TIMEOUT_MILLIS,
            MongoConfig.resolveServerSelectionTimeoutMillis(null),
        )
        assertEquals(
            MongoConfig.DEFAULT_SERVER_SELECTION_TIMEOUT_MILLIS,
            MongoConfig.resolveServerSelectionTimeoutMillis("soon"),
        )
        // Zero would mean "give up before trying", which is a broken deployment rather than a fast
        // one. Same shape as every other resolver in this project.
        assertEquals(
            MongoConfig.DEFAULT_SERVER_SELECTION_TIMEOUT_MILLIS,
            MongoConfig.resolveServerSelectionTimeoutMillis("0"),
        )
        assertEquals(2_500L, MongoConfig.resolveServerSelectionTimeoutMillis(" 2500 "))
    }

    @Test
    fun `the default is inside fly's health-check timeout`() {
        // `fly.toml`'s health check allows 5s. The driver's own default is 30s, so an unreachable
        // database made /api/health take six times longer to answer than the check waits — the
        // endpoint that exists to report a database outage could not answer during one.
        assertTrue(
            MongoConfig.DEFAULT_SERVER_SELECTION_TIMEOUT_MILLIS < 5_000,
            "the default must leave room inside the 5s health-check timeout",
        )
    }

    @Test
    fun `ping gives up quickly when the database is unreachable`() = runTest {
        // 192.0.2.0/24 is TEST-NET-1 (RFC 5737): guaranteed not routable, so this is a genuine
        // "server never answers" rather than a refused connection.
        val mongo = Mongo(
            MongoConfig(
                uri = "mongodb://192.0.2.1:27017",
                databaseName = "unreachable",
                serverSelectionTimeoutMillis = 1_000,
            )
        )

        var reachable = true
        val elapsed = measureTimeMillis { reachable = mongo.ping() }
        mongo.close()

        assertFalse(reachable, "an unroutable address reported as reachable")
        assertTrue(elapsed < 5_000, "ping took ${elapsed}ms; the configured timeout was not applied")
    }
}
