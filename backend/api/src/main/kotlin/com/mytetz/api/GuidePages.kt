package com.mytetz.api

/**
 * The public path of every static guide page under `/guides`.
 *
 * The pages themselves are hand-written HTML under `frontend/public/guides`, and the Angular build
 * copies them into the `static` resources that [spaRoutes] serves. Nothing generates them, so this
 * list is the only machine-readable record of which pages exist.
 *
 * Two consumers need that record:
 *
 * - `frontend/public/sitemap.xml` lists the same URLs today. `GuidePagesTest` compares the two, so
 *   a page that enters one and not the other fails the build.
 * - #46 replaces that static file with a `GET /sitemap.xml` route. Step 3 of #46 deletes the file,
 *   so the route must read this list instead. One list gives one truth.
 *
 * A path stays lowercase. The runtime serves these files from inside `api.jar`, and
 * `JarFileContent` is case-sensitive, so `/Guides/...` would answer 200 on a Windows developer
 * machine and 404 in production.
 */
internal object GuidePages {
    val paths: List<String> = listOf(
        "/guides",
        "/guides/how-to-study-on-your-own",
        "/guides/what-to-use-instead-of-a-highlighter",
        "/guides/how-to-test-yourself-while-you-read",
        "/guides/when-to-review-what-you-read",
        "/guides/how-students-study-now",
        "/guides/why-a-person-stops-an-online-course",
        "/guides/how-to-understand-a-difficult-text",
        "/guides/how-to-use-ai-to-study-without-cheating",
        "/guides/how-to-explain-a-text-to-yourself-while-you-read",
        "/guides/how-many-times-should-you-reread-something",
        "/guides/how-do-you-know-if-you-understand-something",
        "/guides/how-long-should-a-study-session-be",
    )
}
