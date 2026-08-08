package org.skepsun.kototoro.core.parser.tvbox

import com.github.catvod.Proxy
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderApi
import com.google.gson.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class TVBoxSpiderAbiTest {

	@Test
	fun `paged search falls back to legacy search contract`() {
		val spider = object : Spider() {
			override fun searchContent(key: String, quick: Boolean): String = "$key:$quick"
		}

		assertEquals("query:false", spider.searchContent("query", false, "2"))
	}

	@Test
	fun `shared empty result field is available to compiled spiders`() {
		assertNotNull(Spider.empty)
		assertEquals(0, Spider.empty.length())
	}

	@Test
	fun `spider api exposes host methods used by current jars`() {
		assertNotNull(Spider::class.java.getMethod("initApi", SpiderApi::class.java))
		assertNotNull(Spider::class.java.getField("empty"))
		assertNotNull(Spider::class.java.getDeclaredField("mContext"))
		assertNotNull(SpiderApi::class.java.getMethod("getAddress", Boolean::class.javaPrimitiveType))
		assertNotNull(SpiderApi::class.java.getMethod("getPort"))
		assertNotNull(SpiderApi::class.java.getMethod("log", String::class.java))
		assertNotNull(SpiderApi::class.java.getMethod("getScreenOrientation"))
		assertNotNull(SpiderApi::class.java.getMethod("multiReq", JsonArray::class.java))
		assertNotNull(SpiderApi::class.java.getMethod("webParse", String::class.java, String::class.java))
	}

	@Test
	fun `spider api and catvod proxy use bound runtime endpoint`() {
		Proxy.setEndpoint("http://127.0.0.1:12345/dynamic/test", "http://192.0.2.1:12345/dynamic/test")
		try {
			val api = SpiderApi()

			assertEquals("http://127.0.0.1:12345/dynamic/test/", api.getAddress(true))
			assertEquals("http://192.0.2.1:12345/dynamic/test/", api.getAddress(false))
			assertEquals("12345", api.port)
			assertEquals("http://127.0.0.1:12345/dynamic/test/proxy", Proxy.getUrl(true))
		} finally {
			Proxy.clearEndpoint()
		}
	}

	@Test
	fun `catvod proxy endpoint is inherited by spider worker threads`() {
		Proxy.setEndpoint("http://127.0.0.1:12345/dynamic/test", null)
		try {
			val inheritedUrl = AtomicReference<String>()
			Thread { inheritedUrl.set(Proxy.getUrl(true)) }.apply {
				start()
				join()
			}

			assertEquals("http://127.0.0.1:12345/dynamic/test/proxy", inheritedUrl.get())
		} finally {
			Proxy.clearEndpoint()
		}
	}
}
