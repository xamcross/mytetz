package com.mytetz.graph.scripts

import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.MAX_PUBLISHED_EXPLANATIONS
import com.mytetz.graph.Verb
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/** How many hex characters name one explanation page's short key (spec section 7.1). */
private const val SHORT_KEY_LENGTH = 12

/**
 * How many candidates [listTopCandidates] shows by default. The spec gives no number for this;
 * 50 matches issue #49's own owner step ("Run the review path... to list the top 50 nodes").
 */
const val DEFAULT_LIST_LIMIT: Int = 50

/**
 * The review path from spec section 17, question 1
 * (`docs/superpowers/specs/2026-09-19-public-surface-design.md`): the owner's way to set
 * [Explanation.published] on the explanations with the highest demand, one explanation at a time.
 *
 * ## Why one at a time, and not the top N as a group
 *
 * The whole point of this issue is that a text becomes public only after a person has read that
 * exact text. Issue #49's own owner steps say so directly: "Read each explanation. Mark an
 * accurate one as published. Leave a wrong one unpublished." A command that published every top
 * candidate in one call would publish the wrong ones too, the moment the owner found even one
 * candidate wrong. So this script never publishes "the top N": it only ever publishes the exact
 * keys the owner names, after the owner has read them.
 *
 * ## How the owner runs this
 *
 * **List the candidates. Writes nothing. This is the default, with no argument:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:run
 * ```
 * Add `--limit <n>` to see more or fewer than the default [DEFAULT_LIST_LIMIT]. Add
 * `--out <file>` to also write the same list to a Markdown file, to read outside the terminal.
 *
 * **Publish an exact list of keys, after reading each one:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:run --args="--publish --keys <key1>,<key2>"
 * ```
 * or, for a longer list, one full key per line in a file:
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:run --args="--publish --keys-file keys.txt"
 * ```
 * `--keys` and `--keys-file` both take the **full**, 64-character content key, never the
 * 12-character short key: a short key can name more than one document (spec section 7.2), and a
 * command that acted on a short key could publish the wrong text by accident.
 *
 * **Take a text back:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:run --args="--unpublish --keys <key1>"
 * ```
 *
 * `--publish` or `--unpublish` with no `--keys`/`--keys-file` is an error: there is no bulk mode.
 *
 * ## The all-or-nothing rule
 *
 * [publishByKeys] and [unpublishByKeys] check every key first: the document exists, its
 * [Explanation.verb] is [Verb.EXPLAIN], and it is not already in the state the command would set it
 * to. [publishByKeys] also checks that the write would not cross [MAX_PUBLISHED_EXPLANATIONS]. If
 * one key fails a check, the whole command writes nothing, names every bad key, and [main] exits
 * with a non-zero code.
 *
 * ## What this script cannot check, and why that is fine
 *
 * This script lives in the `graph` module, and a topic's `PUBLISHED`/`DRAFT` status lives in the
 * `catalog` module, which `graph` does not depend on. So this script cannot refuse a key because
 * its topic is unpublished. That check does not need to happen here: `ExplanationPageRoutes.kt`
 * checks the topic's status on every request, so a published explanation under a withdrawn topic
 * still answers `404` at the route, exactly as spec section 5.4 requires.
 *
 * ## What this script never does
 *
 * It never reads a `.env` file: it reads the `MONGODB_URI` environment variable only, the same way
 * production does ([MongoConfig.fromEnv]). It never prints that variable's value, or any other part
 * of a connection string. [listTopCandidates], [publishByKeys] and [unpublishByKeys] are the
 * functions this issue's own tests exercise, against a real Testcontainers Mongo, never against a
 * live database — see `PublishTopExplanationsTest.kt`.
 */
fun main(args: Array<String>) {
    when (val command = parseArgs(args.toList()) { path -> File(path).readText() }) {
        is ReviewCommand.InvalidArgs -> {
            System.err.println("Error: ${command.message}")
            exitProcess(1)
        }
        is ReviewCommand.ListCandidates -> runBlocking {
            val explanations = explanationRepository()
            val candidates = listTopCandidates(explanations, command.limit)
            val report = renderCandidateReport(candidates)
            println(report)
            if (command.outFile != null) {
                File(command.outFile).writeText(report)
                println("Wrote ${candidates.size} candidate(s) to ${command.outFile}")
            }
        }
        is ReviewCommand.Publish -> runBlocking {
            when (val outcome = publishByKeys(explanationRepository(), command.keys)) {
                is PublishOutcome.Applied -> println("Published ${outcome.changedKeys.size} document(s).")
                is PublishOutcome.Rejected -> {
                    System.err.println("Nothing was published. Problems:")
                    outcome.problems.forEach { System.err.println("- $it") }
                    exitProcess(1)
                }
            }
        }
        is ReviewCommand.Unpublish -> runBlocking {
            when (val outcome = unpublishByKeys(explanationRepository(), command.keys)) {
                is PublishOutcome.Applied -> println("Unpublished ${outcome.changedKeys.size} document(s).")
                is PublishOutcome.Rejected -> {
                    System.err.println("Nothing was unpublished. Problems:")
                    outcome.problems.forEach { System.err.println("- $it") }
                    exitProcess(1)
                }
            }
        }
    }
}

private fun explanationRepository(): ExplanationRepository =
    ExplanationRepository(Mongo(MongoConfig.fromEnv()).database)

// ---------------------------------------------------------------------- argument parsing

/** One parsed command line. See [main]'s own KDoc for the exact commands. */
internal sealed interface ReviewCommand {
    data class ListCandidates(val limit: Int, val outFile: String?) : ReviewCommand
    data class Publish(val keys: List<String>) : ReviewCommand
    data class Unpublish(val keys: List<String>) : ReviewCommand
    data class InvalidArgs(val message: String) : ReviewCommand
}

/**
 * Reads [args] into one [ReviewCommand]. [readFile] resolves `--keys-file`; [main] passes a real
 * file read, and a test passes a plain lambda over an in-memory string, so this function needs no
 * disk access of its own to test.
 */
internal fun parseArgs(args: List<String>, readFile: (String) -> String): ReviewCommand {
    val publish = "--publish" in args
    val unpublish = "--unpublish" in args
    if (publish && unpublish) return ReviewCommand.InvalidArgs("give --publish or --unpublish, not both")

    if (publish || unpublish) {
        val flagName = if (publish) "--publish" else "--unpublish"
        val inlineKeys = optionValue(args, "--keys")?.let(::parseKeysArg)
        val fileKeys = optionValue(args, "--keys-file")?.let { path -> parseKeysFileText(readFile(path)) }
        val keys = inlineKeys ?: fileKeys
        if (keys.isNullOrEmpty()) {
            return ReviewCommand.InvalidArgs("$flagName needs --keys <key1,key2,...> or --keys-file <path>")
        }
        return if (publish) ReviewCommand.Publish(keys) else ReviewCommand.Unpublish(keys)
    }

    val limit = optionValue(args, "--limit")?.toIntOrNull() ?: DEFAULT_LIST_LIMIT
    val outFile = optionValue(args, "--out")
    return ReviewCommand.ListCandidates(limit, outFile)
}

/** The value right after [flag] in [args], or null when [flag] is absent or has no value after it. */
private fun optionValue(args: List<String>, flag: String): String? {
    val index = args.indexOf(flag)
    return if (index == -1 || index + 1 >= args.size) null else args[index + 1]
}

/** Splits a `--keys` value on a comma, trims each entry, and drops an empty one. */
internal fun parseKeysArg(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** Reads a `--keys-file`'s text as one key per line, trims each line, and drops a blank one. */
internal fun parseKeysFileText(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }

// ---------------------------------------------------------------------- listing

/**
 * The top [limit] `EXPLAIN` nodes that are not published yet, by [Explanation.requestCount]
 * descending — the candidates the owner reads before choosing which to publish.
 *
 * Over-fetches past [limit] by the number already published, then drops the published ones and
 * trims back to [limit]: [ExplanationRepository.findTopByRequestCount] has no "unpublished only"
 * filter of its own, and an already-published node can still be a top node by demand.
 */
suspend fun listTopCandidates(explanations: ExplanationRepository, limit: Int): List<Explanation> {
    val alreadyPublished = explanations.findPublished().size
    return explanations.findTopByRequestCount(limit = limit + alreadyPublished)
        .filterNot { it.published }
        .take(limit)
}

/**
 * Renders [candidates] as Markdown a person can read: for each one, the full content key (to copy
 * into `--keys`), the short key, the topic, the request count, the public path, and the full body
 * text. [main] prints this to the terminal and, with `--out`, also writes it to a file.
 */
internal fun renderCandidateReport(candidates: List<Explanation>): String = buildString {
    appendLine("# Review candidates")
    appendLine()
    appendLine("${candidates.size} unpublished EXPLAIN node(s), by request count, highest first.")
    appendLine("Read each one. Then publish only the keys you approve.")
    candidates.forEachIndexed { index, candidate ->
        val shortKey = candidate.key.take(SHORT_KEY_LENGTH)
        appendLine()
        appendLine("## ${index + 1}. ${candidate.span ?: candidate.key}")
        appendLine()
        appendLine("- Full key: `${candidate.key}`")
        appendLine("- Short key: `$shortKey`")
        appendLine("- Topic: `${candidate.topicSlug}`")
        appendLine("- Request count: ${candidate.requestCount}")
        appendLine("- Public path: `/topics/${candidate.topicSlug}/explain/$shortKey`")
        appendLine()
        appendLine("Body:")
        appendLine()
        appendLine("> ${candidate.body.replace("\n", "\n> ")}")
    }
}

// ---------------------------------------------------------------------- publish / unpublish

/** What [publishByKeys] or [unpublishByKeys] did. */
sealed interface PublishOutcome {
    /** The command wrote [changedKeys], and nothing else. */
    data class Applied(val changedKeys: List<String>) : PublishOutcome

    /** The command wrote nothing. [problems] names every key that failed a check. */
    data class Rejected(val problems: List<String>) : PublishOutcome
}

/**
 * Publishes exactly [keys], all or nothing.
 *
 * Every key must: name a document that exists; carry [Verb.EXPLAIN] (never [Verb.SEED] or
 * [Verb.VISUALIZE] — see [main]'s own KDoc for why a `VISUALIZE` node must never become public);
 * and not be published already. The whole set must also fit under [cap]: the count already
 * published plus [keys]'s own size must not exceed it. If any check fails for any key, this
 * function writes nothing and returns [PublishOutcome.Rejected] with one message per problem.
 */
suspend fun publishByKeys(
    explanations: ExplanationRepository,
    keys: List<String>,
    cap: Int = MAX_PUBLISHED_EXPLANATIONS,
): PublishOutcome {
    if (keys.isEmpty()) {
        return PublishOutcome.Rejected(listOf("no key given: pass --keys <key1,key2,...> or --keys-file <path>"))
    }

    val problems = mutableListOf<String>()
    val toPublish = mutableListOf<Explanation>()
    for (key in keys) {
        val document = explanations.findByKey(key)
        when {
            document == null -> problems += "key $key: no such document"
            document.verb != Verb.EXPLAIN -> problems += "key $key: verb is ${document.verb}, not EXPLAIN"
            document.published -> problems += "key $key: already published"
            else -> toPublish += document
        }
    }

    if (problems.isEmpty()) {
        val alreadyPublished = explanations.findPublished().size
        val newTotal = alreadyPublished + toPublish.size
        if (newTotal > cap) {
            problems += "publishing ${toPublish.size} key(s) would raise the published count to " +
                "$newTotal, over the cap of $cap ($alreadyPublished already published)"
        }
    }

    if (problems.isNotEmpty()) return PublishOutcome.Rejected(problems)

    toPublish.forEach { explanations.setPublished(it.key, true) }
    return PublishOutcome.Applied(toPublish.map { it.key })
}

/**
 * Unpublishes exactly [keys], all or nothing — the way back for a text the owner published and
 * then wants to withdraw.
 *
 * Every key must name a document that exists, carries [Verb.EXPLAIN], and is currently published.
 * If any check fails for any key, this function writes nothing and returns
 * [PublishOutcome.Rejected] with one message per problem. No cap check is needed: this only ever
 * lowers the published count.
 */
suspend fun unpublishByKeys(explanations: ExplanationRepository, keys: List<String>): PublishOutcome {
    if (keys.isEmpty()) {
        return PublishOutcome.Rejected(listOf("no key given: pass --keys <key1,key2,...> or --keys-file <path>"))
    }

    val problems = mutableListOf<String>()
    val toUnpublish = mutableListOf<Explanation>()
    for (key in keys) {
        val document = explanations.findByKey(key)
        when {
            document == null -> problems += "key $key: no such document"
            document.verb != Verb.EXPLAIN -> problems += "key $key: verb is ${document.verb}, not EXPLAIN"
            !document.published -> problems += "key $key: not published"
            else -> toUnpublish += document
        }
    }

    if (problems.isNotEmpty()) return PublishOutcome.Rejected(problems)

    toUnpublish.forEach { explanations.setPublished(it.key, false) }
    return PublishOutcome.Applied(toUnpublish.map { it.key })
}
