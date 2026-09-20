package com.mytetz.api

import io.ktor.http.HttpStatusCode
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
 * The path prefix [installEdgeSecret] checks. A path outside this prefix stays open. See that
 * function's own KDoc for the reason.
 */
private const val API_PATH_PREFIX: String = "/api/"

/**
 * The one path under [API_PATH_PREFIX] that [installEdgeSecret] never blocks. See that function's
 * own KDoc for the reason.
 */
private const val HEALTH_PATH: String = "/api/health"

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
 * Refuses a request under `/api/` when it does not carry [EDGE_SECRET_HEADER] with the exact
 * value [EdgeSecretConfig.secret] names, while [EdgeSecretConfig.isOn] is true.
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
 * ## What stays open
 *
 * A path outside [API_PATH_PREFIX] is never checked. A public page, a static file and the SPA
 * shell all reach a learner or a crawler through Cloudflare anyway. A direct visit of
 * `mytetz.fly.dev` gives a caller no power over any of them. [HEALTH_PATH] is the one path under
 * the prefix that stays open too, on every host: fly's own health check calls it on the internal
 * port, a route that never passes through Cloudflare at all.
 *
 * ## The comparison, and what the log never carries
 *
 * [MessageDigest.isEqual] compares the two byte arrays in constant time. `Principal.kt`'s own
 * `constantTimeEquals` states the same reason: a fast-fail comparison between a value this app
 * holds and a value the caller sent is a byte-at-a-time oracle on the secret. Header names carry
 * no case, so `call.request.headers` already matches [EDGE_SECRET_HEADER] regardless of the case
 * the caller sent it in. The value comparison below stays exact, so a value that differs only in
 * case is a wrong value.
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
        if (!path.startsWith(API_PATH_PREFIX) || path == HEALTH_PATH) return@intercept

        val header = call.request.headers[EDGE_SECRET_HEADER]
        val carriesTheSecret = header != null &&
            MessageDigest.isEqual(header.toByteArray(Charsets.UTF_8), secretBytes)

        if (!carriesTheSecret) {
            log.warn("EDGE_SECRET_REFUSED path={}", path)
            call.respond(
                HttpStatusCode.Forbidden,
                ApiError("EDGE_SECRET_REQUIRED", "this request did not come through the edge"),
            )
            finish()
        }
    }
}
