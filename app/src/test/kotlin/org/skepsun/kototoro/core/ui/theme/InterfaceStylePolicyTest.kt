package org.skepsun.kototoro.core.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.normalized

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
	@Suppress("DEPRECATION")
	fun legacyMd3MigratesToExpressiveDefaults() {
		val migratedStyle = InterfaceStyle.MATERIAL_3.normalized()
		val policy = InterfaceStylePolicy.from(migratedStyle)

		assertEquals(InterfaceStyle.MATERIAL_3_EXPRESSIVE, migratedStyle)
		assertTrue(policy.useExpressiveComponents)
		assertTrue(policy.useExpandedTouchTargets)
		assertEquals(56, migratedStyle.tokens().controlHeight.value.toInt())
	}
}
