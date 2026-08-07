package com.mytetz.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The largest email address this service accepts, in characters. */
private const val MAX_EMAIL_LENGTH = 254

/** The number of random bytes behind every minted token. */
private const val TOKEN_BYTE_LENGTH = 32

/** The one [SecureRandom] instance every call to [MagicLinkService.newToken] draws from. */
private val secureRandom = SecureRandom()

/**
 * Issues a magic-link sign-in token, and later redeems that token for an email address.
 *
 * [request] never checks whether an account exists for an address. It treats a known address
 * and an unknown address the same way. This design stops the method from telling a caller
 * which address holds an account.
 *
 * [request] stores the SHA-256 hash of the token, never the token itself. A later database
 * read then cannot yield a usable sign-in link on its own. [consume] hashes the presented
 * token and asks [AccountRepository.consumeToken] to remove the matching row. That removal is
 * atomic, so two concurrent calls with the same token cannot both succeed.
 *
 * [clock] and [tokens] each carry a default for production use. A test overrides [clock] to
 * fix the current time, and overrides [tokens] to fix the minted token.
 */
class MagicLinkService(
    private val repository: AccountRepository,
    private val mail: MailSender,
    private val baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokens: () -> String = { newToken() },
) {

    /**
     * Sends a sign-in link to [rawEmail], when the address is a plausible email address.
     *
     * The method returns on every path: a malformed address, and a mailed link both end the
     * same way. It never reports whether [rawEmail] matches a known account.
     */
    suspend fun request(rawEmail: String) {
        val email = normaliseEmail(rawEmail) ?: return
        val rawToken = tokens()
        val now = clock()
        repository.insertToken(
            MagicLinkToken(
                tokenHash = hash(rawToken),
                email = email,
                expiresAtEpochMillis = now + TTL_MILLIS,
                createdAtEpochMillis = now,
            ),
        )
        mail.sendMagicLink(email, "$baseUrl/api/auth/magic-link/$rawToken")
    }

    /**
     * Redeems [rawToken] and returns the email address it was minted for.
     *
     * The method returns null for an unknown token, an already-consumed token, and an
     * expired token.
     */
    suspend fun consume(rawToken: String): String? =
        repository.consumeToken(hash(rawToken), clock())?.email

    companion object {

        /** The lifetime of a minted token, in milliseconds. A token expires 15 minutes after mint. */
        const val TTL_MILLIS: Long = 15 * 60 * 1000

        /**
         * Normalises [raw] to a canonical email address, or returns null when the address is
         * not plausible.
         *
         * The method trims [raw] and lower-cases it. It keeps every dot and every plus-tag in
         * the local part. A mail provider may treat two such addresses as one mailbox, but this
         * service treats them as two separate identities. Removing a dot or a plus-tag here
         * would silently merge two accounts into one.
         *
         * The method refuses an address with no `@` character, with more than one `@`
         * character, with no dot after the `@` character, longer than [MAX_EMAIL_LENGTH]
         * characters, or holding a space.
         */
        internal fun normaliseEmail(raw: String): String? {
            val candidate = raw.trim().lowercase()
            if (candidate.isEmpty() || candidate.length > MAX_EMAIL_LENGTH) return null
            if (candidate.any { it.isWhitespace() }) return null
            if (candidate.count { it == '@' } != 1) return null

            val atIndex = candidate.indexOf('@')
            val localPart = candidate.substring(0, atIndex)
            val domainPart = candidate.substring(atIndex + 1)
            if (localPart.isEmpty() || domainPart.isEmpty()) return null
            if (!domainPart.contains('.')) return null

            return candidate
        }

        /**
         * Renders the SHA-256 hash of [rawToken] as 64 lower-case hex characters.
         *
         * [AccountRepository] stores this hash, never the raw token. [MagicLinkToken.tokenHash]
         * holds the same value.
         */
        internal fun hash(rawToken: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        /**
         * Mints a token from [TOKEN_BYTE_LENGTH] random bytes, encoded as base64url without
         * padding.
         *
         * The method draws from [SecureRandom]. A generator such as `kotlin.random.Random`
         * produces a predictable sequence, and a predictable magic link is an account takeover.
         */
        internal fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTE_LENGTH)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
