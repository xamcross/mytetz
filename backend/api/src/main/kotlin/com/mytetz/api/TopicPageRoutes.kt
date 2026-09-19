package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ContentKey
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.GraphConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** How many popular questions a topic page shows at most, per spec section 6.3, which names no
 * exact count. A short list keeps the page focused; a topic with more published explanations than
 * this still lists every one of them on `/glossary`. */
private const val POPULAR_QUESTIONS_LIMIT = 10

/**
 * `GET /topics/{slug}`. Renders a full HTML page for a published topic, with its stored seed, and
 * never triggers a generation.
 *
 * A pure read, deliberately: this function calls [CatalogService.findBySlug],
 * [CatalogService.listPublished] and [ExplanationRepository.findByKey] only. It never calls
 * `Principals.resolve` or `Principals.setSessionCookie`, so the response carries no `Set-Cookie`
 * header — a requirement from spec section 13.2, because a Cloudflare Cache Rule (Task 1.8, an
 * owner step) is meant to put this response in a shared edge cache, and a cached response must
 * never carry a cookie meant for one visitor. [modelFamily] is a plain `String`, not a lazy
 * `LlmClient`: see `Components.modelFamily`'s own KDoc for why a public page must never read
 * `llm.modelFamily`.
 *
 * An unknown or an unpublished slug answers with [respondSpaShell], the same shell issue #36's
 * `spaRoutes()` answers an unmatched path with — one 404 page for the whole site, and not a
 * second, blank one that only a bad `/topics/` link ever shows.
 */
fun Route.topicPageRoutes(
    catalog: CatalogService,
    explanations: ExplanationRepository,
    modelFamily: String,
    graphConfig: GraphConfig = GraphConfig(),
) {
    get("/topics/{slug}") {
        val slug = call.parameters["slug"].orEmpty()

        // Same 404 for "no such topic" and "not published" as CatalogRoutes.kt:96-111 — an
        // unpublished topic must not be distinguishable from an absent one.
        val topic = catalog.findBySlug(slug)?.takeIf { it.status == TopicStatus.PUBLISHED }
        if (topic == null) {
            respondSpaShell(call, HttpStatusCode.NotFound)
            return@get
        }

        // A pure read. A topic whose seed is not yet in the store renders the summary and no seed
        // paragraph (TopicPageHtml.kt's own fallback) — this route never generates one.
        val seedKey = ContentKey.seed(topic.slug, graphConfig.promptVersion, modelFamily)
        val seed = explanations.findByKey(seedKey)

        val related = catalog.listPublished(category = topic.category, query = null)
            .filter { it.slug != topic.slug }
            .map { RelatedTopicView(it.slug, it.title) }

        // Spec section 6.3: the "Popular questions" list. A pure read, like the seed lookup above;
        // empty until this topic has a published explanation, in which case TopicPageHtml.kt
        // renders no such list at all — see PopularQuestionView's own KDoc.
        val popularQuestions = explanations.findPublishedByTopic(topic.slug, limit = POPULAR_QUESTIONS_LIMIT)
            .map { PopularQuestionView(span = it.span ?: it.key, shortKey = it.key.take(12)) }

        call.respondHtml(HttpStatusCode.OK) {
            topicPageHtml(
                TopicPageView(
                    slug = topic.slug,
                    title = topic.title,
                    category = topic.category,
                    summary = topic.summary,
                    seedBody = seed?.body,
                    relatedTopics = related,
                    popularQuestions = popularQuestions,
                )
            )
        }
    }
}
