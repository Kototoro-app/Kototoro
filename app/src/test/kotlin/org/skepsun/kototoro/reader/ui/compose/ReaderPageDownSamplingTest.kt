package org.skepsun.kototoro.reader.ui.compose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderMemoryOptimizationPolicyTest {

	@Test
	fun `page preloading remains enabled by default`() {
		assertEquals(1, resolveReaderBeyondViewportPageCount(false))
	}

	@Test
	fun `preload reduction removes pages beyond the viewport`() {
		assertEquals(0, resolveReaderBeyondViewportPageCount(true))
	}

	@Test
	fun `webtoon preloads two viewports unless reduction is enabled`() {
		assertEquals(2f, resolveWebtoonAheadCacheFraction(false))
		assertEquals(0f, resolveWebtoonAheadCacheFraction(true))
	}

	@Test
	fun `telephoto preserves the configured bitmap precision`() {
		assertEquals(ImageBitmapConfig.Argb8888, Bitmap.Config.ARGB_8888.toImageBitmapConfig())
		assertEquals(ImageBitmapConfig.Rgb565, Bitmap.Config.RGB_565.toImageBitmapConfig())
	}
}
