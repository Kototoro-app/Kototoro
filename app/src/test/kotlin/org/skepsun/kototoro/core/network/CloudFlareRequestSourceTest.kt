package org.skepsun.kototoro.core.network

import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.ContentType as KotatsuContentType
import org.koitharu.kotatsu.parsers.model.MangaSource as KotatsuMangaSource
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource
import org.skepsun.kototoro.parsers.model.ContentSource

class CloudFlareRequestSourceTest {

	@Test
	fun `explicit content source remains authoritative`() {
		val request = Request.Builder()
			.url("https://example.test/api/list")
			.tag(ContentSource::class.java, TestContentSource)
			.tag(KotatsuMangaSource::class.java, testKotatsuSource)
			.build()

		assertSame(TestContentSource, request.resolveContentSource())
	}

	@Test
	fun `kotatsu source tag is available before common headers interceptor`() {
		val request = Request.Builder()
			.url("https://example.test/api/list")
			.tag(KotatsuMangaSource::class.java, testKotatsuSource)
			.build()

		val source = request.resolveContentSource() as KotatsuParserSource

		assertSame(testKotatsuSource, source.delegate)
		assertEquals(testKotatsuSource.name, source.name)
	}

	private val testKotatsuSource = object : KotatsuMangaSource {
		override val name = "KAGANE_TEST"
		override val locale = "en"
		override val contentType = KotatsuContentType.MANGA
	}
}
