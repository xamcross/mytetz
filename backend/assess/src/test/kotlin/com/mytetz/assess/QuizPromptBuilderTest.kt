package com.mytetz.assess

import kotlin.test.Test
import kotlin.test.assertTrue

class QuizPromptBuilderTest {

    private val sources = listOf(QuizSource("key-1", "Body one."), QuizSource("key-2", "Body two."))

    @Test
    fun `the user prompt names every source key and body`() {
        val prompt = QuizPromptBuilder.user(sources, maxQuestions = 3)
        assertTrue("key-1" in prompt)
        assertTrue("key-2" in prompt)
        assertTrue("Body one." in prompt)
        assertTrue("Body two." in prompt)
        assertTrue("3" in prompt)
    }

    @Test
    fun `the nudge keeps the original prompt and adds a correction`() {
        val original = QuizPromptBuilder.user(sources, maxQuestions = 3)
        val nudged = QuizPromptBuilder.nudge(original)
        assertTrue(original in nudged)
        assertTrue("previous attempt" in nudged)
    }

    @Test
    fun `the schema requires exactly the five question fields`() {
        @Suppress("UNCHECKED_CAST")
        val questions = QuizPromptBuilder.inputSchema()["questions"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val items = questions["items"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val required = items["required"] as List<String>
        assertTrue(required.containsAll(listOf("stem", "options", "correctIndex", "sourceKey", "rationale")))
    }
}
