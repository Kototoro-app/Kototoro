package org.skepsun.kototoro.cloudstream.runtime

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import dagger.Lazy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.network.CloudFlareInterceptor
import org.skepsun.kototoro.core.network.webview.WebViewExecutor

class CloudstreamRequestContextTest {

	private lateinit var server: MockWebServer

	@BeforeEach
	fun setUp() {
		server = MockWebServer()
		server.start()
	}

	@AfterEach
	fun tearDown() {
		server.shutdown()
	}

	@Test
	fun `loadLinks compatibility returns challenge response to plugin and records it`() = runTest {
		server.enqueue(cloudflareChallenge())
		val client = OkHttpClient.Builder()
			.addInterceptor(CloudstreamRequestContext.interceptor())
			.addInterceptor(CloudFlareInterceptor())
			.build()

		val execution = CloudstreamRequestContext.withSource(testSource) {
			CloudstreamRequestContext.withLoadLinksCompatibility {
				client.newCall(Request.Builder().url(server.url("/embed")).build()).execute().use { response ->
					response.code
				}
			}
		}

		assertEquals(403, execution.value)
		assertNotNull(execution.challenge)
		assertEquals(server.url("/embed").toString(), execution.challenge?.url)
		assertEquals(testSource, execution.challenge?.source)
	}

	@Test
	fun `default request still throws cloudflare challenge`() {
		server.enqueue(cloudflareChallenge())
		var browserExecutorAccessed = false
		val browserExecutor = Lazy<WebViewExecutor> {
			browserExecutorAccessed = true
			error("Browser transport must not run without an authoritative source tag")
		}
		val client = OkHttpClient.Builder()
			.addInterceptor(CloudFlareInterceptor(browserExecutor))
			.build()

		assertThrows(CloudFlareProtectedException::class.java) {
			client.newCall(Request.Builder().url(server.url("/embed")).build()).execute()
		}
		assertFalse(browserExecutorAccessed)
	}

	@Test
	fun `cloudstream source request reserves challenge for source solver`() = runTest {
		server.enqueue(cloudflareChallenge())
		var browserExecutorAccessed = false
		val browserExecutor = Lazy<WebViewExecutor> {
			browserExecutorAccessed = true
			error("Shared browser transport must not preempt the Cloudstream source solver")
		}
		val client = OkHttpClient.Builder()
			.addInterceptor(CloudstreamRequestContext.interceptor())
			.addInterceptor(CloudFlareInterceptor(browserExecutor))
			.build()

		CloudstreamRequestContext.withSource(testSource) {
			assertThrows(CloudFlareProtectedException::class.java) {
				client.newCall(Request.Builder().url(server.url("/protected")).build()).execute().use { }
			}
		}
		assertFalse(browserExecutorAccessed)
	}

	private fun cloudflareChallenge() = MockResponse()
		.setResponseCode(403)
		.setHeader("server", "cloudflare")
		.setHeader("content-type", "text/html; charset=utf-8")
		.setBody(
			"""
			<!doctype html>
			<html>
				<head><title>Just a moment...</title></head>
				<body><script src="/cdn-cgi/challenge-platform/orchestrate/chl_page/v1"></script></body>
			</html>
			""".trimIndent(),
		)

	private companion object {
		val testApi = object : MainAPI() {
			override var name = "Test"
			override var mainUrl = "https://example.test"
			override var lang = "en"
			override val supportedTypes = setOf(TvType.Movie)
		}
		val testSource = CloudstreamSource(testApi, "test.cs3", "test.plugin")
	}
}
