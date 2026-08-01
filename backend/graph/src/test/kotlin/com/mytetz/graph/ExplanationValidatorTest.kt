package com.mytetz.graph

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [ExplanationValidator] is the last gate before persistence. Whatever it calls Valid is hashed
 * to a content key, stored immutably, served to every future learner who walks that path, and
 * later published. There is no edit path and no delete path, so a bad explanation that passes
 * here is permanent.
 *
 * Two rules follow from that, and every test below obeys them:
 *
 * 1. **One-fault fixtures.** Each negative case takes [validBody] -- proven acceptable in
 *    [`the shared good body is a one-fault fixture`] -- and injects exactly one violation. A
 *    fixture that breaks two rules cannot tell you which check is missing, so `assertIs<Invalid>`
 *    against it is close to a free assertion.
 * 2. **Expectations are spelled out here, never read off the implementation.** A test that
 *    iterated the validator's own refusal-prefix or tag list could not notice an entry being
 *    deleted from it.
 */
class ExplanationValidatorTest {

    private val validator = ExplanationValidator()

    /** 112 characters: in range, tag-free, and opening with nothing refusal-shaped. */
    private val validBody = "A hash function maps data of any size to a fixed-size value, " +
        "and the same input always produces the same output."

    /**
     * Filler of an exact length, used for the boundary cases. Deliberately built from prose that
     * trips no other rule: no '<', and an opening word that is on no refusal list.
     */
    private fun bodyOfLength(n: Int): String {
        val filler = "Energy moves between stores and never simply vanishes. "
        val raw = filler.repeat(n / filler.length + 1).take(n)
        // Must be trim-stable. Cutting the filler mid-gap leaves a trailing space, which the
        // validator trims away -- so `bodyOfLength(100)` was silently a 99-character body and
        // the boundary it claimed to probe was one off. Caught by the custom-bounds test.
        return if (raw.last().isWhitespace()) raw.dropLast(1) + "." else raw
    }

    /** Asserts acceptance and returns the accepted body. */
    private fun valid(body: String, stopReason: String? = "end_turn"): String {
        val result = validator.validate(body, stopReason)
        assertIs<ValidationResult.Valid>(result, "expected acceptance of: ${body.take(90)}")
        return result.body
    }

    /**
     * Asserts rejection and returns the reason. Folds in the contract that every rejection is
     * diagnosable: an `Invalid("")` is useless in a log and would otherwise pass silently.
     */
    private fun invalid(body: String, stopReason: String? = "end_turn"): String {
        val result = validator.validate(body, stopReason)
        assertIs<ValidationResult.Invalid>(result, "expected rejection of: ${body.take(90)}")
        assertTrue(result.reason.isNotBlank(), "rejection carried no reason")
        return result.reason
    }

    @Test
    fun `the shared good body is a one-fault fixture`() {
        // If validBody were itself out of range, tag-bearing or refusal-shaped, then every
        // "rejected" assertion built on it could be satisfied by the wrong rule.
        assertTrue(validBody.length in 41..599, "validBody length is ${validBody.length}")
        assertEquals(validBody, validBody.trim())
        assertIs<ValidationResult.Valid>(validator.validate(validBody, "end_turn"))

        // ... and the length helper has to be honest for the boundary tests to mean anything:
        // the right length, AND trim-stable, or it probes a boundary one off from the named one.
        listOf(39, 40, 99, 100, 120, 121, 600, 601).forEach {
            assertEquals(it, bodyOfLength(it).length, "bodyOfLength($it) is the wrong length")
            assertEquals(bodyOfLength(it), bodyOfLength(it).trim(), "bodyOfLength($it) is not trim-stable")
        }
    }

    @Test
    fun `accepts a well-formed body and returns it trimmed`() {
        assertEquals(validBody, valid(validBody))
        assertEquals(validBody, valid("  \n\t $validBody \n  "))

        // Exact equality rather than `contains`: this also pins that the interior survives
        // untouched -- no whitespace collapsing, no case folding, no truncation.
    }

    @Test
    fun `rejects a blank body and says so, rather than blaming its length`() {
        // "" and "   " are also under the minimum length, so a validator carrying ONLY a length
        // check satisfies a bare `assertIs<Invalid>` here. Naming emptiness isolates the branch.
        listOf("", " ", "   \n\t  ", "\n").forEach { blank ->
            val reason = invalid(blank)
            assertContains(reason, "empty", ignoreCase = true, message = "for ${blank.length} blank char(s)")
        }
    }

    @Test
    fun `rejects at the minimum length boundary`() {
        // 40 in, 39 out. Without both sides a `<` mutated to `<=` is invisible, and so is a
        // threshold that quietly drifted.
        assertEquals(bodyOfLength(40), valid(bodyOfLength(40)))
        assertContains(invalid(bodyOfLength(39)), "short", ignoreCase = true)
    }

    @Test
    fun `rejects at the maximum length boundary`() {
        // 600 in, 601 out -- the mirror of the above, for `>` mutated to `>=`.
        assertEquals(bodyOfLength(600), valid(bodyOfLength(600)))
        assertContains(invalid(bodyOfLength(601)), "long", ignoreCase = true)
    }

    @Test
    fun `length is measured on the trimmed body, so padding buys nothing`() {
        // Measured on the raw string, four spaces of padding would lift a 39-character body over
        // the minimum and shove a legal 600-character body over the maximum. Both are wrong.
        invalid("  ${bodyOfLength(39)}  ")
        assertEquals(600, valid("  ${bodyOfLength(600)}  ").length)
    }

    @Test
    fun `custom bounds are honoured rather than the defaults being hardcoded`() {
        val tight = ExplanationValidator(minChars = 100, maxChars = 120)

        // Each of these four sits on the opposite side of the DEFAULT bounds, so a validator
        // that ignored its constructor arguments would get every one of them wrong.
        assertIs<ValidationResult.Invalid>(tight.validate(bodyOfLength(99), "end_turn"))
        assertIs<ValidationResult.Valid>(tight.validate(bodyOfLength(100), "end_turn"))
        assertIs<ValidationResult.Valid>(tight.validate(bodyOfLength(120), "end_turn"))
        assertIs<ValidationResult.Invalid>(tight.validate(bodyOfLength(121), "end_turn"))

        // ... and the defaults really are 40 and 600.
        invalid(bodyOfLength(39))
        valid(bodyOfLength(40))
        valid(bodyOfLength(600))
        invalid(bodyOfLength(601))
    }

    @Test
    fun `end_turn is the only stop reason that means the generation completed`() {
        // Fail CLOSED. A blocklist of {refusal, max_tokens} that waves everything else through
        // fails OPEN: it is correct only if the adapter spells those two exactly that way, and
        // nothing in this project has ever round-tripped either of them -- only "end_turn",
        // which AnthropicLlmClientTest pins against the real SDK parsing real SSE.
        //
        // The concrete hazard: an earlier plan had the adapter unwrap the Optional with
        // `stopReason()?.toString()`, yielding the string "Optional[refusal]". Under a blocklist
        // that matches neither branch, so a model refusal would have been cached as a legitimate
        // explanation, permanently, and later published. Under an allowlist it simply is not
        // "end_turn", so it is rejected -- and if the adapter's spelling ever drifts, generation
        // fails loudly and immediately instead of silently persisting junk.
        assertEquals(validBody, valid(validBody, "end_turn"))

        listOf(
            null,                            // adapter contract allows it; unknown is not complete
            "",
            "  ",
            "END_TURN",                      // the Java enum constant name
            "EndTurn",
            "end turn",
            "Optional[end_turn]",            // the historical Optional-toString shape, benign value
            "Optional[refusal]",             // ... and the dangerous one
            "stop_sequence",
            "tool_use",
            "pause_turn",
            "model_context_window_exceeded",
        ).forEach { stopReason ->
            invalid(validBody, stopReason)
        }
    }

    @Test
    fun `rejects a refusal stop reason before it looks at the body at all`() {
        // A refusal arrives as HTTP 200 with empty or PARTIAL content, not as an error. A partial
        // refusal body can be long enough, tag-free and plausible enough to clear every body
        // check, so the stop reason has to be decided first or the gate leaks.
        // "refused", not "refusal": the catch-all branch echoes the offending stop reason back,
        // so asserting on "refusal" alone would still pass if this dedicated branch were deleted.
        assertContains(invalid(validBody, "refusal"), "refused", ignoreCase = true)

        // Partial but entirely plausible prose: nothing about the body itself is objectionable.
        assertContains(
            invalid("The subatomic scale is where the traditional laws of", "refusal"),
            "refused",
            ignoreCase = true,
        )

        // Empty body plus refusal. If the emptiness check ran first the reason would blame the
        // body, which would mean the stop-reason gate is only reachable for bodies that already
        // passed -- exactly the ordering that lets a partial refusal through.
        val onEmpty = invalid("", "refusal")
        assertContains(onEmpty, "refused", ignoreCase = true)
        assertFalse(onEmpty.contains("empty", ignoreCase = true), "emptiness was decided first: $onEmpty")
    }

    @Test
    fun `rejects truncation at max_tokens before it looks at the body at all`() {
        // A truncated generation ends mid-thought. It may still be well-formed prose of a legal
        // length, so nothing downstream of the stop reason can detect it.
        // "truncated" for the same reason: the catch-all echoes "max_tokens" back verbatim.
        assertContains(invalid(validBody, "max_tokens"), "truncated", ignoreCase = true)

        val onEmpty = invalid("", "max_tokens")
        assertContains(onEmpty, "truncated", ignoreCase = true)
        assertFalse(onEmpty.contains("empty", ignoreCase = true), "emptiness was decided first: $onEmpty")
    }

    @Test
    fun `rejects every leaked internal tag, opening or closing, in any case`() {
        // Spelled out here rather than read off the validator: a test that iterated the
        // implementation's own list could not notice an entry being deleted from it.
        //
        // The tool-call tags matter as much as <thinking>: AnthropicLlmClient's own notes record
        // that this model writes tool calls into visible prose when thinking is disabled.
        listOf(
            "<thinking>", "</thinking>", "<THINKING>", "<Thinking>",
            "<invoke", "</invoke>", "<function_calls>",
            "<system>", "</system>", "<system-reminder>",
            "<assistant>", "</assistant>",
            "<function_calls>", "</function_calls>",
            "<invoke name=\"x\">", "</invoke>",
            "<tool_use>", "</tool_use>", "<tool_result>", "</tool_result>",
        ).forEach { tag ->
            // One fault: validBody alone is accepted, so the tag is the sole cause of rejection.
            assertContains(invalid("$tag $validBody"), "tag", ignoreCase = true, message = "for $tag")
            assertContains(invalid("$validBody $tag"), "tag", ignoreCase = true, message = "for trailing $tag")
        }
    }

    @Test
    fun `does not reject legitimate markup or the bare words behind those tags`() {
        // Nothing is persisted on rejection, so a false positive costs one regeneration while a
        // false negative caches a leaked tag forever -- the asymmetry justifies erring toward
        // rejection. But a pattern broad enough to swallow ordinary markup would make an entire
        // topic ungeneratable, and "HTML" is a legitimate topic.
        valid(
            "In HTML a <p> element marks a paragraph, a <div> groups related content, " +
                "and a <section> labels a themed region of a page."
        )

        // The `\b` in the pattern is what stops <system> from also matching <systems>.
        valid(
            "The <systems> element does not exist, but <span> and <script> both do, " +
                "and each behaves quite differently inside a document."
        )

        // These are ordinary English words. They may only matter when they follow a '<', which
        // is what the `</?` anchor is for.
        valid(
            "An assistant process running in the system does the thinking about scheduling " +
                "and invokes the next task whenever a slot frees up."
        )
    }

    @Test
    fun `rejects a body opening with any known refusal phrase, in any case`() {
        val tail = " help with that request, but here is something else that may be useful instead."

        listOf(
            "I'm sorry", "I am sorry", "Sorry,",
            "I can't", "I cannot", "I'm unable", "I am unable",
            "I'm not able", "I am not able",
            "I won't", "I will not",
            "I apologize", "I apologise",
            "As an AI", "As a language model",
            "Unfortunately, I'm", "Unfortunately, I am", "Unfortunately, I can",
        ).forEach { opening ->
            assertContains(invalid(opening + tail), "refusal", ignoreCase = true, message = "for '$opening'")

            // Case must not matter: dropping the `lowercase()` would let a SHOUTED or oddly
            // capitalised refusal straight through.
            invalid(opening.uppercase() + tail.uppercase())
            invalid(opening.lowercase() + tail)
        }
    }

    @Test
    fun `rejects a refusal opening written with a typographic apostrophe`() {
        // Models routinely emit U+2019 rather than ASCII '. A prefix list spelled with ASCII
        // apostrophes misses every one of those, and a missed refusal is the worst outcome this
        // file has: it is a false NEGATIVE, so the refusal is persisted forever.
        assertContains(
            invalid("I’m sorry, but I can’t help with that request. Let me know if there is anything else."),
            "refusal",
            ignoreCase = true,
        )
        invalid("I’m unable to assist with that particular request, but here is a related idea to consider.")
        invalid("I’m not able to answer that, though I can point you at some background reading on the subject.")
    }

    @Test
    fun `does not reject a body that merely mentions a refusal phrase later on`() {
        // Anchored at the start on purpose. Swapping `startsWith` for `contains` would reject
        // ordinary expository prose, because "cannot", "unable" and "sorry" are everyday words.
        valid(
            "A closed system cannot exchange matter with its surroundings, so its total mass " +
                "stays constant even as energy crosses the boundary."
        )
        valid(
            "The word sorry, when used as an apology, performs an action rather than describing " +
                "one, which is why linguists call it a speech act."
        )
        valid(
            "Determinism says a system cannot deviate from its trajectory once its initial state " +
                "is fixed, so I cannot be a free actor within it."
        )
    }

    @Test
    fun `an unquoted refusal opening is rejected even on a legitimate linguistics topic`() {
        // A known and deliberate false positive, recorded here so it cannot be lost. An
        // explanation of the apology formula that opens with the bare phrase is indistinguishable
        // from a refusal. Rejecting it costs one regeneration and persists nothing; the reverse
        // mistake -- a real refusal cached under a content key and later published -- is forever.
        invalid(
            "I'm sorry is a conventional apology formula, and pragmatics treats it as a speech " +
                "act rather than a description of a feeling."
        )

        // The same explanation with the phrase quoted -- which is how it would normally be
        // written -- is accepted, so the false positive is narrow rather than topic-wide.
        valid(
            "\"I'm sorry\" is a conventional apology formula, and pragmatics treats it as a speech " +
                "act rather than a description of a feeling."
        )
    }
}
