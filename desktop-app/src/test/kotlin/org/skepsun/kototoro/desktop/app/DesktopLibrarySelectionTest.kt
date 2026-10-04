package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.desktop.runtime.*

class DesktopLibrarySelectionTest {
    private fun entry(id: Long, title: String, source: String, category: Long, progress: Float?, updated: Long) =
        DesktopLibraryEntry(SourceContent(id, title, setOf("别名 $id"), "/$id", "/$id", 0f, null, null,
            emptySet(), null, setOf("Author $id"), SourceRef(source, "zh", "MANGA")), setOf(category), updated,
            if (progress == null) null else updated + 1, progress)

    private val snapshot = DesktopLibrarySnapshot(listOf(
        entry(Long.MIN_VALUE, "Zeta", "one", Long.MAX_VALUE, null, 3),
        entry(Long.MAX_VALUE, "alpha", "two", 2, .5f, 2),
        entry(7, "Beta", "one", Long.MAX_VALUE, 1f, 1),
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
