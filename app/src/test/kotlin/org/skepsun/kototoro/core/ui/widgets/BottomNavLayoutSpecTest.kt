package org.skepsun.kototoro.core.ui.widgets

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BottomNavLayoutSpecTest {

	@Test
	fun `badge number is capped before it can overflow navigation item`() {
		formatBottomNavBadgeNumber(7) shouldBe "7"
		formatBottomNavBadgeNumber(99) shouldBe "99"
		formatBottomNavBadgeNumber(100) shouldBe "100"
		formatBottomNavBadgeNumber(999) shouldBe "999"
		formatBottomNavBadgeNumber(1000) shouldBe "999+"
		formatBottomNavBadgeNumber(Int.MAX_VALUE) shouldBe "999+"
	}

	@Test
	fun `five items with fab use compact density before minimum density`() {
		resolveBottomNavLayout(
			availableWidth = 360.dp,
			itemCount = 5,
			fabWidth = 56.dp,
			showLabels = true,
		).let { spec ->
			spec.density shouldBe BottomNavDensity.COMPACT
			spec.showLabels shouldBe false
		}
	}

	@Test
	fun `narrow layout keeps minimum density without shrinking touch targets`() {
		resolveBottomNavLayout(
			availableWidth = 320.dp,
			itemCount = 5,
			fabWidth = 56.dp,
			showLabels = true,
		).let { spec ->
			spec.density shouldBe BottomNavDensity.MINIMUM
			spec.itemSpacing shouldBe 2.dp
			spec.horizontalPadding shouldBe 0.dp
		}
	}

	@Test
	fun `wide layout preserves regular density and label preference`() {
		resolveBottomNavLayout(
			availableWidth = 420.dp,
			itemCount = 4,
			fabWidth = 56.dp,
			showLabels = true,
		).let { spec ->
			spec.density shouldBe BottomNavDensity.REGULAR
			spec.showLabels shouldBe true
		}
	}

	@Test
	fun `expressive compact layout keeps selected label with smaller text`() {
		resolveBottomNavLayout(
			availableWidth = 393.dp,
			itemCount = 5,
			fabWidth = 56.dp,
			showLabels = true,
			isExpressivePill = true,
		).let { spec ->
			spec.density shouldBe BottomNavDensity.COMPACT
			spec.showLabels shouldBe true
			spec.labelScale shouldBe 0.84f
			spec.labelMaxWidth shouldBe 40.dp
		}
	}

	@Test
	fun `idle selection pill has no motion-only lens or edge effects`() {
		resolveBottomNavPillEffect(0f).let { spec ->
			spec.idleMaterialFraction shouldBe 1f
			spec.lensHeightDp shouldBe 0f
			spec.lensAmountDp shouldBe 0f
			spec.highlightAlpha shouldBe 0f
			spec.innerShadowAlpha shouldBe 0f
		}
	}

	@Test
	fun `expanded selection pill reaches bilipai motion material`() {
		resolveBottomNavPillEffect(1f).let { spec ->
			spec.idleMaterialFraction shouldBe 0f
			spec.lensHeightDp shouldBe 10f
			spec.lensAmountDp shouldBe 14f
			spec.highlightAlpha shouldBe 1f
			spec.innerShadowAlpha shouldBe 0.15f
		}
	}

	@Test
	fun `expressive and full width pills use the same bilipai magnification amplitude`() {
		resolveBottomNavMagnifyScale() shouldBe 78f / 56f
	}

	@Test
	fun `full width pill matches the tab content height at the default bar`() {
		// navFloatingHeight 52 -> 56dp bar, tab content 48dp after the 4dp
		// insets; the resting pill must follow the tab instead of overflowing.
		resolveBottomNavFullWidthPillHeight(
			tabContentHeightPx = 48,
			idealPillHeightPx = 56,
		) shouldBe 48
	}

	@Test
	fun `full width pill shrinks with the smallest floating bar`() {
		// navFloatingHeight 48 -> 52dp bar, tab content only 44dp.
		resolveBottomNavFullWidthPillHeight(
			tabContentHeightPx = 44,
			idealPillHeightPx = 56,
		) shouldBe 44
	}

	@Test
	fun `full width pill never grows past the 56dp sample cap`() {
		// A tall bar (84dp) leaves a 76dp tab; the resting pill stays at the
		// sample's 56dp design height rather than filling the whole tab.
		resolveBottomNavFullWidthPillHeight(
			tabContentHeightPx = 76,
			idealPillHeightPx = 56,
		) shouldBe 56
	}

	@Test
	fun `full width pill falls back to the ideal height before measurement`() {
		resolveBottomNavFullWidthPillHeight(
			tabContentHeightPx = 0,
			idealPillHeightPx = 56,
		) shouldBe 56
	}

	@Test
	fun `floating bar height follows the floating height setting plus inset`() {
		resolveNavBarHeight(
			isFloating = true,
			navHeight = 80,
			navFloatingHeight = 52,
		) shouldBe 56.dp
		resolveNavBarHeight(
			isFloating = true,
			navHeight = 80,
			navFloatingHeight = 84,
		) shouldBe 88.dp
	}

	@Test
	fun `docked bar height follows the nav height setting`() {
		resolveNavBarHeight(
			isFloating = false,
			navHeight = 48,
			navFloatingHeight = 52,
		) shouldBe 48.dp
		resolveNavBarHeight(
			isFloating = false,
			navHeight = 88,
			navFloatingHeight = 52,
		) shouldBe 88.dp
	}

	@Test
	fun `dragged indicator center follows the pointer instead of snapping to a tab`() {
		resolveBottomNavDragIndicatorX(
			pointerX = 137.5f,
			indicatorWidth = 80,
			containerWidth = 360,
			snappedOffsetX = 80,
		) shouldBe 98
	}

	@Test
	fun `released indicator interpolates from finger to selected tab center`() {
		interpolateBottomNavSettleX(startX = 80f, targetX = 160f, progress = 0.5f) shouldBe 120f
	}
}
