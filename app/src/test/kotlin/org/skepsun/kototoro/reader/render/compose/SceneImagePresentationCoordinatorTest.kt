package org.skepsun.kototoro.reader.render.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.IntRect

class SceneImagePresentationCoordinatorTest {

    @Test
    fun `computeVisibleLogicalRect maps relative intersection to image coordinates accurately`() {
        val sceneBounds = FloatRect(100f, 200f, 900f, 1400f) // 800 x 1200
        val visibleRegion = FloatRect(100f, 200f, 500f, 800f) // half width, half height
        val imageWidth = 1600
        val imageHeight = 2400

        val logicalRect = SceneImagePresentationCoordinator.computeVisibleLogicalRect(
            nodeSceneBounds = sceneBounds,
            nodeVisibleRegion = visibleRegion,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )

        assertEquals(IntRect(0, 0, 800, 1200), logicalRect)
    }

    @Test
    fun `computeVisibleLogicalRect clamps to image bounds when partially out of range`() {
        val sceneBounds = FloatRect(0f, 0f, 1000f, 1000f)
        val visibleRegion = FloatRect(-50f, -50f, 1200f, 1200f)
        val imageWidth = 2000
        val imageHeight = 2000

        val logicalRect = SceneImagePresentationCoordinator.computeVisibleLogicalRect(
            nodeSceneBounds = sceneBounds,
            nodeVisibleRegion = visibleRegion,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )

        assertEquals(IntRect(0, 0, 2000, 2000), logicalRect)
    }

    @Test
    fun `computeVisibleBounds calculates horizontal scene viewport under 2x zoom`() {
        val visible = SceneImagePresentationCoordinator.computeVisibleBounds(
            viewportWidth = 1000f,
            viewportHeight = 1500f,
            scrollOffset = 2000f,
            isHorizontal = true,
            canvasScale = 2f,
            canvasOffsetX = 0f,
            canvasOffsetY = 0f,
            totalSceneExtent = 10000f,
            totalCrossExtent = 1500f,
        )

        // Center = (500, 750)
        // Zoom 2x: span is [250..750] horizontally, [375..1125] vertically
        // Offset = 2000
        assertEquals(2250f, visible.left)
        assertEquals(375f, visible.top)
        assertEquals(2750f, visible.right)
        assertEquals(1125f, visible.bottom)
    }

    @Test
    fun `computeVisibleBounds calculates vertical scene viewport under 2x zoom`() {
        val visible = SceneImagePresentationCoordinator.computeVisibleBounds(
            viewportWidth = 1000f,
            viewportHeight = 1500f,
            scrollOffset = 3000f,
            isHorizontal = false,
            canvasScale = 2f,
            canvasOffsetX = 0f,
            canvasOffsetY = 0f,
            totalSceneExtent = 10000f,
            totalCrossExtent = 1000f,
        )

        // Center = (500, 750)
        // Zoom 2x: span is [250..750] horizontally, [375..1125] vertically
        // Offset = 3000 vertically
        assertEquals(250f, visible.left)
        assertEquals(3375f, visible.top)
        assertEquals(750f, visible.right)
        assertEquals(4125f, visible.bottom)
    }
}
