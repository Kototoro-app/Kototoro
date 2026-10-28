package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.mergeRestoredTrackLog
import org.skepsun.kototoro.tracker.domain.dailyUpdateGroups
import org.skepsun.kototoro.tracker.domain.decodeUpdateChapters
import org.skepsun.kototoro.tracker.domain.encodeUpdateChapters
import org.skepsun.kototoro.tracker.domain.feed.FeedSnapshotAssembler
import java.io.IOException
import java.nio.file.Path

class DesktopTrackerTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("MIHON_42", "zh", "MANGA")

    private fun chapter(id: Long, branch: String? = null) =
        SourceChapter(id, "第 $id 话", id.toFloat(), 0, "/c$id", null, 1_000L * id, branch, source)

    private fun work(chapters: List<SourceChapter>) = SourceContent(
        10, "追踪作品", emptySet(), "/work", "https://fixture.invalid/work", 0f, null, null,
        emptySet(), "ONGOING", emptySet(), source, chapters = chapters,
    )

    @Test
    fun `cross day pending updates accumulate by identity and stale sync cannot undo reading`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            var remote = work(listOf(chapter(1)))
            var now = 86_400_000L
            val tracker = DesktopTracker(runtime.database, library, { remote }, { null }, { now })
            library.addFavourite(remote)
            tracker.checkAll()
            remote = work((1L..3L).map { chapter(it) })
            tracker.checkAll()
            assertEquals(2, runtime.database.getTracksDao().find(10)?.newChapters)
            val original = runtime.database.getTrackLogsDao().dump().single()
            now += 86_400_000L
            remote = work((1L..4L).map { chapter(it) })
            tracker.checkAll()
            assertEquals(3, runtime.database.getTracksDao().find(10)?.newChapters)
            val timestamps = dailyUpdateGroups(runtime.database.getTrackLogsDao().dump()).map { it.discoveredAt }
            assertEquals(2, timestamps.size)
            now += 86_400_000L
            assertEquals(0, tracker.checkAll().newChapters)
            assertEquals(timestamps, dailyUpdateGroups(runtime.database.getTrackLogsDao().dump()).map { it.discoveredAt })

            library.recordPage(remote, chapter(2), 1, 2)
            assertEquals(2, runtime.database.getTracksDao().find(10)?.newChapters)
            repeat(3) { runtime.database.mergeRestoredTrackLog(original) }
            assertEquals(2, runtime.database.getTracksDao().find(10)?.newChapters)
            assertEquals(2, runtime.database.getTrackLogsDao().count())
            val updated = runtime.database.getTrackLogsDao().find(original.id)!!
            assertFalse(updated.isUnread)
            assertTrue(decodeUpdateChapters(updated.chapterMetadata).single { it.chapterId == 2L }.readAt > 0)

            // Receiving a seen notification from another device must not complete its other chapter.
            val seen = TrackLogEntity(mangaId = 10, chapters = original.chapters, createdAt = original.createdAt,
                isUnread = false, chapterMetadata = original.chapterMetadata)
            runtime.database.mergeRestoredTrackLog(seen)
            assertEquals(2, runtime.database.getTracksDao().find(10)?.newChapters)
        }
    }

    @Test
    fun `checks find new chapters with Android rules and feed the shared read model`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            var remote = work(listOf(chapter(1), chapter(2)))
            var failure: Exception? = null
            val tracker = DesktopTracker(runtime.database, library, fetchDetails = { failure?.let { throw it }; remote },
                preferredBranch = { null }, now = { 5_000_000L })
            library.addFavourite(remote)
            assertEquals(1, tracker.trackedCategoryCount(), "the default favourites category tracks updates, as on Android")

            // The first check only anchors the track.
            assertEquals(DesktopTrackReport(1, 0, 0, 0), tracker.checkAll())
            val anchored = requireNotNull(runtime.database.getTracksDao().find(10))
            assertEquals(2L, anchored.lastChapterId)
            assertEquals(0, anchored.newChapters)

            remote = work(listOf(chapter(1), chapter(2), chapter(3), chapter(4)))
            assertEquals(DesktopTrackReport(1, 1, 0, 2), tracker.checkAll())
            val updated = requireNotNull(runtime.database.getTracksDao().find(10))
            assertEquals(4L, updated.lastChapterId)
            assertEquals(2, updated.newChapters)
            assertEquals(TrackEntity.RESULT_HAS_UPDATE, updated.lastResult)
            assertEquals(4, library.find(10)?.chapters?.size, "the refreshed details are stored like Android's snapshot")

            val assembler = FeedSnapshotAssembler({ _, _ -> ContentGroup.MANGA }, { OriginGroup.MIHON })
            val feed = assembler.observe(runtime.database).first()
            assertEquals(listOf("第 3 话", "第 4 话"), feed.rows.single().chapters)
            assertTrue(feed.rows.single().unread)
            assertEquals(2, feed.updateRowsByOwnerId.getValue(10).newChapters)

            // A failed check keeps the pending counter and records the error.
            failure = IOException("offline")
            assertEquals(DesktopTrackReport(1, 0, 1, 0), tracker.checkAll())
            val failed = requireNotNull(runtime.database.getTracksDao().find(10))
            assertEquals(TrackEntity.RESULT_FAILED, failed.lastResult)
            assertEquals(2, failed.newChapters)
            failure = null

            tracker.markSeen(10)
            val read = assembler.observe(runtime.database).first()
            assertFalse(read.rows.single().unread)
            assertEquals(2, read.updateRowsByOwnerId.getValue(10).newChapters, "viewing is not reading")
            library.recordPage(remote, chapter(3), 0, 2)
            assertEquals(2, runtime.database.getTracksDao().find(10)?.newChapters, "partial reading keeps the chapter")
            library.recordPage(remote, chapter(3), 1, 2)
            assertEquals(1, runtime.database.getTracksDao().find(10)?.newChapters)
            library.recordPage(remote, chapter(4), 1, 2)
            assertEquals(0, runtime.database.getTracksDao().find(10)?.newChapters)
            assertEquals(1, runtime.database.getTrackLogsDao().count(), "reading retains update history")

            // Turning tracking off for the category drops the track; enabling it again restores it.
            val category = runtime.database.getFavouriteCategoriesDao().findAll().single()
            runtime.database.getFavouriteCategoriesDao().updateTracking(category.categoryId.toLong(), false)
            assertEquals(emptyList<Long>(), tracker.syncAnchors())
            assertNull(runtime.database.getTracksDao().find(10))
            tracker.enableTrackingForAllCategories()
            assertEquals(listOf(10L), tracker.syncAnchors())
        }
    }

    @Test
    fun `a badge from before chapters carried identities clears when the newest chapter is read`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            val remote = work((1L..4L).map { chapter(it) })
            library.addFavourite(remote)
            // A row written before v86 carried chapter identities: the count still owns the badge, but its log has
            // no chapters to complete, so nothing the reader wrote could ever lower it.
            runtime.database.getTracksDao().upsert(TrackEntity(mangaId = 10, lastChapterId = 2L, newChapters = 2,
                lastCheckTime = 5_000_000L, lastChapterDate = 2_000L, lastResult = TrackEntity.RESULT_HAS_UPDATE,
                lastError = null))
            runtime.database.getTrackLogsDao().insert(TrackLogEntity(mangaId = 10, chapters = "第 3 话\n第 4 话",
                createdAt = 5_000_000L, isUnread = true, chapterMetadata = ""))
            assertEquals(1, runtime.database.getTracksDao().findUpdated().size)

            library.recordPage(remote, chapter(3), 0, 2)
            assertEquals(1, runtime.database.getTracksDao().find(10)?.newChapters,
                "reading the first of the two counted chapters leaves the one after it")

            library.recordPage(remote, chapter(4), 1, 2)
            assertEquals(0, runtime.database.getTracksDao().find(10)?.newChapters)
            assertTrue(runtime.database.getTracksDao().findUpdated().isEmpty(), "the badge is gone")
            assertFalse(runtime.database.getTrackLogsDao().findByOwner(10).single().isUnread)
            assertEquals(1, runtime.database.getTrackLogsDao().count(), "the counter's history is kept")
        }
    }

    @Test
    fun `a device that read before counters carried identities is repaired by the next check`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            val remote = work((1L..4L).map { chapter(it) })
            val tracker = DesktopTracker(runtime.database, library, { remote }, { null })
            library.addFavourite(remote)
            // The work was finished while tracking only kept a plain count.
            library.recordPage(remote, chapter(4), 1, 2)
            runtime.database.getTracksDao().upsert(TrackEntity(mangaId = 10, lastChapterId = 2L, newChapters = 2,
                lastCheckTime = 5_000_000L, lastChapterDate = 2_000L, lastResult = TrackEntity.RESULT_HAS_UPDATE,
                lastError = null))
            runtime.database.getTrackLogsDao().insert(TrackLogEntity(mangaId = 10, chapters = "第 3 话\n第 4 话",
                createdAt = 5_000_000L, isUnread = true, chapterMetadata = ""))
            assertEquals(1, runtime.database.getTracksDao().findUpdated().size)

            tracker.checkAll()

            assertEquals(0, runtime.database.getTracksDao().find(10)?.newChapters,
                "the stored position is at the end of the branch, so nothing is unread")
            assertTrue(runtime.database.getTracksDao().findUpdated().isEmpty())
            assertFalse(runtime.database.getTrackLogsDao().findByOwner(10).single().isUnread)
        }
    }

    @Test
    fun `the reader's branch decides which chapters count`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            var remote = work(listOf(chapter(1, "A"), chapter(2, "B")))
            val tracker = DesktopTracker(runtime.database, library, { remote }, preferredBranch = { "B" })
            library.addFavourite(remote)
            tracker.checkAll()
            assertEquals(2L, runtime.database.getTracksDao().find(10)?.lastChapterId, "no history: the preferred branch")
            remote = work(listOf(chapter(1, "A"), chapter(2, "B"), chapter(3, "A")))
            tracker.checkAll()
            assertEquals(0, runtime.database.getTracksDao().find(10)?.newChapters, "a chapter in another branch is not new")
            remote = work(listOf(chapter(1, "A"), chapter(2, "B"), chapter(3, "A"), chapter(4, "B")))
            tracker.checkAll()
            assertEquals(1, runtime.database.getTracksDao().find(10)?.newChapters)
        }
    }
}
