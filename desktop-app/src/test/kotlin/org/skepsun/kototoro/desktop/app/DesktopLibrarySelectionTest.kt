package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.desktop.runtime.*

class DesktopLibrarySelectionTest {
    private fun entry(id: Long, title: String, source: String, category: Long, progress: Float?, updated: Long,
        tags: Set<SourceTag> = emptySet(), state: String? = null, nsfw: Boolean = false, newChapters: Int = 0,
        categories: Set<Long> = setOf(category)) =
        DesktopLibraryEntry(SourceContent(id = id, title = title, altTitles = setOf("别名 $id"), url = "/$id",
            publicUrl = "/$id", rating = 0f, contentRating = null, coverUrl = null, tags = tags, state = state,
            authors = setOf("Author $id"), source = SourceRef(source, "zh", "MANGA")), categories, updated,
            if (progress == null) null else updated + 1, progress, isNsfw = nsfw, newChapters = newChapters)

    private val tag = SourceTag("Drama", "drama", SourceRef("one", "zh", "MANGA"))
    private val otherTag = SourceTag("Action", "action", SourceRef("one", "zh", "MANGA"))

    private val snapshot = DesktopLibrarySnapshot(listOf(
        entry(Long.MIN_VALUE, "Zeta", "one", Long.MAX_VALUE, null, 3, setOf(tag)),
        entry(Long.MAX_VALUE, "alpha", "two", 2, .5f, 2, setOf(otherTag), state = "FINISHED"),
        entry(7, "Beta", "one", Long.MAX_VALUE, 1f, 1, setOf(tag, otherTag), state = "ONGOING"),
    ))

    @Test
    fun `source choices combine with category and progress while search checks aliases and authors`() {
        assertEquals(listOf(7L), DesktopLibrarySelection(categoryId = Long.MAX_VALUE, sources = setOf("one", "two"),
            reading = DesktopLibraryReading.FINISHED).select(snapshot).map { it.content.id })
        assertEquals(listOf(Long.MIN_VALUE), DesktopLibrarySelection(reading = DesktopLibraryReading.UNREAD)
            .select(snapshot).map { it.content.id })
        assertEquals(listOf(Long.MAX_VALUE), DesktopLibrarySelection(query = " ALPHA ",
            reading = DesktopLibraryReading.READING).select(snapshot).map { it.content.id })
        assertEquals(1, DesktopLibrarySelection(query = "别名 7").select(snapshot).size)
        assertEquals(1, DesktopLibrarySelection(query = "author 7").select(snapshot).size)
        assertTrue(DesktopLibrarySelection(sources = setOf("missing")).select(snapshot).isEmpty())
    }

    @Test
    fun `tags inside the group are OR-ed and combine with the other groups`() {
        assertEquals(listOf(Long.MIN_VALUE, 7L), DesktopLibrarySelection(tags = setOf(tag))
            .select(snapshot).map { it.content.id })
        assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE, 7L), DesktopLibrarySelection(tags = setOf(tag, otherTag))
            .select(snapshot).map { it.content.id })
        assertEquals(listOf(7L), DesktopLibrarySelection(tags = setOf(otherTag), reading = DesktopLibraryReading.FINISHED)
            .select(snapshot).map { it.content.id })
        assertTrue(DesktopLibrarySelection(tags = setOf(tag), query = "alpha").select(snapshot).isEmpty())
        assertEquals(2, DesktopLibrarySelection(tags = setOf(tag, otherTag)).filterCount)
    }

    @Test
    fun `publication states are OR-ed inside the group and skip works without a state`() {
        assertEquals(listOf(7L), DesktopLibrarySelection(states = setOf("ONGOING"))
            .select(snapshot).map { it.content.id })
        assertEquals(listOf(Long.MAX_VALUE, 7L), DesktopLibrarySelection(states = setOf("ONGOING", "FINISHED"))
            .select(snapshot).map { it.content.id })
        assertEquals(listOf(Long.MAX_VALUE), DesktopLibrarySelection(states = setOf("FINISHED"),
            reading = DesktopLibraryReading.READING, tags = setOf(otherTag)).select(snapshot).map { it.content.id })
        assertTrue(DesktopLibrarySelection(states = setOf("UPCOMING")).select(snapshot).isEmpty())
        assertEquals(2, DesktopLibrarySelection(states = setOf("ONGOING", "FINISHED")).filterCount)
    }

    @Test
    fun `the download group is exclusive and reads the stored chapter set`() {
        assertEquals(listOf(Long.MAX_VALUE, 7L), DesktopLibrarySelection(download = DesktopLibraryDownload.DOWNLOADED)
            .select(snapshot, downloadedContentIds = setOf(Long.MAX_VALUE, 7L)).map { it.content.id })
        assertEquals(listOf(Long.MIN_VALUE), DesktopLibrarySelection(download = DesktopLibraryDownload.MISSING)
            .select(snapshot, downloadedContentIds = setOf(Long.MAX_VALUE, 7L)).map { it.content.id })
        assertEquals(snapshot.entries.size,
            DesktopLibrarySelection(download = DesktopLibraryDownload.MISSING).select(snapshot).size)
        assertTrue(DesktopLibrarySelection(download = DesktopLibraryDownload.DOWNLOADED).select(snapshot).isEmpty())
        assertEquals(1, DesktopLibrarySelection(download = DesktopLibraryDownload.DOWNLOADED).filterCount)
        assertEquals(DesktopLibraryDownload.ALL, DesktopLibrarySelection().download)
    }

    @Test
    fun `macros read the stored nsfw flag, the update counter and the category membership`() {
        val shelf = DesktopLibrarySnapshot(listOf(
            entry(1, "Fresh", "one", 5, .2f, 4, nsfw = true, newChapters = 3),
            entry(2, "Untracked", "one", 5, .4f, 3, newChapters = 0),
            entry(3, "Loose", "one", 5, null, 2, newChapters = 1, categories = emptySet()),
        ))
        assertEquals(listOf(1L, 3L), DesktopLibrarySelection(macros = setOf(DesktopLibraryMacro.NEW_CHAPTERS))
            .select(shelf).map { it.content.id })
        assertEquals(listOf(1L), DesktopLibrarySelection(macros = setOf(DesktopLibraryMacro.NSFW))
            .select(shelf).map { it.content.id })
        assertEquals(listOf(2L, 3L), DesktopLibrarySelection(macros = setOf(DesktopLibraryMacro.SFW))
            .select(shelf).map { it.content.id })
        assertEquals(listOf(3L), DesktopLibrarySelection(macros = setOf(DesktopLibraryMacro.NOT_FAVORITE))
            .select(shelf).map { it.content.id })
        assertEquals(listOf(1L, 2L), DesktopLibrarySelection(macros = setOf(DesktopLibraryMacro.FAVORITE))
            .select(shelf).map { it.content.id })
        // Separate macros are separate groups, so they intersect; SFW and NSFW together match nothing.
        assertEquals(listOf(1L), DesktopLibrarySelection(macros = setOf(DesktopLibraryMacro.NSFW,
            DesktopLibraryMacro.NEW_CHAPTERS)).select(shelf).map { it.content.id })
        assertTrue(DesktopLibrarySelection(macros = setOf(DesktopLibraryMacro.NSFW, DesktopLibraryMacro.SFW))
            .select(shelf).isEmpty())
        assertEquals(2, DesktopLibrarySelection(macros = setOf(DesktopLibraryMacro.NSFW,
            DesktopLibraryMacro.NEW_CHAPTERS)).filterCount)
    }

    @Test
    fun `the counter badge hides for read-through works and never shows on the history shelf`() {
        val fresh = entry(11, "Fresh", "one", 5, .4f, 4, newChapters = 3)
        // Android's FavouritesCardMapper zeroes the counter once ReadingProgress.isCompleted() holds.
        assertEquals(3, newChaptersBadge(fresh, history = false))
        assertEquals(2, newChaptersBadge(entry(12, "Unread", "one", 5, null, 4, newChapters = 2), history = false))
        assertEquals(0, newChaptersBadge(fresh.copy(progressPercent = 1f), history = false))
        assertEquals(0, newChaptersBadge(fresh.copy(progressPercent = 0.99999f), history = false))
        // 99.9% is the reading-status/macro snap, not the badge's own 99.999% threshold.
        assertEquals(3, newChaptersBadge(fresh.copy(progressPercent = 0.999f), history = false))
        assertEquals(0, newChaptersBadge(fresh, history = true))
    }

    @Test
    fun `a pinned work leads every order but only when the shelf asks for it`() {
        val pinned = snapshot.entries.map { if (it.content.id == 7L) it.copy(isPinned = true) else it }
        val shelf = DesktopLibrarySnapshot(pinned)
        // Android's favourites ranking lifts the pin above the chosen order; the tie-break stays the id.
        assertEquals(listOf(7L, Long.MAX_VALUE, Long.MIN_VALUE),
            DesktopLibrarySelection(order = DesktopLibraryOrder.TITLE).select(shelf, pinnedFirst = true)
                .map { it.content.id })
        assertEquals(listOf(7L, Long.MAX_VALUE, Long.MIN_VALUE),
            DesktopLibrarySelection(order = DesktopLibraryOrder.PROGRESS).select(shelf, pinnedFirst = true)
                .map { it.content.id })
        // History has no pin, so it keeps the plain order even when the stored row carries the flag.
        assertEquals(listOf(Long.MAX_VALUE, 7, Long.MIN_VALUE),
            DesktopLibrarySelection(order = DesktopLibraryOrder.TITLE).select(shelf).map { it.content.id })
    }

    @Test
    fun `macro choices keep the favourites-only chips off the favourites shelf`() {
        assertEquals(listOf(DesktopLibraryMacro.NEW_CHAPTERS, DesktopLibraryMacro.NSFW, DesktopLibraryMacro.SFW),
            DesktopLibraryMacro.choices(history = false))
        assertEquals(DesktopLibraryMacro.entries, DesktopLibraryMacro.choices(history = true))
    }

    @Test
    fun `ordering is deterministic and filtering leaves the original snapshot unchanged`() {
        assertEquals(listOf(Long.MAX_VALUE, 7, Long.MIN_VALUE), DesktopLibrarySelection(order = DesktopLibraryOrder.TITLE)
            .select(snapshot).map { it.content.id })
        assertEquals(listOf(7L, Long.MAX_VALUE, Long.MIN_VALUE), DesktopLibrarySelection(order = DesktopLibraryOrder.PROGRESS)
            .select(snapshot).map { it.content.id })
        assertEquals(listOf(Long.MAX_VALUE, 7, Long.MIN_VALUE), DesktopLibrarySelection(order = DesktopLibraryOrder.LAST_READ)
            .select(snapshot).map { it.content.id })
        assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE, 7), snapshot.entries.map { it.content.id })
    }
}
