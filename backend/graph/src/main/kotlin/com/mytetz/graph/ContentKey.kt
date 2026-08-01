package com.mytetz.graph

import java.security.MessageDigest

/**
 * Derives the immutable identity of an explanation.
 *
 * The parent's key carries the entire ancestry in 32 bytes, so identity stays O(1)
 * at any depth and the same span reached via different topics can never collide.
 *
 * Fields are length-prefixed rather than delimiter-joined: a span is user-selected
 * text and could otherwise contain the delimiter and shift a field boundary.
 */
object ContentKey {

    fun derive(
        parentKey: String,
        span: String,
        verb: Verb,
        variant: Int,
        promptVersion: String,
        modelFamily: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(parentKey, span, verb.name, variant.toString(), promptVersion, modelFamily)
            .forEach { field ->
                val bytes = field.toByteArray(Charsets.UTF_8)
                digest.update(encodeLength(bytes.size))
                digest.update(bytes)
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** A seed has no parent; the topic slug occupies the span position. */
    fun seed(topicSlug: String, promptVersion: String, modelFamily: String): String =
        derive(
            parentKey = "",
            span = topicSlug,
            verb = Verb.SEED,
            variant = 0,
            promptVersion = promptVersion,
            modelFamily = modelFamily,
        )

    private fun encodeLength(length: Int): ByteArray = byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )
}
