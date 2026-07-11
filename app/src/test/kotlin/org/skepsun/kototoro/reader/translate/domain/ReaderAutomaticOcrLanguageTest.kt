package org.skepsun.kototoro.reader.translate.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderAutomaticOcrLanguageTest {

	@Test
	fun `translated language takes precedence over source language`() {
		assertEquals("ko", resolveAutomaticReaderOcrLanguage("ko-KR", "en"))
	}

	@Test
	fun `branch language takes precedence over manga languages`() {
		assertEquals("ko", resolveAutomaticReaderOcrLanguage("en", "ja", "한국어 번역"))
		assertEquals("ja", resolveAutomaticReaderOcrLanguage("en", "ko", "Japanese / 日本語"))
	}

	@Test
	fun `branch language parser ignores ordinary names and ambiguous bilingual branches`() {
		assertNull(resolveReaderBranchLanguage("Kotatsu release group"))
		assertNull(resolveReaderBranchLanguage("한국어 / English"))
		assertEquals("en", resolveReaderBranchLanguage("[EN] Webtoon"))
	}

	@Test
	fun `unknown translated language falls back to source language`() {
		assertEquals("th", resolveAutomaticReaderOcrLanguage("auto", "th-TH"))
	}

	@Test
	fun `missing content languages return null`() {
		assertNull(resolveAutomaticReaderOcrLanguage("und", "unknown"))
		assertNull(resolveAutomaticReaderOcrLanguage(null, "  "))
		assertEquals(
			"ppocrv6_medium_rec_onnx",
			resolveAutomaticPaddleRecognizerModelId(
				resolveAutomaticReaderOcrLanguage(null, null),
			),
		)
	}

	@Test
	fun `specialized languages select their PP OCRv5 recognizers`() {
		assertEquals(
			"korean_ppocrv5_mobile_rec_onnx",
			resolveAutomaticPaddleRecognizerModelId("ko-KR"),
		)
		assertEquals(
			"thai_ppocrv5_mobile_rec_onnx",
			resolveAutomaticPaddleRecognizerModelId("th_TH"),
		)
	}

	@Test
	fun `Japanese selects MangaOCR for automatic recognition`() {
		assertEquals(
			"mangaocr_2025_onnx",
			resolveAutomaticReaderRecognizerModelId("ja-JP"),
		)
	}

	@Test
	fun `non-Japanese automatic recognition keeps using Paddle recognizers`() {
		listOf("ko-KR", "th_TH", "en", "zh-CN", null).forEach { language ->
			assertEquals(
				resolveAutomaticPaddleRecognizerModelId(language),
				resolveAutomaticReaderRecognizerModelId(language),
			)
		}
	}

	@Test
	fun `latin script languages select the Latin PP OCRv5 recognizer`() {
		listOf("en", "fr-FR", "de", "vi_VN", "hu", "no", "et", "lv", "lt", "sl", "sq").forEach { language ->
			assertEquals(
				"latin_ppocrv5_mobile_rec_onnx",
				resolveAutomaticPaddleRecognizerModelId(language),
			)
		}
	}

	@Test
	fun `non Latin scripts continue using the multilingual recognizer`() {
		listOf("bg", "el", "hi", "ru", "uk").forEach { language ->
			assertEquals(
				"ppocrv6_medium_rec_onnx",
				resolveAutomaticPaddleRecognizerModelId(language),
			)
		}
	}

	@Test
	fun `other and missing languages select multilingual PP OCRv6`() {
		listOf("ja", "zh-CN", "ru", "ar", null).forEach { language ->
			assertEquals(
				"ppocrv6_medium_rec_onnx",
				resolveAutomaticPaddleRecognizerModelId(language),
			)
		}
	}
}
