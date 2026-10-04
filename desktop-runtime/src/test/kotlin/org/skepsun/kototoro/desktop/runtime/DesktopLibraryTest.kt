package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.*
import java.nio.file.Path

class DesktopLibraryTest {
    @TempDir lateinit var directory: Path
    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")
    private val chapters = listOf(chapter(Long.MIN_VALUE, "A"), chapter(Long.MAX_VALUE, "B"), chapter(7, "A"))
    private val content = SourceContent(
        Long.MAX_VALUE, "中文作品", setOf("另一标题"), "/work", "https://fixture.invalid/work", .8f, null, null,
        setOf(SourceTag("标签", "tag", source)), "ONGOING", setOf("作者一", "作者二"), source,
        description = "说明", chapters = chapters, sourceData = "{\"opaque\":\"9007199254740993\"}",
    )

    @Test
    fun `shared favourites and progress persist lossless DTO fields across reopening`() = runBlocking<Unit> {
        var firstCreated = 0L
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.addFavourite(content)
            assertEquals(content, library.favourites().single())
            library.recordPage(content, chapters[0], 1, 4)
            firstCreated = requireNotNull(library.progress(content.id)).createdAt
            library.recordPage(content, chapters[2], 0, 2)
        }
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            assertEquals(content, library.favourites().single())
            assertEquals(content, library.history().single())
            val progress = requireNotNull(library.progress(content.id))
            assertEquals(firstCreated, progress.createdAt)
            assertEquals(7L, progress.chapterId)
            assertEquals(2, progress.chaptersCount)
            assertEquals(.75f, progress.percent)
            assertEquals(0, progress.page)
        }
    }

    @Test
    fun `continuous history keeps pixel offsets across reopening and rejects invalid scroll values`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.recordPage(content, chapters[0], 1, 5, lastVisiblePage = 2, scroll = 184f)
            val progress = library.progress(content.id)
            assertEquals(184f, progress?.scroll)
            assertEquals(.3f, progress?.percent)
            for (offset in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { library.recordPage(content, chapters[0], 1, 5, scroll = offset) }
                }
            }
            assertEquals(progress, library.progress(content.id))
        }
        DesktopRuntime.open(directory).use { runtime ->
            assertEquals(184f, library(runtime).progress(content.id)?.scroll)
            assertEquals(1, library(runtime).progress(content.id)?.page)
        }
    }

    @Test
    fun `spread history restores its first page while percent includes its last visible page`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.recordPage(content, chapters[2], 3, 5, lastVisiblePage = 4)
            val progress = requireNotNull(library.progress(content.id))
            assertEquals(3, progress.page)
            assertEquals(1f, progress.percent)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.recordPage(content, chapters[2], 3, 5, lastVisiblePage = 2) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.recordPage(content, chapters[2], 3, 5, lastVisiblePage = 5) }
            }
            assertEquals(progress, library.progress(content.id))
        }
        DesktopRuntime.open(directory).use { runtime ->
            assertEquals(3, library(runtime).progress(content.id)?.page)
            assertEquals(1f, library(runtime).progress(content.id)?.percent)
        }
    }

    @Test
    fun `readding a favourite preserves pin order and creation time and revives only its row`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.addFavourite(content)
            val dao = runtime.database.getFavouritesDao()
            val previous = dao.findByMangaId(content.id).single().copy(isPinned = true, sortKey = 29, createdAt = 123,
                deletedAt = 500)
            dao.upsert(previous)
            assertFalse(library.isFavourite(content.id))
            library.addFavourite(content)
            val restored = dao.findByMangaId(content.id).single()
            assertTrue(restored.isPinned)
            assertEquals(29, restored.sortKey)
            assertEquals(123L, restored.createdAt)
            assertEquals(0L, restored.deletedAt)
            assertEquals(1, runtime.database.getFavouriteCategoriesDao().findAll().size)
        }
    }

    @Test
    fun `deleted categories are excluded from library and favourite flag`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.addFavourite(content)
            val categories = runtime.database.getFavouriteCategoriesDao()
            categories.delete(categories.findAll().single().categoryId.toLong())
            assertFalse(library.isFavourite(content.id))
            assertTrue(library.favourites().isEmpty())
            library.addFavourite(content)
            assertTrue(library.isFavourite(content.id))
            assertEquals(content, library.favourites().single())
        }
    }

    @Test
    fun `identity collision fails before modifying tags favourites or chapters`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.addFavourite(content)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.addFavourite(content.copy(source = SourceRef("different", "", "MANGA"))) }
            }
            assertEquals(content, library.favourites().single())
        }
    }

    @Test
    fun `invalid pages and changed chapters cannot write history or replace stored details`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            for (page in listOf(-1, 4)) assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.recordPage(content, chapters[0], page, 4) }
            }
            assertNull(runtime.database.getMangaDao().find(content.id))
            library.addFavourite(content)
            val changed = chapters[0].copy(url = "/changed")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.recordPage(content.copy(chapters = listOf(changed)), changed, 0, 1) }
            }
            assertNull(library.progress(content.id))
            assertEquals(content, library.favourites().single())
        }
    }

    @Test
    fun `bookmarks retain signed ids and pixel offsets after reopening without writing history`() = runBlocking<Unit> {
        val page = SourcePage(Long.MIN_VALUE + 1, "/page", null, source)
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.toggleBookmark(content, chapters[0], page, 2, 5, 184.9f)
            assertNull(library.progress(content.id))
            val bookmark = library.bookmarks(content.id).single()
            assertEquals(content.id, bookmark.contentId)
            assertEquals(page.id, bookmark.pageId)
            assertEquals(Long.MIN_VALUE, bookmark.chapterId)
            assertEquals(184, bookmark.scroll)
            assertEquals(.3f, bookmark.percent)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.toggleBookmark(content, chapters[0], page, 5, 5) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.toggleBookmark(content, chapters[0], page, 2, 5, Float.NaN) }
            }
            assertEquals(bookmark, library.bookmarks(content.id).single())
        }
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            assertEquals(184, library.bookmarks(content.id).single().scroll)
            library.toggleBookmark(content, chapters[0], page.copy(id = 42), 2, 5)
            assertTrue(library.bookmarks(content.id).isEmpty())
            assertNull(library.progress(content.id))
        }
    }

    @Test
    fun `library snapshot contains active memberships and history facets without deleted categories`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.addFavourite(content)
            library.recordPage(content, chapters[0], 1, 4)
            val snapshot = library.snapshot()
            val entry = snapshot.entries.single()
            assertEquals(content, entry.content)
            assertEquals(snapshot.categories.map { it.id }.toSet(), entry.categoryIds)
            assertEquals(.25f, entry.progressPercent)
            assertEquals(library.progress(content.id)?.updatedAt, entry.lastReadAt)
            runtime.database.getFavouriteCategoriesDao().delete(snapshot.categories.single().id)
            assertTrue(library.snapshot().entries.isEmpty())
            val history = library.snapshot(history = true)
            assertEquals(content, history.entries.single().content)
            assertTrue(history.entries.single().categoryIds.isEmpty())
            assertTrue(history.categories.isEmpty())
        }
    }

    private fun library(runtime: DesktopRuntime) = DesktopLibrary(runtime.database) { if (it == source.name) source else null }
    private fun chapter(id: Long, branch: String) = SourceChapter(id, "章节 $id", 1f, 0, "/chapter/$id", "组",
        Long.MAX_VALUE, branch, source, "{\"native\":true}")
}
