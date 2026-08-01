package com.mytetz.persistence

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildSanityTest {
    @Test
    fun `module compiles and tests run`() {
        assertEquals(21, Runtime.version().feature())
    }
}
