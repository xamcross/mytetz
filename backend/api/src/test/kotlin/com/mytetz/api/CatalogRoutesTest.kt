package com.mytetz.api

import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogRoutesTest {

    /**
     * `TestFixtures.topicRequests()` is one shared collection for the whole module, so every test
     * below submits text nothing else submits. The suffix keeps that true without coordination.
     */
    private fun uniqueText(name: String) = "$name ${System.nanoTime()}"

    private fun ApplicationTestBuilder.catalogApp(
        limiter: FixedWindowRateLimiter = FixedWindowRateLimiter(limit = 100, windowMillis = 60_000),
        clientAddresses: ClientAddressConfig = ClientAddressConfig(trustedHeader = null),
    ) {
        application {
            install(ContentNegotiation) { json() }
            installErrorMapping()
            routing {
                catalogRoutes(
                    catalog = TestFixtures.seededCatalog(),
                    topicRequests = TestFixtures.topicRequests(),
                    cookies = TestFixtures.cookieConfig,
                    clientAddresses = clientAddresses,
                    topicRequestLimiter = limiter,
                )
            }
        }
    }

    // ------------------------------------------------------------------ browsing

    @Test
    fun `listing returns published topics as json`() = testApplication {
        catalogApp()

        val response = client.get("/api/catalog/topics")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("quantum-physics"))
    }

    @Test
    fun `a known slug returns its detail`() = testApplication {
        catalogApp()

        val response = client.get("/api/catalog/topics/quantum-physics")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Quantum Physics"))
    }

    @Test
    fun `an unknown slug returns 404 with a coded error`() = testApplication {
        catalogApp()

        val response = client.get("/api/catalog/topics/nope")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("NOT_FOUND"))
    }

    @Test
    fun `a draft topic is not served by the detail route`() = testApplication {
        // `CatalogService.findBySlug` deliberately does NOT filter on status — CatalogServiceTest
        // pins that, so an admin lookup can still see a draft — which makes the detail route the
        // place where publication has to be enforced for readers. Handing `findBySlug` straight to
        // `respond` publishes every unreviewed topic to anyone who guesses its slug, and browsing
        // gives no clue that anything is wrong because `listPublished` filters correctly.
        val draft = Topic(
            slug = "unreviewed-route-topic",
            title = "Unreviewed Route Topic",
            category = "Physics",
            summary = "Not yet reviewed, and must never reach a reader.",
            status = TopicStatus.DRAFT,
        )
        runBlocking { TestFixtures.topics().upsert(draft) }
        catalogApp()

        val response = client.get("/api/catalog/topics/${draft.slug}")

        assertEquals(HttpStatusCode.NotFound, response.status, "a DRAFT topic was served to a reader")
        assertFalse(response.bodyAsText().contains("Unreviewed"), "draft content leaked in the 404 body")
    }

    @Test
    fun `the category and query parameters reach the catalogue`() = testApplication {
        catalogApp()

        val matched = client.get("/api/catalog/topics?q=quantum")
        val unmatched = client.get("/api/catalog/topics?q=definitelynotatopic")

        assertTrue(matched.bodyAsText().contains("quantum-physics"))
        // Without this half, a route that dropped both parameters on the floor would still pass the
        // assertion above — every topic contains quantum-physics when nothing is filtered.
        assertEquals("[]", unmatched.bodyAsText(), "the q parameter was ignored")
    }

    @Test
    fun `a topic summary carries the browse fields and nothing else`() = testApplication {
        catalogApp()

        val body = client.get("/api/catalog/topics/quantum-physics").bodyAsText()

        assertTrue(body.contains("\"slug\":\"quantum-physics\""))
        assertTrue(body.contains("\"category\":\"Physics\""))
        assertTrue(body.contains("\"summary\":"))
        // `status` and `sortWeight` are editorial bookkeeping. Shipping them makes them part of the
        // wire contract by accident, and `status` is only ever PUBLISHED here anyway.
        assertFalse(body.contains("sortWeight"), "an editorial field leaked into the wire shape")
        assertFalse(body.contains("status"), "an editorial field leaked into the wire shape")
    }

    // ------------------------------------------------------------------ topic requests

    @Test
    fun `a topic request is recorded and repeats increment the counter`() = testApplication {
        catalogApp()
        val text = uniqueText("Organic Chemistry")

        repeat(2) {
            val response = client.post("/api/topic-requests") {
                contentType(ContentType.Application.Json)
                setBody("""{"text":"  ${text.replace(" ", "   ")} "}""")
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

        assertEquals(2, TestFixtures.topicRequests().countFor(text))
    }

    @Test
    fun `a blank or oversized request is rejected`() = testApplication {
        catalogApp()

        val blank = client.post("/api/topic-requests") {
            contentType(ContentType.Application.Json); setBody("""{"text":"   "}""")
        }
        val huge = client.post("/api/topic-requests") {
            contentType(ContentType.Application.Json); setBody("""{"text":"${"x".repeat(300)}"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals(HttpStatusCode.BadRequest, huge.status)
        assertEquals(0, TestFixtures.topicRequests().countFor("x".repeat(300)))
    }

    @Test
    fun `a topic request identifies its sender with a signed principal cookie`() = testApplication {
        catalogApp()

        val response = client.post("/api/topic-requests") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"${uniqueText("thermodynamics")}"}""")
        }

        // Continuity, not authorisation and not the rate-limit key: a browser that submits a topic
        // request and later starts a session must carry the same principal into it rather than be
        // issued a second one.
        val cookie = assertNotNull(response.headers[HttpHeaders.SetCookie], "no principal was established")
        assertTrue(cookie.startsWith("mytetz_pid=anon:"))
    }

    @Test
    fun `one caller cannot spend more than its allowance, without presenting any cookie`() = testApplication {
        val limiter = FixedWindowRateLimiter(limit = 2, windowMillis = 60_000)
        catalogApp(limiter)

        fun post(text: String) = runBlocking {
            client.post("/api/topic-requests") {
                contentType(ContentType.Application.Json)
                setBody("""{"text":"$text"}""")
            }
        }

        // Note what is NOT here: no cookie is carried between these calls. The limit used to be
        // keyed on the principal, and `Principals.resolve` mints a fresh one for every cookie-less
        // request — so this sequence was allowed through for ever, and the limit only ever applied
        // to honest browsers that returned their cookie.
        assertEquals(HttpStatusCode.Accepted, post(uniqueText("a")).status)
        assertEquals(HttpStatusCode.Accepted, post(uniqueText("b")).status)
        val refused = post(uniqueText("c"))

        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertTrue(refused.bodyAsText().contains("RATE_LIMITED"))
    }

    @Test
    fun `cookie-less requests do not each create a rate limiter entry`() = testApplication {
        val limiter = FixedWindowRateLimiter(limit = 500, windowMillis = 60_000)
        catalogApp(limiter)

        repeat(40) {
            client.post("/api/topic-requests") {
                contentType(ContentType.Application.Json)
                setBody("""{"text":"${uniqueText("flood")}"}""")
            }
        }

        // The Critical this replaces: one map entry per request, cleared only when the 24h window
        // rolled. 40 requests is nothing; the shape is what matters — the table must be keyed on
        // something the caller cannot re-mint at will.
        assertEquals(1, limiter.trackedKeys, "each cookie-less request created its own limiter key")
    }

    @Test
    fun `the limiter table stays bounded even when every caller looks different`() = testApplication {
        val limiter = FixedWindowRateLimiter(limit = 5, windowMillis = 60_000, maxTrackedKeys = 8)
        catalogApp(limiter, ClientAddressConfig(trustedHeader = "CF-Connecting-IP"))

        repeat(60) { i ->
            client.post("/api/topic-requests") {
                header("CF-Connecting-IP", "203.0.113.$i")
                contentType(ContentType.Application.Json)
                setBody("""{"text":"${uniqueText("spread")}"}""")
            }
        }

        // Even a caller that can forge the trusted header cannot spend memory: the ceiling holds
        // regardless of whether the address resolution is right, which is exactly why it exists.
        assertEquals(8, limiter.trackedKeys, "the ceiling did not hold under key churn")
    }

    @Test
    fun `distinct callers get distinct allowances when a trusted header is configured`() = testApplication {
        val limiter = FixedWindowRateLimiter(limit = 1, windowMillis = 60_000)
        catalogApp(limiter, ClientAddressConfig(trustedHeader = "CF-Connecting-IP"))

        fun post(ip: String) = runBlocking {
            client.post("/api/topic-requests") {
                header("CF-Connecting-IP", ip)
                contentType(ContentType.Application.Json)
                setBody("""{"text":"${uniqueText("who")}"}""")
            }
        }

        assertEquals(HttpStatusCode.Accepted, post("203.0.113.1").status)
        assertEquals(HttpStatusCode.TooManyRequests, post("203.0.113.1").status)
        assertEquals(HttpStatusCode.Accepted, post("203.0.113.2").status, "one caller spent another's allowance")
    }

    @Test
    fun `a request body larger than the cap is refused before it is read`() = testApplication {
        catalogApp()

        // Ktor buffers the body before ContentNegotiation can look at it, so a length check is the
        // only thing between a public endpoint and an arbitrary allocation on a 512 MB machine.
        val response = client.post("/api/topic-requests") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"${"x".repeat(MAX_TOPIC_REQUEST_BODY_BYTES.toInt())}"}""")
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().contains("PAYLOAD_TOO_LARGE"))
    }

    @Test
    fun `the browse routes do not mint a principal cookie`() = testApplication {
        catalogApp()

        val listing = client.get("/api/catalog/topics")
        val detail = client.get("/api/catalog/topics/quantum-physics")
        val missing = client.get("/api/catalog/topics/nope")

        // Browsing is anonymous and nothing about it needs an identity. Setting a cookie here would
        // hand every crawler and every cache a `Set-Cookie` on a public GET — and, since the browse
        // routes are the first thing any visitor touches, it would make the principal a
        // tracking identifier for people who never interact with the site at all.
        assertNull(listing.headers[HttpHeaders.SetCookie], "the listing route minted a principal")
        assertNull(detail.headers[HttpHeaders.SetCookie], "the detail route minted a principal")
        assertNull(missing.headers[HttpHeaders.SetCookie], "the 404 path minted a principal")
    }

    @Test
    fun `a malformed topic request body is 400 rather than 500`() = testApplication {
        catalogApp()

        val response = client.post("/api/topic-requests") {
            contentType(ContentType.Application.Json)
            setBody("{ not json at all")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
