package org.skepsun.kototoro.reader.render.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageSeamPolicyTest {

    @Test
    fun `forScene returns seamless overlap when page spacing is zero`() {
        val verticalSeamless = PageSeamPolicy.forScene(spacingPx = 0, isVertical = true)
        assertEquals(0, verticalSeamless.overlapX)
        assertEquals(1, verticalSeamless.overlapY)

        val horizontalSeamless = PageSeamPolicy.forScene(spacingPx = 0, isVertical = false)
        assertEquals(1, horizontalSeamless.overlapX)
        assertEquals(0, horizontalSeamless.overlapY)
    }

    @Test
    fun `forScene returns zero overlap when page spacing is positive`() {
        val verticalSpaced = PageSeamPolicy.forScene(spacingPx = 8, isVertical = true)
        assertEquals(0, verticalSpaced.overlapX)
        assertEquals(0, verticalSpaced.overlapY)

        val horizontalSpaced = PageSeamPolicy.forScene(spacingPx = 16, isVertical = false)
        assertEquals(0, horizontalSpaced.overlapX)
        assertEquals(0, horizontalSpaced.overlapY)
    }
}
