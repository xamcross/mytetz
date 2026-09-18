package com.mytetz.assess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuizValidatorTest {

    private val validator = QuizValidator()
    private val scopeKeys = listOf("key-1", "key-2")

    private fun raw(
        stem: String = "What is the escape velocity?",
        options: List<String> = listOf("a", "b", "c", "d"),
        correctIndex: Int = 1,
        sourceKey: String = "key-1",
        rationale: String = "Because the text said so.",
    ) = RawQuizQuestion(stem, options, correctIndex, sourceKey, rationale)

    @Test
    fun `a well-formed candidate is valid`() {
        val result = validator.validate(raw(), scopeKeys, questionId = "q1")
        val valid = assertIs<QuizValidationResult.Valid>(result)
        assertEquals("q1", valid.question.questionId)
        assertEquals("key-1", valid.question.sourceKey)
    }

    @Test
    fun `an out-of-scope sourceKey is invalid`(): Unit {
        val result = validator.validate(raw(sourceKey = "not-in-scope"), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `three options is invalid`(): Unit {
        val result = validator.validate(raw(options = listOf("a", "b", "c")), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `five options is invalid`(): Unit {
        val result = validator.validate(raw(options = listOf("a", "b", "c", "d", "e")), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `a correctIndex of 4 is invalid for four options`(): Unit {
        val result = validator.validate(raw(correctIndex = 4), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `a negative correctIndex is invalid`(): Unit {
        val result = validator.validate(raw(correctIndex = -1), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `an empty stem is invalid`(): Unit {
        val result = validator.validate(raw(stem = "   "), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `an empty rationale is invalid`(): Unit {
        val result = validator.validate(raw(rationale = ""), scopeKeys, "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }

    @Test
    fun `an empty scope set rejects every candidate`(): Unit {
        val result = validator.validate(raw(sourceKey = "key-1"), scopeKeys = emptyList(), "q1")
        assertIs<QuizValidationResult.Invalid>(result)
    }
}
