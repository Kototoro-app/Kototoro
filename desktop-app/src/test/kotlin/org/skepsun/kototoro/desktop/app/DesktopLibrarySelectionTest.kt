package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.desktop.runtime.*

class DesktopLibrarySelectionTest {
    private fun entry(id: Long, title: String, source: String, category: Long, progress: Float?, updated: Long,
        tags: Set<SourceTag> = emptySet(), state: String? = null) =
        DesktopLibraryEntry(SourceContent(id = id, title = title, altTitles = setOf("别名 $id"), url = "/$id",
            publicUrl = "/$id", rating = 0f, contentRating = null, coverUrl = null, tags = tags, state = state,
            authors = setOf("Author $id"), source = SourceRef(source, "zh", "MANGA")), setOf(category), updated,
            if (progress == null) null else updated + 1, progress)

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
