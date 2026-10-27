package org.skepsun.kototoro.reader.ui.compose.design

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The info-bar geometry moved out of Android's reader scaffold: the shared row places the reading status, the
 * progress and the host status slot on both hosts, so the placement rules are tested here rather than in `:app`.
 */
class ReaderInfoBarGeometryTest {

    @Test
    fun `centered bar separates reading and status without moving the row`() {
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 260,
            systemStatusWidth = 150,
            alignment = ReaderInfoBarAlignment.CENTERED,
            leftPadding = 24,
            rightPadding = 24,
            cutout = ReaderInfoBarCutout(left = 490, right = 590),
            cutoutSpacing = 12,
        )

        assertEquals(478, placement.readingX + placement.readingWidth)
        assertEquals(602, placement.systemStatusX)
    }

    @Test
    fun `centered bar reserves a cutout side for the progress text`() {
        val progressWidth = 40
        val itemSpacing = 12
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 260,
            systemStatusWidth = itemSpacing + progressWidth + 150,
            alignment = ReaderInfoBarAlignment.CENTERED,
            leftPadding = 24,
            rightPadding = 24,
            cutout = ReaderInfoBarCutout(left = 490, right = 590),
            cutoutSpacing = 12,
        )

        assertEquals(478, placement.readingX + placement.readingWidth)
        assertEquals(602, placement.systemStatusX)
        assertEquals(614, placement.systemStatusX + itemSpacing)
    }

    @Test
    fun `split bar keeps edge content in place when it misses the cutout`() {
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 260,
            systemStatusWidth = 150,
            alignment = ReaderInfoBarAlignment.SPLIT,
            leftPadding = 24,
            rightPadding = 24,
            cutout = ReaderInfoBarCutout(left = 490, right = 590),
            cutoutSpacing = 12,
        )

        assertEquals(24, placement.readingX)
        assertEquals(906, placement.systemStatusX)
    }

    @Test
    fun `split bar keeps the status edge anchored when the reading text grows`() {
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 480,
            systemStatusWidth = 150,
            alignment = ReaderInfoBarAlignment.SPLIT,
            leftPadding = 24,
            rightPadding = 24,
            cutout = ReaderInfoBarCutout(left = 490, right = 590),
            cutoutSpacing = 12,
        )

        assertEquals(24, placement.readingX)
        assertEquals(454, placement.readingWidth)
        assertEquals(906, placement.systemStatusX)
    }

    @Test
    fun `split bar places the progress text right after the cutout`() {
        val placement = resolveReaderInfoBarProgressPlacement(
            readingEnd = 478,
            systemStatusX = 906,
            desiredProgressWidth = 40,
            cutout = ReaderInfoBarCutout(left = 490, right = 590),
            cutoutSpacing = 12,
        )

        assertEquals(602, placement.x)
        assertEquals(40, placement.width)
    }

    @Test
    fun `left edge cutout moves only the reading status`() {
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 260,
            systemStatusWidth = 150,
            alignment = ReaderInfoBarAlignment.SPLIT,
            leftPadding = 24,
            rightPadding = 24,
            cutout = ReaderInfoBarCutout(left = 0, right = 100),
            cutoutSpacing = 12,
        )

        assertEquals(112, placement.readingX)
        assertEquals(906, placement.systemStatusX)
    }

    @Test
    fun `right edge cutout moves only the status slot`() {
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 260,
            systemStatusWidth = 150,
            alignment = ReaderInfoBarAlignment.SPLIT,
            leftPadding = 24,
            rightPadding = 24,
            cutout = ReaderInfoBarCutout(left = 980, right = 1080),
            cutoutSpacing = 12,
        )

        assertEquals(24, placement.readingX)
        assertEquals(818, placement.systemStatusX)
    }

    @Test
    fun `left quarter cutout moves the reading status to its right`() {
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 260,
            systemStatusWidth = 150,
            alignment = ReaderInfoBarAlignment.SPLIT,
            leftPadding = 24,
            rightPadding = 24,
            cutout = ReaderInfoBarCutout(left = 220, right = 300),
            cutoutSpacing = 12,
        )

        assertEquals(312, placement.readingX)
        assertEquals(906, placement.systemStatusX)
    }

    @Test
    fun `right quarter cutout moves the status slot to its left`() {
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 260,
            systemStatusWidth = 150,
            alignment = ReaderInfoBarAlignment.SPLIT,
            leftPadding = 24,
            rightPadding = 24,
            cutout = ReaderInfoBarCutout(left = 850, right = 930),
            cutoutSpacing = 12,
        )

        assertEquals(24, placement.readingX)
        assertEquals(688, placement.systemStatusX)
    }

    @Test
    fun `disabled cutout avoidance keeps both split groups edge anchored`() {
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 260,
            systemStatusWidth = 150,
            alignment = ReaderInfoBarAlignment.SPLIT,
            leftPadding = 24,
            rightPadding = 24,
            cutout = null,
            cutoutSpacing = 12,
        )

        assertEquals(24, placement.readingX)
        assertEquals(906, placement.systemStatusX)
    }

    @Test
    fun `split bar honours an asymmetric curved edge inset`() {
        val placement = resolveReaderInfoBarPlacement(
            width = 1080,
            desiredReadingWidth = 260,
            systemStatusWidth = 150,
            alignment = ReaderInfoBarAlignment.SPLIT,
            leftPadding = 24,
            rightPadding = 72,
            cutout = null,
            cutoutSpacing = 12,
        )

        assertEquals(1008, placement.systemStatusX + 150)
    }

    @Test
    fun `rounded corner geometry protects content at its actual vertical position`() {
        val padding = resolveReaderInfoBarRoundedCornerPadding(
            edgeDistanceToCenter = 158,
            cornerCenterY = 158,
            radius = 158,
            contentTopY = 20,
            spacing = 13,
        )

        assertEquals(95, padding)
    }

    @Test
    fun `rounded corner padding disappears for a square corner`() {
        assertEquals(
            0,
            resolveReaderInfoBarRoundedCornerPadding(
                edgeDistanceToCenter = 0,
                cornerCenterY = 158,
                radius = 158,
                contentTopY = 20,
                spacing = 13,
            ),
        )
        assertEquals(
            0,
            resolveReaderInfoBarRoundedCornerPadding(
                edgeDistanceToCenter = 158,
                cornerCenterY = 158,
                radius = 0,
                contentTopY = 20,
                spacing = 13,
            ),
        )
    }
}
