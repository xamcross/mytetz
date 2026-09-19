package com.mytetz.graph

import kotlin.test.Test
import kotlin.test.assertIs

class MediaValidatorTest {

    private val validator = MediaValidator(maxSourceChars = 10)

    @Test
    fun `a source within the limit is valid`() {
        assertIs<ValidationResult.Valid>(validator.validate("<svg/>"))
    }

    @Test
    fun `a source over the limit is invalid`() {
        assertIs<ValidationResult.Invalid>(validator.validate("<svg>" + "x".repeat(20) + "</svg>"))
    }
}
