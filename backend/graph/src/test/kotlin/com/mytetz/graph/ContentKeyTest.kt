package com.mytetz.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContentKeyTest {

    private val p = "v1"
    private val m = "claude-opus-5"

    /** The sentence that holds the span. It is a field of the key; see [ContentKey]. */
    private val s = "It studies the microscopic realm."

    @Test
    fun `key is 64 lowercase hex characters`() {
        val key = ContentKey.seed("quantum-physics", p, m)
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" }, "not lowercase hex: $key")
    }

    @Test
    fun `same inputs always produce the same key`() {
        val a = ContentKey.derive("abc", "microscopic realm", s, Verb.EXPLAIN, 0, p, m)
        val b = ContentKey.derive("abc", "microscopic realm", s, Verb.EXPLAIN, 0, p, m)
        assertEquals(a, b)
    }

    @Test
    fun `changing any single input changes the key`() {
        val base = ContentKey.derive("abc", "microscopic realm", s, Verb.EXPLAIN, 0, p, m)
        val variations = listOf(
            ContentKey.derive("abd", "microscopic realm", s, Verb.EXPLAIN, 0, p, m),
            ContentKey.derive("abc", "microscopic realms", s, Verb.EXPLAIN, 0, p, m),
            ContentKey.derive("abc", "microscopic realm", "$s Again.", Verb.EXPLAIN, 0, p, m),
            ContentKey.derive("abc", "microscopic realm", s, Verb.DIG_DEEPER, 0, p, m),
            ContentKey.derive("abc", "microscopic realm", s, Verb.EXPLAIN, 1, p, m),
            ContentKey.derive("abc", "microscopic realm", s, Verb.EXPLAIN, 0, "v2", m),
            ContentKey.derive("abc", "microscopic realm", s, Verb.EXPLAIN, 0, p, "claude-sonnet-5"),
        )
        variations.forEachIndexed { i, key -> assertNotEquals(base, key, "variation $i collided") }
        assertEquals(variations.size, variations.toSet().size, "variations collided with each other")
    }

    @Test
    fun `the same span in two different sentences is two explanations`() {
        // The defect this closes. The key carried no sentence, and the justification for that said
        // two callers reaching the same span by the same path must land on the same document. That
        // treats the sentence as derivable from the parent key and the span. It is not.
        //
        // One body can hold the same word twice, in two sentences. `SessionService.validateSpan`
        // accepts both selections, because both really sit at the offsets that they claim. The
        // prompt then differs — `SessionService` calls the sentence one of its two prompt inputs,
        // and says "microscopic realm" resolves differently by sentence — while the identity did
        // not. The first generation won for ever, and the stored `Explanation.spanSentence`
        // recorded a context that the second learner never saw.
        val scale = ContentKey.derive(
            "abc",
            "microscopic realm",
            "The microscopic realm is smaller than 0.1 nanometers.",
            Verb.EXPLAIN,
            0,
            p,
            m,
        )
        val measurement = ContentKey.derive(
            "abc",
            "microscopic realm",
            "Only an electron microscope reaches the microscopic realm.",
            Verb.EXPLAIN,
            0,
            p,
            m,
        )

        assertNotEquals(scale, measurement, "two sentences shared one document")
    }

    @Test
    fun `the same span in the same sentence is still one explanation`() {
        // The other half, and the reason the field costs almost nothing. Two learners that select
        // the same span in the same sentence still share one document. That is the common case, and
        // it is what keeps the store a cache.
        val first = ContentKey.derive("abc", "microscopic realm", s, Verb.EXPLAIN, 0, p, m)
        val second = ContentKey.derive("abc", "microscopic realm", s, Verb.EXPLAIN, 0, p, m)

        assertEquals(first, second)
    }

    @Test
    fun `the same span under different ancestry produces different keys`() {
        val quantum = ContentKey.seed("quantum-physics", p, m)
        val micro = ContentKey.seed("microbiology", p, m)

        val underQuantum = ContentKey.derive(quantum, "microscopic realm", s, Verb.EXPLAIN, 0, p, m)
        val underMicro = ContentKey.derive(micro, "microscopic realm", s, Verb.EXPLAIN, 0, p, m)

        assertNotEquals(underQuantum, underMicro)
    }

    @Test
    fun `field boundaries cannot be forged by embedding the separator`() {
        // "a" + "bc" must not hash the same as "ab" + "c".
        val left = ContentKey.derive("a", "bc", s, Verb.EXPLAIN, 0, p, m)
        val right = ContentKey.derive("ab", "c", s, Verb.EXPLAIN, 0, p, m)
        assertNotEquals(left, right)

        // A span containing control characters must not shift a boundary either.
        val withControlChar = ContentKey.derive("a", "b\u0000c", s, Verb.EXPLAIN, 0, p, m)
        assertNotEquals(left, withControlChar)
        assertNotEquals(right, withControlChar)

        // The actual forgery this test exists to rule out: under a NUL-delimited join
        // (as opposed to length-prefixing), derive("a", "b\u0000c", ...) and
        // derive("a\u0000b", "c", ...) would both serialize to the identical joined
        // string "a\u0000b\u0000c\u0000EXPLAIN\u00000\u0000v1\u0000claude-opus-5" --
        // the NUL a crafted span contributes is indistinguishable from the NUL the join
        // would insert as a field separator, so the parent/span boundary shifts by one
        // character and two different explanations collide on the same key. Comparing
        // against `left`/`right` above does not catch this: those two calls differ under
        // any join scheme, delimited or not, so they would pass even with a broken
        // implementation. This pair is the one a regression to delimiter-joining would
        // actually fail on.
        val parentContainsSeparator = ContentKey.derive("a\u0000b", "c", s, Verb.EXPLAIN, 0, p, m)
        assertNotEquals(withControlChar, parentContainsSeparator)

        // The span and the sentence are a third pair of adjacent text fields. The sentence contains
        // the span by construction, so this is the pair most likely to be split at the wrong place.
        val spanThenSentence = ContentKey.derive("p", "a", "bc", Verb.EXPLAIN, 0, p, m)
        val sentenceTakesOne = ContentKey.derive("p", "ab", "c", Verb.EXPLAIN, 0, p, m)
        assertNotEquals(spanThenSentence, sentenceTakesOne)
    }

    @Test
    fun `a seed and a non-seed explanation with the same span do not collide`() {
        // Both calls use an empty parentKey, the same span, variant, promptVersion, and
        // modelFamily -- the only field that differs is the verb (SEED vs EXPLAIN). An
        // empty parentKey is deliberate: a non-empty parent would also make these differ,
        // but for the wrong reason. This is the tightest pair that isolates verb as the
        // thing standing between a seed and an indistinguishable non-seed explanation
        // whose span happens to equal the topic slug.
        //
        // The sentence is empty on both sides for the same reason. `ContentKey.seed` passes an
        // empty sentence, so a non-empty value here would make the pair differ for a second reason
        // and would stop isolating the verb.
        val seed = ContentKey.seed("quantum-physics", p, m)
        val nonSeed = ContentKey.derive("", "quantum-physics", "", Verb.EXPLAIN, 0, p, m)
        assertNotEquals(seed, nonSeed)
    }

    @Test
    fun `length prefix counts UTF-8 bytes, not characters`() {
        // "café" and a span holding a microscope emoji each contain a character that
        // encodes to more than one byte in UTF-8 (e acute is 2 bytes; the emoji is 4).
        // If encodeLength were ever computed from character count instead of
        // bytes.size, these length prefixes would be wrong and the key would no longer
        // reliably reflect the field's actual content.
        val accented = ContentKey.derive("abc", "café", s, Verb.EXPLAIN, 0, p, m)
        val emoji = ContentKey.derive("abc", "microscope 🔬", s, Verb.EXPLAIN, 0, p, m)
        val plain = ContentKey.derive("abc", "microscopic realm", s, Verb.EXPLAIN, 0, p, m)

        listOf(accented, emoji).forEach { key ->
            assertEquals(64, key.length)
            assertTrue(key.all { it in "0123456789abcdef" }, "not lowercase hex: $key")
        }
        assertNotEquals(accented, emoji)
        assertNotEquals(accented, plain)
        assertNotEquals(emoji, plain)
    }
}
