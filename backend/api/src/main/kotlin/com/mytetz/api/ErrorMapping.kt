package com.mytetz.api

import com.mytetz.graph.GenerationFailedException
import com.mytetz.session.CorruptSessionException
import com.mytetz.session.DepthLimitException
import com.mytetz.session.SessionFullException
import com.mytetz.session.SessionNotFoundException
import com.mytetz.session.SpanMismatchException
import com.mytetz.session.VariantLimitException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * The one wire shape for every failure.
 *
 * [code] is the stable, machine-readable half and the only part a client may branch on. [message] is
 * for a human reading a log or a console, and is **only** ever the text of an exception this
 * codebase raises for the caller's benefit — see the echo policy on [installErrorMapping].
 *
 * [retryAfter] is seconds, and is populated by the quota refusals Task 1.12 adds; it is here now so
 * that the shape of an error does not change under a client halfway through slice 1.
 */
@Serializable
data class ApiError(val code: String, val message: String, val retryAfter: Long? = null)

/**
 * A resource this API looked for and did not find, with a message written **by us** and safe to
 * return.
 *
 * A type of our own rather than Ktor's `NotFoundException`, and the difference is the echo policy
 * below. That policy is an allowlist of "messages this codebase authored for the caller's benefit",
 * and keying it on a *framework* type gets the property wrong: any plugin, and Task 1.12's session
 * routes, can raise a `NotFoundException` carrying whatever they like — for instance
 * `"session $id for principal $p not found"` — and the disclosure rule this task exists to establish
 * would be undone with no test noticing. Authorship is exactly the thing the type should encode.
 */
class ResourceNotFoundException(message: String) : Exception(message)

/** The name an operator alerts on. Present in the log line and nowhere in any response body. */
internal const val CORRUPT_SESSION_ALERT: String = "CORRUPT_SESSION"

/** A logger of this module's own, so an alert can be filtered to it rather than to all of Ktor. */
internal const val ERROR_MAPPING_LOGGER: String = "com.mytetz.api.ErrorMapping"

private val log = LoggerFactory.getLogger(ERROR_MAPPING_LOGGER)

/**
 * One body for every "we cannot read what you sent". Deliberately says nothing about *which* field
 * or *which* parser complained: those messages quote the request back, and the request is attacker
 * controlled. The detail goes to the log.
 */
private val INVALID_REQUEST = ApiError("INVALID_REQUEST", "the request was not valid for this endpoint")

/**
 * Turns the domain's exceptions into status codes, and does it by **type** — never by inspecting a
 * message — which is the entire reason Tasks 1.9 and 1.10 built a three-way taxonomy instead of one
 * exception class with different strings in it.
 *
 * ## The three-way split, and why each arm exists
 *
 * | condition                                    | type                        | answer |
 * |----------------------------------------------|-----------------------------|--------|
 * | the thing you named is gone or never existed | [SessionNotFoundException], [NotFoundException] | 404 |
 * | your request was malformed                   | [IllegalArgumentException]  | 400 |
 * | our stored data is broken                    | [CorruptSessionException]   | 500 + alert |
 *
 * All three were previously one answer. [SessionNotFoundException] descends from
 * `NoSuchElementException`, so without its own arm it reaches the `Throwable` catch-all and a stale
 * session id — the commonest client error this API will ever serve — is reported as a server fault.
 * [CorruptSessionException] descends from `IllegalStateException` and does the same, which makes a
 * genuine data incident indistinguishable from an ordinary crash. And collapsing the second row into
 * the first, by mapping every [IllegalArgumentException] to 404, answers "your request was
 * malformed" with "that does not exist" — including for `require(verb != Verb.SEED)` and for a node
 * id the caller invented, neither of which is a missing resource.
 *
 * ## Which messages are echoed, and which are not
 *
 * Echoing `cause.message` by default is a disclosure bug, not a convenience. `SessionService.create`
 * raises **one** type for "no such topic" and "that topic is not published", explicitly so a client
 * cannot enumerate unpublished topics, and its KDoc ends: *"The messages differ, and they are for
 * the log — Task 1.11 must not echo them."* [GenerationFailedException]'s message carries an
 * internal content key and, through its cause, whatever the upstream said — which on a bad key is a
 * statement about our own credentials.
 *
 * So the rule is a allowlist, not a denylist:
 *
 * - **Echoed** — [SpanMismatchException] and the three ceiling types. Every one of these is raised
 *   *at* the caller, describes the caller's own input or the limit it crossed, and is the only way a
 *   client can explain the refusal to the learner.
 * - **Echoed** — [ResourceNotFoundException], which exists precisely so that "this API wrote this
 *   message" is a property of the *type* rather than an assumption about a framework class.
 * - **Not echoed** — everything else, including Ktor's own `NotFoundException`. The full detail goes
 *   to the log, where it belongs.
 *
 * ## Handler selection
 *
 * Ktor's StatusPages routes a throwable to the closest registered handler up its class hierarchy.
 * This configuration deliberately never depends on the precision of that walk: apart from the
 * `Throwable` catch-all, **no registered type is a supertype of another registered type**. The one
 * case that would have needed it — a 415 being more specific than a 400 — turned out not to be an
 * exception at all: ContentNegotiation answers an unhandled `Content-Type` itself, with a bodyless
 * 415, so that one is decorated through `status(...)` instead. Measured, not assumed; an
 * `exception<UnsupportedMediaTypeException>` arm never ran.
 *
 * ## The client-error types Ktor raises are not in the hierarchy you would guess
 *
 * `io.ktor.server.plugins.BadRequestException` extends **`Exception`**, not
 * [IllegalArgumentException]. It is an easy and expensive assumption to get wrong: a mapping that
 * registers only [IllegalArgumentException] and assumes it inherits the rest answers **every**
 * malformed request body with a 500, because a body that will not deserialise arrives as
 * `BadRequestException` and falls straight past it to the catch-all. Verified against the published
 * API reference for `io.ktor.server.plugins` and `io.ktor.serialization`, and pinned by test.
 */
fun Application.installErrorMapping() {
    install(StatusPages) {

        // -------------------------------------------------- the caller's request was malformed

        exception<SpanMismatchException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("SPAN_MISMATCH", cause.message.orEmpty()))
        }

        /*
         * `require(...)` anywhere in the stack, and `ContextChain.pathTo`'s unknown node id.
         *
         * The message is not echoed. The known hazard of this arm is the reverse of the brief's:
         * an *internal* invariant expressed as `require` also lands here and is reported as the
         * caller's fault. That is the price of Kotlin's convention that `require` means "the caller
         * broke the contract", it is the direction that discloses least, and the log below keeps
         * the real text.
         */
        exception<IllegalArgumentException> { call, cause ->
            log.info("rejected a malformed request: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, INVALID_REQUEST)
        }

        /*
         * The same answer, for the types Ktor and the JSON converter raise on a body we cannot read.
         * Registered separately and explicitly because NONE of them descends from
         * IllegalArgumentException — see the note on the hierarchy above. Left unregistered, every
         * one of these is a 500.
         *
         * - BadRequestException      : Exception — what ContentNegotiation rethrows a failed
         *                              conversion as, so this is the live path for malformed JSON.
         * - ContentConvertException  : Exception — the converter's own type, in case it ever
         *                              reaches the pipeline unwrapped.
         * - ContentTransformationException — no converter could handle the body.
         *
         * The published API reference gives `ContentTransformationException`'s supertype as
         * `IOException`; that could not be confirmed against the artifact on the classpath and
         * nothing here depends on it, since the type is registered directly. Recorded as unverified
         * rather than asserted.
         */
        exception<BadRequestException> { call, cause ->
            log.info("rejected a malformed request body: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, INVALID_REQUEST)
        }
        exception<ContentConvertException> { call, cause ->
            log.info("rejected an unconvertible request body: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, INVALID_REQUEST)
        }
        exception<ContentTransformationException> { call, cause ->
            log.info("rejected an untransformable request body: {}", cause.message)
            call.respond(HttpStatusCode.BadRequest, INVALID_REQUEST)
        }

        /*
         * A Content-Type no converter handles never reaches an exception handler: ContentNegotiation
         * answers 415 itself, with an empty body. The status is already right — this is only about
         * the body, so that a client parsing `ApiError` gets one from every endpoint on every
         * failure rather than an empty string on this one.
         *
         * `status` rather than `exception` for exactly that reason, and it is the documented hook
         * for decorating a status Ktor produced on its own.
         */
        status(HttpStatusCode.UnsupportedMediaType) { call, status ->
            call.respond(
                status,
                ApiError("UNSUPPORTED_MEDIA_TYPE", "this endpoint accepts application/json"),
            )
        }

        // -------------------------------------------------- the thing you named is not there

        exception<SessionNotFoundException> { call, cause ->
            // Not 500. See the table above; this is the commonest client error on the whole API.
            log.info("session {} was requested and does not exist", cause.sessionId)
            call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "no such session"))
        }

        // Ours, so the message is echoed: we wrote it, and it names only what the client sent.
        exception<ResourceNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", cause.message.orEmpty()))
        }

        // Ktor's, or a plugin's. Same status, but the message is NOT echoed — we did not write it
        // and cannot vouch for what it names. See [ResourceNotFoundException].
        exception<NotFoundException> { call, cause ->
            log.info("a framework not-found reached the mapping: {}", cause.message)
            call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "not found"))
        }

        // -------------------------------------------------- a ceiling was reached

        exception<DepthLimitException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiError("DEPTH_LIMIT", cause.message.orEmpty()))
        }
        exception<SessionFullException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiError("SESSION_FULL", cause.message.orEmpty()))
        }
        exception<VariantLimitException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiError("VARIANT_LIMIT", cause.message.orEmpty()))
        }

        // -------------------------------------------------- upstream, and our own data

        exception<GenerationFailedException> { call, cause ->
            log.warn("generation failed", cause)
            call.respond(
                HttpStatusCode.BadGateway,
                ApiError("GENERATION_FAILED", "the explanation could not be generated; try again"),
            )
        }

        /*
         * Its own code and its own log line, which is the whole reason Task 1.9 made this a distinct
         * type carrying a `sessionId` field rather than a message fragment. A dangling parent, a
         * parent cycle or a duplicate node id is a document that got written wrong: no retry fixes
         * it, no client can act on it, and somebody has to go and look at the session named here.
         *
         * Reported to the client as a 500 with nothing in it. The learner's session is broken and
         * there is nothing they can do about it; the detail is for the operator.
         */
        exception<CorruptSessionException> { call, cause ->
            log.error(
                "$CORRUPT_SESSION_ALERT sessionId={} — the stored session no longer describes a tree; " +
                    "this needs an operator, no retry will fix it",
                cause.sessionId,
                cause,
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("CORRUPT_SESSION", "this session's stored data is inconsistent and cannot be read"),
            )
        }

        exception<Throwable> { call, cause ->
            log.error("unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL", "unexpected error"))
        }
    }
}
