package com.mytetz.api

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * A fresh, empty set for a route registration's own `configMissingLogged` parameter.
 *
 * `authRoutes` and `billingRoutes` each default their own `configMissingLogged` parameter to a
 * call of this function, so each is fresh per call: the production default lives for the life of
 * that one route registration, and a test can hold and inspect its own set instead. A concurrent
 * set, since two requests can race into [buildConfiguredOrNull] at once.
 */
internal fun newConfigMissingLog(): MutableSet<String> = ConcurrentHashMap.newKeySet()

/** The logger [buildConfiguredOrNull] writes to. `AuthRoutesTest` attaches its own appender here. */
internal const val CONFIG_GATE_LOGGER: String = "com.mytetz.api.ConfigGate"

private val log = LoggerFactory.getLogger(CONFIG_GATE_LOGGER)

/**
 * The first environment-variable-shaped name in a message, such as `MYTETZ_MAIL_MODE`.
 *
 * Every factory [buildConfiguredOrNull] wraps throws a message that leads with the missing
 * variable's own name. A regex over the message finds that name without a second, hand-written
 * list of variable names to keep in step with `Components.kt`.
 *
 * ## The contract this regex depends on
 *
 * A thrown message must name its own variable before it names anything else, including a bad
 * value an operator set. `MailConfig.resolveMode` is the one resolver whose message states such a
 * value — `"$MODE_ENV must be 'resend' or 'log', was '$raw'"` — and it is safe only because
 * `MODE_ENV` leads. `MailSenderTest` and `FreemiusWebhookTest` each pin this contract on their own
 * resolver, from their own module, since this file cannot reach into either to test it directly.
 * A resolver whose message ever puts a value first breaks this contract, and its own module's
 * pinning test is what catches that, not this file.
 */
private val VARIABLE_NAME = Regex("""[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+""")

/**
 * Runs [factory]. Returns the built value, or null when [factory] throws because a required
 * environment variable is missing.
 *
 * A caller uses a null answer to pick its own "not configured" response. This function only
 * decides what reaches the log.
 *
 * The function logs one `CONFIG_MISSING` line the first time it sees a variable. [logged] holds
 * every name already logged, so a repeat call for the same variable logs nothing more — see
 * `authRoutes`' and `billingRoutes`' own KDoc for why each route registration keeps one such set
 * for the life of the deployment.
 *
 * The log line never carries [factory]'s own exception message. It carries only the variable name
 * matched out of that message by [VARIABLE_NAME]. The message can also carry the bad value an
 * operator set, and that value must never reach the log.
 *
 * One call names only the first missing variable a factory's own chain reaches. `magicLink`, for
 * instance, resolves mail mode before it resolves the public base url, so a deployment missing
 * both variables logs only the mail mode name until an operator sets it — the base url name
 * follows only on a later call. A single `CONFIG_MISSING` line is therefore a lower bound on how
 * unconfigured a deployment is, not the whole list.
 */
internal fun <T> buildConfiguredOrNull(logged: MutableSet<String>, factory: () -> T): T? = try {
    factory()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    val variable = VARIABLE_NAME.find(e.message.orEmpty())?.value ?: "UNKNOWN"
    if (logged.add(variable)) {
        log.error("CONFIG_MISSING {}", variable)
    }
    null
}
