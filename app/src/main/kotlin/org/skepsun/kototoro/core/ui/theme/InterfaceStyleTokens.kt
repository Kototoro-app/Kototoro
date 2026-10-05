package org.skepsun.kototoro.core.ui.theme

import org.skepsun.kototoro.core.prefs.InterfaceStyle

fun InterfaceStyle.tokens(): InterfaceStyleTokens = when (this) {
    @Suppress("DEPRECATION")
    InterfaceStyle.MATERIAL_3,
    InterfaceStyle.MATERIAL_3_EXPRESSIVE -> InterfaceStyleTokens.Material3Expressive
    InterfaceStyle.IOS -> InterfaceStyleTokens.Ios
}
