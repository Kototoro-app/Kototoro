package org.skepsun.kototoro.migration

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.migration.data.LibraryRow
import org.skepsun.kototoro.migration.data.MigrationDao
import org.skepsun.kototoro.migration.domain.SourceHealthStatus
import org.skepsun.kototoro.migration.domain.SourceHealthUseCase
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentSource as ParserContentSource

class SourceHealthUseCaseTest {

    private val loadedMihon = object : ParserContentSource {
        override val name = "MIHON_42"
        override val locale = "en"
        override val contentType = ContentType.MANGA
    }

    private fun row(id: Long, source: String) = LibraryRow(
        id = id, title = "t$id", altTitles = null, source = source, contentType = "MANGA", coverUrl = "",
        chaptersCount = 0, trackResult = null, trackCheckTime = null, trackError = null,
        historyPercent = null, historyChapterNumber = null,
    )

    private val dao = mockk<MigrationDao> {
        coEvery { findLibraryRows() } returns listOf(row(1, "MIHON_42"), row(2, "MIHON_404"))
    }
    private val database = mockk<MangaDatabase> { every { getMigrationDao() } returns dao }
    private val repository = mockk<ContentSourcesRepository> {
        coEvery { getDisabledSources() } returns emptySet()
        every { resolveSource("MIHON_42") } returns loadedMihon
        every { resolveSource("MIHON_404") } returns ContentSource("MIHON_404")
    }

    @Test
    fun `loaded extension source is healthy and carries the resolved source`() = runTest {
        val health = SourceHealthUseCase(database, repository)().associateBy { it.source.name }
        assertEquals(SourceHealthStatus.HEALTHY, health.getValue("MIHON_42").status)
        assertSame(loadedMihon, health.getValue("MIHON_42").source)
        assertEquals(SourceHealthStatus.UNINSTALLED, health.getValue("MIHON_404").status)
    }
}
