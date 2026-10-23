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
    fun `category edits keep memberships until the category is deleted`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.addFavourite(content)
            val dao = runtime.database.getFavouriteCategoriesDao()
            val preset = library.categories().single()
            val created = library.createCategory("  Queue  ")
            assertEquals("Queue", library.categories().last().title)
            assertEquals(listOf(preset.id, created), library.categories().map { it.id })

            // Renaming keeps the stored sort order and flags untouched, and refuses a blank title.
            dao.updateTracking(preset.id, true)
            library.renameCategory(preset.id, "Reading")
            assertEquals("Reading", library.categories().first().title)
            assertEquals("NEWEST", dao.find(preset.id.toInt()).order)
            assertTrue(dao.find(preset.id.toInt()).track)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { library.renameCategory(created, " ") } }

            library.reorderCategories(listOf(created, preset.id))
            assertEquals(listOf(created, preset.id), library.categories().map { it.id })
            library.reorderCategories(listOf(preset.id, created))

            // The grid snapshots the same order the rail shows.
            assertEquals(listOf("Reading", "Queue"), library.snapshot().categories.map { it.title })
            assertEquals(setOf(preset.id), library.snapshot().entries.single().categoryIds.map { it.toLong() }.toSet())
        }
    }

    @Test
    fun `deleting a category takes its memberships and repairs the remaining rail`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.addFavourite(content)
            val preset = library.categories().single()
            val other = library.createCategory("Queue")
            library.removeCategories(listOf(preset.id))
            assertFalse(library.isFavourite(content.id))
            assertTrue(library.favourites().isEmpty())
            assertEquals(listOf(other), library.categories().map { it.id })
            assertEquals(other, library.snapshot().categories.single().id)
            // A later favourite needs a default category again, so a fresh one replaces the deleted preset
            // instead of reviving the soft-deleted row or reusing the user's own category.
            library.addFavourite(content)
            val member = library.snapshot().entries.single().categoryIds.map { it.toLong() }
            assertTrue(preset.id !in member)
            assertTrue(other !in member)
            assertEquals("Queue", library.categories().first().title)
            assertEquals(2, library.categories().size)
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
    fun `chapter bookmark covers the chapter without a position and survives reopening`() = runBlocking<Unit> {
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.addChapterBookmark(content, chapters[0])
            val bookmark = library.bookmarks(content.id).single()
            assertEquals(chapters[0].id, bookmark.pageId)
            assertEquals(chapters[0].id, bookmark.chapterId)
            assertEquals(0, bookmark.page)
            assertEquals(0f, bookmark.percent)
            // A second add is a no-op, so the chapter keeps one card.
            library.addChapterBookmark(content, chapters[0])
            assertEquals(1, library.bookmarks(content.id).size)
            // A regenerated chapter is rejected the same way a page bookmark is.
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.addChapterBookmark(content, chapters[0].copy(url = "/changed")) }
            }
            assertEquals(1, library.bookmarks(content.id).size)
        }
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            val bookmark = library.bookmarks(content.id).single()
            assertEquals(chapters[0].id, bookmark.pageId)
            assertNull(library.progress(content.id))
            library.removeBookmark(content, bookmark)
            assertTrue(library.bookmarks(content.id).isEmpty())
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

    @Test
    fun `novel bookmarks persist excerpts and branch positions without history and remove after reopening`() = runBlocking<Unit> {
        val novelSource = source.copy(contentType = "NOVEL")
        val novelChapters = chapters.map { it.copy(source = novelSource) }
        val novel = content.copy(source = novelSource, chapters = novelChapters)
        val excerpt = "正文段落".repeat(80)
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            library.toggleNovelBookmark(novel, novelChapters[0], 2, 5, "  $excerpt  ")
            library.toggleNovelBookmark(novel, novelChapters[2], 1, 4, "第二章文字")
            val bookmarks = library.bookmarks(novel.id)
            assertEquals(2, bookmarks.size)
            assertEquals(2, bookmarks.map { it.pageId }.distinct().size)
            assertEquals(excerpt.take(200), bookmarks[0].preview)
            assertEquals(.3f, bookmarks[0].percent)
            assertEquals(.75f, bookmarks[1].percent)
            assertEquals(0, bookmarks[0].scroll)
            assertNull(library.progress(novel.id))
        }
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            val bookmarks = library.bookmarks(novel.id)
            assertEquals(excerpt.take(200), bookmarks[0].preview)
            library.removeBookmark(novel, bookmarks[1])
            library.toggleNovelBookmark(novel, novelChapters[0], 2, 5, "不同文字")
            assertTrue(library.bookmarks(novel.id).isEmpty())
            assertNull(library.progress(novel.id))
        }
    }

    @Test
    fun `invalid novel bookmark positions identities and chapters leave stored bookmarks intact`() = runBlocking<Unit> {
        val novelSource = source.copy(contentType = "NOVEL")
        val novelChapters = chapters.map { it.copy(source = novelSource) }
        val novel = content.copy(source = novelSource, chapters = novelChapters)
        DesktopRuntime.open(directory).use { runtime ->
            val library = library(runtime)
            for (block in listOf(-1, 5)) assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.toggleNovelBookmark(novel, novelChapters[0], block, 5, "文字") }
            }
            assertNull(runtime.database.getMangaDao().find(novel.id))
            library.toggleNovelBookmark(novel, novelChapters[0], 2, 5, "文字")
            val bookmark = library.bookmarks(novel.id).single()
            val changed = novelChapters[0].copy(url = "/changed")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.toggleNovelBookmark(novel.copy(chapters = listOf(changed)), changed, 0, 1, "变更") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.toggleNovelBookmark(novel.copy(source = novelSource.copy(name = "different")),
                    novelChapters[0], 0, 1, "碰撞") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.removeBookmark(novel.copy(id = 1), bookmark) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.removeBookmark(novel, bookmark.copy(preview = "过期卡片")) }
            }
            assertEquals(bookmark, library.bookmarks(novel.id).single())
            library.removeBookmark(novel, bookmark)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { library.removeBookmark(novel, bookmark) }
            }
            assertNull(library.progress(novel.id))
        }
    }

    private fun library(runtime: DesktopRuntime) = DesktopLibrary(runtime.database) { if (it == source.name) source else null }
    private fun chapter(id: Long, branch: String) = SourceChapter(id, "章节 $id", 1f, 0, "/chapter/$id", "组",
        Long.MAX_VALUE, branch, source, "{\"native\":true}")
}
