@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceCall
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceProtocolJson
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRequest
import org.skepsun.kototoro.core.source.SourceResponse
import org.skepsun.kototoro.core.source.SourceResult
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.domain.recordUpdateBatch
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
    fun updateTimelineGroupsDiscoveriesAndKeepsSeenSeparateFromCompletedChapters() = withDirectory { directory ->
        val library = IosLibrary("$directory/library.db")
        val original = work(1)
        val first = original.value.chapters!!.single().copy(uploadDate = 1_704_096_000_000L)
        val second = first.copy(id = 11, title = "第二话", number = 2f, url = "/chapter/11", uploadDate = 1_704_182_400_000L)
        val content = IosSourceContent(original.value.copy(chapters = listOf(first, second)))
        val time = 1_704_268_800_000L
        try {
            library.saveDetails(content)
            library.database.getTracksDao().upsert(TrackEntity.create(1))
            assertEquals(1, library.database.recordUpdateBatch(1, setOf(10), time))
            assertEquals(1, library.database.recordUpdateBatch(1, setOf(11), time + 1))
            assertEquals(0, library.database.recordUpdateBatch(1, setOf(10, 11), time + 2))
            val before = library.statistics.snapshot("ALL", "ALL", "", null).timeline.single { it.isUpdate }
            assertEquals(2, before.count)
            assertEquals(2, before.pendingCount)
            assertEquals(2, before.updateLogIds.size)
            assertEquals(listOf("10", "11"), before.downloadChapters.map { it.id })
            assertTrue(before.isUnseen)
            assertEquals(first.uploadDate, before.publishedFrom)
            assertEquals(second.uploadDate, before.publishedAt)
            assertEquals(0, before.unknownPublishedCount)
            assertEquals(listOf(first.uploadDate, second.uploadDate), before.updateChapters.map { it.sourceUpdatedAt })
            val sources = updateSource { error("Stored chapter identities should not need another request") }
            try {
                assertEquals(2, library.prepareUpdateDownload(before, sources).downloadChapters.size)
                assertEquals(2, library.updates().single().newChapters)
                assertTrue(library.statistics.snapshot("ALL", "ALL", "", null).timeline.single { it.isUpdate }.isUnseen)
            } finally { sources.close() }
            library.markUpdateSeen(before)
            assertEquals(2, library.updates().single().newChapters)
            assertFalse(library.statistics.snapshot("ALL", "ALL", "", null).timeline.single { it.isUpdate }.isUnseen)
            library.recordPosition(content, IosSourceChapter(first), 0, 2, 0f)
            assertEquals(2, library.updates().single().newChapters)
            library.recordPosition(content, IosSourceChapter(first), 1, 2, 0f)
            val partial = library.statistics.snapshot("ALL", "ALL", "", null).timeline.single { it.isUpdate }
            assertEquals(2, partial.count)
            assertEquals(1, partial.pendingCount)
            assertEquals(before.timestamp, partial.timestamp)
            library.recordPosition(content, IosSourceChapter(second), 1, 2, 0f)
            assertTrue(library.updates().isEmpty())
            val complete = library.statistics.snapshot("ALL", "ALL", "", null).timeline.single { it.isUpdate }
            assertEquals(2, complete.count)
            assertEquals(0, complete.pendingCount)
            assertEquals(before.timestamp, complete.timestamp)
        } finally { library.close() }
    }

    @Test
    fun updateDownloadDoesNotRequireOpeningDetailsToPopulateChapters() = withDirectory { directory ->
        val library = IosLibrary("$directory/library.db")
        val original = work(1)
        library.updateSources(listOf(IosSource(original.sourceName, "测试源", "zh", "MANGA", "extension")))
        val updated = original.value.chapters!!.single().copy(sourceData = "opaque-download-request")
        val older = updated.copy(id = 9, title = "旧章节", number = 0f)
        val full = original.value.copy(chapters = listOf(older, updated))
        var fetches = 0
        val sources = updateSource {
            fetches++
            full
        }
        try {
            library.saveDetails(IosSourceContent(full.copy(chapters = null)))
            library.database.getTrackLogsDao().insert(TrackLogEntity(
                mangaId = 1, chapters = "第一话", createdAt = Clock.System.now().toEpochMilliseconds(), isUnread = true,
            ))
            val before = library.statistics.snapshot("ALL", "ALL", "", null).timeline.single { it.isUpdate }
            assertTrue(before.downloadChapters.isEmpty())
            val prepared = library.prepareUpdateDownload(before, sources)
            val after = library.statistics.snapshot("ALL", "ALL", "", null).timeline.single { it.isUpdate }
            assertEquals(listOf(updated), prepared.downloadChapters.map { it.value })
            assertEquals(after.downloadChapters.map { it.id }, prepared.downloadChapters.map { it.id })
            assertEquals(full, library.storedDetails(before.content)?.value)
            assertEquals(prepared.downloadChapters.map { it.id },
                library.prepareUpdateDownload(before, sources).downloadChapters.map { it.id })
            assertEquals(1, fetches, "A populated chapter cache must not require another source request")
        } finally { sources.close(); library.close() }
    }

    @Test
    fun updateDownloadRejectsAmbiguousOrMissingChapterNamesAfterRefresh() = withDirectory { directory ->
        val library = IosLibrary("$directory/library.db")
        val original = work(1)
        val chapter = original.value.chapters!!.single()
        var fetched = original.value.copy(chapters = listOf(chapter, chapter.copy(id = 11, branch = "另一分支")))
        val sources = updateSource { fetched }
        try {
            library.saveDetails(IosSourceContent(original.value.copy(chapters = null)))
            library.database.getTrackLogsDao().insert(TrackLogEntity(
                mangaId = 1, chapters = "第一话", createdAt = Clock.System.now().toEpochMilliseconds(), isUnread = true,
            ))
            val event = library.statistics.snapshot("ALL", "ALL", "", null).timeline.single { it.isUpdate }
            assertFailsWith<IllegalStateException> { library.prepareUpdateDownload(event, sources) }
            fetched = original.value.copy(chapters = listOf(chapter.copy(title = "其他章节")))
            assertFailsWith<IllegalStateException> { library.prepareUpdateDownload(event, sources) }
        } finally { sources.close(); library.close() }
    }

    @Test
    fun updateDownloadsRequireUniqueStoredChapterNamesAndKeepOpaqueFields() {
        val original = work(1)
        val chapter = original.value.chapters!!.single().copy(sourceData = "opaque-request")
        val content = IosSourceContent(original.value.copy(chapters = listOf(chapter)))
        val resolved = resolveUpdateDownloadChapters(content, listOf("第一话", "第一话"))
        assertEquals(listOf(chapter), resolved.map { it.value })
        assertTrue(resolveUpdateDownloadChapters(content, listOf("第一话", "已删除的章节")).isEmpty())
        val ambiguous = IosSourceContent(original.value.copy(chapters = listOf(
            chapter, chapter.copy(id = 11, branch = "另一分支"),
        )))
        assertTrue(resolveUpdateDownloadChapters(ambiguous, listOf("第一话")).isEmpty())
    }

    @Test
    fun timelineExportsPerEventProgressDownloadChaptersAndTheSharedDateWindow() = withDirectory { directory ->
        val library = IosLibrary("$directory/library.db")
        val content = work(1)
        val now = Clock.System.now().toEpochMilliseconds() - 60_000
        try {
            library.saveDetails(content)
            library.statistics.record(content, sample(content, now, now + 20_000))
            library.database.getTrackLogsDao().insert(TrackLogEntity(
                mangaId = 1, chapters = "第一话", createdAt = now, isUnread = true,
            ))
            val snapshot = library.statistics.snapshot("DAY", "ALL", "", null)
            val reading = snapshot.timeline.single { !it.isUpdate }
            assertTrue(reading.hasProgress)
            assertEquals(0.5f, reading.progress)
            assertEquals(listOf("10"), snapshot.timeline.single { it.isUpdate }.downloadChapters.map { it.id })
            assertTrue(snapshot.windowFrom <= now)
            assertTrue(snapshot.windowUntil >= now + 20_000)
            assertEquals(0L, library.statistics.snapshot("ALL", "ALL", "", null).windowFrom)
        } finally { library.close() }
    }

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
            library.markUpdatesSeen(manga)
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

    private fun updateSource(details: (SourceContent) -> SourceContent) = IosSources(object : IosSourceTransport {
        override fun exchange(requestJson: String): String {
            val request = SourceProtocolJson.decodeFromString<SourceRequest>(requestJson)
            val call = request.call as? SourceCall.Details ?: error("Unexpected call: ${request.call}")
            val result = SourceResult.Details(details(call.content))
            return SourceProtocolJson.encodeToString(SourceResponse(1, request.requestId, result))
        }

        override fun close() = Unit
    })

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
