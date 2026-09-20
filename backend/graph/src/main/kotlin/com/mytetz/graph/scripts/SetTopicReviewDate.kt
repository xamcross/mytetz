package com.mytetz.graph.scripts

import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ContentKey
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.GraphConfig
import com.mytetz.llm.AnthropicLlmClient
import com.mytetz.persistence.Mongo
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.system.exitProcess

/**
 * The owner command for issue #47. It sets or clears [Topic.reviewedAt].
 *
 * ## Why a person sets this field
 *
 * Spec section 8 of `docs/superpowers/specs/2026-09-19-public-surface-design.md` decides the
 * rule: `reviewedAt` records one fact. A person read a topic's seed text and found it correct on a
 * given day. No other field can supply that fact. A migration must never set it.
 *
 * This command follows the same safety shape as
 * [com.mytetz.graph.scripts.PublishTopExplanations]. The default is a dry run. A write needs an
 * explicit flag. A write is all or nothing.
 *
 * ## How the owner runs this
 *
 * **List the present state. This step writes nothing. It is the default, with no argument:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:runReviewDate
 * ```
 * Add `--slugs <a,b,c>` to see only those topics.
 *
 * **Set a review date, after a person reads the topic's seed text:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:runReviewDate --args="--set-reviewed --slugs <a,b,c> --date 2026-09-20"
 * ```
 * For a longer list, put one slug on each line of a file. Use `--slugs-file` in place of
 * `--slugs`. `--date` takes a plain calendar date, `YYYY-MM-DD`, in UTC.
 *
 * **Take a review date back:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:runReviewDate --args="--clear-reviewed --slugs <a,b,c>"
 * ```
 *
 * ## The all-or-nothing rule
 *
 * [setReviewedAt] and [clearReviewedAt] check every slug first. Suppose one slug is unknown, not
 * published, or the date fails a check. Then the whole command writes nothing. It names every bad
 * value. [main] exits with a non-zero code.
 *
 * ## What this command refuses
 *
 * - An unknown slug, or a slug whose topic is not [TopicStatus.PUBLISHED].
 * - A date that is not a valid `YYYY-MM-DD` calendar date.
 * - A date after today, in UTC. A review cannot happen in the future.
 * - A date before the topic's own seed text was written. This command reads that date the same
 *   way [com.mytetz.api.TopicPageRoutes] reads it: [ContentKey.seed], then
 *   [ExplanationRepository.findByKey]. A topic with no stored seed has no creation date to
 *   compare, so this one check is skipped for it. Every other check still applies to it.
 *
 * ## What this command never does
 *
 * It never reads a `.env` file. It reads the `MONGODB_URI` environment variable only, the same way
 * production does (`MongoConfig.fromEnv`). It never prints that variable's value. It never prints
 * any other part of a connection string. A database failure is reported by its exception class
 * name alone, and never by its message. See `runOwnerScript`'s own KDoc, in `ScriptSupport.kt`.
 *
 * [setReviewedAt], [clearReviewedAt] and [listReviewRows] are the functions this issue's own tests
 * exercise. Each test runs against a real Testcontainers Mongo. No test connects to a live
 * database. See `SetTopicReviewDateTest.kt`.
 *
 * ## How this process ends (issue #175)
 *
 * [main] ends with one call to `exitProcess`, as its last statement, on the result of `runMain`.
 * `runMain` catches every `Throwable`. This one call is then the sole reason this process always
 * ends. It does not depend on which thread of the driver is not a daemon thread, or on why.
 */
fun main(args: Array<String>) {
    exitProcess(
        runMain {
            val command = parseReviewDateArgs(args.toList()) { path -> File(path).readText() }
            when {
                command is ReviewDateCommand.InvalidArgs -> {
                    System.err.println("Error: ${command.message}")
                    1
                }
                !requireMongoUriSet() -> 1
                else -> runOwnerScript { mongo -> runCommand(mongo, command) }
            }
        },
    )
}

private suspend fun runCommand(mongo: Mongo, command: ReviewDateCommand): Int {
    val topics = TopicRepository(mongo.database)
    val explanations = ExplanationRepository(mongo.database)
    val modelFamily = AnthropicLlmClient.resolveModel(System.getenv(AnthropicLlmClient.MODEL_FAMILY_ENV))
    return when (command) {
        is ReviewDateCommand.ListReviewed -> {
            println(renderReviewRows(listReviewRows(topics, command.slugs)))
            0
        }
        is ReviewDateCommand.SetReviewed -> reportOutcome(
            "written",
            setReviewedAt(topics, explanations, command.slugs, command.date, modelFamily, GraphConfig()),
        )
        is ReviewDateCommand.ClearReviewed -> reportOutcome("cleared", clearReviewedAt(topics, command.slugs))
        is ReviewDateCommand.InvalidArgs -> 0 // handled in main, before this function runs
    }
}

private fun reportOutcome(verb: String, outcome: ReviewDateOutcome): Int = when (outcome) {
    is ReviewDateOutcome.Applied -> {
        println(renderApplied(verb, outcome.changes))
        0
    }
    is ReviewDateOutcome.Rejected -> {
        System.err.println("Nothing was $verb. Problems:")
        outcome.problems.forEach { System.err.println("- $it") }
        1
    }
}

// ---------------------------------------------------------------------- argument parsing

/** One parsed command line. See [main]'s own KDoc for the exact commands. */
internal sealed interface ReviewDateCommand {
    data class ListReviewed(val slugs: List<String>?) : ReviewDateCommand
    data class SetReviewed(val slugs: List<String>, val date: String) : ReviewDateCommand
    data class ClearReviewed(val slugs: List<String>) : ReviewDateCommand
    data class InvalidArgs(val message: String) : ReviewDateCommand
}

/**
 * Reads [args] into one [ReviewDateCommand]. [readFile] resolves `--slugs-file`.
 * [main] passes a real file read. A test passes a plain lambda over an in-memory string.
 * `PublishTopExplanations.parseArgs` uses the same split.
 */
internal fun parseReviewDateArgs(args: List<String>, readFile: (String) -> String): ReviewDateCommand {
    val setReviewed = "--set-reviewed" in args
    val clearReviewed = "--clear-reviewed" in args
    if (setReviewed && clearReviewed) {
        return ReviewDateCommand.InvalidArgs("give --set-reviewed or --clear-reviewed, not both")
    }

    val inlineSlugs = optionValue(args, "--slugs")?.let(::parseSlugsArg)
    val fileSlugs = optionValue(args, "--slugs-file")?.let { path -> parseSlugsFileText(readFile(path)) }
    val slugs = inlineSlugs ?: fileSlugs

    if (setReviewed) {
        if (slugs.isNullOrEmpty()) {
            return ReviewDateCommand.InvalidArgs("--set-reviewed needs --slugs <a,b,c> or --slugs-file <path>")
        }
        val date = optionValue(args, "--date")
            ?: return ReviewDateCommand.InvalidArgs("--set-reviewed needs --date <YYYY-MM-DD>")
        return ReviewDateCommand.SetReviewed(slugs, date)
    }

    if (clearReviewed) {
        if (slugs.isNullOrEmpty()) {
            return ReviewDateCommand.InvalidArgs("--clear-reviewed needs --slugs <a,b,c> or --slugs-file <path>")
        }
        return ReviewDateCommand.ClearReviewed(slugs)
    }

    return ReviewDateCommand.ListReviewed(slugs)
}

/** The value right after [flag] in [args], or null when [flag] is absent or has no value after it. */
private fun optionValue(args: List<String>, flag: String): String? {
    val index = args.indexOf(flag)
    return if (index == -1 || index + 1 >= args.size) null else args[index + 1]
}

/** Splits a `--slugs` value on a comma, trims each entry, and drops an empty one. */
internal fun parseSlugsArg(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** Reads a `--slugs-file`'s text as one slug per line, trims each line, and drops a blank one. */
internal fun parseSlugsFileText(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }

// ---------------------------------------------------------------------- the dry run (listing)

/**
 * One row of the dry-run listing: a topic's slug, title, present review date (`null` for "none"),
 * its public path, and [note] for a named slug that is unknown or not published.
 */
data class TopicReviewRow(
    val slug: String,
    val title: String,
    val reviewedAt: String?,
    val path: String,
    val note: String? = null,
)

/**
 * Builds one row per slug in [slugs]. With `null` for [slugs], it builds one row per published
 * topic. This is a pure read: it never writes to [topics].
 *
 * A named slug can name no topic, or a draft topic. That row still appears, with
 * [TopicReviewRow.note] set. The owner then sees the reason for the missing review date. The slug
 * does not vanish from the list without a reason.
 */
suspend fun listReviewRows(topics: TopicRepository, slugs: List<String>?): List<TopicReviewRow> {
    if (slugs == null) {
        return topics.listPublished(category = null, query = null).map { it.toReviewRow() }
    }
    return slugs.map { slug ->
        val topic = topics.findBySlug(slug)
        when {
            topic == null -> TopicReviewRow(slug, title = "", reviewedAt = null, path = topicPath(slug), note = "unknown topic")
            topic.status != TopicStatus.PUBLISHED -> topic.toReviewRow(note = "not published")
            else -> topic.toReviewRow()
        }
    }
}

private fun Topic.toReviewRow(note: String? = null): TopicReviewRow = TopicReviewRow(
    slug = slug,
    title = title,
    reviewedAt = reviewedAt?.let(::isoDate),
    path = topicPath(slug),
    note = note,
)

private fun topicPath(slug: String): String = "/topics/$slug"

/** Renders [rows] as plain, tab-separated text: one line per row, for the terminal. */
internal fun renderReviewRows(rows: List<TopicReviewRow>): String = buildString {
    if (rows.isEmpty()) {
        append("No topic matched.")
        return@buildString
    }
    rows.forEachIndexed { index, row ->
        if (index > 0) appendLine()
        val date = row.reviewedAt ?: "none"
        val suffix = row.note?.let { " ($it)" } ?: ""
        append("${row.slug}\t${row.title}\t$date\t${row.path}$suffix")
    }
}

// ---------------------------------------------------------------------- set / clear

/** What [setReviewedAt] or [clearReviewedAt] did. */
sealed interface ReviewDateOutcome {
    /** The command wrote [changes], and nothing else. */
    data class Applied(val changes: List<ReviewDateChange>) : ReviewDateOutcome

    /** The command wrote nothing. [problems] names every value that failed a check. */
    data class Rejected(val problems: List<String>) : ReviewDateOutcome
}

/** One topic's review date before and after a write. `null` reads as "none". */
data class ReviewDateChange(val slug: String, val oldDate: String?, val newDate: String?)

/**
 * Sets [date] as the review date for exactly [slugs], all or nothing.
 *
 * Every check below runs before any write, over every slug. One bad slug or one bad date leaves
 * every named topic untouched:
 *
 * - [date] is a valid `YYYY-MM-DD` calendar date.
 * - [date] is not after [today] (UTC). A review cannot happen in the future.
 * - the slug names a topic that exists and is [TopicStatus.PUBLISHED].
 * - [date] is not before that topic's own seed text was written. The seed key is
 *   [ContentKey.seed], with [graphConfig]'s prompt version and [modelFamily]. [explanations]
 *   reads the stored seed by that key. A topic with no stored seed skips only this one check.
 *
 * If any check fails for any slug, this function writes nothing. It returns
 * [ReviewDateOutcome.Rejected], with one message per problem.
 */
suspend fun setReviewedAt(
    topics: TopicRepository,
    explanations: ExplanationRepository,
    slugs: List<String>,
    date: String,
    modelFamily: String,
    graphConfig: GraphConfig = GraphConfig(),
    today: LocalDate = LocalDate.now(ZoneOffset.UTC),
): ReviewDateOutcome {
    if (slugs.isEmpty()) {
        return ReviewDateOutcome.Rejected(listOf("no slug given: pass --slugs <a,b,c> or --slugs-file <path>"))
    }

    val parsedDate = parseReviewDate(date)
        ?: return ReviewDateOutcome.Rejected(listOf("date '$date' is not a valid YYYY-MM-DD date"))

    if (parsedDate.isAfter(today)) {
        return ReviewDateOutcome.Rejected(listOf("date '$date' is in the future"))
    }

    val problems = mutableListOf<String>()
    val toWrite = mutableListOf<Topic>()
    for (slug in slugs) {
        val topic = topics.findBySlug(slug)
        when {
            topic == null -> problems += "slug '$slug': unknown topic"
            topic.status != TopicStatus.PUBLISHED -> problems += "slug '$slug': not published"
            else -> {
                val seedKey = ContentKey.seed(slug, graphConfig.promptVersion, modelFamily)
                val seedDate = explanations.findByKey(seedKey)
                    ?.let { Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(ZoneOffset.UTC).toLocalDate() }
                if (seedDate != null && parsedDate.isBefore(seedDate)) {
                    problems += "slug '$slug': date '$date' is before the seed's creation date '$seedDate'"
                } else {
                    toWrite += topic
                }
            }
        }
    }

    if (problems.isNotEmpty()) return ReviewDateOutcome.Rejected(problems)

    val epochMillis = parsedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val changes = toWrite.map { topic ->
        ReviewDateChange(slug = topic.slug, oldDate = topic.reviewedAt?.let(::isoDate), newDate = parsedDate.toString())
    }
    toWrite.forEach { topics.setReviewedAt(it.slug, epochMillis) }
    return ReviewDateOutcome.Applied(changes)
}

/**
 * Clears the review date for exactly [slugs], all or nothing. This is the way back for
 * [setReviewedAt].
 *
 * Every slug must name a topic that exists. If any slug is unknown, this function writes nothing.
 * It returns [ReviewDateOutcome.Rejected]. A topic that already carries no review date is a safe
 * target too: clearing it again is a no-op write, and not a problem.
 */
suspend fun clearReviewedAt(topics: TopicRepository, slugs: List<String>): ReviewDateOutcome {
    if (slugs.isEmpty()) {
        return ReviewDateOutcome.Rejected(listOf("no slug given: pass --slugs <a,b,c> or --slugs-file <path>"))
    }

    val problems = mutableListOf<String>()
    val toClear = mutableListOf<Topic>()
    for (slug in slugs) {
        val topic = topics.findBySlug(slug)
        if (topic == null) problems += "slug '$slug': unknown topic" else toClear += topic
    }

    if (problems.isNotEmpty()) return ReviewDateOutcome.Rejected(problems)

    val changes = toClear.map { topic ->
        ReviewDateChange(slug = topic.slug, oldDate = topic.reviewedAt?.let(::isoDate), newDate = null)
    }
    toClear.forEach { topics.clearReviewedAt(it.slug) }
    return ReviewDateOutcome.Applied(changes)
}

/** Renders the result of a write: a count, then each slug with its old and its new date. */
internal fun renderApplied(verb: String, changes: List<ReviewDateChange>): String = buildString {
    appendLine("$verb the review date for ${changes.size} topic(s).")
    changes.forEachIndexed { index, change ->
        if (index > 0) appendLine()
        append("- ${change.slug}: ${change.oldDate ?: "none"} -> ${change.newDate ?: "none"}")
    }
}

// ---------------------------------------------------------------------- small date helpers

private fun parseReviewDate(date: String): LocalDate? = try {
    LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
} catch (e: DateTimeParseException) {
    null
}

/**
 * Formats [epochMillis] as a plain UTC calendar date, `yyyy-MM-dd`. `TopicPageHtml.isoDate` and
 * `SitemapRoutes.lastModifiedFor`, in `:backend:api`, use the same rule. A date this command
 * prints then reads the same as the date a visitor sees on the topic page.
 *
 * This is a private copy, and not a shared import. That function is `internal` to `:backend:api`,
 * a module this one does not depend on. One line of arithmetic does not need a new dependency.
 */
internal fun isoDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
