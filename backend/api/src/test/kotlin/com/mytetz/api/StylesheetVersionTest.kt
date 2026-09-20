package com.mytetz.api

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the version in the URL of `guides.css`.
 *
 * The file is served with `Cache-Control: max-age=86400`, so Cloudflare and each browser keep it
 * for a day. A page with new markup then gets the old stylesheet. On 2026-09-20 each public page
 * looked broken for that reason: a logo mark with no size, a count that showed two times, and
 * footer links with no space between them. The URL now carries the hash of the file, so a change
 * of the file is a new URL, and no cache holds it.
 */
class StylesheetVersionTest {

    /** The first 10 characters of the SHA-256 of the stylesheet. A carriage return is removed
     * first, so a checkout with CRLF line ends and a checkout with LF line ends give one value. */
    private fun versionOfTheFile(): String {
        val bytes = javaClass.getResource("/static/guides/guides.css")?.readBytes()
        assertTrue(bytes != null, "guides.css did not ship")
        val carriageReturn = 13.toByte()
        val normalised = bytes!!.filter { it != carriageReturn }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalised)
        return digest.joinToString("") { "%02x".format(it) }.take(10)
    }

    @Test
    fun `the version of the stylesheet URL equals the hash of the file`() {
        assertEquals(
            versionOfTheFile(),
            GUIDES_STYLESHEET_VERSION,
            "guides.css changed. Put the new value into GUIDES_STYLESHEET_VERSION in " +
                "TopicPageHtml.kt, and into the stylesheet link of each page under " +
                "frontend/public/guides.",
        )
    }

    @Test
    fun `each static guide page links the stylesheet with the same version`() {
        assertTrue(GuidePages.paths.isNotEmpty(), "the guide list must not be empty")
        for (path in GuidePages.paths) {
            val html = javaClass.getResource("/static$path/index.html")?.readText()
            assertTrue(html != null, "$path did not ship a static file")
            assertTrue(
                """href="$GUIDES_STYLESHEET"""" in html!!,
                "$path does not link $GUIDES_STYLESHEET",
            )
        }
    }
}
