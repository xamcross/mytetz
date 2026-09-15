package com.mytetz.api

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [buildConfiguredOrNull] against real, throwing resolvers — not a hand-written message.
 *
 * `AuthRoutesTest` and `BillingRoutesTest` both prove the surrounding route behaviour with a
 * factory that throws a message the test itself writes, such as `error("MYTETZ_MAIL_MODE is not
 * set")`. That proves the route, but not the regex: a resolver could change its own message
 * shape, and a synthetic message would never notice. The two tests below call a real resolver in
 * this same module, with no variable set, so [buildConfiguredOrNull] reads the exact message
 * production code throws.
 */
class ConfigGateTest {

    @Test
    fun `extracts the variable name from Components' own resolvePublicBaseUrl failure`() {
        val logged = ConcurrentHashMap.newKeySet<String>()

        val result = buildConfiguredOrNull(logged) { Components.resolvePublicBaseUrl(null) }

        assertNull(result)
        assertEquals(setOf(Components.PUBLIC_BASE_URL_ENV), logged)
    }

    @Test
    fun `extracts the variable name from FreemiusApiConfig's own resolveRequired failure`() {
        val logged = ConcurrentHashMap.newKeySet<String>()

        val result = buildConfiguredOrNull(logged) {
            FreemiusApiConfig.resolveRequired(FreemiusApiConfig.API_KEY_ENV, null)
        }

        assertNull(result)
        assertEquals(setOf(FreemiusApiConfig.API_KEY_ENV), logged)
    }
}
