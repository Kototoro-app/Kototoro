package org.skepsun.kototoro.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ColorSchemeExtTest {

	@Test
	fun `transparent light background remains light`() {
		val colorScheme = lightColorScheme(
			background = Color.Transparent,
			onBackground = Color.Black,
		)

		assertFalse(colorScheme.isDarkTheme())
	}

	@Test
	fun `transparent dark background remains dark`() {
		val colorScheme = darkColorScheme(
			background = Color.Transparent,
			onBackground = Color.White,
		)

		assertTrue(colorScheme.isDarkTheme())
	}

	@Test
	fun `artwork overlay follows light theme`() {
		val colorScheme = lightColorScheme(onBackground = Color.Black)

		assertEquals(Color.White.copy(alpha = 0.68f), colorScheme.artworkOverlayColor())
	}

	@Test
	fun `artwork overlay follows dark theme`() {
		val colorScheme = darkColorScheme(onBackground = Color.White)

		assertEquals(Color.Black.copy(alpha = 0.60f), colorScheme.artworkOverlayColor())
	}
}
