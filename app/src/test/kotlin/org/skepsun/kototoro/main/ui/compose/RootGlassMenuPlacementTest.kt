package org.skepsun.kototoro.main.ui.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RootGlassMenuPlacementTest {

    @Test
    fun `bottom anchor opens menu above itself`() {
        val offset = calculateRootGlassMenuOffset(
            rootSize = IntSize(width = 1080, height = 1920),
            menuSize = IntSize(width = 320, height = 480),
            anchorBounds = Rect(left = 920f, top = 1740f, right = 1000f, bottom = 1820f),
            gapPx = 12,
            openAboveAnchor = true,
        )

        assertEquals(IntOffset(x = 680, y = 1248), offset)
    }

    @Test
    fun `top anchor keeps default downward placement`() {
        val offset = calculateRootGlassMenuOffset(
            rootSize = IntSize(width = 1080, height = 1920),
            menuSize = IntSize(width = 320, height = 480),
            anchorBounds = Rect(left = 920f, top = 80f, right = 1000f, bottom = 160f),
            gapPx = 12,
            openAboveAnchor = false,
        )

        assertEquals(IntOffset(x = 680, y = 172), offset)
    }

    @Test
    fun `placement is clamped inside root bounds`() {
        val offset = calculateRootGlassMenuOffset(
            rootSize = IntSize(width = 300, height = 400),
            menuSize = IntSize(width = 360, height = 480),
            anchorBounds = Rect(left = -40f, top = 20f, right = 20f, bottom = 80f),
            gapPx = 12,
            openAboveAnchor = true,
        )

        assertEquals(IntOffset.Zero, offset)
    }
}
