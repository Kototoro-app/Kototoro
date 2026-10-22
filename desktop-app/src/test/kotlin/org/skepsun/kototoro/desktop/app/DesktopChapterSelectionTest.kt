package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceRef

class DesktopChapterSelectionTest {
    private fun chapter(id: Long) =
        SourceChapter(id, "第 $id 话", id.toFloat(), 0, "/$id", null, 0, null, SourceRef("fixture", "zh", "MANGA"))

    private val one = chapter(1)
    private val two = chapter(2)
    private val three = chapter(3)
    private val visible = listOf(one, two, three)

    @Test
    fun `toggling adds and removes the same chapter`() {
        val once = DesktopChapterSelection().toggle(two)
        assertEquals(setOf(2L), once.ids)
        assertTrue(once.contains(two))
        assertFalse(once.contains(one))
        assertTrue(once.toggle(two).isEmpty)
    }

    @Test
    fun `select all covers only what the filter shows`() {
        val selection = DesktopChapterSelection(ids = setOf(99L)).withAll(listOf(two))
        assertEquals(setOf(2L), selection.ids)
        assertTrue(selection.withAll(emptyList()).isEmpty)
    }

    @Test
    fun `batch actions resolve in reading order regardless of click order`() {
        val selection = DesktopChapterSelection().toggle(three).toggle(one)
        assertEquals(listOf(one, three), selection.resolve(visible))
        // A chapter that scrolled out of the filtered list is not acted on.
        assertEquals(listOf(one), selection.resolve(listOf(one)))
    }
}
