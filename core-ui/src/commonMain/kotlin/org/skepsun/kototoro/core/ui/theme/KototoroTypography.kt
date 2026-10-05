package org.skepsun.kototoro.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Extracted from the Android theme; both hosts use the same typographic roles. */
fun kototoroTypography(
    isExpressiveStyle: Boolean,
    defaultFontFamily: FontFamily?,
): Typography {
    val base = Typography()
    val destinationTitleWeight = if (isExpressiveStyle) FontWeight.SemiBold else FontWeight.Bold
    fun androidx.compose.ui.text.TextStyle.withDefaultFont(): androidx.compose.ui.text.TextStyle {
        return if (defaultFontFamily == null) this else copy(fontFamily = defaultFontFamily)
    }
    return base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = destinationTitleWeight, letterSpacing = 0.sp).withDefaultFont(),
        displayMedium = base.displayMedium.copy(fontWeight = destinationTitleWeight, letterSpacing = 0.sp).withDefaultFont(),
        displaySmall = base.displaySmall.copy(fontWeight = destinationTitleWeight, letterSpacing = 0.sp).withDefaultFont(),
        headlineLarge = base.headlineLarge.copy(
            fontWeight = destinationTitleWeight,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = destinationTitleWeight,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        titleSmall = base.titleSmall.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        bodyLarge = base.bodyLarge.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        bodyMedium = base.bodyMedium.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        bodySmall = base.bodySmall.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        labelLarge = base.labelLarge.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        labelMedium = base.labelMedium.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
        labelSmall = base.labelSmall.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
        ).withDefaultFont(),
    )
}
