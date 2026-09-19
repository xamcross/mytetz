package com.mytetz.api

import com.mytetz.graph.ExplanationRepository
import com.mytetz.persistence.Mongo
import com.mytetz.persistence.MongoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds [GuidePages] to the pages that really ship.
 *
 * `:backend:api:processResources` copies the real Angular build into the `static` classpath
 * resources, so these tests read the shipped files and not a fixture. A rename of a guide folder,
 * a change of case, or a page that enters the sitemap alone therefore fails the build.
 */
class GuidePagesTest {

    /**
     * The `/guides` paths the live `GET /sitemap.xml` route lists, in file order.
     *
     * #46 deletes the static `frontend/public/sitemap.xml` this test used to read from the
     * classpath and replaces it with [sitemapRoutes], a Ktor route that reads [GuidePages.paths]
     * itself. This helper now asks that same route for its answer, over [TestFixtures.seededCatalog],
     * the real catalogue `topics.json` seeds in production.
     *
     * `testApplication`'s own block returns `Unit`, not the value the block computes — the plan's
     * own literal code for this helper assumed otherwise and does not compile. [paths] carries the
     * answer back out instead.
     */
    private fun sitemapGuidePaths(): List<String> {
        var paths: List<String> = emptyList()
        testApplication {
            val explanations = ExplanationRepository(
                Mongo(MongoConfig(TestFixtures.connectionString, "test_api_guide_sitemap")).database
            )
            application {
                routing { sitemapRoutes(TestFixtures.seededCatalog(), explanations, modelFamily = "fake-model") }
            }
            val body = client.get("/sitemap.xml").bodyAsText()
            paths = Regex("""<loc>https://mytetz\.com(/guides[^<]*)</loc>""")
                .findAll(body)
                .map { it.groupValues[1] }
                .toList()
        }
        return paths
    }

    @Test
    fun `the guide list holds every guides URL that sitemap xml lists`() {
        assertEquals(sitemapGuidePaths(), GuidePages.paths)
    }

    @Test
    fun `every guide page answers 200 and carries its own canonical tag`() = testApplication {
        application { routing { spaRoutes() } }

        assertTrue(GuidePages.paths.isNotEmpty(), "the guide list must not be empty")
        for (path in GuidePages.paths) {
            val response = client.get(path)

            assertEquals(HttpStatusCode.OK, response.status, "$path must answer 200")
            assertTrue(
                response.bodyAsText().contains("""<link rel="canonical" href="https://mytetz.com$path" />"""),
                "$path must name itself in its canonical tag",
            )
        }
    }

    @Test
    fun `no guide page still holds the issue 45 placeholder comment`() {
        // The plan's own literal path, "../frontend/public/guides", is one level short: a Gradle
        // `Test` task's working directory is the module's own project directory
        // (`backend/api`), not the repository root, so that path silently walked a directory that
        // does not exist and passed with an empty, meaningless offender list. Confirmed by running
        // this test both ways.
        val guidesRoot = File("../../frontend/public/guides")
        assertTrue(guidesRoot.isDirectory, "guides root not found at ${guidesRoot.absolutePath}")

        val offenders = guidesRoot.walkTopDown()
            .filter { it.name == "index.html" }
            .filter { it.readText().contains("issue #45") }
            .map { it.path }
            .toList()

        assertTrue(offenders.isEmpty(), "these guide pages still name issue #45: $offenders")
    }

    /**
     * Acceptance criterion 1 of #45, the "answers 200" half. Reads every real, built guide page
     * from the static classpath (the same source `every guide page answers 200...` above reads
     * from), finds every `/topics/<slug>` link its own "Start here" block now carries, and asks the
     * real [topicPageRoutes] — wired to [TestFixtures.seededCatalog], the same catalogue
     * `topics.json` seeds in production — for each one.
     */
    @Test
    fun `every Start Here topic link in a guide page answers 200`() = testApplication {
        val explanations = ExplanationRepository(
            Mongo(MongoConfig(TestFixtures.connectionString, "test_api_guide_topic_links")).database
        )
        application {
            routing {
                topicPageRoutes(
                    catalog = TestFixtures.seededCatalog(),
                    explanations = explanations,
                    modelFamily = "fake-model",
                )
            }
        }

        val slugs = GuidePages.paths
            .map { path -> javaClass.getResource("/static$path/index.html")?.readText() ?: "" }
            .flatMap { html -> Regex("""href="/topics/([a-z0-9-]+)"""").findAll(html).map { it.groupValues[1] } }
            .distinct()

        assertTrue(slugs.isNotEmpty(), "no /topics/<slug> link found in any guide page")
        for (slug in slugs) {
            assertEquals(HttpStatusCode.OK, client.get("/topics/$slug").status, "/topics/$slug must answer 200")
        }
    }

    @Test
    fun `every guide path is lowercase and carries no trailing slash`() {
        assertTrue(GuidePages.paths.isNotEmpty(), "the guide list must not be empty")
        for (path in GuidePages.paths) {
            assertEquals(path.lowercase(), path, "$path must stay lowercase for a case-sensitive jar lookup")
            assertEquals(false, path.endsWith("/"), "$path must carry no trailing slash, so one URL is canonical")
        }
    }

    /**
     * The six new pages that issue #118 adds. The list is hardcoded here, and not read from
     * [GuidePages], so this test fails while the pages do not exist yet, and it keeps passing
     * once the registration commit adds the same six paths to [GuidePages.paths].
     */
    private val wave2Paths = listOf(
        "/guides/how-to-understand-a-difficult-text",
        "/guides/how-to-use-ai-to-study-without-cheating",
        "/guides/how-to-explain-a-text-to-yourself-while-you-read",
        "/guides/how-many-times-should-you-reread-something",
        "/guides/how-do-you-know-if-you-understand-something",
        "/guides/how-long-should-a-study-session-be",
    )

    @Test
    fun `every wave 2 guide page answers 200, holds one h1 and names itself in its canonical tag`() =
        testApplication {
            application { routing { spaRoutes() } }

            for (path in wave2Paths) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.OK, response.status, "$path must answer 200")

                val body = response.bodyAsText()
                val h1Count = Regex("""<h1[ >]""").findAll(body).count()
                assertEquals(1, h1Count, "$path must hold exactly one h1, found $h1Count")
                assertTrue(
                    body.contains("""<link rel="canonical" href="https://mytetz.com$path" />"""),
                    "$path must name itself in its canonical tag",
                )
            }
        }

    @Test
    fun `every wave 2 page is registered in GuidePages paths`() {
        for (path in wave2Paths) {
            assertTrue(GuidePages.paths.contains(path), "$path must be added to GuidePages.paths")
        }
    }
}
