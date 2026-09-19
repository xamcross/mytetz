package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.Verb
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.mytetz.api.ExplanationPageRoutes")

/**
 * 12 lowercase hexadecimal characters, per spec section 7.1
 * (`docs/superpowers/specs/2026-09-19-public-surface-design.md`). Checked before any database
 * read, so a short key of the wrong shape never reaches a query.
 */
private val SHORT_KEY_FORMAT = Regex("^[0-9a-f]{12}$")

/**
 * `GET /topics/{slug}/explain/{shortKey}`. A pure read, on the same "no cookie, no generation"
 * rule as `TopicPageRoutes.kt`: this function calls [CatalogService.findBySlug] and
 * [ExplanationRepository.findByShortKeyPrefix] only.
 *
 * ## What answers `404`, through [respondSpaShell]
 *
 * The topic's `PUBLISHED` status gates with a `404` — the same shell `TopicPageRoutes.kt` answers
 * an unpublished topic with — so an unpublish removes every explanation page under that topic at
 * once (spec section 5.4). Beyond that, every one of these also answers `404`, indistinguishably
 * from one another and from an unknown page, because each one names no real public page:
 *
 * - a short key of the wrong shape (checked by [SHORT_KEY_FORMAT], before any query is made);
 * - an unknown short key (zero matches);
 * - a short key that names a document under a *different* topic than the one in the URL;
 * - a short key that names more than one document — a collision, logged at `WARN` under
 *   `EXPLANATION_KEY_COLLISION` with both keys, per spec section 7.2;
 * - a short key that names a node whose [Verb] is not `EXPLAIN` — a `VISUALIZE` node, for example,
 *   carries an SVG in [com.mytetz.graph.Explanation.media], and this issue does not render media
 *   on a public page, so such a node is never a page this route may serve, whatever its own
 *   `published` flag says.
 *
 * ## What does not answer `404`: an unpublished `EXPLAIN` node under a published topic
 *
 * The explanation's own `published` flag gates only `X-Robots-Tag: noindex` and the canonical
 * tag (spec section 7.3), never `404`. The interactive reader can link a learner to any node it
 * reaches, published or not, and a stable URL must not break the moment a curator has not yet
 * reviewed the text.
 *
 * **Deviation, recorded here.** A stricter draft of this issue's own task brief asked for one
 * `404` answer across every case above, including this one, so that a visitor could never tell an
 * unpublished explanation from an absent one. The approved spec (section 7.3) decides the
 * opposite, for three stated reasons — precedent (`/learn/`, `/account` and `/auth` already carry
 * `noindex` rather than `404`), a stable link the interactive reader depends on, and no redirect on
 * publication — and issue #48's own body already quotes this exact rule. This route follows the
 * spec, and this issue's own final report records the point for whoever resolves the conflict
 * between the two documents.
 */
fun Route.explanationPageRoutes(catalog: CatalogService, explanations: ExplanationRepository) {
    get("/topics/{slug}/explain/{shortKey}") {
        val slug = call.parameters["slug"].orEmpty()
        val shortKey = call.parameters["shortKey"].orEmpty()

        val topic = catalog.findBySlug(slug)?.takeIf { it.status == TopicStatus.PUBLISHED }
        if (topic == null) {
            respondSpaShell(call, HttpStatusCode.NotFound)
            return@get
        }

        if (!SHORT_KEY_FORMAT.matches(shortKey)) {
            respondSpaShell(call, HttpStatusCode.NotFound)
            return@get
        }

        val matches = explanations.findByShortKeyPrefix(shortKey)
        val explanation = when {
            matches.isEmpty() -> null
            matches.size == 1 -> matches.single()
            else -> {
                log.warn("EXPLANATION_KEY_COLLISION shortKey={} keys={}", shortKey, matches.map { it.key })
                null
            }
        }
        if (explanation == null || explanation.topicSlug != topic.slug || explanation.verb != Verb.EXPLAIN) {
            respondSpaShell(call, HttpStatusCode.NotFound)
            return@get
        }

        if (!explanation.published) {
            call.response.header(X_ROBOTS_TAG, NOINDEX)
        }

        call.respondHtml(HttpStatusCode.OK) {
            explanationPageHtml(
                ExplanationPageView(
                    topicSlug = topic.slug,
                    topicTitle = topic.title,
                    span = explanation.span ?: topic.title,
                    body = explanation.body,
                    shortKey = shortKey,
                    published = explanation.published,
                ),
            )
        }
    }
}
