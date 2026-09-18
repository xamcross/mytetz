package com.mytetz.assess

import java.security.MessageDigest

/**
 * Derives a quiz template's identity from scopeKeys, kind, promptVersion, and modelFamily.
 *
 * This object uses length-prefixed fields, like com.mytetz.graph.ContentKey.
 * A scope key is a hex hash that could collide with a delimiter in some future encoding.
 * Length prefixing removes that collision risk.
 * This method does not rely on the alphabet.
 *
 * The order of scopeKeys is part of the identity.
 * Exam requests over the same explanations in different orders produce different keys.
 * This is correct: Exam questions cover material presented earlier only.
 * A quiz that ignored order could be served before the learner reaches its last scope key.
 */
object QuizContentKey {

    fun derive(scopeKeys: List<String>, kind: QuizKind, promptVersion: String, modelFamily: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(encodeLength(scopeKeys.size))
        scopeKeys.forEach { digest.updateLengthPrefixed(it) }
        listOf(kind.name, promptVersion, modelFamily).forEach { digest.updateLengthPrefixed(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun MessageDigest.updateLengthPrefixed(field: String) {
        val bytes = field.toByteArray(Charsets.UTF_8)
        update(encodeLength(bytes.size))
        update(bytes)
    }

    private fun encodeLength(length: Int): ByteArray = byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )
}
