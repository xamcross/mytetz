package com.mytetz.graph

/** The verdict of the last gate an explanation passes before it becomes immutable. */
sealed interface ValidationResult {
    data class Valid(val body: String) : ValidationResult
    data class Invalid(val reason: String) : ValidationResult
}

/**
 * The last gate before an explanation becomes immutable. Nothing that fails here is ever
 * persisted, so a rejected generation leaves no trace and a retry is clean — but whatever passes
 * is hashed to a content key, stored forever, served to every future learner who walks the same
 * path and later published. There is no edit path and no delete path.
 *
 * That asymmetry sets the whole design: a false positive costs one regeneration, a false negative
 * is permanent. Every rule below therefore errs toward rejection, and the checks are ordered so
 * that the cheapest, most certain evidence of a bad generation is consulted first.
 */
class ExplanationValidator(
    private val minChars: Int = 40,
    private val maxChars: Int = 600,
) {

    fun validate(rawBody: String, stopReason: String?): ValidationResult {
        // HOW the generation ended is decided before WHAT it produced is even looked at.
        //
        // A refusal is not an error: it arrives as HTTP 200 with empty or *partial* content. A
        // partial body can be long enough, tag-free and plausible enough to clear every body
        // check below, so any ordering that reaches the body first leaks refusals into storage.
        //
        // Allowlist, not blocklist. Rejecting only {refusal, max_tokens} and waving the rest
        // through fails OPEN — correct only if the adapter spells those two exactly so. This is
        // an allowlist because it fails CLOSED: an unrecognised stop reason stops the generation
        // loudly instead of quietly persisting it. `end_turn` is the only value that means "the
        // model finished saying what it meant to say", and it is the one value this project has
        // actually round-tripped (AnthropicLlmClientTest pins it, and the other six documented
        // values, against the real SDK parsing real SSE).
        //
        // `stop_sequence` is not on the allowlist because the adapter sets no stop sequences: if
        // one ever appears, something changed and a human should look.
        when (stopReason) {
            COMPLETED_STOP_REASON -> Unit
            "refusal" ->
                return ValidationResult.Invalid("model refused: stop reason was 'refusal'")
            "max_tokens" ->
                return ValidationResult.Invalid("output truncated: stop reason was 'max_tokens'")
            null ->
                return ValidationResult.Invalid(
                    "no stop reason: the generation cannot be shown to have completed"
                )
            else ->
                return ValidationResult.Invalid(
                    "unrecognised stop reason '${stopReason.take(64)}'; " +
                        "only '$COMPLETED_STOP_REASON' means the model finished"
                )
        }

        val body = rawBody.trim()

        if (body.isEmpty()) return ValidationResult.Invalid("empty body")

        if (TAG_PATTERN.containsMatchIn(body)) {
            return ValidationResult.Invalid("body contains an internal tag")
        }

        // Measured on the trimmed body: padding must not be able to buy length at either end.
        if (body.length < minChars) {
            return ValidationResult.Invalid("body shorter than $minChars characters")
        }
        if (body.length > maxChars) {
            return ValidationResult.Invalid("body longer than $maxChars characters")
        }

        // Anchored at the start, not a substring search. "cannot", "unable" and "sorry" are
        // ordinary English, so `contains` would reject a great deal of legitimate prose; a
        // refusal, by contrast, essentially always leads with its apology. The one known false
        // positive is a linguistics explanation that opens with the bare phrase unquoted — a
        // regeneration, against a permanently cached refusal. Curly apostrophes are folded to
        // ASCII first because models emit U+2019 freely, and a prefix list spelled with ASCII
        // apostrophes would otherwise miss every refusal written that way.
        val opening = body.lowercase().replace('’', '\'')
        if (REFUSAL_PREFIXES.any { opening.startsWith(it) }) {
            return ValidationResult.Invalid("body opens with a refusal phrase")
        }

        return ValidationResult.Valid(body)
    }

    companion object {

        /** The only Messages API stop reason that means the model finished of its own accord. */
        const val COMPLETED_STOP_REASON: String = "end_turn"

        /**
         * Internal markup that must never reach a learner. `<thinking>` is the obvious one; the
         * tool-call tags matter just as much, because AnthropicLlmClient's own notes record that
         * this model writes tool calls into visible prose when thinking is disabled.
         *
         * `</?` anchors every token to a real tag opener, and `\b` stops `<system>` from also
         * matching `<systems>`, so the everyday words behind these tokens stay usable in prose.
         * The list is deliberately specific rather than "any angle-bracketed token": a topic
         * about HTML has to remain generatable.
         */
        private val TAG_PATTERN = Regex(
            "</?(thinking|antml|system|assistant|function_calls|invoke|tool_use|tool_result)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Lowercase, ASCII-apostrophe openings. Each is a phrase no explanation would start on. */
        private val REFUSAL_PREFIXES = listOf(
            "i'm sorry", "i am sorry", "sorry,",
            "i can't", "i cannot", "i'm unable", "i am unable",
            "i'm not able", "i am not able",
            "i won't", "i will not",
            "i apologize", "i apologise",
            "as an ai", "as a language model",
            // Bare "unfortunately," is a legitimate way to open an explanation, so only the
            // first-person continuations are listed.
            "unfortunately, i'm", "unfortunately, i am", "unfortunately, i can",
        )
    }
}
