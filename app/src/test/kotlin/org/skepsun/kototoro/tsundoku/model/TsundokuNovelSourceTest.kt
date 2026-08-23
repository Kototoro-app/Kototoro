package org.skepsun.kototoro.tsundoku.model

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.mihon.compat.MihonRequestContext
import org.skepsun.kototoro.mihon.compat.SourceRequestContext
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class TsundokuNovelSourceTest {

    private val upstream = mockk<Source>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { upstream.id } returns 9001L
        every { upstream.name } returns "Ex Novel"
        every { upstream.lang } returns "en"
        every { upstream.isNovelSource() } returns true
    }

    private fun newSource(
        pkgName: String = "eu.kanade.tachiyomi.novelextension.en.x",
        isNsfw: Boolean = false,
        hasLanguageSuffix: Boolean = false,
    ) = TsundokuNovelSource(
        upstreamSource = upstream,
        pkgName = pkgName,
        isNsfw = isNsfw,
        hasLanguageSuffix = hasLanguageSuffix,
    )

    @Test
    fun `sourceKey is TSUNDOKU_9001 and matches name and identity`() {
        val source = newSource()
        assertEquals("TSUNDOKU_9001", source.sourceKey)
        assertEquals(source.sourceKey, source.name)
        assertEquals("TSUNDOKU_9001", source.identity.sourceKey)
        assertEquals(ExternalExtensionType.TSUNDOKU, source.ecosystem)
        assertEquals(ExternalExtensionType.TSUNDOKU, source.identity.ecosystem)
    }

    @Test
    fun `preferenceNamespace is tsundoku package and sourceId`() {
        val source = newSource()
        assertEquals("tsundoku:eu.kanade.tachiyomi.novelextension.en.x:9001", source.preferenceNamespace)
    }

    @Test
    fun `contentType reflects isNsfw`() {
        assertEquals(ContentType.NOVEL, newSource(isNsfw = false).contentType)
        assertEquals(ContentType.HENTAI_NOVEL, newSource(isNsfw = true).contentType)
    }

    @Test
    fun `baseUrlOrNull and shared request seams consume the upstream HttpSource`() {
        val httpSource = mockk<HttpSource>(relaxed = true)
        every { httpSource.id } returns 9001L
        every { httpSource.name } returns "Ex Novel"
        every { httpSource.lang } returns "en"
        every { httpSource.baseUrl } returns "https://example.org"

        val source = TsundokuNovelSource(
            upstreamSource = httpSource,
            pkgName = "eu.kanade.tachiyomi.novelextension.en.x",
        )

        assertEquals("https://example.org", source.baseUrlOrNull)
        assertTrue(
            SourceRequestContext.from(source)
                .allowedBrowserOrigins
                .contains("https://example.org"),
        )
        MihonRequestContext.registerSource(source)
        assertEquals(source, MihonRequestContext.sourceForHost("example.org"))
    }

    @Test
    fun `displayName uses upstream name with optional language suffix`() {
        assertEquals("Ex Novel", newSource().displayName)
        assertEquals("Ex Novel (English)", newSource(hasLanguageSuffix = true).displayName)
    }

    @Test
    fun `equals compares by name with anonymous ContentSource`() {
        val source = newSource()
        val anonymous = object : ContentSource {
            override val name = "TSUNDOKU_9001"
            override val locale = "en"
            override val contentType = ContentType.NOVEL
        }
        assertEquals(source, anonymous)
        // Production hashCode is name-based (the anonymous object keeps identity hashCode, but
        // every equal TsundokuNovelSource must still share the same hash).
        assertEquals(source.hashCode(), "TSUNDOKU_9001".hashCode())
        assertTrue(source == anonymous)
    }
}
