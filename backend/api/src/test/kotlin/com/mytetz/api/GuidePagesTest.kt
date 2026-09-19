package com.mytetz.api

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

    /** The `/guides` paths that the built `sitemap.xml` lists, in file order. */
    private fun sitemapGuidePaths(): List<String> {
        val sitemap = javaClass.getResource("/static/sitemap.xml")?.readText()
        assertTrue(sitemap != null, "the built sitemap.xml must be on the static classpath")
        return Regex("""<loc>https://mytetz\.com(/guides[^<]*)</loc>""")
            .findAll(sitemap)
            .map { it.groupValues[1] }
            .toList()
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

    @Test
    fun `every guide path is lowercase and carries no trailing slash`() {
        assertTrue(GuidePages.paths.isNotEmpty(), "the guide list must not be empty")
        for (path in GuidePages.paths) {
            assertEquals(path.lowercase(), path, "$path must stay lowercase for a case-sensitive jar lookup")
            assertEquals(false, path.endsWith("/"), "$path must carry no trailing slash, so one URL is canonical")
        }
    }
}
