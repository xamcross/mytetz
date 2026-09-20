package com.mytetz.graph.scripts

import com.mytetz.graph.MongoTestSupport
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import kotlinx.coroutines.test.runTest
import org.bson.BsonDocument
import org.bson.BsonInt32
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [runOwnerScript] itself, the one shared shape for every owner script's `main` (issue
 * #175). Every test runs against the real Testcontainers Mongo, through [MongoTestSupport]. Each
 * test captures the [Mongo] instance [runOwnerScript] builds. It can then read from that same
 * instance after the call returns, to show that the client is already closed.
 */
class ScriptSupportTest {

    private fun testMongo(): Mongo = Mongo(MongoConfig(MongoTestSupport.connectionString, "test_script_support"))

    /** Swaps `System.err` for [block]. Gives back its printed text and its own return value. */
    private fun <T> captureStderr(block: () -> T): Pair<T, String> {
        val originalErr = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer))
        return try {
            val result = block()
            result to buffer.toString()
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `on success, runOwnerScript returns the work's own code and closes the client`() = runTest {
        var captured: Mongo? = null

        val code = runOwnerScript(makeMongo = { testMongo().also { captured = it } }) { mongo ->
            mongo.database.runCommand(BsonDocument("ping", BsonInt32(1)))
            0
        }

        assertEquals(0, code)
        assertFalse(captured!!.ping(), "the client must already be closed")
    }

    @Test
    fun `on a thrown exception, runOwnerScript returns 1, prints the message, and closes the client`() = runTest {
        var captured: Mongo? = null

        val (code, printed) = captureStderr {
            runOwnerScript(makeMongo = { testMongo().also { captured = it } }) {
                throw IllegalStateException("a plain, local failure")
            }
        }

        assertEquals(1, code)
        assertTrue("a plain, local failure" in printed, "a plain exception must print its own message")
        assertFalse(captured!!.ping(), "the client must already be closed")
    }

    /**
     * A [com.mongodb.MongoException]'s own message can carry a host name. See [runOwnerScript]'s
     * own KDoc for the rule. This test drives a real [com.mongodb.MongoException], with an
     * unreachable host and a short server-selection timeout. The test then stays fast, and never
     * touches a live database.
     */
    @Test
    fun `on a driver exception, runOwnerScript hides the message and closes the client`() = runTest {
        var captured: Mongo? = null
        val unreachableHost = "unreachable-host-issue175.invalid"

        val (code, printed) = captureStderr {
            runOwnerScript(
                makeMongo = {
                    Mongo(
                        MongoConfig(
                            uri = "mongodb://$unreachableHost:27017",
                            databaseName = "test_script_support",
                            serverSelectionTimeoutMillis = 200,
                        )
                    ).also { captured = it }
                },
            ) { mongo ->
                mongo.database.runCommand(BsonDocument("ping", BsonInt32(1)))
                0
            }
        }

        assertEquals(1, code)
        assertTrue("Error:" in printed)
        assertFalse(unreachableHost in printed, "the host name must never be printed")
        assertFalse(captured!!.ping(), "the client must already be closed")
    }
}
