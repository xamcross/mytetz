package com.mytetz.graph.scripts

import java.io.File
import java.util.concurrent.TimeUnit

/** What a child-JVM run of one owner script's `main` produced. */
internal data class ScriptProcessResult(val exitCode: Int, val output: String)

/**
 * Runs [mainClass] as a separate `java` process. This is issue #175's own proof that a script
 * ends its process for real. An in-process test cannot give this proof: a non-daemon thread stops
 * only the JVM the script's own `main` runs in, and a test that calls `main` in-process shares the
 * test's own JVM, and its own non-daemon threads, with every other test.
 *
 * The classpath goes through the `CLASSPATH` environment variable, and not a `-cp` argument. This
 * project's own test classpath is long. A `-cp` argument that long can be over the Windows
 * command-line length limit. [System.getProperty]`("java.class.path")` already holds this test's
 * own classpath, a superset of the one script's own runtime classpath.
 *
 * [env] adds entries to the current process's own environment, and can override an entry too. The
 * most common entries are `MONGODB_URI`, always pointed at the Testcontainers database, and
 * `MYTETZ_MONGO_SERVER_SELECTION_TIMEOUT_MILLIS`, to keep a deliberately unreachable host fast.
 * [removeEnv] takes an inherited entry away instead. One example: it gives the child no
 * `MONGODB_URI` at all. Standard output and standard error merge into one
 * [ScriptProcessResult.output]. One test can then check a printed line, and the absence of a
 * leaked value, in a single string.
 */
internal fun runScriptProcess(
    mainClass: String,
    args: List<String> = emptyList(),
    env: Map<String, String> = emptyMap(),
    removeEnv: Set<String> = emptySet(),
    timeoutSeconds: Long = 60,
): ScriptProcessResult {
    val javaBinary = File(System.getProperty("java.home"), "bin/java").absolutePath
    val command = listOf(javaBinary, mainClass) + args
    val builder = ProcessBuilder(command)
    builder.environment()["CLASSPATH"] = System.getProperty("java.class.path")
    removeEnv.forEach { builder.environment().remove(it) }
    builder.environment().putAll(env)
    builder.redirectErrorStream(true)

    val process = builder.start()

    // A separate, daemon thread drains the merged stream while the process runs. A read of the
    // stream only after `waitFor` would deadlock a process that fills its output pipe and then
    // hangs. That hang is exactly the failure this test proves the fix for.
    val output = StringBuilder()
    val readerThread = Thread {
        process.inputStream.bufferedReader().forEachLine { line -> output.appendLine(line) }
    }
    readerThread.isDaemon = true
    readerThread.start()

    val endedInTime = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!endedInTime) {
        process.destroyForcibly()
        error("the process for $mainClass did not end within $timeoutSeconds second(s)")
    }
    readerThread.join(TimeUnit.SECONDS.toMillis(5))
    return ScriptProcessResult(process.exitValue(), output.toString())
}
