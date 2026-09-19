package com.mytetz.api

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mytetz.graph.Ancestor
import com.mytetz.graph.ImageMedia
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CommonsClient] against a [MockEngine]: no socket, no live Wikimedia traffic. On the model of
 * [FreemiusApiClientTest] — the client under test is built directly on an injected [HttpClient],
 * so a test controls the exact answer and the exact failure the network gives.
 *
 * The wire shape every fixture below uses is not invented. It is the real answer Commons gave on
 * 2026-09-19 to `action=query&generator=search&gsrsearch=<span>&gsrnamespace=6&prop=imageinfo&
 * iiprop=url|mime|extmetadata&iiurlwidth=800&format=json&formatversion=2` — one combined request
 * that both searches the File namespace and reads each hit's image metadata, run by hand against
 * `https://commons.wikimedia.org/w/api.php` before this file was written. See [CommonsClient]'s
 * own KDoc for the field names this call confirms: `thumburl`, `url`, `descriptionurl`, `mime`,
 * and the `extmetadata` keys `LicenseShortName`, `LicenseUrl`, `Artist`, `Credit`.
 */
class CommonsClientTest {

    private fun clientReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK): CommonsClient {
        val engine = MockEngine {
            respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CommonsClient(HttpClient(engine))
    }

    /** One page of the search-plus-imageinfo answer, shaped exactly as Commons sends it. */
    private fun pageJson(
        title: String = "File:Example.jpg",
        index: Int = 1,
        thumbUrl: String? = "https://upload.wikimedia.org/thumb/example.jpg",
        originalUrl: String? = "https://upload.wikimedia.org/example.jpg",
        descriptionUrl: String? = "https://commons.wikimedia.org/wiki/File:Example.jpg",
        mime: String? = "image/jpeg",
        licenseShortName: String? = "CC BY-SA 4.0",
        licenseUrl: String? = "https://creativecommons.org/licenses/by-sa/4.0",
        artist: String? = "Example Author",
        credit: String? = "Wikimedia Commons",
    ): String {
        val thumbField = thumbUrl?.let { """"thumburl":"$it",""" } ?: ""
        val urlField = originalUrl?.let { """"url":"$it",""" } ?: ""
        val descriptionField = descriptionUrl?.let { """"descriptionurl":"$it",""" } ?: ""
        val mimeField = mime?.let { """"mime":"$it",""" } ?: ""
        val licenseField = licenseShortName?.let { """"LicenseShortName":{"value":"$it"},""" } ?: ""
        val licenseUrlField = licenseUrl?.let { """"LicenseUrl":{"value":"$it"},""" } ?: ""
        val artistField = artist?.let { """"Artist":{"value":"$it"},""" } ?: ""
        val creditField = credit?.let { """"Credit":{"value":"$it"},""" } ?: ""
        return """
            {"title":"$title","index":$index,"imageinfo":[
                {$thumbField$urlField$descriptionField${mimeField}
                 "extmetadata":{$licenseField$licenseUrlField${artistField}$creditField"CommonsMetadataExtension":{"value":1.2}}}
            ]}
        """.trimIndent()
    }

    private fun bodyWithPages(vararg pages: String): String =
        """{"batchcomplete":true,"query":{"pages":[${pages.joinToString(",")}]}}"""

    private fun bodyWithNoPages(): String = """{"batchcomplete":true,"query":{"pages":[]}}"""

    // ------------------------------------------------------------------ the request itself

    @Test
    fun `the request Ktor sends carries the span as a parameter, never concatenated into the URL`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(bodyWithNoPages(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommonsClient(HttpClient(engine))

        client.findImage("a phrase with & and ? in it", emptyList())

        val request = requireNotNull(captured) { "no request reached the mock engine" }
        assertEquals("commons.wikimedia.org", request.url.host)
        assertEquals("https", request.url.protocol.name)
        assertEquals("/w/api.php", request.url.encodedPath)
        // Ktor's own `parameter()` percent-encodes the value once; a literal "&" or "?" in the
        // decoded query parameter proves the span went through `parameter()` rather than through
        // string concatenation into the URL, which would have let either character break the
        // query string or open a second, attacker-controlled parameter.
        assertEquals("a phrase with & and ? in it", request.url.parameters["gsrsearch"])
        assertEquals("6", request.url.parameters["gsrnamespace"])
        assertEquals("2", request.url.parameters["formatversion"])
    }

    @Test
    fun `the request carries a descriptive User-Agent naming the project and the site, with no email`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(bodyWithNoPages(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommonsClient(HttpClient(engine))

        client.findImage("escape velocity", emptyList())

        val userAgent = requireNotNull(captured).headers[HttpHeaders.UserAgent]
        assertTrue(userAgent.orEmpty().contains("mytetz"), "the User-Agent must name the project")
        assertFalse(userAgent.orEmpty().contains("@"), "the User-Agent must carry no email address")
    }

    /**
     * `Ancestor(span, body)` is root-first (`PromptBuilder.kt`), so the *last* entry is the nearest
     * ancestor — the one immediately above the span this call is about. That is the one short,
     * relevant context term the chain offers: a phrase alone ("wave", "field", "energy") can name a
     * completely different subject than the one the learner is actually reading about.
     */
    @Test
    fun `the nearest ancestor's own span is added to the search query as short context`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(bodyWithNoPages(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommonsClient(HttpClient(engine))

        client.findImage(
            "wave",
            listOf(Ancestor("the topic introduction", "a long seed body…"), Ancestor("sound", "a long body…")),
        )

        assertEquals("wave sound", requireNotNull(captured).url.parameters["gsrsearch"])
    }

    @Test
    fun `an ancestor's body never reaches the search query, only its span`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(bodyWithNoPages(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommonsClient(HttpClient(engine))

        client.findImage("wave", listOf(Ancestor("sound", "a full paragraph the model wrote, never sent to a third party")))

        val query = requireNotNull(captured).url.parameters["gsrsearch"].orEmpty()
        assertFalse(query.contains("paragraph"), "an ancestor's body must never reach the search query: $query")
    }

    @Test
    fun `with no ancestors, the search query is the span alone`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(bodyWithNoPages(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommonsClient(HttpClient(engine))

        client.findImage("wave", emptyList())

        assertEquals("wave", requireNotNull(captured).url.parameters["gsrsearch"])
    }

    @Test
    fun `the search query is bounded to 120 characters`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(bodyWithNoPages(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommonsClient(HttpClient(engine))

        client.findImage("wave", listOf(Ancestor("x".repeat(200), "body")))

        val query = requireNotNull(captured).url.parameters["gsrsearch"].orEmpty()
        assertTrue(query.length <= 120, "the search query must be bounded: ${query.length} characters")
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    fun `a relevant, freely licensed result is returned as ImageMedia`() = runTest {
        val body = bodyWithPages(pageJson())

        val image: ImageMedia? = clientReturning(body).findImage("escape velocity", emptyList())

        assertEquals("https://upload.wikimedia.org/thumb/example.jpg", image?.imageUrl, "the bounded thumbnail, not the original")
        assertEquals("Example.jpg", image?.title, "the File: prefix is not shown to the learner")
        assertEquals("CC BY-SA 4.0", image?.license)
        assertEquals("https://commons.wikimedia.org/wiki/File:Example.jpg", image?.commonsPageUrl)
    }

    @Test
    fun `the second candidate is used when the first fails an acceptance check`() = runTest {
        val body = bodyWithPages(
            pageJson(title = "File:Bad.jpg", index = 1, licenseShortName = "All rights reserved"),
            pageJson(title = "File:Good.jpg", index = 2),
        )

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        assertEquals("Good.jpg", image?.title)
    }

    // ------------------------------------------------------------------ failures answer null

    @Test
    fun `a non-2xx status answers null`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        assertNull(CommonsClient(HttpClient(engine)).findImage("escape velocity", emptyList()))
    }

    @Test
    fun `a thrown IOException answers null`() = runTest {
        val engine = MockEngine { throw IOException("simulated network failure") }
        assertNull(CommonsClient(HttpClient(engine)).findImage("escape velocity", emptyList()))
    }

    @Test
    fun `an empty search result answers null`() = runTest {
        val image = clientReturning(bodyWithNoPages()).findImage("escape velocity", emptyList())
        assertNull(image)
    }

    @Test
    fun `a body this class cannot decode answers null`() = runTest {
        assertNull(clientReturning("not json").findImage("escape velocity", emptyList()))
    }

    @Test
    fun `a request that exceeds the timeout answers null rather than hanging the caller`() = runTest {
        val engine = MockEngine {
            delay(60_000)
            respond(bodyWithNoPages(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = CommonsClient(
            HttpClient(engine) {
                install(HttpTimeout) { requestTimeoutMillis = 10 }
            },
        )

        assertNull(client.findImage("escape velocity", emptyList()))
    }

    // ------------------------------------------------------------------ the acceptance checks

    @Test
    fun `a result with no accepted license is skipped`() = runTest {
        val body = bodyWithPages(pageJson(licenseShortName = "All rights reserved"))
        assertNull(clientReturning(body).findImage("escape velocity", emptyList()))
    }

    @Test
    fun `a public domain result is accepted, with no LicenseUrl`() = runTest {
        val body = bodyWithPages(pageJson(licenseShortName = "Public domain", licenseUrl = null))
        val image = clientReturning(body).findImage("escape velocity", emptyList())
        assertEquals("Public domain", image?.license)
    }

    /**
     * `ImageMedia` and `ImageMediaView` carry no license-URL field, and no page ever shows one, so a
     * refusal on its scheme protects nothing. The real sample this pins is a live answer for a
     * `CC0` file, read 2026-09-19: Commons itself gives `LicenseUrl` as
     * `http://creativecommons.org/publicdomain/zero/1.0/deed.en` — plain `http`, not `https` — for a
     * file whose license short name, `CC0`, is otherwise perfectly acceptable. An earlier version of
     * this class refused this exact, real, freely licensed image over that one field.
     */
    @Test
    fun `a CC0 result with a real, http-scheme LicenseUrl is accepted`() = runTest {
        val body = bodyWithPages(
            pageJson(
                licenseShortName = "CC0",
                licenseUrl = "http://creativecommons.org/publicdomain/zero/1.0/deed.en",
            ),
        )
        val image = clientReturning(body).findImage("escape velocity", emptyList())
        assertEquals("CC0", image?.license)
    }

    @Test
    fun `an imageUrl on a look-alike host is refused`() = runTest {
        val body = bodyWithPages(
            pageJson(thumbUrl = "https://upload.wikimedia.org.evil.example/example.jpg"),
        )
        assertNull(clientReturning(body).findImage("escape velocity", emptyList()))
    }

    @Test
    fun `a commonsPageUrl on a look-alike host is refused`() = runTest {
        val body = bodyWithPages(
            pageJson(descriptionUrl = "https://commons.wikimedia.org.evil.example/wiki/File:Example.jpg"),
        )
        assertNull(clientReturning(body).findImage("escape velocity", emptyList()))
    }

    @Test
    fun `an imageUrl with a plain http scheme is refused`() = runTest {
        val body = bodyWithPages(pageJson(thumbUrl = "http://upload.wikimedia.org/thumb/example.jpg"))
        assertNull(clientReturning(body).findImage("escape velocity", emptyList()))
    }

    @Test
    fun `a file whose mime type is not a safely displayable image type is skipped`() = runTest {
        val body = bodyWithPages(pageJson(mime = "application/pdf"))
        assertNull(clientReturning(body).findImage("escape velocity", emptyList()))
    }

    @Test
    fun `an svg original is accepted through its PNG-rendered thumbnail`() = runTest {
        val body = bodyWithPages(
            pageJson(
                mime = "image/svg+xml",
                thumbUrl = "https://upload.wikimedia.org/thumb/example.svg.png",
            ),
        )
        val image = clientReturning(body).findImage("escape velocity", emptyList())
        assertEquals("https://upload.wikimedia.org/thumb/example.svg.png", image?.imageUrl)
    }

    @Test
    fun `a candidate with no thumbnail is skipped rather than falling back to the original`() = runTest {
        val body = bodyWithPages(pageJson(thumbUrl = null))
        assertNull(clientReturning(body).findImage("escape velocity", emptyList()))
    }

    // ------------------------------------------------------------------ the attribution reduction

    @Test
    fun `the Artist field's HTML is reduced to text plus one https link, never passed through`() = runTest {
        val body = bodyWithPages(
            pageJson(
                artist = "<a href=\\\"https://example.com/artist\\\" onclick=\\\"alert(1)\\\">Example Author</a>",
                credit = null,
            ),
        )

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        assertFalse(image?.attributionHtml.orEmpty().contains("onclick"))
        assertTrue(image?.attributionHtml.orEmpty().contains("Example Author"))
        assertTrue(image?.attributionHtml.orEmpty().contains("https://example.com/artist"))
    }

    @Test
    fun `a script element in the attribution is removed, its text left as inert text`() = runTest {
        val body = bodyWithPages(
            pageJson(artist = "<script>alert(1)</script>Real Name", credit = null),
        )

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        assertFalse(image?.attributionHtml.orEmpty().contains("<script"))
        assertTrue(image?.attributionHtml.orEmpty().contains("Real Name"))
    }

    @Test
    fun `an onerror image tag in the attribution is dropped entirely`() = runTest {
        val body = bodyWithPages(
            pageJson(artist = "<img src=x onerror=alert(1)>Real Name", credit = null),
        )

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        assertFalse(image?.attributionHtml.orEmpty().contains("onerror"))
        assertTrue(image?.attributionHtml.orEmpty().contains("Real Name"))
    }

    @Test
    fun `a javascript colon link in the attribution is dropped, its text is kept`() = runTest {
        val body = bodyWithPages(
            pageJson(artist = "<a href=\\\"javascript:alert(1)\\\">x</a>", credit = null),
        )

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        assertFalse(image?.attributionHtml.orEmpty().contains("javascript:"))
        assertTrue(image?.attributionHtml.orEmpty().contains("x"))
    }

    @Test
    fun `a plain http link in the attribution is dropped, its text is kept`() = runTest {
        val body = bodyWithPages(
            pageJson(artist = "<a href=\\\"http://plain.example\\\">x</a>", credit = null),
        )

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        assertFalse(image?.attributionHtml.orEmpty().contains("plain.example"))
        assertTrue(image?.attributionHtml.orEmpty().contains("x"))
    }

    @Test
    fun `an unclosed tag in the attribution never reaches the wire as an open element`() = runTest {
        val body = bodyWithPages(
            pageJson(artist = "Real Name <a href=\\\"https://example.com", credit = null),
        )

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        val html = image?.attributionHtml.orEmpty()
        assertTrue(html.contains("Real Name"))
        // Whatever survives from the unclosed tag is escaped, so it can only ever read as text.
        assertFalse(html.contains("<a "), "no unescaped opening anchor tag may reach the wire")
    }

    @Test
    fun `a quote and an ampersand in the attribution text round-trip as safe text`() = runTest {
        val body = bodyWithPages(
            pageJson(artist = "Jane &quot;The Great&quot; &amp; Sons", credit = null),
        )

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        val html = image?.attributionHtml.orEmpty()
        assertTrue(html.contains("Jane &quot;The Great&quot; &amp; Sons"))
    }

    @Test
    fun `attribution with no link at all carries no anchor element`() = runTest {
        val body = bodyWithPages(pageJson(artist = "Example Author", credit = null))

        val image = clientReturning(body).findImage("escape velocity", emptyList())

        assertFalse(image?.attributionHtml.orEmpty().contains("<a "))
        assertTrue(image?.attributionHtml.orEmpty().contains("Example Author"))
    }

    // ------------------------------------------------------------------ logging

    /**
     * On the model of [FreemiusApiClientTest]'s own `an undecodable body's contents never reach the
     * log`: neither the span, nor the built URL, nor the response body may reach a log line —
     * only a status code or an exception's class name.
     */
    @Test
    fun `a failure never logs the span, the query or the response body`() = runTest {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("com.mytetz.api.CommonsClient") as ch.qos.logback.classic.Logger
        logger.addAppender(appender)

        val secretLookingSpan = "confidential span 42"
        try {
            clientReturning("""{"secret": "$secretLookingSpan", this is not valid json""").findImage(secretLookingSpan, emptyList())
        } finally {
            logger.detachAppender(appender)
        }

        assertTrue(appender.list.isNotEmpty(), "the failure must still be logged")
        assertTrue(appender.list.none { it.formattedMessage.contains(secretLookingSpan) })
        assertTrue(appender.list.none { it.formattedMessage.contains("gsrsearch") })
        assertTrue(appender.list.all { it.throwableProxy == null })
    }
}
