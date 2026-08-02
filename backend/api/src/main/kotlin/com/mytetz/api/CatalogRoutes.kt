package com.mytetz.api

import com.mytetz.catalog.CatalogService
import com.mytetz.catalog.RecordOutcome
import com.mytetz.catalog.Topic
import com.mytetz.catalog.TopicRequestRepository
import com.mytetz.catalog.TopicStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * The browse shape of a topic.
 *
 * A projection rather than [Topic] itself, and deliberately: `status` and `sortWeight` are editorial
 * bookkeeping. Serialising the stored document would make both part of the wire contract by
 * accident, and `status` is only ever `PUBLISHED` on anything this route returns anyway.
 */
@Serializable
data class TopicSummary(val slug: String, val title: String, val category: String, val summary: String)

@Serializable
data class TopicRequestPayload(val text: String)

private fun Topic.toSummary() = TopicSummary(slug, title, category, summary)

private val log = LoggerFactory.getLogger("com.mytetz.api.CatalogRoutes")

/** How many topic requests one caller address may submit per [TOPIC_REQUEST_WINDOW_MILLIS]. */
const val TOPIC_REQUESTS_PER_CALLER: Int = 10

const val TOPIC_REQUEST_WINDOW_MILLIS: Long = 24L * 60 * 60 * 1000

/**
 * The largest `POST /api/topic-requests` body that will be read at all.
 *
 * The payload is one field capped at `TopicRequestRepository.MAX_TEXT_LENGTH` characters, so this is
 * an order of magnitude of headroom. It is enforced on `Content-Length` **before** `receive`,
 * because by the time a converter can object the body has already been buffered — and this endpoint
 * is public, on a 512 MB machine.
 */
const val MAX_TOPIC_REQUEST_BODY_BYTES: Long = 2_048

/**
 * `GET /api/catalog/topics`, `GET /api/catalog/topics/{slug}` and `POST /api/topic-requests`.
 *
 * ## The topic-request endpoint is public, and everything that bounds it is here or below
 *
 * It takes no account, it writes to the database, and with a curated-only catalogue it is the only
 * demand signal for what to add next — so it is worth having, and worth bounding properly. Four
 * bounds, at three different layers, because no single one of them is sufficient:
 *
 * 1. **Body size**, here, before `receive`. Nothing else stops an arbitrary allocation.
 * 2. **A per-caller rate**, here, via [FixedWindowRateLimiter] keyed on [ClientAddress] — whose KDoc
 *    states plainly what it does not cover. It is keyed on the **address and not the principal**,
 *    and that distinction is the whole point: `Principals.resolve` mints a fresh principal for any
 *    request without a valid cookie, so a per-principal limit limits only callers polite enough to
 *    return their cookie, while growing the limiter's table by one entry per request.
 * 3. **A principal**, here, via [Principals.resolve] — *not* as the rate-limit key, and not for
 *    attribution either: the log line names the address, because that is the thing a limit is
 *    enforced against. It is here purely for continuity, so that a real browser which submits a
 *    request and later starts a session carries the same principal into it rather than being issued
 *    a second one. Established after the limiter, so a refused request costs no cookie.
 * 4. **Storage**, in `TopicRequestRepository`: text length, and a cap on the number of distinct rows
 *    that evicts the least-demanded row rather than refusing new ones. That is the backstop, it is
 *    the only bound a caller cannot dodge by changing identity, and it is enforced in the repository
 *    precisely so that a future second write path cannot forget it.
 *
 * Validation of the text itself is **not** duplicated here. The repository owns it, this route maps
 * the outcome to a status, and there is exactly one place where the rule can be wrong.
 */
fun Route.catalogRoutes(
    catalog: CatalogService,
    topicRequests: TopicRequestRepository,
    cookies: PrincipalCookieConfig,
    clientAddresses: ClientAddressConfig = ClientAddressConfig(),
    topicRequestLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        limit = TOPIC_REQUESTS_PER_CALLER,
        windowMillis = TOPIC_REQUEST_WINDOW_MILLIS,
    ),
) {
    get("/api/catalog/topics") {
        val topics = catalog.listPublished(
            category = call.request.queryParameters["category"],
            query = call.request.queryParameters["q"],
        )
        call.respond(topics.map { it.toSummary() })
    }

    get("/api/catalog/topics/{slug}") {
        val slug = call.parameters["slug"].orEmpty()

        // `CatalogService.findBySlug` does not filter on status, deliberately — CatalogServiceTest
        // pins that so an admin lookup can still see a draft — which makes THIS the place where
        // publication is enforced for readers. Responding with whatever `findBySlug` returns would
        // serve every unreviewed topic to anyone who guessed its slug, invisibly, because browsing
        // filters correctly and so nothing would look wrong.
        //
        // Same 404 for "no such topic" and "not published", for the same reason SessionService.create
        // raises one type for both: a distinguishable answer is an oracle for what is in the
        // pipeline.
        val topic = catalog.findBySlug(slug)?.takeIf { it.status == TopicStatus.PUBLISHED }
            ?: throw ResourceNotFoundException("no topic with slug '$slug'")

        call.respond(topic.toSummary())
    }

    post("/api/topic-requests") {
        val declaredLength = call.request.contentLength()
        if (declaredLength == null || declaredLength > MAX_TOPIC_REQUEST_BODY_BYTES) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ApiError("PAYLOAD_TOO_LARGE", "a topic request must be under $MAX_TOPIC_REQUEST_BODY_BYTES bytes"),
            )
            return@post
        }

        // Keyed on the caller's address, NOT on the principal. See the note on this function and on
        // ClientAddress: a principal is free to re-mint, so keying on one limits nobody and grows a
        // table by one entry per request.
        val caller = ClientAddress.of(call, clientAddresses)
        if (!topicRequestLimiter.tryAcquire(caller)) {
            log.info("rate limited topic requests from {}", caller)
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiError(
                    code = "RATE_LIMITED",
                    message = "too many topic requests; try again tomorrow",
                    retryAfter = TOPIC_REQUEST_WINDOW_MILLIS / 1000,
                ),
            )
            return@post
        }

        // After the limiter, so a refused request costs no cookie. Continuity only — never the
        // bound. See the note on this function.
        Principals.resolve(call, cookies)

        val payload = call.receive<TopicRequestPayload>()
        when (topicRequests.record(payload.text)) {
            RecordOutcome.RECORDED -> call.respond(HttpStatusCode.Accepted)

            RecordOutcome.INVALID_TEXT -> call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    "INVALID_REQUEST",
                    "text must be 1-${TopicRequestRepository.MAX_TEXT_LENGTH} characters",
                ),
            )
        }
    }
}
