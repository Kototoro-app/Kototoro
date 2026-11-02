@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.tracker.data.TrackEntity
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Query budgets exercise the actual iOS API and Room driver, independently of machine timing. */
class IosLibraryReadPerformanceTest {
    @Test
    fun historyDoesNotReadOtherWorksWithTheSameChapterId() = runBlocking {
        withLibrary { library, queries ->
            val first = work(1)
            val other = IosSourceContent(work(2).value.copy(chapters = first.value.chapters?.map {
                it.copy(title = "unrelated", url = "/other/${it.id}")
            }))
            library.setFavourite(first, true)
            library.setFavourite(other, true)
            library.recordPosition(first, first.chapters[1], 0, 10, 0f)
            queries.reset()
            val history = library.history().single()
            assertEquals(first.id, history.content.id)
            assertEquals("chapter 2", history.lastChapterTitle)
            assertEquals(1, queries.loadedChapterRows, "Resume captions must read only the chapters of listed works")
        }
    }

    @Test
    fun listSnapshotsLeaveChaptersForDetailsAndKeepResumeAndCategories() = runBlocking {
        withLibrary { library, queries ->
            val full = work(1)
            library.addCategory("first")
            library.addCategory("second")
            val categories = library.categories().map { it.id }
            library.setCategories(full, categories)
            library.recordPosition(full, full.chapters[1], 2, 10, 0f)
            queries.reset()
            val favourite = library.favourites().single()
            val history = library.history().single()
            val reads = queries.take()
            assertTrue(favourite.content.chapters.isEmpty(), "Collection snapshots must not hydrate full chapters")
            assertTrue(history.content.chapters.isEmpty())
            assertEquals(categories.toSet(), favourite.categoryIds.toSet())
            assertEquals(full.id, history.content.id)
            assertEquals("chapter 2", history.lastChapterTitle)
            assertTrue(history.hasProgress)
            assertEquals(favourite.progress, history.progress)
            assertTrue(reads.none { it.contains("FROM chapters WHERE manga_id", ignoreCase = true) }, reads.toString())
            val details = requireNotNull(library.storedDetails(favourite.content))
            assertEquals(3, details.chapterCount)
            assertEquals(full.chapters[1].id, library.position(details)?.chapterId)
            assertEquals(library.position(details), library.position(favourite.content))
            // A sparse collection item must never erase saved chapters when re-saved or re-categorized.
            library.saveDetails(favourite.content)
            library.setCategories(favourite.content, listOf(categories.first()))
            assertEquals(3, library.storedDetails(favourite.content)?.chapterCount)
        }
    }

    @Test
    fun updatesUseOneFilteredTrackReadAndBatchWorksWithoutChapters() = runBlocking {
        withLibrary { library, queries ->
            for (id in 1L..64L) {
                library.setFavourite(work(id), true)
                library.database.getTracksDao().upsert(track(id))
            }
            queries.reset()
            assertTrue(library.updates().isEmpty())
            val emptyReads = queries.take()
            assertEquals(1, emptyReads.count { it.contains("FROM tracks", ignoreCase = true) }, emptyReads.toString())
            assertTrue(emptyReads.none { it.contains("FROM chapters", ignoreCase = true) })
            library.database.getTracksDao().upsert(track(1, 3, 10))
            library.database.getTracksDao().upsert(track(63, 5, 20))
            queries.reset()
            val updates = library.updates()
            val reads = queries.take()
            assertEquals(listOf("63", "1"), updates.map { it.content.id })
            assertEquals(listOf(5, 3), updates.map { it.newChapters })
            assertEquals(listOf(20L, 10L), updates.map { it.checkedAt })
            assertTrue(updates.all { it.content.chapters.isEmpty() })
            assertEquals(1, reads.count { it.contains("FROM tracks", ignoreCase = true) }, reads.toString())
            assertTrue(reads.none { it.contains("FROM chapters", ignoreCase = true) })
            assertEquals(3, library.storedDetails(updates.first().content)?.chapterCount)
        }
    }

    @Test
    fun recoveryReadsOnlySourceReferencesAndPreservesActiveMembershipRules() = runBlocking {
        withLibrary { library, queries ->
            val favourite = work(1)
            val historyOnly = work(2)
            val removed = work(3, "REMOVED")
            val deletedCategory = work(4, "DELETED_CATEGORY")
            val local = work(5, "LOCAL")
            library.addCategory("first")
            library.addCategory("second")
            library.addCategory("removed category")
            val categories = library.categories().map { it.id }
            library.setCategories(favourite, categories.take(2))
            library.recordPosition(favourite, favourite.chapters[1], 0, 10, 0f)
            library.recordPosition(historyOnly, historyOnly.chapters[0], 0, 10, 0f)
            library.setFavourite(removed, true)
            library.setFavourite(removed, false)
            library.setCategories(deletedCategory, listOf(categories[2]))
            val categoryDao = library.database.getFavouriteCategoriesDao()
            val category = categoryDao.findAll().single { it.categoryId == categories[2] }
            categoryDao.upsert(category.copy(deletedAt = 1))
            library.setFavourite(local, true)
            queries.reset()
            val missing = library.recovery.missing(emptyList()).single()
            val reads = queries.take()
            assertEquals("AUDIT", missing.key)
            assertEquals(2, missing.works)
            assertEquals(1, missing.favourites)
            assertEquals(2, missing.history)
            assertTrue(reads.size <= 3, "Recovery must not build library DTOs: $reads")
            assertTrue(reads.none { it.contains("FROM chapters", ignoreCase = true) })
            assertTrue(library.recovery.missing(listOf("AUDIT")).isEmpty())
        }
    }

    private fun track(id: Long, newChapters: Int = 0, checkedAt: Long = 0) =
        TrackEntity(id, 0, newChapters, checkedAt, 0, TrackEntity.RESULT_NONE, null)

    private fun work(id: Long, sourceName: String = "AUDIT"): IosSourceContent {
        val source = SourceRef(sourceName, "zh", "MANGA")
        return IosSourceContent(SourceContent(id, "work $id", emptySet(), "/$id", "https://fixture.invalid/$id",
            0f, null, null, emptySet(), null, emptySet(), source,
            chapters = (1..3).map { index ->
                SourceChapter(id * 10 + index, "chapter $index", index.toFloat(), 0, "/$id/$index", null, 0, null, source)
            }))
    }

    private suspend fun withLibrary(block: suspend (IosLibrary, QueryDriver) -> Unit) {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val driver = QueryDriver()
        val database = Room.databaseBuilder<MangaDatabase>(name = "$directory/library.db")
            .setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
        val library = IosLibrary(database)
        try { block(library, driver) }
        finally {
            library.close()
            files.removeItemAtPath(directory, null)
        }
    }

    private class QueryDriver : SQLiteDriver {
        private val delegate = BundledSQLiteDriver()
        private val reads = AtomicReference<List<String>>(emptyList())
        private val chapterRows = AtomicInt(0)
        val loadedChapterRows: Int get() = chapterRows.load()
        fun reset() { reads.store(emptyList()); chapterRows.store(0) }
        fun take(): List<String> = reads.load()
        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        private var started = false
                        override fun step(): Boolean {
                            if (!started && sql.trimStart().startsWith("SELECT", ignoreCase = true)) {
                                do { val old = reads.load() } while (!reads.compareAndSet(old, old + sql))
                            }
                            started = true
                            val row = statement.step()
                            if (row && sql.contains("FROM chapters", ignoreCase = true)) chapterRows.fetchAndAdd(1)
                            return row
                        }
                        override fun reset() {
                            started = false
                            statement.reset()
                        }
                    }
                }
            }
        }
    }
}
