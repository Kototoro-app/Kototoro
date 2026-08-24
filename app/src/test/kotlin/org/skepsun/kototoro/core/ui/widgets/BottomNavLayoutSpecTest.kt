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
