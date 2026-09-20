package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PageGeometryHintTest {

    @Test
    fun `Exact calculates aspectRatio correctly`() {
        val hint = PageGeometryHint.Exact(width = 1000, height = 2000)
        assertEquals(0.5f, hint.aspectRatio)
    }

    @Test
    fun `Exact rejects non-positive dimensions`() {
        assertThrows<IllegalArgumentException> {
            PageGeometryHint.Exact(width = 0, height = 100)
        }
        assertThrows<IllegalArgumentException> {
            PageGeometryHint.Exact(width = 100, height = -10)
        }
    }

    @Test
    fun `AspectRatio rejects non-positive or non-finite values`() {
        assertThrows<IllegalArgumentException> {
            PageGeometryHint.AspectRatio(0f)
        }
        assertThrows<IllegalArgumentException> {
            PageGeometryHint.AspectRatio(Float.NaN)
        }
        assertThrows<IllegalArgumentException> {
            PageGeometryHint.AspectRatio(Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `Estimated rejects non-positive or non-finite values`() {
        assertThrows<IllegalArgumentException> {
            PageGeometryHint.Estimated(-0.5f)
        }
        assertThrows<IllegalArgumentException> {
            PageGeometryHint.Estimated(Float.NaN)
        }
    }
}
