package com.mytetz.account

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives `GoogleOAuth` against a real `HttpServer` on 127.0.0.1, the same way `MailSenderTest`
 * drives `ResendMailSender` against one. Nothing here reaches the network beyond loopback, and no
 * Google credential is required to run this suite.
 */
class GoogleOAuthTest {

    private val config = GoogleConfig(
        clientId = "test-client-id.apps.googleusercontent.com",
        clientSecret = "test-client-secret-value-never-sent-anywhere-real",
        redirectUri = "https://mytetz.example/api/auth/google/callback",
    )

    /** A `/token` endpoint that always answers with [status] and [body]. */
    private fun tokenServer(status: Int, body: String, onRequest: (String) -> Unit = {}): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/token") { exchange ->
                onRequest(exchange.requestBody.use { it.readBytes() }.decodeToString())
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    /** The loopback URL [server] answers `/token` requests on. */
    private fun endpointOf(server: HttpServer): String = "http://127.0.0.1:${server.address.port}/token"

    /** Builds an ID token whose middle segment decodes to [payload]. The header and the trailing
     * "signature" are placeholders: [GoogleOAuth.parseIdToken] does not check either one. */
    private fun idTokenWith(payload: String): String {
        val b64 = { s: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }
        return "${b64("""{"alg":"RS256"}""")}.${b64(payload)}.signature-not-verified"
    }

    // ------------------------------------------------------------------ authorizationUrl

    @Test
    fun `the authorization url carries the challenge the state and S256`() {
        val oauth = GoogleOAuth(config, HttpClient(CIO))

        val url = oauth.authorizationUrl(state = "state-value-1", codeChallenge = "challenge-value-1")

        assertTrue(
            url.startsWith("https://accounts.google.com/o/oauth2/v2/auth"),
            "the URL did not target the Google authorization endpoint: $url",
        )

        val params = url.substringAfter("?").split("&").associate { pair ->
            val (name, value) = pair.split("=", limit = 2)
            name to URLDecoder.decode(value, Charsets.UTF_8)
        }

        assertEquals("code", params["response_type"])
        assertEquals("openid email", params["scope"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("challenge-value-1", params["code_challenge"])
        assertEquals("state-value-1", params["state"])
        assertEquals(config.clientId, params["client_id"])
        assertEquals(config.redirectUri, params["redirect_uri"])
    }

    // ------------------------------------------------------------------ newVerifier / challengeOf

    @Test
    fun `a verifier and its challenge match the S256 rule`() {
        val verifier = GoogleOAuth.newVerifier()

        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)),
        )

        assertEquals(expected, GoogleOAuth.challengeOf(verifier))
    }

    @Test
    fun `two verifiers never match`() {
        val verifiers = (1..100).map { GoogleOAuth.newVerifier() }

        assertEquals(100, verifiers.toSet().size, "two of 100 minted verifiers matched")
    }

    @Test
    fun `a verifier is url-safe`() {
        val allowed = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')).toSet()

        repeat(100) {
            val verifier = GoogleOAuth.newVerifier()
            assertTrue(
                verifier.all { it in allowed },
                "the verifier held a character outside A-Za-z0-9_-: $verifier",
            )
        }
    }

    // ------------------------------------------------------------------ parseIdToken

    @Test
    fun `an id token yields the subject the email and the verified flag`() {
        val token = idTokenWith("""{"sub":"g-1","email":"Alice@Example.com","email_verified":true}""")

        val identity = GoogleOAuth.parseIdToken(token)

        assertEquals(GoogleIdentity("g-1", "alice@example.com", true), identity)
    }

    @Test
    fun `an id token with no email claim is refused`() {
        val token = idTokenWith("""{"sub":"g-1","email_verified":true}""")

        assertFailsWith<GoogleAuthException> { GoogleOAuth.parseIdToken(token) }
    }

    @Test
    fun `an id token with no sub claim is refused`() {
        val token = idTokenWith("""{"email":"alice@example.com","email_verified":true}""")

        assertFailsWith<GoogleAuthException> { GoogleOAuth.parseIdToken(token) }
    }

    @Test
    fun `an id token with a malformed payload is refused`() {
        // Two segments: no dot after the payload, so there is no third segment to split off.
        assertFailsWith<GoogleAuthException> {
            GoogleOAuth.parseIdToken("only-one-dot-follows.a-second-segment")
        }

        // A middle segment that is not valid base64url: '!' is outside the base64url alphabet.
        assertFailsWith<GoogleAuthException> {
            GoogleOAuth.parseIdToken("header.not-valid-base64!!!.signature")
        }

        // Valid base64url that decodes to text which is not a JSON object.
        val notJson = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("this is not a json object".toByteArray())
        assertFailsWith<GoogleAuthException> {
            GoogleOAuth.parseIdToken("header.$notJson.signature")
        }
    }

    // ------------------------------------------------------------------ exchange

    @Test
    fun `an exchange with email_verified false is refused`(): Unit = runBlocking {
        val token = idTokenWith("""{"sub":"g-2","email":"bob@example.com","email_verified":false}""")
        val server = tokenServer(200, """{"id_token":"$token","access_token":"unused"}""")
        try {
            val oauth = GoogleOAuth(config, HttpClient(CIO), tokenEndpoint = endpointOf(server))
            assertFailsWith<GoogleAuthException> { oauth.exchange("auth-code", "verifier-value") }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an exchange with email_verified true yields the identity`() = runBlocking {
        val token = idTokenWith("""{"sub":"g-3","email":"Carol@Example.com","email_verified":true}""")
        val server = tokenServer(200, """{"id_token":"$token","access_token":"unused"}""")
        try {
            val oauth = GoogleOAuth(config, HttpClient(CIO), tokenEndpoint = endpointOf(server))
            val identity = oauth.exchange("auth-code", "verifier-value")
            assertEquals(GoogleIdentity("g-3", "carol@example.com", true), identity)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the exchange posts the verifier`() = runBlocking {
        val token = idTokenWith("""{"sub":"g-4","email":"dora@example.com","email_verified":true}""")
        var capturedBody = ""
        val server = tokenServer(200, """{"id_token":"$token"}""") { body -> capturedBody = body }
        try {
            val oauth = GoogleOAuth(config, HttpClient(CIO), tokenEndpoint = endpointOf(server))
            oauth.exchange("auth-code", "the-verifier-value-123")
        } finally {
            server.stop(0)
        }

        assertTrue(
            "code_verifier=the-verifier-value-123" in capturedBody,
            "the posted body did not carry the code verifier: $capturedBody",
        )
    }

    @Test
    fun `a non-2xx from the token endpoint is refused`(): Unit = runBlocking {
        val server = tokenServer(400, """{"error":"invalid_grant"}""")
        try {
            val oauth = GoogleOAuth(config, HttpClient(CIO), tokenEndpoint = endpointOf(server))
            assertFailsWith<GoogleAuthException> { oauth.exchange("bad-code", "verifier-value") }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an exchange failure names no client secret`() = runBlocking {
        val secretConfig = config.copy(clientSecret = "s3cr3t-value-must-not-leak-anywhere")
        // A real provider error body can echo a request field back, exactly as the client secret
        // is echoed here, on purpose, to prove the class withholds the whole body on a failure.
        val body = """{"error":"invalid_client","error_description":"secret was ${secretConfig.clientSecret}"}"""
        val server = tokenServer(400, body)
        try {
            val oauth = GoogleOAuth(secretConfig, HttpClient(CIO), tokenEndpoint = endpointOf(server))

            val raised = assertFailsWith<GoogleAuthException> {
                oauth.exchange("bad-code", "verifier-value")
            }

            val message = raised.message.orEmpty()
            assertFalse(
                secretConfig.clientSecret in message,
                "the client secret leaked into the exception message: $message",
            )
            assertFalse(body in message, "the response body leaked into the exception message: $message")

            // The whole chain, not only the top message: default logging prints the cause's
            // message and the full stack trace too, and either one is a place the secret could
            // otherwise leak back out.
            val rendered = StringWriter().also { w ->
                PrintWriter(w).use { raised.printStackTrace(it) }
            }.toString()
            assertFalse(
                secretConfig.clientSecret in rendered,
                "the client secret leaked into the exception chain: $rendered",
            )
            assertFalse(body in rendered, "the response body leaked into the exception chain: $rendered")
        } finally {
            server.stop(0)
        }
    }
}
