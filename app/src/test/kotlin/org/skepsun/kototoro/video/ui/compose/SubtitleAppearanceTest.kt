package org.skepsun.kototoro.video.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubtitleAppearanceTest {

    @Test
    fun `no lines draws no background`() {
        val boxes = subtitleBackgroundBoxes(
            style = SubtitleBackgroundStyle.LINES,
            lines = emptyList(),
            containerWidth = 1000f,
            containerHeight = 200f,
            paddingHorizontal = 12f,
            paddingVertical = 6f,
        )

        assertTrue(boxes.isEmpty())
    }

    @Test
    fun `disabled background draws no boxes`() {
        val boxes = subtitleBackgroundBoxes(
            style = SubtitleBackgroundStyle.NONE,
            lines = listOf(rect(400f, 100f, 600f, 130f)),
            containerWidth = 1000f,
            containerHeight = 200f,
            paddingHorizontal = 12f,
            paddingVertical = 6f,
        )

        assertTrue(boxes.isEmpty())
    }

    @Test
    fun `unmeasured container draws no boxes`() {
        val boxes = subtitleBackgroundBoxes(
            style = SubtitleBackgroundStyle.BAR,
            lines = listOf(rect(400f, 100f, 600f, 130f)),
            containerWidth = 0f,
            containerHeight = 200f,
            paddingHorizontal = 12f,
            paddingVertical = 6f,
        )

        assertTrue(boxes.isEmpty())
    }

    @Test
    fun `bar spans the whole container and grows by the vertical padding`() {
        val boxes = subtitleBackgroundBoxes(
            style = SubtitleBackgroundStyle.BAR,
            lines = listOf(rect(400f, 100f, 600f, 130f)),
            containerWidth = 1000f,
            containerHeight = 200f,
            paddingHorizontal = 12f,
            paddingVertical = 6f,
        )

        assertEquals(1, boxes.size)
        assertEquals(0f, boxes[0].left)
        assertEquals(1000f, boxes[0].right)
        assertEquals(94f, boxes[0].top)
        assertEquals(136f, boxes[0].bottom)
    }

    @Test
    fun `block hugs the widest line instead of the container`() {
        val boxes = subtitleBackgroundBoxes(
            style = SubtitleBackgroundStyle.BLOCK,
            lines = listOf(
                rect(400f, 100f, 600f, 130f),
                rect(300f, 134f, 700f, 164f),
            ),
            containerWidth = 1000f,
            containerHeight = 200f,
            paddingHorizontal = 12f,
            paddingVertical = 6f,
        )

        assertEquals(1, boxes.size)
        assertEquals(288f, boxes[0].left)
        assertEquals(712f, boxes[0].right)
        assertEquals(94f, boxes[0].top)
        assertEquals(170f, boxes[0].bottom)
    }

    @Test
    fun `line style follows each rendered line`() {
        val boxes = subtitleBackgroundBoxes(
            style = SubtitleBackgroundStyle.LINES,
            lines = listOf(
                rect(400f, 100f, 600f, 130f),
                rect(350f, 134f, 650f, 164f),
            ),
            containerWidth = 1000f,
            containerHeight = 200f,
            paddingHorizontal = 12f,
            paddingVertical = 6f,
        )

        assertEquals(2, boxes.size)
        assertEquals(388f, boxes[0].left)
        assertEquals(612f, boxes[0].right)
        assertEquals(338f, boxes[1].left)
        assertEquals(662f, boxes[1].right)
        assertEquals(94f, boxes[0].top)
        assertEquals(132f, boxes[0].bottom)
        assertEquals(132f, boxes[1].top)
        assertEquals(170f, boxes[1].bottom)
    }

    @Test
    fun `line boxes share the gap between two lines instead of overlapping`() {
        val boxes = subtitleBackgroundBoxes(
            style = SubtitleBackgroundStyle.LINES,
            lines = listOf(
                rect(400f, 100f, 600f, 130f),
                rect(400f, 132f, 600f, 162f),
            ),
            containerWidth = 1000f,
            containerHeight = 200f,
            paddingHorizontal = 12f,
            paddingVertical = 12f,
        )

        assertEquals(2, boxes.size)
        assertEquals(88f, boxes[0].top)
        assertEquals(131f, boxes[0].bottom)
        assertEquals(131f, boxes[1].top)
        assertEquals(174f, boxes[1].bottom)
        assertTrue(boxes[0].bottom <= boxes[1].top)
    }

    @Test
    fun `boxes are clipped to the container`() {
        val boxes = subtitleBackgroundBoxes(
            style = SubtitleBackgroundStyle.LINES,
            lines = listOf(rect(2f, 2f, 998f, 30f)),
            containerWidth = 1000f,
            containerHeight = 200f,
            paddingHorizontal = 20f,
            paddingVertical = 20f,
        )

        assertEquals(1, boxes.size)
        assertEquals(0f, boxes[0].left)
        assertEquals(1000f, boxes[0].right)
        assertEquals(0f, boxes[0].top)
        assertEquals(50f, boxes[0].bottom)
    }

    @Test
    fun `unknown background values fall back to the default style`() {
        assertEquals(SubtitleBackgroundStyle.Default, SubtitleBackgroundStyle.fromPrefValue(-1))
        assertEquals(SubtitleBackgroundStyle.Default, SubtitleBackgroundStyle.fromPrefValue(99))
        assertEquals(SubtitleBackgroundStyle.BAR, SubtitleBackgroundStyle.fromPrefValue(1))
    }

    @Test
    fun `unknown font families fall back to the default family`() {
        assertEquals(SubtitleFontFamily.Default, SubtitleFontFamily.fromPrefValue(-1))
        assertEquals(SubtitleFontFamily.Default, SubtitleFontFamily.fromPrefValue(42))
        assertEquals(SubtitleFontFamily.SERIF, SubtitleFontFamily.fromPrefValue(1))
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        SubtitleRect(left = left, top = top, right = right, bottom = bottom)
}
