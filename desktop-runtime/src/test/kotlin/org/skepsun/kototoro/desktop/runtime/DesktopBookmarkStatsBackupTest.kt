package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.bookmarks.data.BookmarkEntity
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.stats.data.StatsEntity
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DesktopBookmarkStatsBackupTest {
    @TempDir lateinit var directory: Path
    private val index = """[{"app_id":"org.skepsun.kototoro","app_version":1,"created_at":1}]"""
    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private fun content(id: Long) = SourceContent(id, "仅有书签或统计", emptySet(), "/$id", "https://fixture.invalid/$id",
        -1f, null, null, setOf(SourceTag("标签", "key", source)), null, emptySet(), source)
    private fun manga(id: Long) =
        """{"id":$id,"title":"仅有书签或统计","url":"/$id","public_url":"https://fixture.invalid/$id","cover_url":"","source":"${source.name}"}"""
    private fun bookmark(id: Long, page: Int = 2, scroll: Int = 5, percent: String = "0.5", chapter: Long = Long.MIN_VALUE,
        created: Long = 10) =
        """{"manga_id":$id,"page_id":9223372036854775807,"chapter_id":$chapter,"page":$page,"scroll":$scroll,"percent":$percent,"image_url":"https://fixture.invalid/image","created_at":$created}"""
    private fun bookmarks(id: Long, row: String = bookmark(id)) =
        """[{"manga":${manga(id)},"tags":[{"id":8,"title":"外置标签","key":"outer","source":"${source.name}"}],"bookmarks":[$row]}]"""
    private fun statistic(id: Long, duration: Long = 100, pages: Int = 3) =
        """[{"manga_id":$id,"started_at":9223372036854775807,"duration":$duration,"pages":$pages}]"""

    @Test
    fun `bookmarks across export pagination and stats-only works retain tags and full ids after two restores`() = runBlocking<Unit> {
        val file = directory.resolve("书签统计.zip")
        val expected = (0..8).map { page -> BookmarkEntity(0, Long.MAX_VALUE - page, Long.MIN_VALUE, page, 120,
            "https://fixture.invalid/$page", 20, page / 9f) }
        DesktopRuntime.open(directory.resolve("original")).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            library.save(content(0)); library.save(content(-9))
            runtime.database.getBookmarksDao().upsert(expected)
            runtime.database.getStatsDao().upsert(StatsEntity(-9, Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE))
            DesktopLibraryBackup(runtime.database).export(file)
            assertTrue(library.favourites().isEmpty()); assertTrue(library.history().isEmpty())
        }
        ZipFile(file.toFile()).use { zip ->
            val projection = zip.getInputStream(zip.getEntry("projections")).bufferedReader().use { it.readText() }
            assertTrue(projection.contains("\"id\":0")); assertTrue(projection.contains("\"id\":-9"))
        }
        for (name in listOf("first", "second")) {
            val exported = directory.resolve("$name.zip")
            DesktopRuntime.open(directory.resolve(name)).use { runtime ->
                val backups = DesktopLibraryBackup(runtime.database)
                backups.preview(if (name == "first") file else directory.resolve("first.zip")).use { preview ->
                    assertEquals(9, preview.counts[BackupSection.BOOKMARKS])
                    assertEquals(1, preview.counts[BackupSection.STATS])
                    assertEquals(2, preview.counts[BackupSection.CONTENTS])
                    assertTrue(preview.otherEntries.isEmpty())
                    backups.restore(preview)
                }
                backups.export(exported)
            }
            DesktopRuntime.open(directory.resolve(name)).use { runtime ->
                assertEquals(expected, runtime.database.getBookmarksDao().observe(0).first())
                assertEquals(StatsEntity(-9, Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE),
                    runtime.database.getStatsDao().find(-9, Long.MAX_VALUE))
                for (id in listOf(0L, -9L)) assertEquals("key", runtime.database.getMangaDao().find(id)?.tags?.single()?.key)
            }
        }
    }

    @Test
    fun `merge protects newer bookmarks uses both primary keys and merges duplicate session counters idempotently`() = runBlocking<Unit> {
        val file = zip("statistics" to statistic(0, 200, 2), "bookmarks" to bookmarks(0), "index" to index,
            "projections" to "[${manga(0)}]", "work_stats" to
                """[{"entity_id":999,"anchor_manga_id":0,"started_at":9223372036854775807,"duration":150,"pages":8}]""")
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val library = DesktopLibrary(runtime.database) { source }
            library.save(content(0)); library.save(content(-9))
            val newer = BookmarkEntity(0, Long.MAX_VALUE, Long.MIN_VALUE, 2, 90, "local", 500, .8f)
            val other = newer.copy(mangaId = -9, chapterId = 3, page = 0)
            runtime.database.getBookmarksDao().upsert(listOf(other, newer))
            runtime.database.getStatsDao().upsert(StatsEntity(0, Long.MAX_VALUE, 100, 5))
            val backups = DesktopLibraryBackup(runtime.database)
            repeat(2) {
                backups.preview(file).use { preview ->
                    assertEquals(1, preview.counts[BackupSection.WORK_STATS]); backups.restore(preview)
                }
                assertEquals(newer, runtime.database.getBookmarksDao().find(0, Long.MAX_VALUE))
                assertEquals(other, runtime.database.getBookmarksDao().find(-9, Long.MAX_VALUE))
                assertEquals(StatsEntity(0, Long.MAX_VALUE, 200, 8), runtime.database.getStatsDao().find(0, Long.MAX_VALUE))
                assertEquals("outer", runtime.database.getMangaDao().find(0)?.tags?.single()?.key)
            }
            backups.preview(zip("index" to index, "bookmarks" to bookmarks(0, bookmark(0, scroll = 72, created = 600)))).use {
                backups.restore(it)
            }
            assertEquals(72, runtime.database.getBookmarksDao().find(0, Long.MAX_VALUE)?.scroll)
        }
    }

    @Test
    fun `invalid bookmark identities positions and statistics roll back all preceding categories content and state`() = runBlocking<Unit> {
        val invalidSections = listOf(
            "bookmarks" to bookmarks(0, bookmark(-9)),
            "bookmarks" to bookmarks(0, bookmark(0, page = -1)),
            "bookmarks" to bookmarks(0, bookmark(0, scroll = -1)),
            "bookmarks" to bookmarks(0, bookmark(0, percent = "1.1")),
            "statistics" to statistic(0, -1),
            "statistics" to statistic(0, pages = -1),
            "statistics" to statistic(-9),
        )
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            val backups = DesktopLibraryBackup(runtime.database)
            for ((section, rows) in invalidSections) {
                val entries = mutableListOf("index" to index, "categories" to
                    """[{"category_id":1,"created_at":1,"sort_key":1,"title":"分类","track":false}]""",
                    "projections" to "[${manga(0)}]", section to rows)
                if (section == "statistics") entries += "bookmarks" to bookmarks(0)
                val file = zip(*entries.toTypedArray())
                backups.preview(file).use { preview ->
                    assertThrows(IllegalArgumentException::class.java) { runBlocking { backups.restore(preview) } }
                }
                assertTrue(runtime.database.getFavouriteCategoriesDao().findAll().isEmpty())
                assertNull(runtime.database.getMangaDao().find(0))
                assertTrue(runtime.database.getBookmarksDao().observe(0).first().isEmpty())
                assertTrue(runtime.database.getStatsDao().findAll(0).isEmpty())
            }
        }
    }

    @Test
    fun `bookmark page collisions roll back preceding content and missing legacy stats anchors are skipped`() = runBlocking<Unit> {
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            DesktopLibrary(runtime.database) { source }.save(content(0))
            val local = BookmarkEntity(0, Long.MAX_VALUE, 3, 1, 1, "local", 20, .5f)
            runtime.database.getBookmarksDao().upsert(listOf(local))
            val backups = DesktopLibraryBackup(runtime.database)
            backups.preview(zip("index" to index, "projections" to "[${manga(-9)}]",
                "bookmarks" to bookmarks(0))).use { preview ->
                assertThrows(IllegalArgumentException::class.java) { runBlocking { backups.restore(preview) } }
            }
            assertNull(runtime.database.getMangaDao().find(-9))
            assertEquals(local, runtime.database.getBookmarksDao().find(0, Long.MAX_VALUE))
            backups.preview(zip("index" to index, "work_stats" to
                """[{"entity_id":999,"anchor_manga_id":-9,"started_at":1,"duration":2,"pages":1}]""")).use {
                assertEquals(0, backups.restore(it))
            }
            assertTrue(runtime.database.getStatsDao().findAll(-9).isEmpty())
        }
    }

    @Test
    fun `preview decodes new supported sections before any writes`() = runBlocking<Unit> {
        DesktopRuntime.open(directory.resolve("db")).use { runtime ->
            for (section in listOf("bookmarks", "statistics", "work_stats")) {
                assertThrows(Exception::class.java) {
                    runBlocking { DesktopLibraryBackup(runtime.database).preview(zip("index" to index,
                        "categories" to "[]", section to "[{")) }
                }
            }
            assertTrue(runtime.database.getFavouriteCategoriesDao().findAll().isEmpty())
        }
    }

    private fun zip(vararg entries: Pair<String, String>): Path {
        val file = Files.createTempFile(directory, "fixture-", ".zip")
        ZipOutputStream(Files.newOutputStream(file)).use { output ->
            for ((name, text) in entries) {
                output.putNextEntry(ZipEntry(name)); output.write(text.toByteArray()); output.closeEntry()
            }
        }
        return file
    }
}
