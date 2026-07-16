package org.skepsun.kototoro.core.ui.widgets

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BottomNavLayoutSpecTest {

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
}
