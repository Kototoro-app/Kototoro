package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.library.SourceTrackReport
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.mergeRestoredTrackLog
import org.skepsun.kototoro.tracker.domain.completeAllPendingUpdates
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
    fun `chapter only checks refresh full details only when updates are found`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            var remote = work(listOf(chapter(1)))
            var fullCalls = 0
            var chapterCalls = 0
            val tracker = DesktopTracker(runtime.database, library,
                fetchDetails = { fullCalls++; remote }, preferredBranch = { null }, now = { 86_400_000L },
                fetchChapters = { chapterCalls++; it.copy(chapters = remote.chapters) })
            library.addFavourite(remote)

            tracker.checkAll()
            tracker.checkAll()
            assertEquals(1, fullCalls)
            assertEquals(1, chapterCalls)
            remote = work(listOf(chapter(1), chapter(2))).copy(state = "FINISHED")
            assertEquals(1, tracker.checkAll().newChapters)
            assertEquals(2, fullCalls)
            assertEquals(2, chapterCalls)
            assertEquals("FINISHED", library.find(remote.id)?.state)
            assertEquals(1, runtime.database.getTracksDao().find(remote.id)?.newChapters)
        }
    }

    @Test
    fun `running counts start from the total and follow every checked work`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            val updated = work(listOf(chapter(1)))
            val broken = work(listOf(chapter(1))).copy(id = 11, url = "/broken", publicUrl = "https://fixture.invalid/broken")
            library.addFavourite(updated)
            library.addFavourite(broken)
            var remote = updated
            val tracker = DesktopTracker(runtime.database, library, { stored ->
                if (stored.id == broken.id) throw IOException("offline")
                remote
            }, { null }, { 86_400_000L })
            tracker.checkAll()
            remote = work(listOf(chapter(1), chapter(2)))

            val counts = mutableListOf<Pair<SourceTrackReport, Int>>()
            tracker.checkAll(onCounts = { report, total -> counts += report to total })
            assertEquals(listOf(0, 1, 2), counts.map { it.first.checked })
            assertTrue(counts.all { it.second == 2 })
            val last = counts.last().first
            assertEquals(1, last.failed)
            assertEquals(1, last.withUpdates)
            assertEquals(1, last.newChapters)
        }
    }

    @Test
    fun `skip completed applies to manual and scheduled checks without removing their tracks`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            val finished = work(listOf(chapter(1))).copy(state = "FINISHED")
            var calls = 0
            val tracker = DesktopTracker(runtime.database, library, { calls++; it }, { null }, { 86_400_000L })
            library.addFavourite(finished)

            assertEquals(0, tracker.checkAll(skipCompleted = true).checked)
            assertEquals(0, tracker.checkAll(respectSchedule = true, skipCompleted = true).checked)
            assertEquals(0, calls)
            assertNotNull(runtime.database.getTracksDao().find(finished.id))
            assertEquals(1, tracker.checkAll().checked)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `duplicate policy also applies when details saves the chapter list`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            var remote = work((1L..3L).map { chapter(it) })
            val tracker = DesktopTracker(runtime.database, library, { remote }, { null }, { 86_400_000L })
            library.addFavourite(remote)
            tracker.checkAll()
            library.recordPage(remote, chapter(3), 1, 2)
            library.markDuplicateChaptersRead = true
            val duplicate = chapter(31).copy(number = 3f, title = "第 3 话", uploadDate = 3_000L)
            remote = remote.copy(chapters = remote.chapters!! + duplicate + chapter(4))

            library.save(remote, now = 86_400_000L)

            assertEquals(1, runtime.database.getTracksDao().find(remote.id)?.newChapters)
            val updates = runtime.database.getTrackLogsDao().findByOwner(remote.id)
                .flatMap { decodeUpdateChapters(it.chapterMetadata) }
            assertTrue(updates.single { it.chapterId == duplicate.id }.readAt > 0)
            assertEquals(0, updates.single { it.chapterId == 4L }.readAt)
        }
    }

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
    fun `a challenged source is skipped for the rest of the run while other sources are checked`() =
        runBlocking<Unit> {
            DesktopRuntime.open(directory).use { runtime ->
                val other = SourceRef("MIHON_7", "zh", "MANGA")
                val library = DesktopLibrary(runtime.database) { name -> if (name == other.name) other else source }
                val challenged = (11L..13L).map { id -> work(listOf(chapter(1))).copy(id = id, url = "/w$id") }
                val healthy = work(listOf(chapter(1))).copy(id = 20, url = "/w20", source = other)
                (challenged + healthy).forEach { library.addFavourite(it) }
                val fetched = ArrayList<Long>()
                val tracker = DesktopTracker(runtime.database, library, fetchDetails = { stored ->
                    fetched += stored.id
                    if (stored.source == source) throw SourceChallengeException("https://fixture.invalid")
                    stored
                }, preferredBranch = { null }, now = { 5_000_000L })

                val report = tracker.checkAll()

                assertEquals(4, report.checked)
                assertTrue(report.skipped >= 1, "report: $report")
                assertEquals(3, report.failed + report.skipped, "report: $report")
                assertTrue(20L in fetched)
                assertEquals(report.failed, fetched.count { it != 20L })
                // A skipped title is not marked as checked, so it keeps its place for the next run.
                val unchecked = challenged.map { it.id }.filterNot { it in fetched }
                assertTrue(unchecked.all { runtime.database.getTracksDao().find(it)?.lastCheckTime == 0L })
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
    fun `mark all read clears every counter and reminder but keeps the timeline`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            var remote = work(listOf(chapter(1)))
            val tracker = DesktopTracker(runtime.database, library, { remote }, { null }, { 86_400_000L })
            library.addFavourite(remote)
            tracker.checkAll()
            remote = work((1L..3L).map { chapter(it) })
            tracker.checkAll()
            assertEquals(2, runtime.database.getTracksDao().find(10)?.newChapters)
            assertEquals(1, runtime.database.getTrackLogsDao().observeUnreadCount().first())

            runtime.database.completeAllPendingUpdates(5_000L)

            assertEquals(0, runtime.database.getTracksDao().find(10)?.newChapters)
            assertTrue(runtime.database.getTracksDao().findUpdated().isEmpty())
            assertEquals(0, runtime.database.getTrackLogsDao().observeUnreadCount().first())
            assertEquals(1, runtime.database.getTrackLogsDao().count(), "the timeline keeps its rows")
            val log = runtime.database.getTrackLogsDao().findByOwner(10).single()
            assertTrue(decodeUpdateChapters(log.chapterMetadata).all { it.readAt == 5_000L })
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
            val tracker = DesktopTracker(runtime.database, library, { remote }, preferredBranch = { "B" },
                now = { 86_400_000L })
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

    @Test
    fun `a rate limiting source cools down and is left alone by the next run`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            val remote = work((1L..2L).map { chapter(it) })
            var calls = 0
            // A wait longer than a minute gives the source up for the run and cools it down for later runs.
            val tracker = DesktopTracker(runtime.database, library, fetchDetails = {
                calls++
                throw SourceRateLimitException(retryAfterMillis = 120_000L)
            }, preferredBranch = { null }, now = { 86_400_000L })
            library.addFavourite(remote)

            assertEquals(1, tracker.checkAll().failed)
            assertEquals(1, calls)
            val second = tracker.checkAll()
            assertEquals(1, calls, "the cooling source is not asked again")
            assertEquals(1, second.skipped)
        }
    }
}
