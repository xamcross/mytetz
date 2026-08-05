package com.mytetz.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port resolver.
 *
 * `main()` read `System.getenv("PORT")?.toInt()`, which throws on any value that is not a number.
 * That made `PORT` the one config in the project able to stop the process at boot. Every other
 * resolver falls back to its default, on the stated grounds that a typo in a deployment variable
 * must not take the server down.
 */
class ApplicationTest {

    @Test
    fun `an absent or blank port gives the default`() {
        assertEquals(DEFAULT_PORT, resolvePort(null))
        assertEquals(DEFAULT_PORT, resolvePort(""))
        assertEquals(DEFAULT_PORT, resolvePort("   "))
    }

    @Test
    fun `a valid port is used, and surrounding whitespace does not defeat it`() {
        assertEquals(3000, resolvePort("3000"))
        // `fly secrets set` and a hand-edited `.env` both leave a trailing newline routinely. The
        // same reason `PrincipalCookieConfig.resolveSigningKey` trims.
        assertEquals(8080, resolvePort(" 8080 \n"))
        assertEquals(65535, resolvePort("65535"))
        assertEquals(1, resolvePort("1"))
    }

    @Test
    fun `a malformed port gives the default rather than stopping the server`() {
        // The defect. `"8O80".toInt()` throws a NumberFormatException out of `main`, so the process
        // exits before the engine binds. On a machine that boots on demand, every arriving request
        // then starts a process that dies, and nothing is served at all — not the catalogue, and
        // not /api/health, which is the endpoint an operator would look at first.
        assertEquals(DEFAULT_PORT, resolvePort("8O80"))
        assertEquals(DEFAULT_PORT, resolvePort("8080;"))
        assertEquals(DEFAULT_PORT, resolvePort("http://8080"))
    }

    @Test
    fun `a port outside the TCP range gives the default`() {
        assertEquals(DEFAULT_PORT, resolvePort("-1"))
        assertEquals(DEFAULT_PORT, resolvePort("65536"))
        assertEquals(DEFAULT_PORT, resolvePort("99999999999"))

        // Zero is excluded deliberately. `embeddedServer` reads 0 as "bind any free port", so the
        // machine would bind a port that fly's proxy does not forward to. It would look healthy
        // and serve nobody, which is worse than the typo it came from.
        assertEquals(DEFAULT_PORT, resolvePort("0"))
    }
}
