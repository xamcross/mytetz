package com.mytetz.api

import com.mytetz.graph.ExplanationRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /glossary`. A pure read: [ExplanationRepository.findPublished] only, no cookie and no
 * model call — the same rule `TopicPageRoutes.kt` and `ExplanationPageRoutes.kt` state and prove.
 *
 * Lists every published `EXPLAIN` node by its span (spec section 7.4). An empty database makes an
 * empty list, and [glossaryHtml] renders that as a real, empty page and not an error — issue #48's
 * own acceptance criterion.
 */
fun Route.glossaryRoutes(explanations: ExplanationRepository) {
    get("/glossary") {
        val entries = explanations.findPublished().map { explanation ->
            GlossaryEntryView(
                span = explanation.span ?: explanation.key,
                topicSlug = explanation.topicSlug,
                shortKey = explanation.key.take(12),
            )
        }

        call.respondHtml(HttpStatusCode.OK) { glossaryHtml(entries) }
    }
}
