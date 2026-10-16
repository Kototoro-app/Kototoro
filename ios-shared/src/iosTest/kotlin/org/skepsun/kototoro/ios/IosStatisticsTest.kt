@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.stats.domain.ReadingSessionPosition
import org.skepsun.kototoro.stats.domain.ReadingSessionSample
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.*
import kotlin.time.Clock

class IosStatisticsTest {
    @Test
    fun checkpointsReopenAndBackUpWithoutDuplicatingDurationOrSessions() = withDirectory { directory ->
        val content = work(1)
        val now = Clock.System.now().toEpochMilliseconds() - 60_000
        val first = IosLibrary("$directory/a.db")
        try {
            first.saveDetails(content)
            first.statistics.record(content, sample(content, now, now + 10_000))
            first.statistics.record(content, sample(content, now, now + 20_000))
            first.statistics.record(content, sample(content, now, now + 10_000))
            val stats = first.statistics.snapshot("ALL", "ALL", "", null)
            assertEquals(20_000L, stats.totalDuration)
            assertEquals(1, stats.sessionCount)
            assertEquals(1, first.database.getReadingRecordDao().findSessions(1).size)
            first.exportBackup("$directory/statistics.zip")
        } finally { first.close() }
        val reopened = IosLibrary("$directory/a.db")
        try { assertEquals(20_000L, reopened.statistics.snapshot("ALL", "ALL", "", null).totalDuration) }
        finally { reopened.close() }
        val restored = IosLibrary("$directory/b.db")
        try {
            repeat(2) { restored.restoreBackup(restored.previewBackup("$directory/statistics.zip")) }
            val stats = restored.statistics.snapshot("ALL", "ALL", "", null)
            assertEquals(20_000L, stats.totalDuration)
            assertEquals(1, stats.sessionCount)
            assertEquals(1, stats.timeline.count { !it.isUpdate })
            assertEquals(1, restored.database.getReadingRecordDao().findSessions(1).size)
        } finally { restored.close() }
    }

    @Test
    fun filtersUseStoredTypesAndReadUpdatesStayInTheTimeline() = withDirectory { directory ->
        val library = IosLibrary("$directory/library.db")
        val manga = work(1)
        val novel = work(2, "NOVEL")
        val now = Clock.System.now().toEpochMilliseconds() - 60_000
        try {
            library.saveDetails(manga); library.saveDetails(novel)
            library.statistics.record(manga, sample(manga, now, now + 20_000))
            library.database.getStatsDao().upsert(StatsEntity(2, now, 10_000, 0))
            library.database.getTrackLogsDao().insert(TrackLogEntity(mangaId = 1, chapters = "第二话\n第三话",
                createdAt = now, isUnread = true))
            library.markUpdateRead(manga)
            val all = library.statistics.snapshot("ALL", "ALL", "", null)
            assertEquals(30_000L, all.totalDuration)
            assertEquals(1, all.timeline.count { it.isUpdate })
            assertEquals(2, all.timeline.single { it.isUpdate }.count)
            val novels = library.statistics.snapshot("ALL", "NOVEL", "", null)
            assertEquals(10_000L, novels.totalDuration)
            assertEquals("2", novels.works.single().content.id)
            val search = library.statistics.snapshot("ALL", "ALL", "作品 1", null)
            assertEquals(20_000L, search.totalDuration)
            assertEquals("1", search.works.single().content.id)
            assertEquals(20_000L, library.statistics.snapshot("ALL", "ALL", "", "1").totalDuration)
        } finally { library.close() }
    }

    @Test
    fun statisticsIncludeWorksBeyondTheHistoryListLimit() = withDirectory { directory ->
        val library = IosLibrary("$directory/library.db")
        val now = Clock.System.now().toEpochMilliseconds() - 60_000
        try {
            for (id in 1L..205L) {
                library.saveDetails(work(id))
                library.database.getStatsDao().upsert(StatsEntity(id, now, 1000, 1))
            }
            val stats = library.statistics.snapshot("ALL", "ALL", "", null)
            assertEquals(205, stats.workCount)
            assertEquals(205_000L, stats.totalDuration)
            assertEquals(205, stats.timeline.size)
        } finally { library.close() }
    }

    @Test
    fun progressAloneDoesNotInventDurationAndDeletedHistoryDoesNotReturnAsAHistoryRow() = withDirectory { directory ->
        val library = IosLibrary("$directory/library.db")
        val content = work(1)
        try {
            library.saveDetails(content)
            library.recordPosition(content, content.chapters.single(), 0, 3, 0f)
            val old = library.statistics.snapshot("ALL", "ALL", "", null)
            assertEquals(0L, old.totalDuration)
            assertFalse(old.works.single().hasTimedStats)
            val now = Clock.System.now().toEpochMilliseconds() - 60_000
            library.statistics.record(content, sample(content, now, now + 20_000))
            library.removeHistory(content)
            val removed = library.statistics.snapshot("ALL", "ALL", "", null)
            assertEquals(20_000L, removed.totalDuration)
            assertFalse(removed.works.single().isHistoryVisible)
        } finally { library.close() }
    }

    private fun work(id: Long, type: String = "MANGA"): IosSourceContent {
        val source = SourceRef("STATISTICS_$type", "zh", type)
        val chapter = SourceChapter(id * 10, "第一话", 1f, 0, "/chapter/$id", null, 0, null, source)
        return IosSourceContent(SourceContent(id, "作品 $id", emptySet(), "/work/$id", "https://example.invalid/$id",
            0f, null, null, emptySet(), null, emptySet(), source, chapters = listOf(chapter)))
    }

    private fun sample(content: IosSourceContent, start: Long, end: Long): IosReadingSample {
        val position = ReadingSessionPosition(content.value.chapters!!.single().id, 0, 0, 0.1f)
        return IosReadingSample(ReadingSessionSample(start, end, position, position.copy(page = 2, percent = 0.5f), 3))
    }

    private fun withDirectory(block: suspend (String) -> Unit) = runBlocking {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        try { block(directory) } finally { files.removeItemAtPath(directory, null) }
    }
}
