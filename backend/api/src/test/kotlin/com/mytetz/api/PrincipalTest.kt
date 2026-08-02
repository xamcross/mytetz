package com.mytetz.api

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signature on this cookie is the only thing standing between a client and a principal of its
 * own choosing — which is a free quota reset, and, once `PrincipalId.user(...)` exists, somebody
 * else's account. So the tests that matter here are the ones that hand the server a cookie an
 * attacker could actually construct, and every one of them asserts *both* halves: that the forged
 * value was not adopted, **and** that a fresh, well-formed principal was minted and set instead.
 *
 * The negative-only form the brief proposed — `assertTrue(!body.contains("attacker"))` — passes
 * against an implementation that never reads cookies at all, which is precisely the implementation
 * the signature exists to rule out. It is not used anywhere below.
 */
class PrincipalTest {

    private val key = "0123456789abcdef0123456789abcdef"
    private val otherKey = "fedcba9876543210fedcba9876543210"
    private val config = PrincipalCookieConfig(signingKey = key, secure = false)

    // ------------------------------------------------------------------ helpers

    private fun ApplicationTestBuilder.whoAmI(with: PrincipalCookieConfig = config) {
        application { installWho(with) }
    }

    private fun Application.installWho(with: PrincipalCookieConfig) {
        routing { get("/who") { call.respondText(Principals.resolve(call, with).value) } }
    }

    /** The cookie pair as a client would echo it back, `name=value` with the attributes stripped. */
    private fun HttpResponse.setCookiePair(): String? =
        headers[HttpHeaders.SetCookie]?.substringBefore(";")

    private fun HttpResponse.setCookieValue(): String? = setCookiePair()?.substringAfter("=")

    /** The same HMAC the implementation uses, written out independently so a forged cookie is real. */
    private fun sign(value: String, signingKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
        return "$value.$signature"
    }

    private fun assertFreshAnonymousPrincipal(body: String) {
        val uuid = body.removePrefix("anon:")
        assertNotEquals(body, uuid, "expected an anon:-namespaced principal, got '$body'")
        assertEquals(
            uuid.lowercase(),
            UUID.fromString(uuid).toString().lowercase(),
            "expected a canonical UUID after the namespace, got '$uuid'",
        )
    }

    // ------------------------------------------------------------------ minting

    @Test
    fun `a first visit mints a signed anonymous principal and sets it as a cookie`() = testApplication {
        whoAmI()

        val response = client.get("/who")
        val body = response.bodyAsText()

        assertFreshAnonymousPrincipal(body)
        val cookie = assertNotNull(response.setCookieValue(), "no principal cookie was set")
        // The cookie carries the principal AND a signature over it, not the bare principal: a
        // bare value would be trivially editable by the client.
        assertEquals(sign(body, key), cookie, "the cookie is not the principal signed under the key")
    }

    @Test
    fun `the principal cookie is httpOnly, site-wide, long lived and SameSite=Lax`() = testApplication {
        whoAmI()

        val header = assertNotNull(client.get("/who").headers[HttpHeaders.SetCookie])

        assertTrue(header.contains("HttpOnly", ignoreCase = true), "missing HttpOnly: $header")
        assertTrue(header.contains("Path=/"), "missing Path=/: $header")
        assertTrue(header.contains("SameSite=Lax", ignoreCase = true), "missing SameSite=Lax: $header")
        assertTrue(header.contains("Max-Age=", ignoreCase = true), "session-scoped cookie: $header")
    }

    // ------------------------------------------------------------------ round trip

    @Test
    fun `the same cookie yields the same principal and is not re-minted`() = testApplication {
        whoAmI()

        val first = client.get("/who")
        val cookie = assertNotNull(first.setCookiePair())

        val second = client.get("/who") { headers.append(HttpHeaders.Cookie, cookie) }

        assertEquals(first.bodyAsText(), second.bodyAsText())
        // The positive half the brief's version lacked. Re-issuing a cookie on every request would
        // also "yield the same principal" only by accident of the value being echoed back; a server
        // that accepted the cookie must have had no reason to set another.
        assertNull(second.headers[HttpHeaders.SetCookie], "an accepted cookie was re-minted")
    }

    // ------------------------------------------------------------------ forgery

    @Test
    fun `a cookie signed under a different key is rejected and a fresh principal issued`() = testApplication {
        whoAmI()

        // The case that matters, and the one the brief never tested: a *structurally perfect*
        // cookie — correct namespace, canonical UUID, a real HMAC-SHA256 over the value — that was
        // simply signed with a key this deployment does not hold.
        val forged = sign("anon:${UUID.randomUUID()}", otherKey)

        val response = client.get("/who") { headers.append(HttpHeaders.Cookie, "$COOKIE=$forged") }
        val body = response.bodyAsText()

        assertFalse(forged.startsWith("$body."), "a cookie signed under a foreign key was adopted")
        assertFreshAnonymousPrincipal(body)
        val reissued = assertNotNull(response.setCookieValue(), "no replacement cookie was set")
        assertEquals(sign(body, key), reissued)
    }

    @Test
    fun `a cookie whose payload was edited under an otherwise valid signature is rejected`() = testApplication {
        whoAmI()

        val genuine = assertNotNull(client.get("/who").setCookieValue())
        val payload = genuine.substringBeforeLast('.')
        val signature = genuine.substringAfterLast('.')
        // Flip one hex digit of the UUID and keep the signature that was minted for the original.
        val edited = payload.dropLast(1) + if (payload.last() == 'a') 'b' else 'a'

        val body = client.get("/who") {
            headers.append(HttpHeaders.Cookie, "$COOKIE=$edited.$signature")
        }.bodyAsText()

        assertNotEquals(edited, body, "an edited payload kept its old signature and was accepted")
        assertFreshAnonymousPrincipal(body)
    }

    @Test
    fun `a cookie with an edited signature is rejected`() = testApplication {
        whoAmI()

        val genuine = assertNotNull(client.get("/who").setCookieValue())
        val payload = genuine.substringBeforeLast('.')
        val signature = genuine.substringAfterLast('.')
        val edited = (if (signature.first() == 'A') 'B' else 'A') + signature.drop(1)

        val body = client.get("/who") {
            headers.append(HttpHeaders.Cookie, "$COOKIE=$payload.$edited")
        }.bodyAsText()

        assertNotEquals(payload, body, "an edited signature was accepted")
        assertFreshAnonymousPrincipal(body)
    }

    @Test
    fun `an unsigned cookie is rejected`() = testApplication {
        whoAmI()

        val body = client.get("/who") {
            headers.append(HttpHeaders.Cookie, "$COOKIE=anon:${UUID.randomUUID()}")
        }.bodyAsText()

        assertFreshAnonymousPrincipal(body)
    }

    @Test
    fun `a correctly signed cookie outside the anonymous namespace is rejected`() = testApplication {
        whoAmI()

        // Defence in depth against a future second signer. Verification alone answers "was this
        // signed by us?", never "is this a principal we mint" — and the returned string becomes a
        // Mongo `_id` and a quota key. `PrincipalId.user(...)` already exists as a namespace, so a
        // value signed by any other code path holding this key must not be able to claim it.
        //
        // The namespace is a CANONICAL UUID in a foreign namespace, deliberately. An earlier
        // version of this test used `user:admin`, which the UUID check rejects on its own — so it
        // passed while testing nothing about the namespace, and a mutation removing the namespace
        // check survived it. This payload is well formed in every respect except the prefix.
        val foreign = "user:${UUID.randomUUID()}"

        val body = client.get("/who") {
            headers.append(HttpHeaders.Cookie, "$COOKIE=${sign(foreign, key)}")
        }.bodyAsText()

        assertNotEquals(foreign, body, "a signed principal in the account namespace was adopted")
        assertFreshAnonymousPrincipal(body)
    }

    @Test
    fun `a correctly signed cookie in an invented namespace is rejected`() = testApplication {
        whoAmI()

        val invented = "bogus:${UUID.randomUUID()}"

        val body = client.get("/who") {
            headers.append(HttpHeaders.Cookie, "$COOKIE=${sign(invented, key)}")
        }.bodyAsText()

        assertNotEquals(invented, body, "an unrecognised principal namespace was adopted")
        assertFreshAnonymousPrincipal(body)
    }

    @Test
    fun `a correctly signed cookie whose uuid is not a uuid is rejected`() = testApplication {
        whoAmI()

        val body = client.get("/who") {
            headers.append(HttpHeaders.Cookie, "$COOKIE=${sign("anon:../../etc/passwd", key)}")
        }.bodyAsText()

        assertFreshAnonymousPrincipal(body)
    }

    // ------------------------------------------------------------------ encoding

    @Test
    fun `a percent-encoded cookie value is not silently decoded`() = testApplication {
        whoAmI()

        // This is the test that discriminates the two encodings. The cookie is written with
        // CookieEncoding.RAW; Ktor's `cookies[name]` defaults to URI_ENCODING. Under that default
        // the server would URL-decode `anon%3A<uuid>.<sig>` back into `anon:<uuid>.<sig>`, the
        // signature would verify, and the ORIGINAL principal would come back. Under RAW the
        // signature is checked over the literal `anon%3A<uuid>` and fails.
        //
        // Without this, the two sides agree only by the accident that base64url, a UUID and a colon
        // contain no '%'.
        val genuine = assertNotNull(client.get("/who").setCookieValue())
        val original = genuine.substringBeforeLast('.')
        val encoded = genuine.replace(":", "%3A")

        val body = client.get("/who") {
            headers.append(HttpHeaders.Cookie, "$COOKIE=$encoded")
        }.bodyAsText()

        assertNotEquals(original, body, "the read side URL-decoded a value the write side wrote raw")
        assertFreshAnonymousPrincipal(body)
    }

    @Test
    fun `the minted cookie value is written without percent-encoding`() = testApplication {
        whoAmI()

        val cookie = assertNotNull(client.get("/who").setCookieValue())

        // The write half of the same agreement. URI_ENCODING on the way out would emit `anon%3A…`,
        // which the RAW read above would then reject — the cookie would never survive one hop.
        assertFalse(cookie.contains("%"), "the cookie was percent-encoded on the way out: $cookie")
        assertTrue(cookie.startsWith("anon:"), "expected a raw `anon:` prefix, got: $cookie")
    }

    // ------------------------------------------------------------------ configuration

    @Test
    fun `the Secure flag comes from configuration and not from the Host header`() = testApplication {
        whoAmI(PrincipalCookieConfig(signingKey = key, secure = true))

        // `Host: localhost` is a client-controlled header. Deriving `secure` from it lets any client
        // ask for its cookie to be issued without Secure, over the network, on a production host.
        val header = assertNotNull(
            client.get("/who") { headers.append(HttpHeaders.Host, "localhost") }
                .headers[HttpHeaders.SetCookie]
        )

        assertTrue(header.contains("Secure", ignoreCase = true), "Host: localhost disabled Secure: $header")
    }

    @Test
    fun `Secure is omitted only when configuration says so`() = testApplication {
        whoAmI(PrincipalCookieConfig(signingKey = key, secure = false))

        val header = assertNotNull(
            client.get("/who") { headers.append(HttpHeaders.Host, "mytetz.fly.dev") }
                .headers[HttpHeaders.SetCookie]
        )

        assertFalse(header.contains("Secure", ignoreCase = true), "Secure survived secure=false: $header")
    }

    private companion object {
        const val COOKIE = "mytetz_pid"
    }
}

/**
 * The signing key is the one piece of configuration in this system that must **not** fall back to a
 * default. Every other resolver in the project (`GraphConfig`, `QuotaConfig`, `SessionLimits`)
 * deliberately swallows a bad value and keeps the process alive; this one deliberately does not, and
 * these tests exist so that a later pass at "making the config handling consistent" fails loudly
 * rather than silently handing every deployment the same key.
 */
class PrincipalCookieConfigTest {

    @Test
    fun `a missing signing key refuses to start`() {
        val error = assertFailsWith<IllegalStateException> { PrincipalCookieConfig.resolveSigningKey(null) }

        assertTrue(error.message.orEmpty().contains(PrincipalCookieConfig.SIGNING_KEY_ENV))
    }

    @Test
    fun `a blank signing key refuses to start`() {
        assertFailsWith<IllegalStateException> { PrincipalCookieConfig.resolveSigningKey("   ") }
    }

    @Test
    fun `a signing key shorter than the minimum is refused`() {
        // A short key is not a typo to be tolerated: it is a key that can be searched. Falling back
        // to a default here — the behaviour every other resolver in this project has — would let
        // anyone holding the source mint any principal they liked.
        val short = "a".repeat(PrincipalCookieConfig.MIN_SIGNING_KEY_LENGTH - 1)

        assertFailsWith<IllegalArgumentException> { PrincipalCookieConfig(signingKey = short) }
    }

    @Test
    fun `a signing key at the minimum length is accepted and whitespace is trimmed`() {
        val key = "b".repeat(PrincipalCookieConfig.MIN_SIGNING_KEY_LENGTH)

        // `fly secrets set` and a hand-edited .env both routinely leave a trailing newline on a
        // value. Trimming makes the key the operator pasted and the key the server signs with the
        // same bytes; not trimming makes every restart after a re-paste invalidate every cookie.
        assertEquals(key, PrincipalCookieConfig.resolveSigningKey("  $key\n"))
    }

    @Test
    fun `Secure defaults to on and is disabled only by an explicit false`() {
        // Opposite polarity to the other resolvers, and deliberately: their safe value is the
        // default, and here the safe value is `true`. An unset or misspelt override must not be the
        // thing that ships a session cookie over plain HTTP.
        assertTrue(PrincipalCookieConfig.resolveSecure(null))
        assertTrue(PrincipalCookieConfig.resolveSecure(""))
        assertTrue(PrincipalCookieConfig.resolveSecure("yes-please"))
        assertTrue(PrincipalCookieConfig.resolveSecure("TRUE"))
        assertFalse(PrincipalCookieConfig.resolveSecure("false"))
        assertFalse(PrincipalCookieConfig.resolveSecure(" FALSE "))
    }
}
