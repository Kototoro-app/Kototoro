package org.skepsun.kototoro.core.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InterfaceStyleTokensTest {

	@Test
	fun bothStylesShareTopBarStructureAndMinimumTouchTarget() {
		val ios = InterfaceStyleTokens.Ios
		val expressive = InterfaceStyleTokens.Material3Expressive

		listOf(ios, expressive).forEach { tokens ->
			assertEquals(64.dp, tokens.mainTopBarHeight)
			assertEquals(56.dp, tokens.secondaryTopBarHeight)
			assertEquals(48.dp, tokens.minimumTouchTarget)
		}
	}

	@Test
	fun visibleTopBarControlsRemainStyleSpecific() {
		assertEquals(44.dp, InterfaceStyleTokens.Ios.topBarButtonSize)
		assertEquals(22.dp, InterfaceStyleTokens.Ios.topBarIconSize)
		assertEquals(48.dp, InterfaceStyleTokens.Material3Expressive.topBarButtonSize)
		assertEquals(24.dp, InterfaceStyleTokens.Material3Expressive.topBarIconSize)
	}

	@Test
	fun dialogGeometryKeepsStableStyleSpecificSurface() {
		assertEquals(22.dp, InterfaceStyleTokens.Ios.dialogCornerRadius)
		assertEquals(0.dp, InterfaceStyleTokens.Ios.dialogTonalElevation)
		assertEquals(0.96f, InterfaceStyleTokens.Ios.dialogContainerAlpha)

		assertEquals(28.dp, InterfaceStyleTokens.Material3Expressive.dialogCornerRadius)
		assertEquals(6.dp, InterfaceStyleTokens.Material3Expressive.dialogTonalElevation)
		assertEquals(1f, InterfaceStyleTokens.Material3Expressive.dialogContainerAlpha)
	}
}
