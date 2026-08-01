package com.mytetz.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContentKeyTest {

    private val p = "v1"
    private val m = "claude-opus-5"

    @Test
    fun `key is 64 lowercase hex characters`() {
        val key = ContentKey.seed("quantum-physics", p, m)
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" }, "not lowercase hex: $key")
    }

    @Test
    fun `same inputs always produce the same key`() {
        val a = ContentKey.derive("abc", "microscopic realm", Verb.EXPLAIN, 0, p, m)
        val b = ContentKey.derive("abc", "microscopic realm", Verb.EXPLAIN, 0, p, m)
        assertEquals(a, b)
    }

    @Test
    fun `changing any single input changes the key`() {
        val base = ContentKey.derive("abc", "microscopic realm", Verb.EXPLAIN, 0, p, m)
        val variations = listOf(
            ContentKey.derive("abd", "microscopic realm", Verb.EXPLAIN, 0, p, m),
            ContentKey.derive("abc", "microscopic realms", Verb.EXPLAIN, 0, p, m),
            ContentKey.derive("abc", "microscopic realm", Verb.DIG_DEEPER, 0, p, m),
            ContentKey.derive("abc", "microscopic realm", Verb.EXPLAIN, 1, p, m),
            ContentKey.derive("abc", "microscopic realm", Verb.EXPLAIN, 0, "v2", m),
            ContentKey.derive("abc", "microscopic realm", Verb.EXPLAIN, 0, p, "claude-sonnet-5"),
        )
        variations.forEachIndexed { i, key -> assertNotEquals(base, key, "variation $i collided") }
        assertEquals(variations.size, variations.toSet().size, "variations collided with each other")
    }

    @Test
    fun `the same span under different ancestry produces different keys`() {
        val quantum = ContentKey.seed("quantum-physics", p, m)
        val micro = ContentKey.seed("microbiology", p, m)

        val underQuantum = ContentKey.derive(quantum, "microscopic realm", Verb.EXPLAIN, 0, p, m)
        val underMicro = ContentKey.derive(micro, "microscopic realm", Verb.EXPLAIN, 0, p, m)

        assertNotEquals(underQuantum, underMicro)
    }

    @Test
    fun `field boundaries cannot be forged by embedding the separator`() {
        // "a" + "bc" must not hash the same as "ab" + "c".
        val left = ContentKey.derive("a", "bc", Verb.EXPLAIN, 0, p, m)
        val right = ContentKey.derive("ab", "c", Verb.EXPLAIN, 0, p, m)
        assertNotEquals(left, right)

        // A span containing control characters must not shift a boundary either.
        val withControlChar = ContentKey.derive("a", "b\u0000c", Verb.EXPLAIN, 0, p, m)
        assertNotEquals(left, withControlChar)
        assertNotEquals(right, withControlChar)
    }
}
