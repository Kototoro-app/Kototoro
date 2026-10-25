package org.skepsun.kototoro.library.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShelfFilterTest {

    private val row = ShelfRow("MIHON_1", listOf("Drama", "Romance"), "ONGOING", 0.4f, onDevice = true,
        newChapters = 2, isNsfw = false)

    @Test
    fun optionsInAGroupAreOredAndGroupsAnded() {
        assertTrue(ShelfFilter(tags = setOf("drama", "Horror")).accepts(row))
        assertTrue(ShelfFilter(sources = setOf("MIHON_1", "JAR_2"), states = setOf("ONGOING")).accepts(row))
        assertFalse(ShelfFilter(sources = setOf("MIHON_1"), states = setOf("FINISHED")).accepts(row))
        assertTrue(ShelfFilter(macros = setOf(ShelfMacro.NEW_CHAPTERS, ShelfMacro.SFW)).accepts(row))
        assertFalse(ShelfFilter(download = ShelfDownload.MISSING).accepts(row))
        assertEquals(4, ShelfFilter(tags = setOf("a", "b"), reading = ShelfReading.READING, download = ShelfDownload.DOWNLOADED).count)
    }

    @Test
    fun readingAndFinishedDoNotOverlap() {
        assertTrue(ShelfReading.FINISHED.accepts(0.9995f))
        assertFalse(ShelfReading.READING.accepts(0.9995f))
        assertTrue(ShelfReading.UNREAD.accepts(null))
        assertTrue(ShelfReading.READING.accepts(0.5f))
    }
}
