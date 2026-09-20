package com.mytetz.graph.scripts

import com.mongodb.MongoException
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one shared shape of every owner script's `main` (issue #175).
 *
 * This function makes one [Mongo] client from [MongoConfig.fromEnv]. It runs [work] inside a
 * `try`. It closes the client in a `finally`. The client closes on every path: a normal return, a
 * thrown exception, and a cancellation. The function returns the process exit code. It never
 * calls `exitProcess` itself. A caller's own `main` must call `exitProcess` with this function's
 * result, as the last statement of `main`. A call to `exitProcess` inside a `try`, or inside
 * `runBlocking`, skips the `finally` and leaves the client open.
 *
 * A [MongoException]'s own message can carry a host name, or another part of a connection string.
 * See `MongoConfig.fromEnv`'s own KDoc for the rule this project follows. For this reason, a
 * driver exception prints its class name and one fixed line, and never its message. Every other
 * exception prints its own message: no other exception of this project carries a connection
 * string.
 *
 * [makeMongo] builds the one [Mongo] instance this run uses. `main` never passes this parameter,
 * so it always defaults to [MongoConfig.fromEnv] — the same environment variable production reads.
 * A test passes its own [makeMongo], pointed at a Testcontainers database. It can then read the
 * [Mongo] instance it built, to show that this function closed it (`ScriptSupportTest.kt`).
 */
internal fun runOwnerScript(
    makeMongo: () -> Mongo = { Mongo(MongoConfig.fromEnv()) },
    work: suspend (Mongo) -> Int,
): Int {
    var mongo: Mongo? = null
    return try {
        mongo = makeMongo()
        runBlocking { work(mongo) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: MongoException) {
        System.err.println("Error: a database operation failed. Exception class: ${e::class.simpleName}")
        1
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        1
    } finally {
        mongo?.close()
    }
}
