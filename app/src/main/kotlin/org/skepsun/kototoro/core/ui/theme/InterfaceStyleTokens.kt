package org.skepsun.kototoro.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.InterfaceStyle

/** Shared geometry for a complete interface style. Keep component sizing here instead of scattering flags. */
data class InterfaceStyleTokens(
    val screenHorizontalPadding: Dp,
    val sectionVerticalSpacing: Dp,
    val groupCornerRadius: Dp,
    val settingsGroupOuterCornerRadius: Dp,
    val settingsGroupInnerCornerRadius: Dp,
    val settingsItemGap: Dp,
    val settingsItemMinHeight: Dp,
    val settingsItemIconContainerSize: Dp,
    val controlCornerRadius: Dp,
    val controlHeight: Dp,
    val compactControlHeight: Dp,
    val minimumTouchTarget: Dp,
    val mainTopBarHeight: Dp,
    val secondaryTopBarHeight: Dp,
    val topBarButtonSize: Dp,
    val topBarIconSize: Dp,
    val dialogCornerRadius: Dp,
    val dialogTonalElevation: Dp,
    val dialogContainerAlpha: Float,
    val sheetCornerRadius: Dp,
    val sliderTrackHeight: Dp,
    val sliderThumbSize: Dp,
    val sliderPressedThumbWidth: Dp,
    val sliderPressedThumbHeight: Dp,
) {
    companion object
}

val LocalInterfaceStyleTokens = staticCompositionLocalOf { InterfaceStyleTokens.Material3Expressive }

val InterfaceStyleTokens.Companion.Ios: InterfaceStyleTokens
    get() = InterfaceStyleTokens(
        screenHorizontalPadding = 16.dp,
        sectionVerticalSpacing = 20.dp,
        groupCornerRadius = 18.dp,
        settingsGroupOuterCornerRadius = 16.dp,
        settingsGroupInnerCornerRadius = 4.dp,
        settingsItemGap = 0.dp,
        settingsItemMinHeight = 56.dp,
        settingsItemIconContainerSize = 40.dp,
        controlCornerRadius = 12.dp,
        controlHeight = 50.dp,
        compactControlHeight = 42.dp,
        minimumTouchTarget = 48.dp,
        mainTopBarHeight = 64.dp,
        secondaryTopBarHeight = 56.dp,
        topBarButtonSize = 44.dp,
        topBarIconSize = 22.dp,
        dialogCornerRadius = 22.dp,
        dialogTonalElevation = 0.dp,
        dialogContainerAlpha = 0.96f,
        sheetCornerRadius = 28.dp,
        sliderTrackHeight = 4.dp,
        sliderThumbSize = 20.dp,
        sliderPressedThumbWidth = 24.dp,
        sliderPressedThumbHeight = 24.dp,
    )

val InterfaceStyleTokens.Companion.Material3Expressive: InterfaceStyleTokens
    get() = InterfaceStyleTokens(
        screenHorizontalPadding = 20.dp,
        sectionVerticalSpacing = 20.dp,
        groupCornerRadius = 28.dp,
        settingsGroupOuterCornerRadius = 24.dp,
        settingsGroupInnerCornerRadius = 4.dp,
        settingsItemGap = 2.dp,
        settingsItemMinHeight = 56.dp,
        settingsItemIconContainerSize = 40.dp,
        controlCornerRadius = 20.dp,
        controlHeight = 50.dp,
        compactControlHeight = 42.dp,
        minimumTouchTarget = 48.dp,
        mainTopBarHeight = 64.dp,
        secondaryTopBarHeight = 56.dp,
        topBarButtonSize = 44.dp,
        topBarIconSize = 22.dp,
        dialogCornerRadius = 28.dp,
        dialogTonalElevation = 6.dp,
        dialogContainerAlpha = 1f,
        sheetCornerRadius = 36.dp,
        sliderTrackHeight = 4.dp,
        sliderThumbSize = 20.dp,
        sliderPressedThumbWidth = 24.dp,
        sliderPressedThumbHeight = 24.dp,
    )

fun InterfaceStyle.tokens(): InterfaceStyleTokens = when (this) {
    @Suppress("DEPRECATION")
    InterfaceStyle.MATERIAL_3,
    InterfaceStyle.MATERIAL_3_EXPRESSIVE -> InterfaceStyleTokens.Material3Expressive
    InterfaceStyle.IOS -> InterfaceStyleTokens.Ios
}
