package org.skepsun.kototoro.mihon

import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.mihon.compat.SourceRequestContext
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.parsers.model.ContentSource

class SourceRequestContextTest {

    @Test
    fun `derives normalized https origin from Mihon base URL`() {
        val httpSource = mockk<HttpSource>()
        every { httpSource.baseUrl } returns "https://example.org:8443/catalog/"
        val source = MihonMangaSource(httpSource, "example.extension")

        val context = SourceRequestContext.from(source)

        assertEquals(setOf("https://example.org:8443"), context.allowedBrowserOrigins)
    }

    @Test
    fun `does not authorize insecure Mihon base URL`() {
        val httpSource = mockk<HttpSource>()
        every { httpSource.baseUrl } returns "http://example.org/"
        val source = MihonMangaSource(httpSource, "example.extension")

        val context = SourceRequestContext.from(source)

        assertEquals(emptySet<String>(), context.allowedBrowserOrigins)
    }

    @Test
    fun `does not infer origins for non Mihon source`() {
        val source = mockk<ContentSource>()

        val context = SourceRequestContext.from(source)

        assertEquals(emptySet<String>(), context.allowedBrowserOrigins)
    }

	@Test
	fun `adapter can freeze authority from the declaring source base URL`() {
		val source = mockk<ContentSource>()

		val context = SourceRequestContext.from(source, "https://kagane.to/catalog/")

		assertEquals(source, context.source)
		assertEquals(setOf("https://kagane.to"), context.allowedBrowserOrigins)
		assertTrue(context.allowsBrowserRequest("https://kagane.to/api/v2/search/series"))
		assertFalse(context.allowsBrowserRequest("https://api.kagane.to/v2/search/series"))
	}

	@Test
	fun `adapter does not authorize an insecure declaring base URL`() {
		val source = mockk<ContentSource>()

		val context = SourceRequestContext.from(source, "http://kagane.to/")

		assertEquals(emptySet<String>(), context.allowedBrowserOrigins)
	}
}
