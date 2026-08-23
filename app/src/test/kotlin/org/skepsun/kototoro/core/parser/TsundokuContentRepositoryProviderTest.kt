package org.skepsun.kototoro.core.parser

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.extensions.recovery.SourceRefreshReporter
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.tsundoku.TsundokuNovelRepository
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource

class TsundokuContentRepositoryProviderTest {

    private val contentCache = mockk<MemoryContentCache>(relaxed = true)
    private val refreshReporter = mockk<SourceRefreshReporter>(relaxed = true)
    private val provider = TsundokuContentRepositoryProvider(contentCache, refreshReporter)

    private fun tsundokuSource(id: Long = 9001L): TsundokuNovelSource {
        val upstream = mockk<CatalogueSource>(relaxed = true)
        every { upstream.id } returns id
        every { upstream.name } returns "Test Novel"
        return TsundokuNovelSource(
            upstreamSource = upstream,
            pkgName = "eu.kanade.tachiyomi.novelextension.en.test",
        )
    }

    @Test
    fun `supports returns true for TsundokuNovelSource`() {
        assertTrue(provider.supports(tsundokuSource()))
    }

    @Test
    fun `supports returns false for MihonMangaSource`() {
        val catalogue = mockk<CatalogueSource>(relaxed = true)
        every { catalogue.id } returns 9002L
        every { catalogue.name } returns "Mihon Manga"
        val mihonSource = MihonMangaSource(
            catalogueSource = catalogue,
            pkgName = "eu.kanade.tachiyomi.extension.en.test",
        )
        assertFalse(provider.supports(mihonSource))
    }

    @Test
    fun `create returns a repository for TsundokuNovelSource with the same source`() {
        val source = tsundokuSource()
        val repository = provider.create(source)

        assertNotNull(repository)
        assertSame(source, repository!!.source)
        assertTrue(repository is TsundokuNovelRepository)
    }

    @Test
    fun `create returns null for non Tsundoku sources`() {
        val source = mockk<org.skepsun.kototoro.parsers.model.ContentSource>(relaxed = true)
        assertNull(provider.create(source))
    }
}
