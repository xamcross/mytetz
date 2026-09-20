package com.mytetz.graph.scripts

import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.ExplanationValidator
import com.mytetz.graph.Verb
import com.mytetz.persistence.Mongo
import org.bson.Document
import java.io.File
import kotlin.system.exitProcess

/**
 * Issue #162's owner command: replace the text of one seed explanation, or take that replace
 * back. **The owner runs this command. An agent never runs it.** It writes to the production
 * database.
 *
 * ## The safety rule this file follows
 *
 * The investigation this command is built on lives in
 * `docs/content/seed-corrections-2026-09-20/README.md`, with a file and a line for every
 * statement it makes. Its conclusion: no existing session can be left in a state a learner
 * cannot recover from, and neither the pre-warm step nor an ordinary migration writes an old
 * text back over a correction. Read that file before you change this one.
 *
 * ## How the owner runs this
 *
 * **The dry run. Writes nothing. This is the default:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:correctSeedText --args="--slug special-relativity --file corrected.txt"
 * ```
 * Prints the present text, the new text from `corrected.txt`, a line diff at the sentence level,
 * how many existing child explanations sit under a sentence the new text drops, and how many
 * sessions still hold a node on one of those children.
 *
 * **The write. All or nothing, and it changes the text only:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:correctSeedText --args="--slug special-relativity --file corrected.txt --write"
 * ```
 * Prints the same report first, then writes — one MongoDB `updateOne`, which is atomic on one
 * document — keeping the key, the topic, the verb and every other field untouched, and recording
 * the text it replaced so the write can be taken back.
 *
 * **Taking a text back:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:correctSeedText --args="--slug special-relativity --revert"
 * ```
 *
 * `--key <full-content-key>` may name the document instead of `--slug <topic-slug>` — the same
 * "always the full key" idea `PublishTopExplanations.kt` uses, and the only way to name a
 * document that is not a seed, which [ExplanationRepository.findSeedsByTopicSlug] filters out of
 * a slug lookup before this command ever sees it.
 *
 * ## What this refuses, all writing nothing and naming the reason
 *
 * An unknown slug or key, a key that is not [Verb.SEED], an empty new text, a new text carrying
 * `<` or `>`, a new text over [ExplanationValidator.DEFAULT_MAX_CHARS], and a new text identical
 * to the present one. See [validateNewText] and [resolveSeed].
 *
 * ## What this cannot check
 *
 * It cannot tell whether a corrected statement is itself accurate — that judgement is the
 * owner's, made before the text ever reaches a file this command reads. It cannot detect a
 * network partition that a MongoDB session considers a lost write after `updateOne` returns; the
 * write is a single, atomic `updateOne` and this command reports exactly what the driver told it.
 *
 * ## What this never does
 *
 * It never reads a `.env` file: it reads `MONGODB_URI` from the environment only, the same way
 * `MongoConfig.fromEnv` and `PublishTopExplanations.kt` both do. It never prints that variable's
 * value or any other part of a connection string. A failed database operation is reported by its
 * exception's class name only, and never by its message. See `runOwnerScript`'s own KDoc, in
 * `ScriptSupport.kt`, for the reason. It never prints a learner's own text: the two texts this
 * command prints are the seed's own public wording, before and after, which is what the dry run
 * exists to show the owner.
 *
 * ## How this process ends (issue #175)
 *
 * [main] ends with one call to [exitProcess], as its last statement. That one call is the sole
 * reason this process always ends. It does not depend on which thread of the driver is not a
 * daemon thread, or on why.
 */
fun main(args: Array<String>) {
    val command = parseCorrectionArgs(args.toList())
    val code = if (command is SeedCorrectionCommand.InvalidArgs) {
        System.err.println("Error: ${command.message}")
        1
    } else {
        runOwnerScript { mongo -> execute(mongo, command) }
    }
    exitProcess(code)
}

private suspend fun execute(mongo: Mongo, command: SeedCorrectionCommand): Int {
    val explanations = ExplanationRepository(mongo.database)

    return when (command) {
        is SeedCorrectionCommand.InvalidArgs -> 0 // handled in main before this is reached

        is SeedCorrectionCommand.DryRun -> {
            val newText = File(command.newBodyFile).readText().trim()
            when (val outcome = prepareReport(explanations, mongo.database, command.selector, newText)) {
                is ReportOutcome.Refused -> {
                    System.err.println("Refused. Nothing was written. Reason: ${outcome.reason}")
                    1
                }
                is ReportOutcome.Ready -> {
                    println(renderReport(outcome.report))
                    println("This was a dry run. Nothing was written. Add --write to apply this change.")
                    0
                }
            }
        }

        is SeedCorrectionCommand.Write -> {
            val newText = File(command.newBodyFile).readText().trim()
            when (val preview = prepareReport(explanations, mongo.database, command.selector, newText)) {
                is ReportOutcome.Refused -> {
                    System.err.println("Refused. Nothing was written. Reason: ${preview.reason}")
                    return 1
                }
                is ReportOutcome.Ready -> println(renderReport(preview.report))
            }

            when (val outcome = applyWrite(explanations, command.selector, newText, System.currentTimeMillis())) {
                is WriteOutcome.Refused -> {
                    System.err.println("Nothing was written. Reason: ${outcome.reason}")
                    1
                }
                is WriteOutcome.Applied -> {
                    println(
                        "Wrote ${outcome.key}. Old text was ${outcome.oldChars} character(s); " +
                            "new text is ${outcome.newChars} character(s).",
                    )
                    println("Take this back with: --key ${outcome.key} --revert")
                    0
                }
            }
        }

        is SeedCorrectionCommand.Revert -> {
            when (val outcome = applyRevert(explanations, command.selector)) {
                is RevertOutcome.Refused -> {
                    System.err.println("Nothing was reverted. Reason: ${outcome.reason}")
                    1
                }
                is RevertOutcome.Applied -> {
                    println("Reverted ${outcome.key} to its previous text.")
                    0
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- argument parsing

/** How the owner names the one seed this run acts on. */
internal sealed interface SeedSelector {
    data class BySlug(val slug: String) : SeedSelector
    data class ByKey(val key: String) : SeedSelector
}

/** One parsed command line. See [main]'s own KDoc for the exact commands. */
internal sealed interface SeedCorrectionCommand {
    data class DryRun(val selector: SeedSelector, val newBodyFile: String) : SeedCorrectionCommand
    data class Write(val selector: SeedSelector, val newBodyFile: String) : SeedCorrectionCommand
    data class Revert(val selector: SeedSelector) : SeedCorrectionCommand
    data class InvalidArgs(val message: String) : SeedCorrectionCommand
}

/**
 * Reads [args] into one [SeedCorrectionCommand]. No disk access of its own — `--file` names a
 * path that [main] reads later, only once a command other than [SeedCorrectionCommand.InvalidArgs]
 * is known, so this function stays pure and a test can drive it with a plain list of strings.
 */
internal fun parseCorrectionArgs(args: List<String>): SeedCorrectionCommand {
    val slug = optionValue(args, "--slug")
    val key = optionValue(args, "--key")
    if (slug == null && key == null) {
        return SeedCorrectionCommand.InvalidArgs("give --slug <topic-slug> or --key <full-content-key>")
    }
    if (slug != null && key != null) {
        return SeedCorrectionCommand.InvalidArgs("give --slug or --key, not both")
    }
    val selector = if (slug != null) SeedSelector.BySlug(slug) else SeedSelector.ByKey(key!!)

    val write = "--write" in args
    val revert = "--revert" in args
    if (write && revert) return SeedCorrectionCommand.InvalidArgs("give --write or --revert, not both")

    if (revert) return SeedCorrectionCommand.Revert(selector)

    val file = optionValue(args, "--file")
        ?: return SeedCorrectionCommand.InvalidArgs("give --file <path> with the new text, or --revert")
    return if (write) SeedCorrectionCommand.Write(selector, file) else SeedCorrectionCommand.DryRun(selector, file)
}

/** The value right after [flag] in [args], or null when [flag] is absent or has no value after it. */
private fun optionValue(args: List<String>, flag: String): String? {
    val index = args.indexOf(flag)
    return if (index == -1 || index + 1 >= args.size) null else args[index + 1]
}

// ---------------------------------------------------------------------- resolving the seed

/** What [resolveSeed] found. */
internal sealed interface SeedLookup {
    data class Found(val explanation: Explanation) : SeedLookup
    data class NotFound(val reason: String) : SeedLookup
}

/**
 * Finds the one document [selector] names.
 *
 * [SeedSelector.BySlug] is the ordinary way in: it looks up the topic's own [Verb.SEED]
 * document(s) and refuses an unknown slug (none found) or an ambiguous one (more than one — two
 * model families' seeds briefly coexisting, say) rather than guessing.
 *
 * [SeedSelector.ByKey] names a document directly, of any verb, so a key that is **not** a seed
 * can be refused explicitly and precisely — the one refusal [SeedSelector.BySlug] can never
 * produce on its own, because [ExplanationRepository.findSeedsByTopicSlug] already filters to
 * `SEED`.
 */
internal suspend fun resolveSeed(explanations: ExplanationRepository, selector: SeedSelector): SeedLookup =
    when (selector) {
        is SeedSelector.BySlug -> {
            val seeds = explanations.findSeedsByTopicSlug(selector.slug)
            when {
                seeds.isEmpty() -> SeedLookup.NotFound("unknown slug: no seed found for topic '${selector.slug}'")
                seeds.size > 1 -> SeedLookup.NotFound(
                    "ambiguous: ${seeds.size} seed documents found for topic '${selector.slug}'; use --key instead",
                )
                else -> SeedLookup.Found(seeds.single())
            }
        }
        is SeedSelector.ByKey -> {
            val document = explanations.findByKey(selector.key)
            when {
                document == null -> SeedLookup.NotFound("unknown key: no document ${selector.key}")
                document.verb != Verb.SEED ->
                    SeedLookup.NotFound("key ${selector.key} is not a seed (verb is ${document.verb})")
                else -> SeedLookup.Found(document)
            }
        }
    }

// ---------------------------------------------------------------------- validating a new text

/**
 * Every refusal of step 3, checked in the order the issue lists them, after the seed itself is
 * already known to exist. Returns the refusal reason, or null when [newText] may proceed.
 */
internal fun validateNewText(current: Explanation, newText: String): String? = when {
    newText.isEmpty() -> "the new text is empty"
    newText.contains('<') || newText.contains('>') ->
        "the new text contains markup ('<' or '>'), which a seed body must never carry"
    newText.length > ExplanationValidator.DEFAULT_MAX_CHARS ->
        "the new text is ${newText.length} character(s), over the " +
            "${ExplanationValidator.DEFAULT_MAX_CHARS}-character limit for a seed"
    newText == current.body -> "the new text is unchanged from the present text"
    else -> null
}

// ---------------------------------------------------------------------- the dry-run report

/** One line of [SeedCorrectionReport.diff], at the sentence level. */
internal sealed interface DiffLine {
    data class Unchanged(val text: String) : DiffLine
    data class Removed(val text: String) : DiffLine
    data class Added(val text: String) : DiffLine
}

/** How many existing children hang off one sentence the new text drops. */
internal data class SentenceImpact(val sentence: String, val childCount: Int)

/** Everything the dry run prints, and everything the write prints before it writes. */
internal data class SeedCorrectionReport(
    val slug: String,
    val key: String,
    val oldText: String,
    val newText: String,
    val diff: List<DiffLine>,
    val changedSentenceImpacts: List<SentenceImpact>,
    val affectedSessionCount: Long,
)

/** What [prepareReport] produced: a report to show, or a reason nothing was written. */
internal sealed interface ReportOutcome {
    data class Ready(val report: SeedCorrectionReport) : ReportOutcome
    data class Refused(val reason: String) : ReportOutcome
}

/**
 * Resolves [selector], validates [newText] against what it finds, and — only once both pass —
 * builds the full [SeedCorrectionReport]. This function never writes to either collection it
 * reads: [ReportOutcome.Ready] is a preview, and [SeedCorrectionCommand.DryRun] stops here.
 * [SeedCorrectionCommand.Write] calls this first, for the same preview, then calls [applyWrite]
 * separately — so a write and a dry run always show the identical report for the identical input.
 */
internal suspend fun prepareReport(
    explanations: ExplanationRepository,
    database: MongoDatabase,
    selector: SeedSelector,
    newText: String,
): ReportOutcome {
    val seed = when (val lookup = resolveSeed(explanations, selector)) {
        is SeedLookup.NotFound -> return ReportOutcome.Refused(lookup.reason)
        is SeedLookup.Found -> lookup.explanation
    }
    val refusal = validateNewText(seed, newText)
    if (refusal != null) return ReportOutcome.Refused(refusal)
    return ReportOutcome.Ready(buildReport(explanations, database, seed, newText))
}

/**
 * Builds the report for a [seed] already known to accept [newText].
 *
 * A "changed sentence" is decided from real, stored data and not from re-deriving sentence
 * boundaries: every direct child of [seed] carries its own exact `spanSentence`, captured by
 * `SessionService.sentenceAround` at the moment it was generated
 * (`backend/session/src/main/kotlin/com/mytetz/session/SessionService.kt:806-881`). A child's
 * sentence "changed" precisely when that exact stored string is no longer found anywhere in
 * [newText] — which is also exactly the condition under which a fresh, identical highlight could
 * never reach that child again. [splitIntoSentences] and [diffSentences] below are for the
 * printed line diff only, and never decide which child is affected.
 */
internal suspend fun buildReport(
    explanations: ExplanationRepository,
    database: MongoDatabase,
    seed: Explanation,
    newText: String,
): SeedCorrectionReport {
    val diff = diffSentences(splitIntoSentences(seed.body), splitIntoSentences(newText))

    val affectedByOldSentence = explanations.findByParentKey(seed.key)
        .filter { child -> child.spanSentence != null && child.spanSentence !in newText }
        .groupBy { it.spanSentence!! }

    val impacts = affectedByOldSentence
        .map { (sentence, children) -> SentenceImpact(sentence, children.size) }
        .sortedByDescending { it.childCount }

    val affectedKeys = affectedByOldSentence.values.flatten().map { it.key }
    val affectedSessionCount = countSessionsReferencing(database, affectedKeys)

    return SeedCorrectionReport(
        slug = seed.topicSlug,
        key = seed.key,
        oldText = seed.body,
        newText = newText,
        diff = diff,
        changedSentenceImpacts = impacts,
        affectedSessionCount = affectedSessionCount,
    )
}

/**
 * How many session documents hold at least one node whose `explanationKey` is one of [keys].
 *
 * Reads the raw `sessions` collection with no dependency on `:backend:session` — that module
 * already depends on `:backend:graph`, so the reverse dependency this would need does not exist,
 * and never will just for this one count. `nodes.explanationKey` is the exact field
 * `SessionRepository.referencedExplanationKeys` already reads for the same collection, in
 * `backend/session/src/main/kotlin/com/mytetz/session/SessionRepository.kt:100-124`, so this
 * mirrors an existing, tested query shape rather than inventing a new one. `countDocuments`
 * counts a *document* that matches, not a *node* — a session with three affected nodes still
 * counts once, which is what the report means by "sessions", not "nodes".
 */
internal suspend fun countSessionsReferencing(database: MongoDatabase, keys: Collection<String>): Long {
    if (keys.isEmpty()) return 0
    return database.getCollection<Document>("sessions")
        .countDocuments(Filters.`in`("nodes.explanationKey", keys.toSet()))
}

/**
 * A deliberately simple split, for the printed line diff only. It is never used to decide which
 * child explanation is affected by a change — [buildReport] answers that from each child's own
 * stored, exact `spanSentence` instead, which does not depend on this function agreeing with
 * `SessionService.sentenceAround`'s own, more careful rules (abbreviations, decimals, and so on).
 * A seed is one short paragraph of plain prose with no such traps in practice, so this is enough
 * to show the owner a readable diff.
 */
internal fun splitIntoSentences(text: String): List<String> =
    Regex("(?<=[.!?])\\s+").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * A longest-common-subsequence diff of two sentence lists, the same idea `diff`/`git diff` use
 * for lines. Unchanged sentences carry no prefix; a sentence only in [oldSentences] is
 * [DiffLine.Removed]; a sentence only in [newSentences] is [DiffLine.Added]. Two equal sentences
 * that reappear in a different order are matched by the LCS, not treated as a remove-then-add
 * pair — the same behaviour an ordinary text diff has.
 */
internal fun diffSentences(oldSentences: List<String>, newSentences: List<String>): List<DiffLine> {
    val m = oldSentences.size
    val n = newSentences.size
    val lengths = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            lengths[i][j] = if (oldSentences[i] == newSentences[j]) {
                lengths[i + 1][j + 1] + 1
            } else {
                maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
    }

    val result = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < m && j < n) {
        when {
            oldSentences[i] == newSentences[j] -> {
                result += DiffLine.Unchanged(oldSentences[i])
                i++
                j++
            }
            lengths[i + 1][j] >= lengths[i][j + 1] -> {
                result += DiffLine.Removed(oldSentences[i])
                i++
            }
            else -> {
                result += DiffLine.Added(newSentences[j])
                j++
            }
        }
    }
    while (i < m) {
        result += DiffLine.Removed(oldSentences[i])
        i++
    }
    while (j < n) {
        result += DiffLine.Added(newSentences[j])
        j++
    }
    return result
}

/** Renders [report] as plain text, for the terminal. [main] prints this for both a dry run and a write. */
internal fun renderReport(report: SeedCorrectionReport): String = buildString {
    appendLine("Seed: ${report.slug} (${report.key})")
    appendLine()
    appendLine("Present text:")
    appendLine(report.oldText)
    appendLine()
    appendLine("New text:")
    appendLine(report.newText)
    appendLine()
    appendLine("Diff, by sentence:")
    report.diff.forEach { line ->
        when (line) {
            is DiffLine.Unchanged -> appendLine("  ${line.text}")
            is DiffLine.Removed -> appendLine("- ${line.text}")
            is DiffLine.Added -> appendLine("+ ${line.text}")
        }
    }
    appendLine()
    if (report.changedSentenceImpacts.isEmpty()) {
        appendLine("No existing child explanation sits under a sentence this change drops.")
    } else {
        appendLine("Children under a sentence this change drops:")
        report.changedSentenceImpacts.forEach { impact ->
            appendLine("- ${impact.childCount} child(ren) of: ${impact.sentence}")
        }
    }
    appendLine()
    appendLine("Sessions holding a node on one of those children: ${report.affectedSessionCount}")
}

// ---------------------------------------------------------------------- write / revert

/** What [applyWrite] did. */
internal sealed interface WriteOutcome {
    data class Applied(val key: String, val oldChars: Int, val newChars: Int) : WriteOutcome
    data class Refused(val reason: String) : WriteOutcome
}

/**
 * Writes [newText] as [selector]'s body, all or nothing — a single `updateOne` on one document,
 * which MongoDB commits atomically or not at all. Re-runs the exact checks [prepareReport] runs
 * ([resolveSeed], then [validateNewText]), so a caller of this function alone — a test, or a
 * future caller with no report to show first — gets the same refusals with no report required.
 */
internal suspend fun applyWrite(
    explanations: ExplanationRepository,
    selector: SeedSelector,
    newText: String,
    nowEpochMillis: Long,
): WriteOutcome {
    val seed = when (val lookup = resolveSeed(explanations, selector)) {
        is SeedLookup.NotFound -> return WriteOutcome.Refused(lookup.reason)
        is SeedLookup.Found -> lookup.explanation
    }
    val refusal = validateNewText(seed, newText)
    if (refusal != null) return WriteOutcome.Refused(refusal)

    val applied = explanations.replaceSeedBody(seed.key, newText, nowEpochMillis)
    return if (applied) {
        WriteOutcome.Applied(seed.key, seed.body.length, newText.length)
    } else {
        WriteOutcome.Refused("the write matched no document; it may have changed since it was read")
    }
}

/** What [applyRevert] did. */
internal sealed interface RevertOutcome {
    data class Applied(val key: String) : RevertOutcome
    data class Refused(val reason: String) : RevertOutcome
}

/** Restores [selector]'s previous text — the one-command way back from [applyWrite]. */
internal suspend fun applyRevert(explanations: ExplanationRepository, selector: SeedSelector): RevertOutcome {
    val seed = when (val lookup = resolveSeed(explanations, selector)) {
        is SeedLookup.NotFound -> return RevertOutcome.Refused(lookup.reason)
        is SeedLookup.Found -> lookup.explanation
    }
    if (seed.previousBody == null) {
        return RevertOutcome.Refused("key ${seed.key} carries no previous text to revert to")
    }
    val reverted = explanations.revertSeedBody(seed.key)
    return if (reverted) {
        RevertOutcome.Applied(seed.key)
    } else {
        RevertOutcome.Refused("the revert matched no document; it may have changed since it was read")
    }
}
