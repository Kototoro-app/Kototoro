package org.skepsun.kototoro.reader.ui.compose

import android.os.BatteryManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderInfoBarLayout

class ReaderInfoBarVisibilityTest {

	@Test
	fun `cutout avoidance is experimental and disabled by default`() {
		assertFalse(ReaderInfoBarState().avoidDisplayCutout)
	}

	@Test
	fun `enabled information bar is visible while controls are hidden`() {
		assertTrue(shouldShowReaderInfoBar(infoBarEnabled = true, controlsVisible = false))
	}

	@Test
	fun `information bar is hidden when disabled or controls are visible`() {
		assertFalse(shouldShowReaderInfoBar(infoBarEnabled = false, controlsVisible = false))
		assertFalse(shouldShowReaderInfoBar(infoBarEnabled = true, controlsVisible = true))
	}

	@Test
	fun `charging and full battery states use charging presentation`() {
		assertTrue(isReaderBatteryCharging(BatteryManager.BATTERY_STATUS_CHARGING))
		assertTrue(isReaderBatteryCharging(BatteryManager.BATTERY_STATUS_FULL))
		assertFalse(isReaderBatteryCharging(BatteryManager.BATTERY_STATUS_DISCHARGING))
		assertFalse(isReaderBatteryCharging(BatteryManager.BATTERY_STATUS_UNKNOWN))
	}

	@Test
	fun `center cutout separates reading and system status without moving the row`() {
		val placement = resolveReaderInfoBarPlacement(
			width = 1080,
			desiredReadingWidth = 260,
			systemStatusWidth = 150,
			layout = ReaderInfoBarLayout.CENTERED,
			leftPadding = 24,
			rightPadding = 24,
			cutout = ReaderInfoBarCutout(left = 490, right = 590),
			cutoutSpacing = 12,
		)

		assertEquals(478, placement.readingX + placement.readingWidth)
		assertEquals(602, placement.systemStatusX)
	}

	@Test
	fun `center cutout reserves its right side for progress before system status`() {
		val progressWidth = 40
		val itemSpacing = 12
		val placement = resolveReaderInfoBarPlacement(
			width = 1080,
			desiredReadingWidth = 260,
			systemStatusWidth = itemSpacing + progressWidth + 150,
			layout = ReaderInfoBarLayout.CENTERED,
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
	fun `split layout keeps edge content in place when it does not overlap cutout`() {
		val placement = resolveReaderInfoBarPlacement(
			width = 1080,
			desiredReadingWidth = 260,
			systemStatusWidth = 150,
			layout = ReaderInfoBarLayout.SPLIT,
			leftPadding = 24,
			rightPadding = 24,
			cutout = ReaderInfoBarCutout(left = 490, right = 590),
			cutoutSpacing = 12,
		)

		assertEquals(24, placement.readingX)
		assertEquals(906, placement.systemStatusX)
	}

	@Test
	fun `split layout keeps system status edge anchored when reading text grows into cutout`() {
		val placement = resolveReaderInfoBarPlacement(
			width = 1080,
			desiredReadingWidth = 480,
			systemStatusWidth = 150,
			layout = ReaderInfoBarLayout.SPLIT,
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
	fun `split layout places progress immediately after the cutout`() {
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
	fun `left edge cutout moves only reading status after the obstruction`() {
		val placement = resolveReaderInfoBarPlacement(
			width = 1080,
			desiredReadingWidth = 260,
			systemStatusWidth = 150,
			layout = ReaderInfoBarLayout.SPLIT,
			leftPadding = 24,
			rightPadding = 24,
			cutout = ReaderInfoBarCutout(left = 0, right = 100),
			cutoutSpacing = 12,
		)

		assertEquals(112, placement.readingX)
		assertEquals(906, placement.systemStatusX)
	}

	@Test
	fun `right edge cutout moves only system status before the obstruction`() {
		val placement = resolveReaderInfoBarPlacement(
			width = 1080,
			desiredReadingWidth = 260,
			systemStatusWidth = 150,
			layout = ReaderInfoBarLayout.SPLIT,
			leftPadding = 24,
			rightPadding = 24,
			cutout = ReaderInfoBarCutout(left = 980, right = 1080),
			cutoutSpacing = 12,
		)

		assertEquals(24, placement.readingX)
		assertEquals(818, placement.systemStatusX)
	}

	@Test
	fun `left quarter cutout moves intersecting reading status to its right`() {
		val placement = resolveReaderInfoBarPlacement(
			width = 1080,
			desiredReadingWidth = 260,
			systemStatusWidth = 150,
			layout = ReaderInfoBarLayout.SPLIT,
			leftPadding = 24,
			rightPadding = 24,
			cutout = ReaderInfoBarCutout(left = 220, right = 300),
			cutoutSpacing = 12,
		)

		assertEquals(312, placement.readingX)
		assertEquals(906, placement.systemStatusX)
	}

	@Test
	fun `right quarter cutout moves intersecting system status to its left`() {
		val placement = resolveReaderInfoBarPlacement(
			width = 1080,
			desiredReadingWidth = 260,
			systemStatusWidth = 150,
			layout = ReaderInfoBarLayout.SPLIT,
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
			layout = ReaderInfoBarLayout.SPLIT,
			leftPadding = 24,
			rightPadding = 24,
			cutout = null,
			cutoutSpacing = 12,
		)

		assertEquals(24, placement.readingX)
		assertEquals(906, placement.systemStatusX)
	}

	@Test
	fun `split layout reserves an asymmetric curved edge inset`() {
		val placement = resolveReaderInfoBarPlacement(
			width = 1080,
			desiredReadingWidth = 260,
			systemStatusWidth = 150,
			layout = ReaderInfoBarLayout.SPLIT,
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
}
