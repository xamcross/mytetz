package com.mytetz.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mytetz.graph.GenerationFailedException
import com.mytetz.graph.Verb
import com.mytetz.session.CorruptSessionException
import com.mytetz.session.DepthLimitException
import com.mytetz.session.SessionFullException
import com.mytetz.session.SessionNotFoundException
import com.mytetz.session.SpanMismatchException
import com.mytetz.session.SpanSelection
import com.mytetz.session.VariantLimitException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The exception taxonomy Tasks 1.9 and 1.10 built exists so that this mapping can key on **type**
 * and never on message text. These tests are what make that pay off, and three of them exist
 * because the mapping this task was handed did not have them:
 *
 * - `SessionNotFoundException` extends `NoSuchElementException`, not `IllegalArgumentException`, so
 *   an unhandled taxonomy answers the single most common client error on the whole API — a stale or
 *   mistyped session id — with **500 INTERNAL**.
 * - `CorruptSessionException` extends `IllegalStateException`, also unhandled, so a dangling parent
 *   or a parent cycle is reported as an ordinary crash and nobody is ever paged.
 * - A blanket `IllegalArgumentException -> 404` tells a caller "that does not exist" when what
 *   actually happened is "your request was malformed".
 */
class ErrorMappingTest {

    @Serializable
    private data class Payload(val text: String)

    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.throwing(cause: () -> Throwable) {
        application {
            install(ContentNegotiation) { json() }
            installErrorMapping()
            routing {
                get("/boom") { throw cause() }
                post("/echo") { call.respondText(call.receive<Payload>().text) }
            }
        }
    }

    private suspend fun HttpResponse.apiError(): ApiError = json.decodeFromString(bodyAsText())

    // ------------------------------------------------------- absent vs invalid vs corrupt

    @Test
    fun `an unknown session id is 404 and not 500`() = testApplication {
        // The headline defect. SessionNotFoundException descends from NoSuchElementException, so it
        // slips past every handler an IllegalArgumentException would hit and lands on the Throwable
        // catch-all. Task 1.9 wrote "404" in this type's KDoc; this is where that becomes true.
        throwing { SessionNotFoundException("does-not-exist") }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.apiError().code)
    }

    @Test
    fun `an unknown node id is 400 and not 404`() = testApplication {
        // ContextChain.pathTo raises IllegalArgumentException for a node id the caller invented.
        // Mapping that to 404 tells the client the resource is missing; the truth is that its
        // request was malformed, and only one of those two answers is actionable.
        throwing { IllegalArgumentException("node n7 not found in session s1") }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_REQUEST", response.apiError().code)
    }

    @Test
    fun `a rejected SEED verb is 400 and not 404`() = testApplication {
        // Task 1.10's `require(verb != Verb.SEED)`. Every require() failure in the stack arrives as
        // an IllegalArgumentException, and none of them means "that does not exist".
        throwing {
            runCatching { require(Verb.SEED != Verb.SEED) { "SEED is not a learner action" } }
                .exceptionOrNull()!!
        }

        assertEquals(HttpStatusCode.BadRequest, client.get("/boom").status)
    }

    @Test
    fun `a corrupt session is 500 under its own code, not the generic one`() = testApplication {
        throwing { CorruptSessionException("s1", "parent cycle through node n3") }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        // Its own code, so a client's retry logic and an operator's dashboard can both tell a data
        // incident from an ordinary crash. "INTERNAL" for both is the state this task inherited.
        assertEquals("CORRUPT_SESSION", response.apiError().code)
    }

    @Test
    fun `a corrupt session logs an alertable line naming the session`() {
        val appender = attachAppender()
        try {
            testApplication {
                throwing { CorruptSessionException("s-42", "parent cycle through node n3") }
                client.get("/boom")
            }
        } finally {
            detachAppender(appender)
        }

        val event = assertNotNull(
            appender.list.firstOrNull { it.level == Level.ERROR },
            "corruption was not logged at ERROR: ${appender.list.map { it.formattedMessage }}",
        )
        // `sessionId` is a field on CorruptSessionException rather than only a message fragment for
        // exactly this reason: the alert has to carry the document somebody must go and look at.
        assertTrue(event.formattedMessage.contains("s-42"), "log line names no session: ${event.formattedMessage}")
        assertTrue(
            event.formattedMessage.contains(CORRUPT_SESSION_ALERT),
            "log line carries no greppable token: ${event.formattedMessage}",
        )
    }

    @Test
    fun `an unexpected failure is 500 INTERNAL and is logged`() {
        val appender = attachAppender()
        try {
            testApplication {
                throwing { RuntimeException("a genuine bug") }

                val response = client.get("/boom")

                assertEquals(HttpStatusCode.InternalServerError, response.status)
                assertEquals("INTERNAL", response.apiError().code)
                assertFalse(response.bodyAsText().contains("a genuine bug"), "internal detail leaked")
            }
        } finally {
            detachAppender(appender)
        }

        assertTrue(appender.list.any { it.level == Level.ERROR }, "an unhandled error was not logged")
    }

    @Test
    fun `a not-found raised by the API layer is 404 and echoes its message`() = testApplication {
        throwing { ResourceNotFoundException("no topic with slug 'nope'") }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.apiError().code)
        // API-authored, so it may be echoed — it names only what the client already sent.
        assertTrue(response.bodyAsText().contains("nope"))
    }

    @Test
    fun `a not-found raised by the framework is 404 but its message is not echoed`() = testApplication {
        // The echo allowlist has to key on AUTHORSHIP, not on a framework class. Keyed on Ktor's
        // NotFoundException, the moment Task 1.12 — or any plugin — raises one carrying a session id
        // and a principal, the disclosure policy this task exists to establish is undone and no test
        // notices. A type of our own makes "we wrote this message" checkable.
        throwing { NotFoundException("session s-42 for principal anon:9c1f not found") }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.apiError().code)
        assertFalse(response.bodyAsText().contains("s-42"), "a framework message was echoed")
        assertFalse(response.bodyAsText().contains("anon:9c1f"), "a principal leaked to the client")
    }

    // ------------------------------------------------------- the domain families

    @Test
    fun `a span mismatch is 400 and says which rule was broken`() = testApplication {
        throwing { SpanMismatchException("span text does not match the parent body at those offsets") }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("SPAN_MISMATCH", response.apiError().code)
        // Echoed deliberately: the injection gate's messages describe the caller's own offsets and
        // are the only way a client can work out what it got wrong.
        assertTrue(response.apiError().message.contains("offsets"))
    }

    @Test
    fun `the three session ceilings are 409 under distinct codes`() = testApplication {
        // One status, three codes. A client that wants to offer "start a new session" needs to know
        // which ceiling it hit, and 409 alone does not say.
        application {
            install(ContentNegotiation) { json() }
            installErrorMapping()
            routing {
                get("/depth") { throw DepthLimitException("a chain of 13 links exceeds the limit of 12") }
                get("/full") { throw SessionFullException("session s1 already holds 200 of 200 nodes") }
                get("/variant") { throw VariantLimitException("variant 4 is outside the permitted range 0..3") }
            }
        }

        val depth = client.get("/depth")
        val full = client.get("/full")
        val variant = client.get("/variant")

        assertEquals(HttpStatusCode.Conflict, depth.status)
        assertEquals(HttpStatusCode.Conflict, full.status)
        assertEquals(HttpStatusCode.Conflict, variant.status)
        assertEquals("DEPTH_LIMIT", depth.apiError().code)
        assertEquals("SESSION_FULL", full.apiError().code)
        assertEquals("VARIANT_LIMIT", variant.apiError().code)
        // Echoed deliberately: a ceiling message states the ceiling, which is the one thing a
        // client needs in order to explain the refusal to the learner.
        assertTrue(depth.apiError().message.contains("12"), "the depth ceiling was not echoed")
        assertTrue(full.apiError().message.contains("200"), "the node ceiling was not echoed")
        assertTrue(variant.apiError().message.contains("0..3"), "the variant range was not echoed")
    }

    @Test
    fun `a failed generation is 502 and does not echo the upstream detail`() = testApplication {
        throwing {
            GenerationFailedException(
                "upstream generation failed for exp_9f3c1d",
                IllegalStateException("401 invalid x-api-key"),
            )
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("GENERATION_FAILED", response.apiError().code)
        // The message carries an internal content key and, through the cause, whatever the upstream
        // said — which on an auth failure is a statement about our own credentials.
        assertFalse(response.bodyAsText().contains("exp_9f3c1d"), "a content key leaked to the client")
        assertFalse(response.bodyAsText().contains("x-api-key"), "an upstream detail leaked to the client")
    }

    // ------------------------------------------------------- disclosure and malformed input

    @Test
    fun `an unpublished topic is not disclosed by the error body`() = testApplication {
        // SessionService.create raises ONE type for "unknown topic" and "topic exists but is not
        // published", explicitly so that a client cannot enumerate unpublished topics — and its
        // KDoc ends "The messages differ, and they are for the log — Task 1.11 must not echo them."
        // Echoing `cause.message` for IllegalArgumentException, as the brief did, undoes that.
        throwing { IllegalArgumentException("topic organic-chemistry is DRAFT, not PUBLISHED") }

        val body = client.get("/boom").bodyAsText()

        assertFalse(body.contains("DRAFT"), "publication status leaked: $body")
        assertFalse(body.contains("organic-chemistry"), "an unpublished slug leaked: $body")
    }

    @Test
    fun `a malformed request body is 400 and not 500`() = testApplication {
        throwing { RuntimeException("unused") }

        val response = client.post("/echo") {
            contentType(ContentType.Application.Json)
            setBody("{ this is not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_REQUEST", response.apiError().code)
    }

    @Test
    fun `a body missing a required field is 400 and not 500`() = testApplication {
        throwing { RuntimeException("unused") }

        val response = client.post("/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"nottext":"x"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a content type nothing can decode is 415 and not 500`() = testApplication {
        // Ktor raises UnsupportedMediaTypeException, whose documented meaning is 415. Left to the
        // catch-all it becomes a server fault, which tells the client to retry something that can
        // never work.
        throwing { RuntimeException("unused") }

        val response = client.post("/echo") {
            contentType(ContentType.Application.OctetStream)
            setBody("""{"text":"x"}""")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals("UNSUPPORTED_MEDIA_TYPE", response.apiError().code)
    }

    @Test
    fun `a conversion failure raised directly is 400`() = testApplication {
        // Belt and braces. Today ContentNegotiation catches ContentConvertException and rethrows it
        // as BadRequestException, so this arm is unreachable through the plugin — but the two types
        // are independent (`BadRequestException` extends `Exception`, not `IllegalArgumentException`
        // as is easy to assume), and a converter reaching the pipeline unwrapped must not be a 500.
        throwing { JsonConvertException("Illegal input") }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_REQUEST", response.apiError().code)
    }

    // ------------------------------------------------------- end to end through the real service

    @Test
    fun `an unknown session id from the real SessionService is 404`() = testApplication {
        // The synthetic throws above pin the mapping; this pins the composition. It is the only
        // assertion in the suite that would still catch SessionService being changed to raise some
        // other type for a session that is not there.
        val sessions = TestFixtures.sessions()
        application {
            install(ContentNegotiation) { json() }
            installErrorMapping()
            routing {
                get("/prepare") {
                    sessions.prepare(
                        sessionId = "no-such-session",
                        parentNodeId = "no-such-node",
                        selection = SpanSelection("microscopic realm", 0, 17),
                        verb = Verb.EXPLAIN,
                        requestedVariant = null,
                    )
                    call.respondText("unreachable")
                }
            }
        }

        val response = client.get("/prepare")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.apiError().code)
    }

    private fun attachAppender(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(ERROR_MAPPING_LOGGER) as ch.qos.logback.classic.Logger).addAppender(appender)
        return appender
    }

    private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
        (LoggerFactory.getLogger(ERROR_MAPPING_LOGGER) as ch.qos.logback.classic.Logger).detachAppender(appender)
    }
}
