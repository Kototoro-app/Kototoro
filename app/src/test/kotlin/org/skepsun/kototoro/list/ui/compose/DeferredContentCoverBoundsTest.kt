package org.skepsun.kototoro.list.ui.compose

import androidx.compose.ui.geometry.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeferredContentCoverBoundsTest {

    @Test
    fun `bounds are resolved only when click requests latest coordinates`() {
        var resolutions = 0
        val resolver = DeferredContentCoverBounds<Int> { coordinate ->
            resolutions++
            Rect(coordinate.toFloat(), 0f, coordinate + 10f, 10f)
        }

        resolver.updateCoordinates(10)
        resolver.updateCoordinates(20)

        assertEquals(0, resolutions)
        assertEquals(Rect(20f, 0f, 30f, 10f), resolver.currentBounds())
        assertEquals(1, resolutions)
    }

    @Test
    fun `bounds are absent before coordinates are available`() {
        val resolver = DeferredContentCoverBounds<Int> { Rect.Zero }

        assertNull(resolver.currentBounds())
    }
}
