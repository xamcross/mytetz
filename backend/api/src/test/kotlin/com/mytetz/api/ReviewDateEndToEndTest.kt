package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.graph.ContentKey
import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.GraphConfig
import com.mytetz.graph.Verb
import com.mytetz.graph.scripts.ReviewDateOutcome
import com.mytetz.graph.scripts.clearReviewedAt
import com.mytetz.graph.scripts.setReviewedAt
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val MODEL_FAMILY = "fake-model"

/**
 * The full path for issue #47's owner command.
 *
 * A repository test alone shows that a write reaches the stored document. It does not show that a
 * visitor ever sees the date. This file proves the whole chain.
 * [com.mytetz.graph.scripts.setReviewedAt] writes through [com.mytetz.catalog.TopicRepository].
 * The topic page and the sitemap route then read the same stored value back out.
 */
class ReviewDateEndToEndTest {

    private class Fixture {
        private val database =
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_review_date_e2e_${System.nanoTime()}")).database
        val topics = TopicRepository(database)
        val explanations = ExplanationRepository(database)
        val catalog = CatalogService(topics)

        suspend fun publishTopicWithSeed(slug: String, createdAtEpochMillis: Long = 0) {
            topics.upsert(Topic(slug = slug, title = "Title for $slug", category = "Physics", summary = "s"))
            val key = ContentKey.seed(slug, GraphConfig().promptVersion, MODEL_FAMILY)
            explanations.insertIfAbsent(
                Explanation(
                    key = key, topicSlug = slug, parentKey = null, span = null, spanSentence = null,
                    verb = Verb.SEED, variant = 0, depth = 0, body = "The seed body for $slug.",
                    grounded = false, sources = emptyList(), promptVersion = GraphConfig().promptVersion,
                    modelFamily = MODEL_FAMILY, modelId = "fake-model", inputTokens = 1, outputTokens = 1,
                    costMicros = 0, requestCount = 0, createdAtEpochMillis = createdAtEpochMillis,
                )
            )
        }
    }

    @Test
    fun `a date the command sets shows as Last reviewed, as dateModified, and as the sitemap lastmod`() =
        testApplication {
            val fx = Fixture()
            runBlocking {
                fx.publishTopicWithSeed("reviewed-e2e")

                val outcome = setReviewedAt(fx.topics, fx.explanations, listOf("reviewed-e2e"), "2026-03-01", MODEL_FAMILY)
                assertIs<ReviewDateOutcome.Applied>(outcome)
            }

            application {
                routing {
                    topicPageRoutes(fx.catalog, fx.explanations, MODEL_FAMILY)
                    sitemapRoutes(fx.catalog, fx.explanations, MODEL_FAMILY)
                }
            }

            val page = client.get("/topics/reviewed-e2e").bodyAsText()
            assertTrue("Last reviewed 2026-03-01" in page, page)
            assertTrue("\"dateModified\":\"2026-03-01\"" in page, page)

            val sitemap = client.get("/sitemap.xml").bodyAsText()
            assertTrue(
                "<url><loc>https://mytetz.com/topics/reviewed-e2e</loc><lastmod>2026-03-01</lastmod></url>" in sitemap,
                sitemap,
            )
        }

    @Test
    fun `a date the command clears removes the line, the JSON-LD field, and the sitemap lastmod`() =
        testApplication {
            val fx = Fixture()
            runBlocking {
                fx.publishTopicWithSeed("cleared-e2e")
                setReviewedAt(fx.topics, fx.explanations, listOf("cleared-e2e"), "2026-03-01", MODEL_FAMILY)

                val outcome = clearReviewedAt(fx.topics, listOf("cleared-e2e"))
                assertIs<ReviewDateOutcome.Applied>(outcome)
            }

            application {
                routing {
                    topicPageRoutes(fx.catalog, fx.explanations, MODEL_FAMILY)
                    sitemapRoutes(fx.catalog, fx.explanations, MODEL_FAMILY)
                }
            }

            val page = client.get("/topics/cleared-e2e").bodyAsText()
            assertFalse("Last reviewed" in page, page)
            assertFalse("\"dateModified\"" in page, page)

            // The seed itself still carries a creation date, epoch 0, "1970-01-01". The sitemap
            // keeps a lastmod for this URL. Only the review date is gone, not every date.
            val sitemap = client.get("/sitemap.xml").bodyAsText()
            assertTrue(
                "<url><loc>https://mytetz.com/topics/cleared-e2e</loc><lastmod>1970-01-01</lastmod></url>" in sitemap,
                sitemap,
            )
            assertFalse("2026-03-01" in sitemap, sitemap)
        }
}
