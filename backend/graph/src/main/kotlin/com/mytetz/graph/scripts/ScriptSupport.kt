package com.mytetz.graph.scripts

import com.mongodb.MongoException
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [body], the whole of one script's `main`. Gives back its exit code (issue #175).
 *
 * This is `main`'s own outermost guard. It catches every [Throwable], and not only every
 * [Exception]. An [Error], for example `OutOfMemoryError` or `NoClassDefFoundError`, would
 * otherwise leave `main` with no exit code. The process would then not end. A
 * [CancellationException] that [runOwnerScript] re-throws also reaches here, and gets this same
 * fixed, safe handling. The process still ends.
 *
 * The caller's own `main` passes its whole body as [body]. `main` then ends with one call to
 * `exitProcess`, on this function's own result, as its last statement.
 */
internal fun runMain(body: () -> Int): Int = try {
    body()
} catch (e: Throwable) {
    System.err.println("Error: the script failed. Exception class: ${e::class.simpleName}")
    1
}

/**
 * Checks the `MONGODB_URI` environment variable, the most frequent owner mistake. When the
 * variable is absent or blank, this function prints one fixed, safe line, and returns `false`.
 * This check reads the variable directly, and never an exception's own message.
 * [MongoConfig.fromEnv] happens to throw a safe message for this same case, but a caller must not
 * depend on that.
 */
internal fun requireMongoUriSet(): Boolean {
    if (!System.getenv("MONGODB_URI").isNullOrBlank()) return true
    System.err.println("Error: MONGODB_URI is not set")
    return false
}

/**
 * Closes [closeable], and never throws. A failed close prints one fixed line, with the exception's
 * class name only, and never its message. [runOwnerScript] calls this from its own `finally`
 * clause. `main` then still reaches `exitProcess`, with the exit code [work] itself gave, even
 * when the close itself fails. This is a small, generic function over [AutoCloseable], and not a
 * method of [Mongo], a final class. A test can then drive it with a fake that throws on `close`.
 */
internal fun closeQuietly(closeable: AutoCloseable) {
    try {
        closeable.close()
    } catch (e: Exception) {
        System.err.println("Error: the database client did not close cleanly. Exception class: ${e::class.simpleName}")
    }
}

/**
 * The one shared shape of every owner script's `main`, once [requireMongoUriSet] has already
 * passed (issue #175).
 *
 * This function has two phases.
 *
 * **The start phase** calls [makeMongo]. A [Mongo] constructor call can throw synchronously. One
 * example: [com.mongodb.ConnectionString]'s own parse of a malformed `MONGODB_URI`. Some of those
 * parse exceptions carry a part of the string in their own message, for example a host name or an
 * option value. For this reason, a start-phase exception prints one fixed line, with the class
 * name only, and never its message. Nothing needs closing yet, because no [Mongo] instance exists
 * yet.
 *
 * **The work phase** runs [work] inside a `try`. It closes the client, through [closeQuietly], in
 * a `finally` clause. The client closes on every path out of this phase: a normal return, a
 * thrown exception, and a cancellation. A [MongoException]'s own message can carry a host name, or
 * another part of a connection string. A driver exception in this phase then also prints its
 * class name and one fixed line, and never its message. Every other exception of this phase
 * prints its own message: no other exception of this project carries a connection string.
 *
 * This function returns the process exit code. It never calls `exitProcess` itself. A caller's
 * own `main` must call `exitProcess` with this function's result, as the last statement of `main`.
 * See [runMain]. A call to `exitProcess` inside a `try`, or inside `runBlocking`, skips a
 * `finally` clause, and leaves the client open.
 *
 * [makeMongo] builds the one [Mongo] instance this run uses. `main` never passes this parameter.
 * It then always defaults to [MongoConfig.fromEnv], the same environment variable production
 * reads. A test passes its own [makeMongo], pointed at a Testcontainers database, or at a
 * malformed or an unreachable connection string. The test can then read the [Mongo] instance it
 * built, to show that this function closed it (`ScriptSupportTest.kt`).
 */
internal fun runOwnerScript(
    makeMongo: () -> Mongo = { Mongo(MongoConfig.fromEnv()) },
    work: suspend (Mongo) -> Int,
): Int {
    val mongo = try {
        makeMongo()
    } catch (e: Exception) {
        System.err.println(
            "Error: the database client did not start. Check MONGODB_URI. Exception class: ${e::class.simpleName}",
        )
        return 1
    }
    return try {
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
        closeQuietly(mongo)
    }
}
