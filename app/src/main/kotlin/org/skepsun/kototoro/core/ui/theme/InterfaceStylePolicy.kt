package org.skepsun.kototoro.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import org.skepsun.kototoro.core.prefs.InterfaceStyle

/** Behavioral decisions for shared Compose components. Geometry belongs in [InterfaceStyleTokens]. */
data class InterfaceStylePolicy(
	val useLiquidGlass: Boolean,
	val useExpressiveComponents: Boolean,
	val emphasizeNavigationSelection: Boolean,
	val useExpandedTouchTargets: Boolean,
) {
	companion object {
		fun from(style: InterfaceStyle): InterfaceStylePolicy = when (style) {
			@Suppress("DEPRECATION")
			InterfaceStyle.MATERIAL_3,
			InterfaceStyle.MATERIAL_3_EXPRESSIVE -> InterfaceStylePolicy(
				useLiquidGlass = false,
				useExpressiveComponents = true,
				emphasizeNavigationSelection = true,
				useExpandedTouchTargets = true,
			)
			InterfaceStyle.IOS -> InterfaceStylePolicy(
				useLiquidGlass = true,
				useExpressiveComponents = false,
				emphasizeNavigationSelection = true,
				useExpandedTouchTargets = true,
			)
		}
	}
}

val LocalInterfaceStylePolicy = staticCompositionLocalOf {
	InterfaceStylePolicy.from(InterfaceStyle.MATERIAL_3_EXPRESSIVE)
}
