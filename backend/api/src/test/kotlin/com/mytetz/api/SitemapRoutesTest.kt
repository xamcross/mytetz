package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRepository
import com.mytetz.catalog.TopicStatus
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
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.xml.sax.InputSource

/**
 * `GET /sitemap.xml`, the route [sitemapRoutes] answers.
 *
 * Every test builds its own [CatalogService] over a fresh database, the same pattern
 * [TopicPageRoutesTest] uses, so one test's topic can never leak into another test's count.
 */
class SitemapRoutesTest {

    private fun catalogWith(vararg topics: Topic): CatalogService {
        val database = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_sitemap_${System.nanoTime()}")).database
        val repository = TopicRepository(database)
        runBlocking { topics.forEach { repository.upsert(it) } }
        return CatalogService(repository)
    }

    @Test
    fun `the sitemap lists the home page, every published topic, and every guide path`() = testApplication {
        val catalog = catalogWith(
            Topic(slug = "topic-a", title = "Topic A", category = "Physics", summary = "s"),
            Topic(slug = "topic-b", title = "Topic B", category = "Biology", summary = "s"),
        )
        application { routing { sitemapRoutes(catalog) } }

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
        val catalog = catalogWith(
            Topic(slug = "draft-topic", title = "Draft", category = "Physics", summary = "s", status = TopicStatus.DRAFT),
        )
        application { routing { sitemapRoutes(catalog) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        assertFalse("draft-topic" in body, "an unpublished topic must not appear in the sitemap")
    }

    @Test
    fun `the sitemap never lists a gated or an internal path`() = testApplication {
        val catalog = catalogWith(Topic(slug = "topic-a", title = "Topic A", category = "Physics", summary = "s"))
        application { routing { sitemapRoutes(catalog) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        for (gatedPath in listOf("/learn/", "/account", "/auth", "/api/")) {
            assertFalse(gatedPath in body, "the sitemap must never list $gatedPath")
        }
    }

    @Test
    fun `the sitemap sets no lastmod, because Topic holds no true timestamp yet`() = testApplication {
        val catalog = catalogWith(Topic(slug = "topic-a", title = "Topic A", category = "Physics", summary = "s"))
        application { routing { sitemapRoutes(catalog) } }

        val body = client.get("/sitemap.xml").bodyAsText()

        assertFalse("<lastmod>" in body, "no true last-modified date exists yet; a false date is worse than none")
    }

    @Test
    fun `a hostile slug renders as well-formed XML, with every reserved character escaped`() = testApplication {
        val hostileSlug = """topic & <tag> "quoted" space"""
        val catalog = catalogWith(Topic(slug = hostileSlug, title = "Hostile", category = "Physics", summary = "s"))
        application { routing { sitemapRoutes(catalog) } }

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

    private fun components(modelFamily: String = "fake-model") = Components(
        mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_sitemap_${System.nanoTime()}")),
        cookies = TestFixtures.cookieConfig,
        llmFactory = { FakeLlmClient(modelFamily = modelFamily) },
        modelFamily = modelFamily,
    )

    @Test
    fun `the response carries no Set-Cookie header`() = testApplication {
        val c = components()
        application { routing { sitemapRoutes(c.catalog) } }

        assertNull(client.get("/sitemap.xml").headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `the route builds no model client`() = testApplication {
        var built = 0
        val fake = FakeLlmClient(modelFamily = "fake-model")
        val c = Components(
            mongo = Mongo(MongoConfig(TestFixtures.connectionString, "test_api_sitemap_no_model")),
            cookies = TestFixtures.cookieConfig,
            llmFactory = { built++; fake },
            modelFamily = "fake-model",
        )
        application { routing { sitemapRoutes(c.catalog) } }

        client.get("/sitemap.xml")

        assertEquals(0, built, "the route forced the lazy model client to build")
    }
}
