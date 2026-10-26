package org.skepsun.kototoro.backups.external

import eu.kanade.tachiyomi.source.model.SChapter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.mihon.model.toKotoChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class ExternalLibraryImportProgressTest {

    @Test
    fun `Mihon history matches loaded chapters when the extension is installed`() = runTest {
        assertImportedProgress(installed = true)
    }

    @Test
    fun `Mihon history matches loaded chapters after a missing extension is installed`() = runTest {
        assertImportedProgress(installed = false)
    }

    @Test
    fun `Mihon histories distinguish the same chapter URL under different manga`() = runTest {
        val histories = assertImportedProgress(
            installed = true,
            mangaUrls = listOf("/manga/first", "/manga/second"),
        )
        assertNotEquals(histories.first().chapterId, histories.last().chapterId)
    }

    @Test
    fun `non Mihon chapters and video episodes keep their existing identities`() {
        val record = payload().records.first()
        val chapterUrl = "/chapter/1"
        listOf(
            "MANGADEX" to 9223372036222790504L,
            "ANIYOMI_123" to 9223372036079224106L,
            "TSUNDOKU_123" to 9223372035304712363L,
        ).forEach { (sourceName, expectedId) ->
            assertEquals(
                expectedId,
                ExternalLibraryImport.generateChapterId(record.copy(sourceName = sourceName), chapterUrl),
            )
        }
        listOf(
            "MIHON_123" to 9223372035008727039L,
            "ANIYOMI_123" to 1048349372L,
            "JSON_test" to 9223372035474050452L,
        ).forEach { (sourceName, expectedId) ->
            assertEquals(
                expectedId,
                ExternalLibraryImport.generateChapterId(
                    record.copy(sourceName = sourceName, contentType = ContentType.VIDEO),
                    chapterUrl,
                ),
            )
        }
    }

    private suspend fun assertImportedProgress(
        installed: Boolean,
        mangaUrls: List<String> = listOf("/manga/first"),
    ): List<HistoryEntity> {
        val payload = payload(mangaUrls)
        val sourceName = payload.records.first().sourceName
        val database = mockk<MangaDatabase>(relaxed = true)
        val histories = slot<Iterable<HistoryEntity>>()
        coEvery { database.getFavouriteCategoriesDao().findAll() } returns emptyList()
        coEvery { database.getFavouriteCategoriesDao().insert(any()) } returns 1L
        coEvery { database.getHistoryDao().upsert(capture(histories)) } returns Unit
        val importer = ExternalLibraryImport(
            database = database,
            catalog = {
                if (installed) {
                    listOf(ExternalSourceCandidate(sourceName, "Test source", ExternalSourceKind.MIHON))
                } else {
                    emptyList()
                }
            },
            transaction = object : ExternalImportTransaction {
                override suspend fun <R> run(block: suspend () -> R): R = block()
            },
            defaultCategoryTitle = "Favourites",
            now = { 1_700_000_200_000L },
        )

        val summary = importer.import(payload)

        assertEquals(mangaUrls.size, summary.favoritesImported)
        assertEquals(mangaUrls.size, summary.historyImported)
        assertEquals(0, summary.failedCount)
        assertEquals(if (installed) 0 else 1, summary.uninstalledSources.size)
        val rows = histories.captured.associateBy { it.mangaId }
        assertEquals(mangaUrls.size, rows.size)
        val source = mockk<ContentSource> { every { name } returns sourceName }
        payload.records.forEach { record ->
            val chapter = SChapter.create().apply {
                url = requireNotNull(record.historyChapterUrl)
                name = "Chapter 1"
                chapter_number = 1f
            }.toKotoChapter(source, overrideNumber = 1f, parentUrl = record.url)
            val history = rows.getValue(ExternalLibraryImport.generateContentId(record))
            assertEquals(chapter.id, history.chapterId, "Imported history must resolve to a loaded chapter")
            assertEquals(0.5f, history.percent)
            assertEquals(7, history.page, "Imported history must resume at the backed-up page")
            assertEquals(2, history.chaptersCount)
            assertEquals(record.historyTimestamp, history.updatedAt)
        }
        return rows.values.toList()
    }

    private fun payload(mangaUrls: List<String> = listOf("/manga/first")): ExternalBackupPayload {
        val backup = MihonBackup(
            backupManga = mangaUrls.map { mangaUrl ->
                MihonBackupManga(
                    source = 123,
                    url = mangaUrl,
                    title = mangaUrl,
                    chapters = listOf(
                        MihonBackupChapter(url = "/chapter/1", name = "Chapter 1", read = true, lastPageRead = 7),
                        MihonBackupChapter(url = "/chapter/2", name = "Chapter 2"),
                    ),
                    history = listOf(MihonBackupHistory(url = "/chapter/1", lastRead = 1_700_000_100_000L)),
                )
            },
        )
        return requireNotNull(
            decodeMihonOrAniyomiBackup(
                ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup),
                ExternalBackupApp.MIHON,
            ),
        )
    }
}
