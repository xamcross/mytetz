package com.mytetz.assess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QuizContentKeyTest {

    private fun key(
        scopeKeys: List<String> = listOf("a", "b"),
        kind: QuizKind = QuizKind.TEST_ME,
        promptVersion: String = "v1",
        modelFamily: String = "family",
    ) = QuizContentKey.derive(scopeKeys, kind, promptVersion, modelFamily)

    @Test
    fun `identical inputs derive identical keys`() {
        assertEquals(key(), key())
    }

    @Test
    fun `a different scope key changes the key`() {
        assertNotEquals(key(scopeKeys = listOf("a", "b")), key(scopeKeys = listOf("a", "c")))
    }

    @Test
    fun `scope order changes the key`() {
        assertNotEquals(key(scopeKeys = listOf("a", "b")), key(scopeKeys = listOf("b", "a")))
    }

    @Test
    fun `a different kind changes the key`() {
        assertNotEquals(key(kind = QuizKind.TEST_ME), key(kind = QuizKind.EXAM))
    }

    @Test
    fun `a different prompt version changes the key`() {
        assertNotEquals(key(promptVersion = "v1"), key(promptVersion = "v2"))
    }

    @Test
    fun `a different model family changes the key`() {
        assertNotEquals(key(modelFamily = "family-a"), key(modelFamily = "family-b"))
    }

    @Test
    fun `two scope keys concatenated without a boundary do not collide with a different split`() {
        // Length prefixing must stop ["ab", "c"] and ["a", "bc"] from hashing the same.
        assertNotEquals(key(scopeKeys = listOf("ab", "c")), key(scopeKeys = listOf("a", "bc")))
    }
}
