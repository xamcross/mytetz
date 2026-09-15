package com.mytetz.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps [SpaRoutes.paths] equal to the routes in `frontend/src/app/app.routes.ts`.
 *
 * A route added to one file and not the other is the defect [SpaRoutes.matches] cannot catch by
 * itself: the shell would answer 200 for a path Angular does not route, or 404 for one that it
 * does.
 */
class SpaRoutesConsistencyTest {

    private val pathPattern = Regex("""path:\s*['"]([^'"]*)['"]""")

    @Test
    fun `SpaRoutes lists exactly the paths in app_routes_ts`() {
        val routesFile = File("../../frontend/src/app/app.routes.ts")
        assertTrue(routesFile.isFile, "cannot find app.routes.ts from ${File(".").absolutePath}")

        // `**` is Angular's own wildcard, for the not-found component. It names no real route, so
        // `SpaRoutes.paths` must not carry it.
        val declaredPaths = pathPattern.findAll(routesFile.readText())
            .map { it.groupValues[1] }
            .filterNot { it == "**" }
            .toList()

        assertEquals(declaredPaths.sorted(), SpaRoutes.paths.sorted())
    }
}
