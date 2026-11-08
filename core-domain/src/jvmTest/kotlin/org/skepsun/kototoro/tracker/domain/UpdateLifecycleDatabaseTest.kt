package org.skepsun.kototoro.tracker.domain

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.tracker.data.TrackEntity

class UpdateLifecycleDatabaseTest {

    private fun newDb(): MangaDatabase = Room.inMemoryDatabaseBuilder<MangaDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    private suspend fun MangaDatabase.addWork(id: Long, source: String, newChapters: Int = 0) {
        getMangaDao().upsert(MangaEntity(
            id = id, title = "Work $id", altTitles = null, url = "$id", publicUrl = "https://example.org/$id",
            rating = 0f, isNsfw = false, contentRating = null, coverUrl = "", largeCoverUrl = null,
            state = null, authors = null, source = source,
        ))
        getTracksDao().upsert(TrackEntity(
            mangaId = id, lastChapterId = 0L, newChapters = newChapters, lastCheckTime = 0L, lastChapterDate = 0L,
            lastResult = TrackEntity.RESULT_NONE, lastError = null,
        ))
    }

    /** A remote chapter as a local copy keeps it: remote source, URL and id. */
    private fun remoteChapter(mangaId: Long, id: Long) = ChapterEntity(
        chapterId = id, mangaId = mangaId, title = "Chapter $id", number = id.toFloat(), volume = 0,
        url = "/chapter/$id", scanlator = null, uploadDate = 0L, branch = null, source = "REMOTE", index = id.toInt(),
    )

    @Test
    fun `a local copy without a stored chapter list records its new chapters and reading completes them`() =
        runBlocking {
            val db = newDb()
            try {
                db.addWork(1, source = "LOCAL")
                val chapters = (1L..3L).map { remoteChapter(1, it) }

                // Nothing is stored for a local copy, so looking the chapters up finds nothing to record.
                assertEquals(0, db.recordUpdateBatch(1, setOf(2L, 3L), now = 10))
                assertEquals(2, db.recordUpdateChapters(1, chapters.drop(1), now = 10))
                assertEquals(2, db.getTracksDao().find(1)?.newChapters)
                assertEquals(1, db.getTrackLogsDao().findByOwner(1).size)

                // Without the reader's list the chapter is unknown and nothing completes.
                db.completeUpdateChapter(1, chapterId = 2, now = 20)
                assertEquals(2, db.getTracksDao().find(1)?.newChapters)
                db.completeUpdateChapter(1, chapterId = 2, now = 20, chapters = chapters)
                assertEquals(1, db.getTracksDao().find(1)?.newChapters)
            } finally {
                db.close()
            }
        }

    @Test
    fun `counters without records are cleared except for local copies`() = runBlocking {
        val db = newDb()
        try {
            db.addWork(1, source = "REMOTE", newChapters = 3) // left over: no record backs it
            db.addWork(2, source = "LOCAL", newChapters = 2) // a local copy, still counted down by position
            db.addWork(3, source = "REMOTE")
            db.recordUpdateChapters(3, listOf(remoteChapter(3, 7)), now = 10)

            assertEquals(1, db.getTracksDao().clearUnrecordedCounters(listOf("LOCAL")))
            assertEquals(0, db.getTracksDao().find(1)?.newChapters)
            assertEquals(2, db.getTracksDao().find(2)?.newChapters)
            assertEquals(1, db.getTracksDao().find(3)?.newChapters)
        } finally {
            db.close()
        }
    }
}
