package com.mytetz.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeLlmClientTest {

    @Test
    fun `fake streams the configured body in chunks and reports usage`() = runTest {
        val fake = FakeLlmClient().apply { nextBody = "The microscopic realm is the subatomic scale." }

        val chunks = fake.stream(LlmRequest("system", "prompt")).toList()

        val text = chunks.filterIsInstance<LlmChunk.Delta>().joinToString("") { it.text }
        assertEquals("The microscopic realm is the subatomic scale.", text)

        val done = chunks.filterIsInstance<LlmChunk.Done>().single()
        assertTrue(done.usage.outputTokens > 0)
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `structured records the request and returns the configured json`() = runBlocking {
        val fake = FakeLlmClient()
        fake.nextStructuredJson = """{"questions":[{"stem":"x"}]}"""

        val result = fake.structured(
            StructuredRequest(
                system = "sys",
                userPrompt = "prompt",
                toolName = "submit_quiz_questions",
                toolDescription = "desc",
                inputSchema = mapOf("questions" to mapOf("type" to "array")),
                requiredFields = listOf("questions"),
            )
        )

        assertEquals("""{"questions":[{"stem":"x"}]}""", result.json)
        assertEquals(1, fake.structuredCalls.size)
        assertEquals("submit_quiz_questions", fake.structuredCalls.single().toolName)
    }

    @Test
    fun `structured selects a json body keyed on a prompt substring`() = runBlocking {
        val fake = FakeLlmClient()
        fake.structuredJsonByPromptSubstring["NUDGE"] = """{"questions":[]}"""
        fake.nextStructuredJson = """{"questions":[{"stem":"first try"}]}"""

        val first = fake.structured(request(userPrompt = "plain prompt"))
        val retried = fake.structured(request(userPrompt = "plain prompt NUDGE"))

        assertEquals("""{"questions":[{"stem":"first try"}]}""", first.json)
        assertEquals("""{"questions":[]}""", retried.json)
    }

    private fun request(userPrompt: String) = StructuredRequest(
        system = "sys",
        userPrompt = userPrompt,
        toolName = "submit_quiz_questions",
        toolDescription = "desc",
        inputSchema = mapOf("questions" to mapOf("type" to "array")),
        requiredFields = listOf("questions"),
    )
}
