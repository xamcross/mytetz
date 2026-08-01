package com.mytetz.graph

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PromptBuilderTest {

    private val quantumContext = PromptContext(
        topicTitle = "Quantum Physics",
        ancestors = listOf(
            Ancestor(
                span = "fundamental physical theory",
                body = "The pillars of modern physics rest on two fundamental physical theories: " +
                    "General Relativity for the macroscopic universe and Quantum Mechanics for the microscopic realm.",
            ),
        ),
        span = "microscopic realm",
        spanSentence = "General Relativity for the macroscopic universe and Quantum Mechanics for the microscopic realm.",
        verb = Verb.EXPLAIN,
    )

    /**
     * Five sentinels, no one a substring of another, so an assertion that a field survived can
     * only be satisfied by that field. The realistic [quantumContext] cannot do this job: its
     * ancestor body literally contains the span *and* the whole span sentence, so
     * `assertContains(prompt, span)` and `assertContains(prompt, spanSentence)` against it both
     * pass even for a builder that drops the span and the sentence entirely.
     */
    private val disjointContext = PromptContext(
        topicTitle = "TOPICTITLE-ALFA",
        ancestors = listOf(Ancestor(span = "ANCESTORSPAN-BRAVO", body = "ANCESTORBODY-CHARLIE")),
        span = "SPAN-DELTA",
        spanSentence = "SENTENCE-ECHO",
        verb = Verb.EXPLAIN,
    )

    private val allVerbs = listOf(
        Verb.SEED,
        Verb.EXPLAIN,
        Verb.DIG_DEEPER,
        Verb.BROADER_PICTURE,
        Verb.SIDE_VIEW,
        Verb.VISUALIZE,
    )

    @Test
    fun `system prompt states the length, context, prose and preamble rules`() {
        val system = PromptBuilder.system()
        val lower = system.lowercase()

        // A stated numeric sentence limit, not merely the digit "1" appearing somewhere. The
        // brief's original assertion (`system.contains("1")`) is satisfied by the "1." of any
        // numbered list, so a prompt that never mentions length at all would pass it.
        assertTrue(
            Regex("""\b\d+ to \d+ sentences\b""").containsMatchIn(lower),
            "system prompt states no sentence limit:\n$system",
        )
        // Context scoping -- the product's central promise.
        assertContains(lower, "context")
        assertTrue(
            lower.contains("different field") || lower.contains("dictionary"),
            "system prompt does not rule out the standalone/other-field reading:\n$system",
        )
        // Plain prose for a beginner, no markup, no preamble.
        assertContains(lower, "markdown")
        assertContains(lower, "preamble")
        assertTrue(system.length > 200, "system prompt is suspiciously short")
    }

    @Test
    fun `system prompt is topic-agnostic so it can be a stable cacheable prefix`() {
        val system = PromptBuilder.system()

        // Anything topic-specific in the system prompt would both break the shared cache prefix
        // and duplicate what the user prompt already carries.
        listOf("Quantum", "Microbiology", "microscopic realm").forEach { leak ->
            assertFalse(system.contains(leak, ignoreCase = true), "system prompt leaks '$leak'")
        }
    }

    @Test
    fun `user prompt carries topic, ancestor span, ancestor body, span and sentence`() {
        val prompt = PromptBuilder.user(disjointContext)

        assertContains(prompt, "TOPICTITLE-ALFA")
        assertContains(prompt, "ANCESTORSPAN-BRAVO")
        assertContains(prompt, "ANCESTORBODY-CHARLIE")
        assertContains(prompt, "SPAN-DELTA")
        assertContains(prompt, "SENTENCE-ECHO")
    }

    @Test
    fun `user prompt is ordered topic, then ancestry, then the highlighted span`() {
        val prompt = PromptBuilder.user(disjointContext)

        // A builder that simply concatenated its arguments in an arbitrary order would still
        // contain every field; this pins the reading order the model actually needs.
        val order = listOf(
            "TOPICTITLE-ALFA",
            "ANCESTORSPAN-BRAVO",
            "ANCESTORBODY-CHARLIE",
            "SPAN-DELTA",
            "SENTENCE-ECHO",
        ).map { prompt.indexOf(it) }

        assertTrue(order.none { it < 0 }, "a field is missing from:\n$prompt")
        assertEquals(order.sorted(), order, "fields are out of order in:\n$prompt")
    }

    @Test
    fun `ancestors are rendered root-first with each span attached to its own body`() {
        val prompt = PromptBuilder.user(
            disjointContext.copy(
                ancestors = listOf(
                    Ancestor("SPAN-ONE", "BODY-ONE"),
                    Ancestor("SPAN-TWO", "BODY-TWO"),
                    Ancestor("SPAN-THREE", "BODY-THREE"),
                )
            )
        )

        // Strict span-then-body interleaving. Checking only body order (the brief's version)
        // passes for a builder that renders every span first and then every body, which reads
        // as three unattributed quotations.
        val order = listOf(
            "SPAN-ONE", "BODY-ONE",
            "SPAN-TWO", "BODY-TWO",
            "SPAN-THREE", "BODY-THREE",
        ).map { prompt.indexOf(it) }

        assertTrue(order.none { it < 0 }, "an ancestor field is missing from:\n$prompt")
        assertEquals(order.sorted(), order, "ancestors must be root-first and paired:\n$prompt")
    }

    @Test
    fun `the same span under a different topic produces a materially different prompt`() {
        val microbiologyContext = quantumContext.copy(
            topicTitle = "Microbiology",
            ancestors = listOf(
                Ancestor(
                    span = "microorganisms",
                    body = "Microbiology studies microorganisms: bacteria, archaea, fungi and protists.",
                )
            ),
            spanSentence = "Bacteria and other cells inhabit the microscopic realm.",
        )

        // Microbiology is rendered FIRST. In the brief's ordering, quantum was built before the
        // microbiology context existed, so its "quantum must not mention bacteria" assertion was
        // unfalsifiable -- no state could have leaked into a string that already existed.
        val microbiology = PromptBuilder.user(microbiologyContext)
        val quantum = PromptBuilder.user(quantumContext)
        val microbiologyAgain = PromptBuilder.user(microbiologyContext)

        assertEquals(microbiology, microbiologyAgain, "a previous call's state leaked into the next")

        // The span itself is identical under both topics -- it is not what disambiguates.
        assertContains(quantum, "microscopic realm")
        assertContains(microbiology, "microscopic realm")

        // What disambiguates is the ancestry and the sentence, so prove each survived using
        // material unique to that field under that topic.
        assertContains(quantum, "The pillars of modern physics") // ancestor body only
        assertContains(microbiology, "archaea") // ancestor body only
        assertContains(microbiology, "Bacteria and other cells inhabit") // span sentence only

        // Neither prompt may carry the other's context.
        listOf("archaea", "Bacteria and other cells", "Microbiology").forEach {
            assertFalse(quantum.contains(it, ignoreCase = true), "quantum prompt leaked '$it'")
        }
        listOf("The pillars of modern physics", "General Relativity", "Quantum").forEach {
            assertFalse(microbiology.contains(it, ignoreCase = true), "microbiology prompt leaked '$it'")
        }

        // "Materially" different: not merely a different topic line. Rename the topic in one and
        // the two must still differ, because the ancestry and the sentence differ too.
        assertNotEquals(quantum.replace("Quantum Physics", "Microbiology"), microbiology)
    }

    @Test
    fun `each verb yields a distinct instruction written as prose`() {
        val prompts = allVerbs.map { PromptBuilder.user(quantumContext.copy(verb = it)) }

        assertEquals(prompts.size, prompts.toSet().size, "verb instructions are not distinct")

        // Distinctness alone is free: a builder that appended `verb.name` would satisfy it while
        // giving the model six identical instructions. The prompt is prose for a teacher, so no
        // SCREAMING_SNAKE identifier may appear in it.
        prompts.forEachIndexed { i, prompt ->
            assertFalse(
                Regex("""\b[A-Z]{2,}(_[A-Z]{2,})+\b""").containsMatchIn(prompt),
                "${allVerbs[i]} prompt leaks an enum name:\n$prompt",
            )
        }

        // Each verb must ask for its own thing, not a generic "explain this".
        val signature = mapOf(
            Verb.SEED to "opening paragraph",
            Verb.EXPLAIN to "as it is used in this context",
            Verb.DIG_DEEPER to "deeper",
            Verb.BROADER_PICTURE to "Zoom out",
            Verb.SIDE_VIEW to "different angle",
            Verb.VISUALIZE to "diagram",
        )
        signature.forEach { (verb, phrase) ->
            assertContains(PromptBuilder.user(quantumContext.copy(verb = verb)), phrase)
        }
    }

    @Test
    fun `every verb that acts on a span carries the exact sentence it appeared in`() {
        // "Microscopic realm" resolves differently depending on whether its sentence concerned
        // scale or measurement, so the sentence has to reach the model for every span-bearing
        // verb -- not just the ones where it felt natural to include it.
        (allVerbs - Verb.SEED).forEach { verb ->
            val prompt = PromptBuilder.user(disjointContext.copy(verb = verb))
            assertContains(prompt, "SPAN-DELTA", message = "$verb dropped the span")
            assertContains(prompt, "SENTENCE-ECHO", message = "$verb dropped the span sentence")
        }
    }

    @Test
    fun `a seed prompt needs no span or ancestors`() {
        val prompt = PromptBuilder.user(
            PromptContext(
                topicTitle = "Quantum Physics",
                ancestors = emptyList(),
                span = "",
                spanSentence = "",
                verb = Verb.SEED,
            )
        )

        assertContains(prompt, "Quantum Physics")
        assertContains(prompt, "opening paragraph")
        assertTrue(prompt.isNotBlank())

        // A builder that rendered the span block unconditionally would emit
        // `They have now highlighted: ""` and still pass a contains-topic assertion.
        assertFalse(prompt.contains("\"\""), "seed prompt renders an empty quoted span:\n$prompt")
        assertFalse(prompt.contains("highlighted"), "seed prompt asks about a span:\n$prompt")
        assertFalse(prompt.contains("read so far"), "seed prompt renders an empty ancestor chain:\n$prompt")
        assertFalse(prompt.contains("\n\n\n"), "seed prompt has a hole where a block was skipped:\n$prompt")
    }

    @Test
    fun `the builder is a pure function of its inputs`() {
        val a = PromptBuilder.user(quantumContext)
        val systemFirst = PromptBuilder.system()
        PromptBuilder.user(disjointContext)
        PromptBuilder.user(quantumContext.copy(verb = Verb.VISUALIZE))

        assertEquals(a, PromptBuilder.user(quantumContext))
        assertEquals(systemFirst, PromptBuilder.system())
    }

    @Test
    fun `version is a non-blank constant that participates in content identity`() {
        assertTrue(PromptBuilder.VERSION.isNotBlank())
        assertFalse(PromptBuilder.VERSION.any { it.isWhitespace() }, "VERSION must be a bare token")

        // VERSION is this project's entire cache-invalidation strategy: it is hashed into every
        // content key, so bumping it re-identifies every explanation generated afterwards.
        assertNotEquals(
            ContentKey.seed("quantum-physics", PromptBuilder.VERSION, "claude-opus-5"),
            ContentKey.seed("quantum-physics", PromptBuilder.VERSION + "-next", "claude-opus-5"),
        )
    }

    @Test
    fun `the rendered prompt text is pinned to VERSION`() {
        // A change-detector on purpose. Editing the system prompt or any verb instruction without
        // bumping VERSION would leave every already-persisted explanation addressed by a key that
        // no longer describes the prompt that produced it -- silently stale content, with no
        // migration to notice it. This test makes that edit impossible to make quietly.
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(PromptBuilder.VERSION.toByteArray())
            update(PromptBuilder.system().toByteArray())
            allVerbs.forEach { update(PromptBuilder.user(quantumContext.copy(verb = it)).toByteArray()) }
        }.digest().joinToString("") { "%02x".format(it) }

        assertEquals(
            "733f2e2c54529c42bd214133e65f2c7475c888b6bdad994a0023f7c8dbe13861",
            digest,
            "The prompt text or VERSION changed. If you edited the prompts: bump PromptBuilder.VERSION " +
                "and re-pin this digest. If you bumped VERSION: re-pin this digest.",
        )
    }
}
