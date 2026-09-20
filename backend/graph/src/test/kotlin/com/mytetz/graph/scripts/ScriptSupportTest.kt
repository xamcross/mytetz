package com.mytetz.graph.scripts

import com.mytetz.graph.MongoTestSupport
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import kotlinx.coroutines.test.runTest
import org.bson.BsonDocument
import org.bson.BsonInt32
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.coroutines.cancellation.CancellationException
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

    /**
     * [com.mongodb.ConnectionString]'s own parse of a malformed `MONGODB_URI` can throw an
     * [IllegalArgumentException]. Its message can quote a part of the string back, for example a
     * bad host. This test's own malformed URI carries a distinct marker in its user part, its
     * host part and one query option. The test then catches a leak from any one of the three.
     */
    @Test
    fun `on a malformed MONGODB_URI, runOwnerScript hides the message and never calls the work`() = runTest {
        val malformedUri = "mongodb://markeruser:markerpass@[markerhost/markerdb?markeropt=markervalue"
        var workCalled = false

        val (code, printed) = captureStderr {
            runOwnerScript(makeMongo = { Mongo(MongoConfig(malformedUri, "test_script_support")) }) {
                workCalled = true
                0
            }
        }

        assertEquals(1, code)
        assertTrue("Error:" in printed)
        assertTrue("IllegalArgumentException" in printed, "the class name must still print")
        for (marker in listOf("markeruser", "markerpass", "markerhost", "markeropt", "markervalue")) {
            assertFalse(marker in printed, "'$marker' must never be printed")
        }
        assertFalse(workCalled, "the work must never run when the client itself did not start")
    }

    @Test
    fun `closeQuietly never throws, and prints one fixed line, when close itself fails`() {
        val failingCloseable = AutoCloseable { throw IllegalStateException("a marker-host detail") }

        val (_, printed) = captureStderr {
            closeQuietly(failingCloseable)
            0
        }

        assertTrue("Error:" in printed)
        assertTrue("IllegalStateException" in printed)
        assertFalse("a marker-host detail" in printed, "a close failure must print its class name only")
    }

    @Test
    fun `closeQuietly does nothing when close succeeds`() {
        var closed = false

        closeQuietly(AutoCloseable { closed = true })

        assertTrue(closed)
    }

    @Test
    fun `runMain returns the body's own code on an ordinary return`() {
        assertEquals(0, runMain { 0 })
        assertEquals(1, runMain { 1 })
    }

    /**
     * An [Error], for example `OutOfMemoryError`, is not an [Exception]. A `catch (e: Exception)`
     * alone would let it leave `main` with no exit code. [runMain] must catch it too.
     */
    @Test
    fun `runMain catches an Error, not only an Exception, and gives a non-zero code`() {
        val (code, printed) = captureStderr {
            runMain { throw OutOfMemoryError("a marker-host detail") }
        }

        assertEquals(1, code)
        assertTrue("Error:" in printed)
        assertTrue("OutOfMemoryError" in printed)
        assertFalse("a marker-host detail" in printed, "the message must never print")
    }

    /**
     * [runOwnerScript] re-throws a [CancellationException] rather than mask it. [runMain] is the
     * outermost guard around the whole of `main`, so it must still give this one a fixed, safe
     * exit, and not let it leave the JVM with no exit code either.
     */
    @Test
    fun `runMain also catches a re-thrown CancellationException`() {
        val (code, printed) = captureStderr {
            runMain { throw CancellationException("a marker-host detail") }
        }

        assertEquals(1, code)
        assertTrue("Error:" in printed)
        assertTrue("CancellationException" in printed)
    }
}
