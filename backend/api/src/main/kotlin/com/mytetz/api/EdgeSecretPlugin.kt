package com.mytetz.api

import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import java.security.MessageDigest

private val log = LoggerFactory.getLogger("com.mytetz.api.EdgeSecret")

/**
 * The header name a Cloudflare Transform Rule adds to every request that reaches the zone.
 *
 * Issue #68 proposes this exact name. This is a constant, not a literal in two places. A rename
 * then stays a one-line change. [EdgeSecretConfig] holds the value this header must carry.
 */
const val EDGE_SECRET_HEADER: String = "X-Mytetz-Edge"

/**
 * The first path segment [installEdgeSecret] checks for, once each segment is read the way
 * Ktor's own router reads it. See that function's own KDoc, "Reading the path the way the router
 * reads it", for why a segment comparison replaces a raw string comparison here.
 */
private const val API_SEGMENT: String = "api"

/**
 * The second segment of the one path under [API_SEGMENT] that [installEdgeSecret] never blocks.
 * See that function's own KDoc for the reason.
 */
private const val HEALTH_SEGMENT: String = "health"

/**
 * Encoded spellings of a slash or a backslash. [installEdgeSecret]'s second line of defence
 * refuses a request under `/api` whose raw path holds any of these, on no path this project's own
 * routes need. See that function's own KDoc, "A second line of defence", for the reason.
 */
private val ENCODED_SLASH_OR_BACKSLASH: List<String> = listOf("%2F", "%2f", "%5C", "%5c")

/**
 * Whether a request under `/api/` must carry [EDGE_SECRET_HEADER], and the value it must carry.
 *
 * ## A null [secret] means "exactly as today"
 *
 * [secret] is null when [EDGE_SECRET_ENV] is unset or blank. A production deployment carries no
 * such variable yet. A merge of this check therefore changes nothing today. The check turns on
 * only once an operator sets both the Cloudflare Transform Rule and this variable together.
 * [installEdgeSecret] reads a null [secret] and installs no interceptor at all.
 *
 * ## A short secret is a configuration error
 *
 * [MIN_EDGE_SECRET_LENGTH] matches `PrincipalCookieConfig.MIN_SIGNING_KEY_LENGTH`, for the same
 * reason: a header value short enough to guess is not a secret. The safe failure is to refuse to
 * start, not to run a check nobody can trust. The message below names the variable and the length
 * of the value. It never names the value itself.
 */
data class EdgeSecretConfig(
    val secret: String? = resolveEdgeSecret(System.getenv(EDGE_SECRET_ENV)),
) {

    init {
        // The resolver above already trims the raw value and turns an empty one into null. A
        // caller can still construct this class directly with a short value, so the length check
        // runs here too. `PrincipalCookieConfig`'s own `init` guards its signing key the same way.
        secret?.let {
            require(it.length >= MIN_EDGE_SECRET_LENGTH) {
                "$EDGE_SECRET_ENV must be at least $MIN_EDGE_SECRET_LENGTH characters, was ${it.length}"
            }
        }
    }

    /** True when [installEdgeSecret] must check every request under `/api/`. */
    val isOn: Boolean get() = secret != null

    companion object {

        const val EDGE_SECRET_ENV: String = "MYTETZ_EDGE_SECRET"

        /** 32 characters. This matches the width the cookie signing key requires. */
        const val MIN_EDGE_SECRET_LENGTH: Int = 32

        /**
         * Blank means "not set". This trims the raw value for the same reason
         * `PrincipalCookieConfig.resolveSigningKey` trims its own: a fly secret, or a hand-edited
         * value, often carries a trailing newline.
         */
        internal fun resolveEdgeSecret(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Refuses a request the router can dispatch to a handler under `/api` when it does not carry
 * [EDGE_SECRET_HEADER] with the exact value [EdgeSecretConfig.secret] names, while
 * [EdgeSecretConfig.isOn] is true. The one exception is the exact health path.
 *
 * ## Where this runs, and why nothing runs before it
 *
 * The check is an `intercept` on [ApplicationCallPipeline.Plugins]. This is the same phase
 * `installNoIndex` uses in `NoIndexPlugin.kt`. This phase runs before
 * [ApplicationCallPipeline.Call], the phase Ktor's routing feature owns. A refusal calls
 * `finish()`, which stops the pipeline there. No route handler runs. No session lookup, no rate
 * limiter and no database call runs either, for a refused request.
 *
 * `/api/billing/webhook` is not an exception. Freemius calls the real, Cloudflare-fronted
 * `mytetz.com` address. Its request always passes through Cloudflare, so it always carries the
 * header. This check runs first, and refuses a missing or wrong header, before
 * `FreemiusWebhook.verify` reads one byte of the body.
 *
 * ## Why this stays one application-level check, and not a route-scoped one
 *
 * The alternative a route-scoped plugin, installed only on a parent route every `/api` route
 * stands under would tie the check to the router by construction, so the two could never read a
 * path two different ways. It would need every `/api` route in this project to move under that one
 * parent, which would need a change to `BillingRoutes.kt`: its routes are registered with an
 * absolute path such as `/api/billing/webhook`, not a path relative to a parent `route("/api")`.
 * That file, and `FaqRoutes.kt`, are owned by other work at the same time as this one, so this
 * check stays the one thing it can be without touching either: an application-level interceptor
 * that reads a path exactly the way the router does, stated and pinned below.
 *
 * ## Reading the path the way the router reads it
 *
 * An earlier version of this check compared the raw text of [io.ktor.server.request.path] against
 * the literal string `/api/`. A security review of issue #68 found the defect this left open:
 * `call.request.path()` returns the raw request line, but Ktor's router does not match a route
 * against that raw text. It splits the path on `/`, drops each empty segment, and decodes each
 * remaining segment once with [decodeURLPart] — confirmed by reading `RoutingPath.Companion.parse`
 * in `ktor-server-core-jvm-3.1.2.jar`, and by a `testApplication` exploration against a real route.
 * A raw path such as `/%61pi/sessions` therefore does not start with `/api/`, so the old check let
 * it through unguarded — and the router still decoded it to the segments `api`, `sessions` and
 * dispatched it to the real handler. A raw path such as `//api/sessions` carried the same defect
 * through a dropped empty segment rather than a decode.
 *
 * So this check now reads the path the same way: it splits on `/`, drops each empty segment, and
 * decodes the segments it needs with [decodeURLPart] before it compares them. This makes it at
 * least as strict as the router — a path the router can dispatch under `/api` always reads as
 * `api` here too — even where it is *more* strict than the router needs, for instance a trailing
 * slash after a real segment. A request the router would 404 either way costs nothing extra by
 * also needing the header first.
 *
 * The health exemption follows the same reading: the path must decode to *exactly* the two
 * segments `api` and `health`, no more and no fewer. `/api/health%2F..%2Fsessions` and
 * `/api/health/../sessions` each read as more than two segments, or as a second segment that does
 * not decode to `health`, so neither counts as the health path.
 *
 * ## Fail closed on a segment that will not decode
 *
 * [decodeURLPart] throws [URLDecodeException] on a malformed percent sequence. When the first
 * segment throws, this check cannot decide whether the router would read it as `api`, so it
 * refuses rather than guesses. A segment beyond the first that throws only affects the health
 * exemption, and a segment this check cannot read as `health` is, correctly, not `health`: the
 * request then still needs the header, the same as any other path under `/api`.
 *
 * ## A second line of defence
 *
 * With the first segment already read as `api`, this check also refuses a request whose raw path
 * holds `%2F`, `%2f`, `%5C` or `%5c`. No route in this project needs an encoded slash or backslash
 * inside a segment, so this costs nothing today. It also does not depend on this function reading
 * every later segment correctly, or on how a later Ktor version might join a decoded segment back
 * into the path.
 *
 * ## What stays open
 *
 * A path whose first segment does not read as [API_SEGMENT] is never checked: a public page, a
 * static file and the SPA shell all reach a learner or a crawler through Cloudflare anyway, and a
 * direct visit of `mytetz.fly.dev` gives a caller no power over any of them.
 *
 * ## The comparison, and what the log never carries
 *
 * [MessageDigest.isEqual] compares the two byte arrays in constant time. `Principal.kt`'s own
 * `constantTimeEquals` states the same reason: a fast-fail comparison between a value this app
 * holds and a value the caller sent is a byte-at-a-time oracle on the secret. Header names carry
 * no case, so `call.request.headers` already matches [EDGE_SECRET_HEADER] regardless of the case
 * the caller sent it in. The value comparison below stays exact, so a value that differs only in
 * case is a wrong value. Segment comparison stays exact too, the same case rule the router itself
 * uses — confirmed by `/API/sessions` and `/Api/health` both failing to dispatch in the same
 * exploration.
 *
 * The log never carries the header value or the secret, on any branch. It carries only the path
 * and the fact of the refusal.
 */
fun Application.installEdgeSecret(config: EdgeSecretConfig = EdgeSecretConfig()) {
    log.info("the edge secret check is {}", if (config.isOn) "on" else "off")

    val secret = config.secret ?: return
    val secretBytes = secret.toByteArray(Charsets.UTF_8)

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()

        // Logs the path, never the header or the secret, answers the shared refusal body, and
        // stops the pipeline. A local function, not a top-level one: it needs `finish()`, which
        // only this `intercept` block's own receiver carries.
        suspend fun refuse() {
            log.warn("EDGE_SECRET_REFUSED path={}", path)
            call.respond(
                HttpStatusCode.Forbidden,
                ApiError("EDGE_SECRET_REQUIRED", "this request did not come through the edge"),
            )
            finish()
        }

        val rawSegments = path.split('/').filter { it.isNotEmpty() }
        val firstSegment = rawSegments.firstOrNull()?.let { decodeSegmentOrNull(it) }

        if (rawSegments.isNotEmpty() && firstSegment == null) {
            // The first segment will not decode. The router's own decode would also fail, but
            // this check cannot rely on that to hold in a later version. It cannot decide, so it
            // refuses — see "Fail closed on a segment that will not decode" above.
            refuse()
            return@intercept
        }
        if (firstSegment != API_SEGMENT) return@intercept

        if (ENCODED_SLASH_OR_BACKSLASH.any { path.contains(it) }) {
            refuse()
            return@intercept
        }

        val isExactHealthPath = rawSegments.size == 2 &&
            decodeSegmentOrNull(rawSegments[1]) == HEALTH_SEGMENT
        if (isExactHealthPath) return@intercept

        val header = call.request.headers[EDGE_SECRET_HEADER]
        val carriesTheSecret = header != null &&
            MessageDigest.isEqual(header.toByteArray(Charsets.UTF_8), secretBytes)

        if (!carriesTheSecret) refuse()
    }
}

/**
 * [String.decodeURLPart], or null on a malformed percent sequence.
 *
 * `internal`, not `private`: `EdgeSecretPluginTest` calls this directly to pin the fail-closed
 * rule on a malformed segment. A real HTTP request cannot easily carry one to this project's own
 * test client, since the client's own `Url` builder rejects a bad percent sequence before it ever
 * sends the request — see that test's own comment.
 */
internal fun decodeSegmentOrNull(segment: String): String? =
    try {
        segment.decodeURLPart()
    } catch (e: URLDecodeException) {
        null
    }
