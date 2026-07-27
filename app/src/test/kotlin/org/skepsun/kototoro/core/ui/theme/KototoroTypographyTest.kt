package org.skepsun.kototoro.core.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KototoroTypographyTest {

	@Test
	fun expressiveTypographyMatchesCanonicalSemanticScale() {
		val typography = kototoroTypography(
			isExpressiveStyle = true,
			defaultFontFamily = null,
		)

		assertTextStyle(typography.headlineLarge, 32, 40, FontWeight.SemiBold)
		assertTextStyle(typography.headlineMedium, 28, 36, FontWeight.SemiBold)
		assertTextStyle(typography.titleLarge, 22, 28, FontWeight.SemiBold)
		assertTextStyle(typography.titleMedium, 16, 24, FontWeight.SemiBold)
		assertTextStyle(typography.bodyLarge, 16, 24, FontWeight.Normal)
		assertTextStyle(typography.bodyMedium, 14, 20, FontWeight.Normal)
		assertTextStyle(typography.bodySmall, 12, 16, FontWeight.Normal)
		assertTextStyle(typography.labelLarge, 14, 20, FontWeight.Medium)
		assertTextStyle(typography.labelMedium, 12, 16, FontWeight.Medium)
		assertTextStyle(typography.labelSmall, 11, 16, FontWeight.Medium)
	}

	@Test
	fun iosSharesScaleAndOnlyStrengthensDestinationTitles() {
		val typography = kototoroTypography(
			isExpressiveStyle = false,
			defaultFontFamily = null,
		)

		assertTextStyle(typography.headlineLarge, 32, 40, FontWeight.Bold)
		assertTextStyle(typography.headlineMedium, 28, 36, FontWeight.Bold)
		assertTextStyle(typography.titleLarge, 22, 28, FontWeight.SemiBold)
		assertTextStyle(typography.bodyLarge, 16, 24, FontWeight.Normal)
		assertTextStyle(typography.labelLarge, 14, 20, FontWeight.Medium)
	}

	@Test
	fun selectedFontFamilyIsAppliedToEverySemanticRole() {
		val typography = kototoroTypography(
			isExpressiveStyle = true,
			defaultFontFamily = FontFamily.Monospace,
		)

		assertEquals(FontFamily.Monospace, typography.headlineMedium.fontFamily)
		assertEquals(FontFamily.Monospace, typography.titleLarge.fontFamily)
		assertEquals(FontFamily.Monospace, typography.bodyMedium.fontFamily)
		assertEquals(FontFamily.Monospace, typography.labelSmall.fontFamily)
	}

	private fun assertTextStyle(
		style: androidx.compose.ui.text.TextStyle,
		fontSize: Int,
		lineHeight: Int,
		fontWeight: FontWeight,
	) {
		assertEquals(fontSize.sp, style.fontSize)
		assertEquals(lineHeight.sp, style.lineHeight)
		assertEquals(fontWeight, style.fontWeight)
		assertEquals(0.sp, style.letterSpacing)
	}
}
