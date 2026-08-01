package com.mytetz.llm

import kotlinx.coroutines.flow.toList
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
}
