package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicStatus
import com.mytetz.graph.ContentKey
import com.mytetz.graph.Explanation
import com.mytetz.graph.ExplanationRepository
import com.mytetz.graph.GraphConfig
import com.mytetz.graph.Verb
import com.mytetz.llm.FakeLlmClient
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.StringReader
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.xml.sax.InputSource

private const val FAKE_MODEL_FAMILY = "fake-model"

/**
 * `GET /sitemap.xml`, the route [sitemapRoutes] answers.
 *
 * Every test builds its own [SitemapFixture] over a fresh database, the same pattern
 * [TopicPageRoutesTest] uses, so one test's topic can never leak into another test's count.
 */
class SitemapRoutesTest {

    /** [catalog] and [explanations] share one database, the same way [Components] wires them. */
    private class SitemapFixture(vararg topics: Topic) {
        private val database =
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_sitemap_${System.nanoTime()}")).database
        val catalog = CatalogService(TopicRepository(database).also { repository ->
            runBlocking { topics.forEach { repository.upsert(it) } }
        })
        val explanations = ExplanationRepository(database)

        /** Stores a `SEED` explanation for [slug], keyed the same way [sitemapRoutes] computes it. */
        suspend fun seed(slug: String, createdAtEpochMillis: Long, modelFamily: String = FAKE_MODEL_FAMILY) {
            val key = ContentKey.seed(slug, GraphConfig().promptVersion, modelFamily)
            explanations.insertIfAbsent(
                Explanation(
                    key = key, topicSlug = slug, parentKey = null, span = null, spanSentence = null,
                    verb = Verb.SEED, variant = 0, depth = 0, body = "seed body",
                    grounded = false, sources = emptyList(), promptVersion = GraphConfig().promptVersion,
                    modelFamily = modelFamily, modelId = "fake-model", inputTokens = 1, outputTokens = 1,
                    costMicros = 0, requestCount = 0, createdAtEpochMillis = createdAtEpochMillis,
                )
            )
        }
    }

    @Test
    fun `the sitemap lists the home page, every published topic, and every guide path`() = testApplication {
        val fx = SitemapFixture(
            Topic(slug = "topic-a", title = "Topic A", category = "Physics", summary = "s"),
            Topic(slug = "topic-b", title = "Topic B", category = "Biology", summary = "s"),
        )
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val response = client.get("/sitemap.xml")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/xml", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))
        assertEquals("public, max-age=3600", response.headers[HttpHeaders.CacheControl])
        val body = response.bodyAsText()
        assertTrue(body.trimStart().startsWith("<?xml"))
        assertTrue("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""" in body)
        assertTrue("<loc>https://mytetz.com/</loc>" in body)
        assertTrue("<loc>https://mytetz.com/topics/topic-a</loc>" in body)
        assertTrue("<loc>https://mytetz.com/topics/topic-b</loc>" in body)
        GuidePages.paths.forEach { path ->
            assertTrue("<loc>https://mytetz.com$path</loc>" in body, "$path missing from the sitemap")
        }
    }

    @Test
    fun `an unpublished topic is not listed`() = testApplication {
        val fx = SitemapFixture(
            Topic(slug = "draft-topic", title = "Draft", category = "Physics", summary = "s", status = TopicStatus.DRAFT),
        )
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        assertFalse("draft-topic" in body, "an unpublished topic must not appear in the sitemap")
    }

    @Test
    fun `the sitemap never lists a gated or an internal path`() = testApplication {
        val fx = SitemapFixture(Topic(slug = "topic-a", title = "Topic A", category = "Physics", summary = "s"))
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        for (gatedPath in listOf("/learn/", "/account", "/auth", "/api/")) {
            assertFalse(gatedPath in body, "the sitemap must never list $gatedPath")
        }
    }

    // ------------------------------------------------------------- lastmod

    @Test
    fun `a published topic with a stored seed gets lastmod from the seed's createdAtEpochMillis, right after its loc`() =
        testApplication {
            // 00:15 UTC, one quarter-hour past midnight. A date built in a zone west of UTC turns
            // this into the day before; this test fails under that defect.
            val createdAt = Instant.parse("2026-01-02T00:15:00Z").toEpochMilli()
            val fx = SitemapFixture(Topic(slug = "topic-a", title = "Topic A", category = "Physics", summary = "s"))
            runBlocking { fx.seed("topic-a", createdAt) }
            application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

            val body = client.get("/sitemap.xml").bodyAsText()

            // A literal substring proves both the date and the element order — loc, then lastmod,
            // the order https://www.sitemaps.org/protocol.html shows in its own example.
            assertTrue(
                "<url><loc>https://mytetz.com/topics/topic-a</loc><lastmod>2026-01-02</lastmod></url>" in body,
                "expected a 2026-01-02 lastmod right after the topic's loc, body was: $body",
            )

            // The real proof of well-formedness: a namespace-aware parser must accept the document.
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
            val lastmodNodes = document.getElementsByTagNameNS(
                "http://www.sitemaps.org/schemas/sitemap/0.9", "lastmod",
            )
            assertEquals(1, lastmodNodes.length, "exactly one lastmod element is expected")
            assertEquals("2026-01-02", lastmodNodes.item(0).textContent)
        }

    @Test
    fun `a published topic with no stored seed gets no lastmod, and the route starts no generation`() =
        testApplication {
            val fx = SitemapFixture(Topic(slug = "no-seed-yet", title = "No Seed Yet", category = "Physics", summary = "s"))
            application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

            val body = client.get("/sitemap.xml").bodyAsText()

            // No FakeLlmClient or AnthropicLlmClient is reachable from this route at all — see the
            // "no model client" test below — so an absent seed can only ever read as absent, never
            // trigger one. This asserts the one visible effect: no lastmod for a topic with no seed.
            assertTrue(
                "<url><loc>https://mytetz.com/topics/no-seed-yet</loc></url>" in body,
                "a topic with no stored seed must carry no lastmod: $body",
            )
        }

    @Test
    fun `a review date later than the seed date wins as lastmod`() = testApplication {
        // Both times sit near midnight UTC, the same guard the other lastmod tests use.
        val seedCreatedAt = Instant.parse("2026-01-02T00:15:00Z").toEpochMilli()
        val reviewedAt = Instant.parse("2026-03-04T00:15:00Z").toEpochMilli()
        val fx = SitemapFixture(
            Topic(
                slug = "topic-a", title = "Topic A", category = "Physics", summary = "s",
                reviewedAt = reviewedAt,
            ),
        )
        runBlocking { fx.seed("topic-a", seedCreatedAt) }
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        assertTrue(
            "<url><loc>https://mytetz.com/topics/topic-a</loc><lastmod>2026-03-04</lastmod></url>" in body,
            "expected the later review date as lastmod, body was: $body",
        )
    }

    @Test
    fun `a review date earlier than the seed date keeps the seed date as lastmod`() = testApplication {
        val seedCreatedAt = Instant.parse("2026-03-04T00:15:00Z").toEpochMilli()
        val reviewedAt = Instant.parse("2026-01-02T00:15:00Z").toEpochMilli()
        val fx = SitemapFixture(
            Topic(
                slug = "topic-a", title = "Topic A", category = "Physics", summary = "s",
                reviewedAt = reviewedAt,
            ),
        )
        runBlocking { fx.seed("topic-a", seedCreatedAt) }
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        assertTrue(
            "<url><loc>https://mytetz.com/topics/topic-a</loc><lastmod>2026-03-04</lastmod></url>" in body,
            "expected the later seed date to survive an earlier review date, body was: $body",
        )
    }

    @Test
    fun `a review date with no stored seed gets the review date as lastmod`() = testApplication {
        val reviewedAt = Instant.parse("2026-05-06T00:15:00Z").toEpochMilli()
        val fx = SitemapFixture(
            Topic(
                slug = "reviewed-no-seed", title = "Reviewed No Seed", category = "Physics", summary = "s",
                reviewedAt = reviewedAt,
            ),
        )
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        assertTrue(
            "<url><loc>https://mytetz.com/topics/reviewed-no-seed</loc><lastmod>2026-05-06</lastmod></url>" in body,
            "expected the review date as lastmod with no stored seed, body was: $body",
        )
    }

    @Test
    fun `the home page and every guide path get no lastmod`() = testApplication {
        val fx = SitemapFixture()
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        // Neither the home page nor a guide page has a seed explanation or a Topic row: no true
        // last-modified date exists for either one, so this route must state no lastmod for them.
        assertTrue("<url><loc>https://mytetz.com/</loc></url>" in body, "the home page must carry no lastmod: $body")
        GuidePages.paths.forEach { path ->
            assertTrue(
                "<url><loc>https://mytetz.com$path</loc></url>" in body,
                "$path must carry no lastmod: $body",
            )
        }
    }

    @Test
    fun `how-it-works is in the sitemap once, with no lastmod`() = testApplication {
        val fx = SitemapFixture()
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        val occurrences = Regex("<loc>https://mytetz\\.com/how-it-works</loc>").findAll(body).count()
        assertEquals(1, occurrences, "expected /how-it-works exactly once, body was: $body")
        assertTrue(
            "<url><loc>https://mytetz.com/how-it-works</loc></url>" in body,
            "/how-it-works must carry no lastmod: $body",
        )
    }

    @Test
    fun `faq is in the sitemap once, with no lastmod`() = testApplication {
        val fx = SitemapFixture()
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        val occurrences = Regex("<loc>https://mytetz\\.com/faq</loc>").findAll(body).count()
        assertEquals(1, occurrences, "expected /faq exactly once, body was: $body")
        assertTrue(
            "<url><loc>https://mytetz.com/faq</loc></url>" in body,
            "/faq must carry no lastmod: $body",
        )
    }

    // ------------------------------------------------------------- hostile input

    @Test
    fun `a hostile slug renders as well-formed XML, with every reserved character escaped`() = testApplication {
        val hostileSlug = """topic & <tag> "quoted" space"""
        val fx = SitemapFixture(Topic(slug = hostileSlug, title = "Hostile", category = "Physics", summary = "s"))
        application { routing { sitemapRoutes(fx.catalog, fx.explanations, FAKE_MODEL_FAMILY) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        // The raw text must hold no un-escaped reserved character from the hostile slug.
        assertFalse("<tag>" in body, "the < and > of the slug were not escaped: $body")
        assertFalse("\"quoted\"" in body, "the \" of the slug was not escaped: $body")
        assertTrue("&amp;" in body, "the & of the slug was not escaped: $body")
        assertTrue("&lt;tag&gt;" in body)
        assertTrue("&quot;quoted&quot;" in body)

        // The real proof: a builder that got the escaping wrong produces a document a
        // namespace-aware parser refuses to read at all, and this parse call would throw.
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
        val locNodes = document.getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "loc")
        val locValues = (0 until locNodes.length).map { locNodes.item(it).textContent }

        assertTrue(
            "https://mytetz.com/topics/$hostileSlug" in locValues,
            "the parsed <loc> text must round-trip to the exact hostile slug",
        )
    }

    // ------------------------------------------------------------- purity: no cookie, no model

    private fun components(modelFamily: String = FAKE_MODEL_FAMILY) = Components(
        mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_sitemap_${System.nanoTime()}")),
        cookies = TestFixtures.cookieConfig,
        llmFactory = { FakeLlmClient(modelFamily = modelFamily) },
        modelFamily = modelFamily,
    )

    @Test
    fun `the response carries no Set-Cookie header`() = testApplication {
        val c = components()
        application { routing { sitemapRoutes(c.catalog, c.explanations, c.modelFamily) } }

        assertNull(client.get("/sitemap.xml").headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `the route builds no model client`() = testApplication {
        var built = 0
        val fake = FakeLlmClient(modelFamily = FAKE_MODEL_FAMILY)
        val c = Components(
            mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_sitemap_no_model")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { built++; fake },
            modelFamily = FAKE_MODEL_FAMILY,
        )
        application { routing { sitemapRoutes(c.catalog, c.explanations, c.modelFamily) } }

        client.get("/sitemap.xml")

        assertEquals(0, built, "the route forced the lazy model client to build")
    }
}
