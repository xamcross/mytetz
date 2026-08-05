package com.mytetz.graph

import java.security.MessageDigest

/**
 * Derives the immutable identity of an explanation.
 *
 * The parent key carries the whole ancestry in 32 bytes. Identity therefore stays O(1) at any
 * depth, and the same span under two different topics cannot collide.
 *
 * Fields are length-prefixed and not joined with a delimiter. A span is text that a learner
 * selected. A delimiter inside that text would move a field boundary.
 *
 * ## Why the sentence is part of the identity
 *
 * The prompt carries the span and the sentence that holds it. `SessionService` gives the reason:
 * "microscopic realm" means one thing in a sentence about scale, and another in a sentence about
 * measurement. The answer depends on both.
 *
 * The parent key and the span do not determine the sentence. One word can appear twice in one
 * parent body, in two different sentences. Both selections are valid, because
 * `SessionService.validateSpan` checks the offsets against the stored body.
 *
 * Without [spanSentence] those two selections share one key. The first generation wins for ever.
 * The second learner then reads an answer to the other sentence, and the stored
 * `Explanation.spanSentence` names a context that the learner never saw. The prompt and the
 * identity would disagree, which is what this field prevents.
 *
 * The cost is small. Two learners that select the same span in the same sentence still share one
 * document, and that is the common case. Only a real difference in the prompt makes a new document.
 */
object ContentKey {

    fun derive(
        parentKey: String,
        span: String,
        spanSentence: String,
        verb: Verb,
        variant: Int,
        promptVersion: String,
        modelFamily: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(parentKey, span, spanSentence, verb.name, variant.toString(), promptVersion, modelFamily)
            .forEach { field ->
                val bytes = field.toByteArray(Charsets.UTF_8)
                digest.update(encodeLength(bytes.size))
                digest.update(bytes)
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * A seed has no parent. The topic slug takes the span position.
     *
     * The sentence is empty, because a seed has no selected span and therefore no sentence around
     * one. `SessionService.seedRequest` passes the same empty value into the prompt.
     */
    fun seed(topicSlug: String, promptVersion: String, modelFamily: String): String =
        derive(
            parentKey = "",
            span = topicSlug,
            spanSentence = "",
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
