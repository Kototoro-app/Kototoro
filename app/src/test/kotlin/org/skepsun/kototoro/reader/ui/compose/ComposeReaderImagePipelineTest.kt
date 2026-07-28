package org.skepsun.kototoro.reader.ui.compose

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComposeReaderImagePipelineTest {

	@Test
	fun `recognizes only images wider than legacy split threshold`() {
		assertEquals(true, isWideReaderPage(width = 116, height = 100))
		assertEquals(false, isWideReaderPage(width = 115, height = 100))
		assertEquals(false, isWideReaderPage(width = 0, height = 100))
	}

	@Test
	fun `uses preview for regular sources`() {
		assertEquals(
			"https://example.org/preview.jpg",
			resolveReaderPreviewUrl("https://example.org/preview.jpg", "MANGA_SOURCE"),
		)
	}

	@Test
	fun `skips blank preview urls`() {
		assertNull(resolveReaderPreviewUrl("  ", "MANGA_SOURCE"))
	}

	@Test
	fun `skips previews for json sources without reliable referer`() {
		assertNull(resolveReaderPreviewUrl("https://example.org/preview.jpg", "JSON_REMOTE"))
	}

	@Test
	fun `reuses image metadata probe within reader session`() = runTest {
		val cache = ReaderImageMetadataCache()
		var probes = 0
		val first = cache.isAnimated("page") {
			probes++
			true
		}
		val second = cache.isAnimated("page") {
			probes++
			false
		}

		assertEquals(true, first)
		assertEquals(true, second)
		assertEquals(1, probes)
	}

	@Test
	fun `coalesces concurrent image metadata probes`() = runTest {
		val cache = ReaderImageMetadataCache()
		var probes = 0
		val first = async {
			cache.isAnimated("page") {
				probes++
				true
			}
		}
		val second = async {
			cache.isAnimated("page") {
				probes++
				false
			}
		}

		assertEquals(true, first.await())
		assertEquals(true, second.await())
		assertEquals(1, probes)
	}
}
