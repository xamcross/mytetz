package com.mytetz.graph.scripts

import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.MAX_PUBLISHED_EXPLANATIONS
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import kotlinx.coroutines.runBlocking

/** The one command-line flag this script reads. Every other run is a dry run: see [main]'s own KDoc. */
private const val PUBLISH_FLAG = "--publish"

/**
 * The review path from spec section 17, question 1
 * (`docs/superpowers/specs/2026-09-19-public-surface-design.md`): the owner's recommended way to
 * set [Explanation.published] on the explanations with the highest demand, capped at
 * [MAX_PUBLISHED_EXPLANATIONS] pages in total (spec section 7.4).
 *
 * ## Before you run this: read the text
 *
 * Read every explanation this script would publish before you run it live. The judgement on
 * accuracy and tone belongs to a person, and a published page carries the name of the site. Leave
 * a wrong or unclear answer unpublished — this script never un-publishes anything, so an explanation
 * this run skips stays exactly as it was.
 *
 * ## How the owner runs this
 *
 * **Dry run — the default. Writes nothing to the database:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:run
 * ```
 * This prints the candidates it would publish, and their spans and request counts, then stops.
 *
 * **Live run — sets `published = true` on each candidate it lists:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:run --args="--publish"
 * ```
 *
 * ## What this script never does
 *
 * It never reads a `.env` file: it reads the `MONGODB_URI` environment variable only, the same way
 * production does ([MongoConfig.fromEnv]). It never prints that variable's value, or any other part
 * of a connection string — [publishTopExplanations] logs a key, a topic slug, a span and a request
 * count, and nothing else. This function is the one this issue's own tests exercise, against a real
 * Testcontainers Mongo, never against a live database — see `PublishTopExplanationsTest.kt`.
 */
fun main(args: Array<String>) {
    val dryRun = PUBLISH_FLAG !in args
    runBlocking {
        val mongo = Mongo(MongoConfig.fromEnv())
        val explanations = ExplanationRepository(mongo.database)
        val selected = publishTopExplanations(explanations, dryRun = dryRun)
        if (selected.isEmpty()) {
            println("Nothing to publish: the cap is already reached, or no unpublished EXPLAIN node exists.")
        }
    }
}

/**
 * Selects the top `EXPLAIN` nodes by [Explanation.requestCount] that still fit under [cap], and —
 * unless [dryRun] — calls [ExplanationRepository.setPublished] on each one.
 *
 * An already-published node is never selected again: [ExplanationRepository.findTopByRequestCount]
 * can return one, and this function filters it out before counting it against the room [cap]
 * leaves, so a repeated run only ever grows the published set, never re-processes it.
 *
 * Returns the list it selected, so [main]'s dry-run printer and a test share one source of truth,
 * and never disagree about what a run did.
 */
suspend fun publishTopExplanations(
    explanations: ExplanationRepository,
    dryRun: Boolean,
    cap: Int = MAX_PUBLISHED_EXPLANATIONS,
): List<Explanation> {
    val alreadyPublished = explanations.findPublished().size
    val room = (cap - alreadyPublished).coerceAtLeast(0)
    if (room == 0) {
        println("The cap of $cap published pages is already reached ($alreadyPublished published). Nothing to do.")
        return emptyList()
    }

    // Over-fetch, then filter and trim to `room`: findTopByRequestCount has no "unpublished only"
    // filter of its own (an already-published node can still be the top node by demand), so this
    // function must not count such a node against the room the cap leaves.
    val candidates = explanations.findTopByRequestCount(limit = room + alreadyPublished)
        .filterNot { it.published }
        .take(room)

    candidates.forEach { candidate ->
        val verb = if (dryRun) "[dry run] would publish" else "publishing"
        println(
            "$verb key=${candidate.key} topicSlug=${candidate.topicSlug} span=${candidate.span} " +
                "requestCount=${candidate.requestCount}",
        )
        if (!dryRun) {
            explanations.setPublished(candidate.key, true)
        }
    }

    val tense = if (dryRun) "would be" else "were"
    println("${candidates.size} node(s) $tense published. Cap: $cap. Already published before this run: $alreadyPublished.")
    return candidates
}
