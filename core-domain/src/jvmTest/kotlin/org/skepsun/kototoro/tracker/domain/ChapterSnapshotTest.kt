package org.skepsun.kototoro.tracker.domain

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.tracker.data.TrackEntity

class ChapterSnapshotTest {

    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun newDb(): MangaDatabase = Room.inMemoryDatabaseBuilder<MangaDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    private fun chapter(
        number: Float,
        id: Long = (number * 10).toLong(),
        url: String = "/c/$id",
        branch: String? = null,
        title: String = "Chapter ${number.toString().removeSuffix(".0")}",
        uploadDate: Long = now - day,
    ) = ChapterEntity(id, 1, title, number, 0, url, null, uploadDate, branch, "REMOTE", 0)

    private fun List<ChapterEntity>.indexed() = mapIndexed { index, chapter -> chapter.copy(index = index) }

    private suspend fun MangaDatabase.work(tracked: Boolean = true) {
        getMangaDao().upsert(MangaEntity(
            id = 1, title = "Work", altTitles = null, url = "/w", publicUrl = "https://example.org/w",
            rating = 0f, isNsfw = false, contentRating = null, coverUrl = "", largeCoverUrl = null,
            state = null, authors = null, source = "REMOTE",
        ))
        if (tracked) {
            getTracksDao().upsert(TrackEntity(1, 0, 0, 0, 0, TrackEntity.RESULT_NONE, null))
        }
    }

    private suspend fun MangaDatabase.read(chapterId: Long) = getHistoryDao().upsert(HistoryEntity(
        mangaId = 1, createdAt = 1, updatedAt = 1, chapterId = chapterId, page = 0, scroll = 0f, percent = 0.5f,
        chaptersCount = 0, deletedAt = 0, parentChapterId = null,
    ))

    private suspend fun MangaDatabase.store(chapters: List<ChapterEntity>, markDuplicatesRead: Boolean = false) =
        storeChapterSnapshot(1, "Work", chapters.indexed(), now, markDuplicatesRead = markDuplicatesRead)

    private fun withDb(block: suspend (MangaDatabase) -> Unit) = runBlocking {
        val db = newDb()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun ChapterSnapshotResult.reportedIds() = (this as ChapterSnapshotResult.Updated).reported.map { it.chapterId }

    @Test
    fun `an untracked work and a tracked work's first list are only stored`() = withDb { db ->
        db.work(tracked = false)
        assertEquals(ChapterSnapshotResult.Stored, db.store(listOf(chapter(1f))))
        assertEquals(ChapterSnapshotResult.Stored, db.store(listOf(chapter(1f), chapter(2f))))
        assertEquals(2, db.getChaptersDao().findAll(1).size)

        db.getTracksDao().upsert(TrackEntity(1, 0, 0, 0, 0, TrackEntity.RESULT_NONE, null))
        db.getChaptersDao().deleteAll(1)
        assertEquals(ChapterSnapshotResult.Stored, db.store(listOf(chapter(1f))))
    }

    @Test
    fun `appended and inserted chapters are recorded and move the track to the end`() = withDb { db ->
        db.work()
        db.store((1..5).map { chapter(it.toFloat()) })

        val result = db.store((1..5).map { chapter(it.toFloat()) } + chapter(2.5f) + chapter(6f))

        assertEquals(listOf(25L, 60L), result.reportedIds())
        assertEquals(2, db.getTracksDao().find(1)?.newChapters)
        assertEquals(60L, db.getTracksDao().find(1)?.lastChapterId)
        assertEquals(1, db.getTrackLogsDao().findByOwner(1).size)
        // Storing the same list again finds nothing new.
        assertEquals(emptyList<Long>(), db.store((1..5).map { chapter(it.toFloat()) } + chapter(2.5f) + chapter(6f))
            .reportedIds())
    }

    @Test
    fun `chapters that moved to new urls keep their update and are not reported again`() = withDb { db ->
        db.work()
        db.store((1..5).map { chapter(it.toFloat()) })
        db.store((1..6).map { chapter(it.toFloat()) })
        assertEquals(1, db.getTracksDao().find(1)?.newChapters)

        // The source moved every chapter: new urls and ids, same numbers, and chapter 7 is new.
        val moved = (1..7).map { chapter(it.toFloat(), id = 100L + it, url = "/new/$it") }
        assertEquals(listOf(107L), db.store(moved).reportedIds())

        val recorded = db.getTrackLogsDao().findByOwner(1).flatMap { decodeUpdateChapters(it.chapterMetadata) }
        assertEquals(setOf(106L, 107L), recorded.mapTo(HashSet()) { it.chapterId })
        assertEquals(2, db.getTracksDao().find(1)?.newChapters, "chapter 6 is still pending under its new identity")
    }

    @Test
    fun `a list cut short is not stored and an unnumbered rebuild is not reported`() = withDb { db ->
        db.work()
        db.store((1..20).map { chapter(it.toFloat()) })

        assertEquals(ChapterSnapshotResult.Incomplete, db.store((1..5).map { chapter(it.toFloat()) }))
        assertEquals(20, db.getChaptersDao().findAll(1).size)

        val rebuilt = (1..21).map { chapter(it.toFloat(), id = 500L + it, url = "/x/$it", title = "Part") }
            .map { it.copy(number = 0f) }
        assertEquals(ChapterSnapshotResult.Stored, db.store(rebuilt))
        assertEquals(0, db.getTracksDao().find(1)?.newChapters)
    }

    @Test
    fun `only the branch being read counts and read or stale chapters are skipped`() = withDb { db ->
        db.work()
        db.store(listOf(chapter(1f, branch = "A"), chapter(1f, id = 11, url = "/b/1", branch = "B")))
        db.read(chapterId = 10)

        val result = db.store(listOf(
            chapter(1f, branch = "A"),
            chapter(2f, branch = "A", uploadDate = now - 200 * day),
            chapter(3f, branch = "A"),
            chapter(1f, id = 11, url = "/b/1", branch = "B"),
            chapter(2f, id = 21, url = "/b/2", branch = "B"),
        ))

        assertEquals(listOf(30L), result.reportedIds(), "stale chapter 2 and branch B are left out")
    }

    @Test
    fun `a new chapter repeating a read number can be recorded as read`() = withDb { db ->
        db.work()
        db.store((1..3).map { chapter(it.toFloat()) })
        db.read(chapterId = 30)

        val reupload = chapter(3f, id = 31, url = "/other/3")
        val result = db.store((1..3).map { chapter(it.toFloat()) } + reupload + chapter(4f), markDuplicatesRead = true)

        assertEquals(listOf(31L, 40L), result.reportedIds())
        assertEquals(1, db.getTracksDao().find(1)?.newChapters, "the duplicate of read chapter 3 does not count")
        assertTrue(db.getTrackLogsDao().findByOwner(1).flatMap { decodeUpdateChapters(it.chapterMetadata) }
            .single { it.chapterId == 31L }.readAt > 0)
    }

    @Test
    fun `a restored track without a stored list reports the chapters after its last chapter`() = withDb { db ->
        // A backup restores the track with its last chapter, but no chapter list.
        db.work(tracked = false)
        db.getTracksDao().upsert(TrackEntity(1, 30, 0, 0, now - 40 * day, TrackEntity.RESULT_NO_UPDATE, null))

        val result = db.store((1..5).map { chapter(it.toFloat()) })

        assertEquals(listOf(40L, 50L), result.reportedIds())
        assertEquals(2, db.getTracksDao().find(1)?.newChapters)
        assertEquals(50L, db.getTracksDao().find(1)?.lastChapterId)
    }

    @Test
    fun `a restored track whose last chapter is gone falls back to its date`() = withDb { db ->
        db.work(tracked = false)
        db.getTracksDao().upsert(TrackEntity(1, 999, 0, 0, now - 10 * day, TrackEntity.RESULT_NO_UPDATE, null))

        val chapters = (1..9).map { chapter(it.toFloat(), uploadDate = now - 30 * day) } + chapter(10f)
        assertEquals(listOf(100L), db.store(chapters).reportedIds())
    }
}
