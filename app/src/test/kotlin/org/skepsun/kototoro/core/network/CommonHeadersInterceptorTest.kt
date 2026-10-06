package org.skepsun.kototoro.core.network

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.mihon.compat.SourceRequestContext
import org.skepsun.kototoro.parsers.model.ContentSource

class CommonHeadersInterceptorTest {

	@Test
	fun `desktop cloudstream user agent emits desktop client hints`() {
		val hints = browserClientHints(
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
				"AppleWebKit/537.36 Chrome/149.0.0.0 Safari/537.36",
		)

		assertEquals("?0", hints.mobile)
		assertEquals("Windows", hints.platform)
	}

	@Test
	fun `android webview user agent emits mobile client hints`() {
		val hints = browserClientHints(
			"Mozilla/5.0 (Linux; Android 16; Device Build/Test; wv) " +
				"AppleWebKit/537.36 Chrome/150.0.0.0 Mobile Safari/537.36",
		)

		assertEquals("?1", hints.mobile)
		assertEquals("Android", hints.platform)
	}

	@Test
	fun `mihon request keeps the headers its extension removed`() {
		val recorded = execute { url ->
			Request.Builder()
				.url(url)
				.header("User-Agent", USER_AGENT)
				.header("Referer", "https://comix.to/")
				.tag(ContentSource::class.java, source)
				.tag(SourceRequestContext::class.java, SourceRequestContext.from(source, "https://comix.to"))
				.build()
		}

		assertNull(recorded.getHeader("Origin"))
		assertEquals("https://comix.to/", recorded.getHeader("Referer"))
	}

	@Test
	fun `source request without extension context gets the source defaults`() {
		val recorded = execute { url ->
			Request.Builder()
				.url(url)
				.header("User-Agent", USER_AGENT)
				.tag(ContentSource::class.java, source)
				.build()
		}

		assertEquals("https://comix.to", recorded.getHeader("Origin"))
		assertEquals("https://comix.to/", recorded.getHeader("Referer"))
	}

	private fun execute(buildRequest: (okhttp3.HttpUrl) -> Request) = MockWebServer().use { server ->
		server.enqueue(MockResponse().setBody("ok"))
		val repository = mockk<ContentRepository>(relaxed = true) {
			every { getRequestHeaders() } returns mapOf(
				"Origin" to "https://comix.to",
				"Referer" to "https://comix.to/",
			)
			every { source } returns this@CommonHeadersInterceptorTest.source
		}
		val factory = mockk<ContentRepository.Factory> {
			every { create(any()) } returns repository
		}
		val interceptor = CommonHeadersInterceptor(mockk<Context>(relaxed = true), { factory }, { mockk() })
		OkHttpClient.Builder()
			.addInterceptor(interceptor)
			.build()
			.newCall(buildRequest(server.url("/image.webp")))
			.execute()
			.use { }
		server.takeRequest()
	}

	private val source: ContentSource = org.skepsun.kototoro.core.model.ContentSource("MIHON_COMIX")

	private companion object {
		const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36"
	}
}
