package com.mytetz.api

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The logger name `installEdgeSecret` writes under. Matches `BillingRoutesTest`'s own pattern. */
private const val EDGE_SECRET_LOGGER = "com.mytetz.api.EdgeSecret"

/**
 * A secret long enough to pass [EdgeSecretConfig]'s own length check, for every "check is on" test.
 *
 * Holds a letter in each case, so `.uppercase()` in `on, a value that differs only in case does
 * not pass` actually produces a different string. An all-digit secret would not.
 */
private const val TEST_SECRET = "MyTetzEdge-Secret-0123456789-aBc"

/**
 * `installEdgeSecret`, issue #68.
 *
 * This uses a stub app, on the model of `NoIndexPluginTest`. None of these tests need Mongo or a
 * model. Each test needs only the plugin and the paths it inspects.
 */
class EdgeSecretPluginTest {

    private fun Route.wireUp(calls: AtomicInteger) {
        get("/api/health") { call.respond(HttpStatusCode.OK, "ok") }
        get("/api/probe") {
            calls.incrementAndGet()
            call.respond(HttpStatusCode.OK, "probe")
        }
        post("/api/billing/webhook") {
            calls.incrementAndGet()
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // -------------------------------------------------- off means unchanged

    @Test
    fun `off with no secret gives an api request with no header the same answer as today`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = null))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, calls.get(), "the handler must still run while the check is off")
    }

    // -------------------------------------------------- on: the four header cases

    @Test
    fun `on, no header gives 403`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe")

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `on, a wrong header gives 403`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe") { header(EDGE_SECRET_HEADER, "wrong-value") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `on, the correct header value passes`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe") { header(EDGE_SECRET_HEADER, TEST_SECRET) }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `on, the header name in another letter case still passes`() = testApplication {
        // Header names have no case. "x-mytetz-edge" and "X-Mytetz-Edge" name the same header.
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe") { header("x-mytetz-edge", TEST_SECRET) }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `on, a value that differs only in case does not pass`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/probe") { header(EDGE_SECRET_HEADER, TEST_SECRET.uppercase()) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -------------------------------------------------- health stays open

    @Test
    fun `on, api health answers 200 with no header`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // -------------------------------------------------- the webhook is not an exception

    @Test
    fun `on, the webhook route with no edge header gives 403 and its handler never runs`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val response = client.post("/api/billing/webhook")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        // The handler never ran, so the signature check inside it never ran either.
        assertEquals(0, calls.get())
    }

    // -------------------------------------------------- a refusal reaches no handler

    @Test
    fun `a refused request never reaches the route handler`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        client.get("/api/probe")

        assertEquals(0, calls.get())
    }

    // -------------------------------------------------- configuration error

    @Test
    fun `a secret of 31 characters stops the start, and the message does not hold the value`() {
        val tooShort = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d" // 31 characters
        assertEquals(31, tooShort.length)

        val error = assertFailsWith<IllegalArgumentException> {
            EdgeSecretConfig(secret = tooShort)
        }

        assertFalse(error.message.orEmpty().contains(tooShort), "the message must not hold the value")
        assertTrue(error.message.orEmpty().contains(EdgeSecretConfig.EDGE_SECRET_ENV), "the message must name the variable")
    }

    // -------------------------------------------------- nothing sensitive is logged

    @Test
    fun `a refused request logs no header value and no secret`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing { wireUp(calls) }
        }

        val appender = attachAppender()
        try {
            client.get("/api/probe") { header(EDGE_SECRET_HEADER, "a-guessed-value") }
        } finally {
            detachAppender(appender)
        }

        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.none { it.contains("a-guessed-value") }, "the log held the caller's header value: $lines")
        assertTrue(lines.none { it.contains(TEST_SECRET) }, "the log held the real secret: $lines")
    }

    // -------------------------------------------------- the router reads a path, not a prefix
    //
    // A security review of issue #68 found that an earlier version of this check compared the raw
    // text of the path against the literal string `/api/`. Ktor's own router does not read a path
    // that way: it splits on `/`, drops each empty segment, and decodes each segment once. A raw
    // path such as `/%61pi/sessions` does not start with `/api/`, but the router still reads it as
    // `api`, `sessions` and dispatches it — so the old check let a real handler run unguarded. Each
    // row below is a path that review named. [dispatchedWhenCheckIsOff] is what a real route
    // records with no plugin installed at all — read from Ktor 3.1.2 directly, in a
    // `testApplication`, not assumed. [expectedStatusWhenCheckIsOn] is this plugin's own answer,
    // with the check on and no header, once it reads the path the same way the router does.

    private data class RoutingCandidate(
        val path: String,
        val dispatchedWhenCheckIsOff: Boolean,
        val expectedStatusWhenCheckIsOn: HttpStatusCode,
    )

    private val ROUTING_CANDIDATES = listOf(
        // A percent-encoded letter in the first segment. The router decodes it to `api` and
        // dispatches; the raw-prefix check missed this, and the fix's whole reason to exist is
        // this row and the two below it.
        RoutingCandidate("/%61pi/sessions", dispatchedWhenCheckIsOff = true, HttpStatusCode.Forbidden),
        RoutingCandidate("/a%70i/sessions", dispatchedWhenCheckIsOff = true, HttpStatusCode.Forbidden),
        // A leading empty segment from a doubled slash. The router drops it and dispatches; the
        // raw path does not start with the literal string `/api/` either.
        RoutingCandidate("//api/sessions", dispatchedWhenCheckIsOff = true, HttpStatusCode.Forbidden),
        // An empty segment between two literal ones. The raw path already starts with `/api/`, so
        // the old check caught this one too; it stays a permanent test regardless.
        RoutingCandidate("/api//sessions", dispatchedWhenCheckIsOff = true, HttpStatusCode.Forbidden),
        // A trailing slash. The router does not dispatch it — Ktor's own segment split drops a
        // trailing empty segment the same way, but its route match still needs an exact registered
        // route, and none is registered with a trailing slash. This check reads the same two
        // segments as `/api/sessions` and still asks for the header; refusing a path the router
        // would 404 anyway costs nothing.
        RoutingCandidate("/api/sessions/", dispatchedWhenCheckIsOff = false, HttpStatusCode.Forbidden),
        // A trailing slash on the health path. This reads as exactly the segments `api`, `health`,
        // the same as `/api/health` — so this plugin exempts it too, on its own stated rule. The
        // router does not dispatch it to any real handler either way, so this is not a bypass: at
        // most it reaches the harmless `/api/{...}` catch-all or the SPA shell, never a handler
        // that reads a session, spends a rate-limit slot or touches the database.
        RoutingCandidate("/api/health/", dispatchedWhenCheckIsOff = false, HttpStatusCode.NotFound),
        // A case difference. Ktor's router compares a constant segment case-sensitively, confirmed
        // by this row's own `dispatchedWhenCheckIsOff = false`. This plugin compares the same way,
        // so a caller gains nothing from it either.
        RoutingCandidate("/API/sessions", dispatchedWhenCheckIsOff = false, HttpStatusCode.NotFound),
        RoutingCandidate("/Api/health", dispatchedWhenCheckIsOff = false, HttpStatusCode.NotFound),
        // An encoded slash inside one segment. The router does not re-split a segment on a decoded
        // `/`, so this reads as one segment, `api/sessions`, which is not `api`.
        RoutingCandidate("/api%2Fsessions", dispatchedWhenCheckIsOff = false, HttpStatusCode.NotFound),
        // A `..` segment before `api`. Neither the router nor this plugin resolves dot segments, so
        // the first segment reads as `x`, not `api`.
        RoutingCandidate("/x/../api/sessions", dispatchedWhenCheckIsOff = false, HttpStatusCode.NotFound),
        // A `.` segment inside `/api`. This reads as three segments, `api`, `.`, `sessions` — under
        // `/api`, but not the two-segment health path, so it still needs the header.
        RoutingCandidate("/api/./sessions", dispatchedWhenCheckIsOff = false, HttpStatusCode.Forbidden),
        // A path parameter (a matrix parameter) on the first segment. Neither the router nor this
        // plugin strips it, so the segment reads as `api;x=1`, not `api`.
        RoutingCandidate("/api;x=1/sessions", dispatchedWhenCheckIsOff = false, HttpStatusCode.NotFound),
        // The health exemption must be exact. Each of these two reads as more than two segments,
        // or as a second segment that does not decode to `health` — see the two tests below this
        // list for the direct assertion — so neither counts as the health path.
        RoutingCandidate("/api/health%2F..%2Fsessions", dispatchedWhenCheckIsOff = false, HttpStatusCode.Forbidden),
        RoutingCandidate("/api/health/../sessions", dispatchedWhenCheckIsOff = false, HttpStatusCode.Forbidden),
    )

    @Test
    fun `off, the router dispatches exactly the candidate paths this suite records`() = testApplication {
        val calls = AtomicInteger()
        application {
            routing {
                get("/api/sessions") { calls.incrementAndGet(); call.respond(HttpStatusCode.OK, "sessions") }
                get("/api/health") { calls.incrementAndGet(); call.respond(HttpStatusCode.OK, "health") }
            }
        }

        for (candidate in ROUTING_CANDIDATES) {
            calls.set(0)
            val response = client.candidateGet(candidate.path)
            assertEquals(
                candidate.dispatchedWhenCheckIsOff,
                calls.get() > 0,
                "dispatch for '${candidate.path}' (status ${response.status}) did not match what this suite records; " +
                    "a Ktor upgrade may have changed how the router reads a path",
            )
        }
    }

    @Test
    fun `on, no header answers exactly as this suite records for every candidate path`() = testApplication {
        val calls = AtomicInteger()
        application {
            install(ContentNegotiation) { json() }
            installEdgeSecret(EdgeSecretConfig(secret = TEST_SECRET))
            routing {
                get("/api/sessions") { calls.incrementAndGet(); call.respond(HttpStatusCode.OK, "sessions") }
                get("/api/health") { calls.incrementAndGet(); call.respond(HttpStatusCode.OK, "health") }
            }
        }

        for (candidate in ROUTING_CANDIDATES) {
            calls.set(0)
            val response = client.candidateGet(candidate.path)
            assertEquals(candidate.expectedStatusWhenCheckIsOn, response.status, "path '${candidate.path}'")
            if (candidate.dispatchedWhenCheckIsOff) {
                assertEquals(
                    0,
                    calls.get(),
                    "the router dispatches '${candidate.path}' with the check off, so the check on must " +
                        "still keep its handler from running",
                )
            }
        }
    }

    /**
     * Sends [path] exactly as given, bypassing the client's own URL string parser.
     *
     * `client.get(path)` reads a leading `//` as a protocol-relative reference — a client concern,
     * not a server one — and refuses to build the request at all. Setting `encodedPath` directly
     * on the request URL hands the raw text to the server unchanged, the way a raw HTTP request
     * line would.
     */
    private suspend fun HttpClient.candidateGet(path: String): HttpResponse = get { url { encodedPath = path } }

    // -------------------------------------------------- a segment that will not decode

    @Test
    fun `decodeSegmentOrNull reads a percent-encoded letter, and refuses a malformed sequence`() {
        assertEquals("api", decodeSegmentOrNull("%61pi"))
        assertEquals("health/../sessions", decodeSegmentOrNull("health%2F..%2Fsessions"))
        // "%zz" is not a hex escape. This is the fail-closed case: the router's own decode would
        // also fail on it, and this plugin cannot decide what the router would do, so it refuses —
        // see `installEdgeSecret`'s own KDoc, "Fail closed on a segment that will not decode".
        //
        // A real HTTP request carrying this could not be proven end to end in this suite: Ktor's
        // own test client validates a URL's path before it ever sends the request, and refuses to
        // build one that carries a malformed percent sequence at all — confirmed by running it,
        // not assumed. This unit test pins the same rule at the one seam this suite can reach.
        assertNull(decodeSegmentOrNull("%zz"))
    }

    // ------------------------------------------------------------------ log capture
    //
    // This uses the same technique as `BillingRoutesTest`. A `ListAppender` attaches straight to
    // the plugin's own logger. Each test detaches it again once it reads the log, so one test's
    // appender never sees another test's log line.

    private fun attachAppender(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(EDGE_SECRET_LOGGER) as ch.qos.logback.classic.Logger).addAppender(appender)
        return appender
    }

    private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
        (LoggerFactory.getLogger(EDGE_SECRET_LOGGER) as ch.qos.logback.classic.Logger).detachAppender(appender)
    }
}
