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
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
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
import kotlin.test.assertContains
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
        val sessions = TestFixtures.sessionApp().sessions
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

    // ------------------------------------------------------- the same taxonomy, mid-stream

    /**
     * Every type the streaming mapping is expected to recognise. The list is deliberately the one
     * `sessionRoutes` can actually raise after the first byte has gone out: the model, the
     * validator, and the append that records the learner's step.
     */
    /**
     * How many `exception<...>` arms `installErrorMapping` registers, excluding the `Throwable`
     * catch-all. Hand written on purpose; see the test that reads it.
     */
    private val REGISTERED_EXCEPTION_ARMS = 13

    /**
     * Every type `installErrorMapping` registers an `exception<...>` arm for, read out of the source.
     *
     * Reading a source file from a test is unusual and is the point: the registration list is the
     * authority, and no runtime API exposes it — `StatusPagesConfig` keeps its handler map private.
     *
     * Comments are stripped first. This file argues about the mapping at length, and an
     * `exception<UnsupportedMediaTypeException>` written *in prose* to explain why that arm does not
     * exist would otherwise be scanned as a registration. Found by this scan on its first run, which
     * is a fair advertisement for it.
     */
    private fun registeredExceptionArms(): Set<String> {
        val source = java.io.File("src/main/kotlin/com/mytetz/api/ErrorMapping.kt")
        assertTrue(source.isFile, "cannot find ErrorMapping.kt from ${java.io.File(".").absolutePath}")

        val code = source.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        return Regex("""exception<([A-Za-z0-9_]+)>""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toSet() - "Throwable" // the catch-all is `else`, not an arm
    }

    private val taxonomy: List<Throwable> = listOf(
        SpanMismatchException("span text does not match the parent body at those offsets"),
        DepthLimitException("a chain of 9 links exceeds the limit of 8"),
        SessionFullException("session s1 already holds 200 of 200 nodes"),
        VariantLimitException("variant 4 is outside the permitted range 0..3"),
        SessionNotFoundException("s-vanished"),
        ResourceNotFoundException("no topic with slug 'nope'"),
        NotFoundException("session s1 for principal anon:p not found"),
        CorruptSessionException("s-broken", "parent cycle through node n3"),
        GenerationFailedException("upstream generation failed for k1"),
        BadRequestException("body was not json"),
        // The concrete types the pipeline actually raises, not their registered supertypes — the
        // coverage check below walks the hierarchy, so `JsonConvertException` stands in for
        // `ContentConvertException`. `ContentTransformationException` is abstract, hence the object.
        JsonConvertException("Illegal input"),
        object : ContentTransformationException("no converter handled the body") {},
        IllegalArgumentException("no node n9 in session s1"),
        IllegalStateException("a genuine bug"),
    )

    /**
     * The drift detector's own drift detector.
     *
     * [taxonomy] is hand written, and a hand-written list is exactly the mechanism that fails
     * silently: the arm-for-arm test below can only compare the types somebody remembered to put in
     * it, so a type registered in `installErrorMapping` and forgotten in [sseErrorFor] would be
     * `INTERNAL` inside a stream with nothing objecting. This reads the registrations out of the
     * source and requires each one to be represented here.
     */
    @Test
    fun `the streaming mapping covers every type the status mapping registers`() {
        val registered = registeredExceptionArms()

        // By hierarchy, not by exact type: StatusPages routes a throwable to the closest registered
        // handler up its class chain, so a fixture of a *subtype* genuinely exercises the arm its
        // supertype registers.
        val covered = taxonomy.flatMap { cause ->
            generateSequence<Class<*>>(cause.javaClass) { it.superclass }.map { it.simpleName }.toList()
        }.toSet()

        // A checked-in count, not merely "not empty". `isNotEmpty` catches a TOTAL parse failure and
        // nothing else: an unbalanced comment marker, a syntax change, or an arm moved to another
        // file yields a SUBSET, and a subset makes this test report coverage it never checked. A
        // count is a hand-written fact, so it fails loudly when the registrations change — which is
        // exactly when somebody should be looking at this list.
        assertEquals(
            REGISTERED_EXCEPTION_ARMS,
            registered.size,
            "installErrorMapping's registrations changed; update REGISTERED_EXCEPTION_ARMS and the " +
                "taxonomy above deliberately rather than letting this test silently narrow: $registered",
        )
        assertContains(registered, "SessionNotFoundException")
        assertContains(registered, "CorruptSessionException")

        assertEquals(
            emptySet(),
            registered - covered,
            "installErrorMapping registers these types and the streaming taxonomy does not cover " +
                "them, so they would fall to INTERNAL inside a stream",
        )
    }

    /**
     * The invariant `installErrorMapping`'s KDoc states and nothing enforced: apart from the
     * `Throwable` catch-all, **no registered type is a supertype of another registered type**.
     *
     * Its own test, and not an extra assertion on the coverage test above, because the two fail for
     * different reasons and a mutation that breaks only this one has to be able to say so.
     *
     * The coverage test needs this. It matches by hierarchy — it has to, since StatusPages routes a
     * throwable to the closest registered handler up its chain — so without this invariant a
     * registered *supertype* counts as covered by a *subtype* fixture, while the arm-for-arm test
     * only ever exercises the subtype and a bare supertype instance still falls to INTERNAL.
     */
    @Test
    fun `no registered type is a supertype of another registered type`() {
        val registered = registeredExceptionArms()

        taxonomy.forEach { cause ->
            val onThisChain = generateSequence<Class<*>>(cause.javaClass) { it.superclass }
                .map { it.simpleName }
                .filter { it in registered }
                .toList()
            assertTrue(
                onThisChain.size <= 1,
                "${cause::class.simpleName} has two registered types in its hierarchy ($onThisChain), " +
                    "so handler selection now depends on StatusPages' most-specific walk, and the " +
                    "coverage test can be satisfied without the supertype ever being exercised",
            )
        }
    }

    @Test
    fun `the streaming mapping answers with the same code as the status mapping, arm for arm`() = testApplication {
        // StatusPages cannot rewrite a response that has already begun streaming, so the explain
        // endpoint has to reproduce this mapping inside the SSE block. The version this task was
        // handed reproduced it as a private `codeFor` that sent BOTH SessionNotFoundException and
        // CorruptSessionException to "INTERNAL" — one task after the arms above were written for
        // them, and with nothing anywhere comparing the two. This is that comparison.
        application {
            install(ContentNegotiation) { json() }
            installErrorMapping()
            routing {
                get("/boom/{i}") { throw taxonomy[call.parameters["i"]!!.toInt()] }
            }
        }

        taxonomy.forEachIndexed { i, cause ->
            val throughStatusPages = client.get("/boom/$i").apiError().code
            assertEquals(
                throughStatusPages,
                sseErrorFor(cause).code,
                "${cause::class.simpleName} is coded differently depending on whether the response " +
                    "had already begun",
            )
        }
    }

    @Test
    fun `the streaming mapping keeps the same echo policy`() = testApplication {
        // Echoed: written by us, about the caller's own input, and the only way a client can explain
        // the refusal to the learner.
        assertEquals(
            "span text does not match the parent body at those offsets",
            sseErrorFor(taxonomy.first { it is SpanMismatchException }).message,
        )
        // Not echoed. GenerationFailedException's message carries an internal content key and,
        // through its cause, whatever the upstream said — which on a bad key is a statement about
        // our own credentials.
        assertFalse(sseErrorFor(taxonomy.first { it is GenerationFailedException }).message.contains("k1"))
        // Not echoed: ContextChain's "no node n9 in session s1" confirms the contents of a session
        // over a channel that has passed the ownership check and need not confirm anything further.
        assertFalse(sseErrorFor(taxonomy.first { it is IllegalArgumentException }).message.contains("n9"))
        assertFalse(sseErrorFor(taxonomy.first { it is IllegalStateException }).message.contains("a genuine bug"))
        // Ktor's own not-found, whose message a route is free to write anything into — Task 1.11
        // added `ResourceNotFoundException` precisely so that "we authored this" is a property of
        // the type. The same split has to hold here or the allowlist is only half a policy.
        assertFalse(sseErrorFor(taxonomy.first { it is NotFoundException }).message.contains("anon:p"))
        assertEquals(
            "no topic with slug 'nope'",
            sseErrorFor(taxonomy.first { it is ResourceNotFoundException }).message,
        )
        // No quota refusal ever reaches this function: those are decided before the stream opens,
        // so they carry a status code and a Retry-After header instead.
        assertTrue(taxonomy.all { sseErrorFor(it).retryAfter == null })
    }

    @Test
    fun `a corruption discovered mid-stream still raises the operator alert`() {
        val appender = attachAppender()
        val error = try {
            sseErrorFor(CorruptSessionException("s-77", "a node points at an explanation that is gone"))
        } finally {
            detachAppender(appender)
        }

        assertEquals("CORRUPT_SESSION", error.code)
        assertFalse(error.message.contains("s-77"), "the session id leaked into the response body")
        val event = assertNotNull(
            appender.list.firstOrNull { it.level == Level.ERROR },
            "corruption inside a stream was not logged at ERROR: ${appender.list.map { it.formattedMessage }}",
        )
        assertTrue(event.formattedMessage.contains(CORRUPT_SESSION_ALERT))
        assertTrue(event.formattedMessage.contains("s-77"))
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
