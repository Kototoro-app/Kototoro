package org.skepsun.kototoro.core.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.InterfaceStyle

class InterfaceStylePolicyTest {

	@Test
	fun expressiveUsesExpandedControlsWithoutIosGlass() {
		val policy = InterfaceStylePolicy.from(InterfaceStyle.MATERIAL_3_EXPRESSIVE)

		assertTrue(policy.useExpressiveComponents)
		assertTrue(policy.useExpandedTouchTargets)
		assertTrue(policy.emphasizeNavigationSelection)
		assertFalse(policy.useLiquidGlass)
		assertEquals(56, InterfaceStyle.MATERIAL_3_EXPRESSIVE.tokens().controlHeight.value.toInt())
	}

	@Test
	fun iosKeepsLiquidGlassSeparateFromExpressiveComponents() {
		val policy = InterfaceStylePolicy.from(InterfaceStyle.IOS)

		assertTrue(policy.useLiquidGlass)
		assertFalse(policy.useExpressiveComponents)
	}

	@Test
	fun standardMd3KeepsCompactDefaults() {
		val policy = InterfaceStylePolicy.from(InterfaceStyle.MATERIAL_3)

		assertFalse(policy.useExpressiveComponents)
		assertFalse(policy.useExpandedTouchTargets)
		assertEquals(48, InterfaceStyle.MATERIAL_3.tokens().controlHeight.value.toInt())
	}
}
