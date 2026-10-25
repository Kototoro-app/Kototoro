package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderMangaQuickActionsTest {

    @Test
    fun `eight actions without translation, chapters first`() {
        val actions = mangaQuickActions(translationAvailable = false, translationActive = false)
        assertEquals(
            listOf("CHAPTERS", "BOOKMARK", "SAVE_PAGE", "CROP_NOTE", "AUTO_SCROLL", "ROTATE", "DOWNLOAD", "BROWSER"),
            actions.map { it.id },
        )
        actions.forEach { assertNull(it.toggled) }
    }

    @Test
    fun `translation is a toggle appended last when available`() {
        val actions = mangaQuickActions(translationAvailable = true, translationActive = true)
        assertEquals(9, actions.size)
        assertEquals("TRANSLATE", actions.last().id)
        assertEquals(true, actions.last().toggled)
        assertEquals(false, mangaQuickActions(true, translationActive = false).last().toggled)
    }

    @Test
    fun `a television has no rotation cell`() {
        val actions = mangaQuickActions(
            translationAvailable = false,
            translationActive = false,
            canRotate = false,
        )
        assertEquals(
            listOf("CHAPTERS", "BOOKMARK", "SAVE_PAGE", "CROP_NOTE", "AUTO_SCROLL", "DOWNLOAD", "BROWSER"),
            actions.map { it.id },
        )
    }

    @Test
    fun `a local chapter has no download cell`() {
        val actions = mangaQuickActions(
            translationAvailable = false,
            translationActive = false,
            canDownload = false,
        )
        assertEquals(
            listOf("CHAPTERS", "BOOKMARK", "SAVE_PAGE", "CROP_NOTE", "AUTO_SCROLL", "ROTATE", "BROWSER"),
            actions.map { it.id },
        )
    }

    @Test
    fun `a television reading a local chapter keeps the seven working cells`() {
        val actions = mangaQuickActions(
            translationAvailable = true,
            translationActive = false,
            canRotate = false,
            canDownload = false,
        )
        assertEquals(
            listOf("CHAPTERS", "BOOKMARK", "SAVE_PAGE", "CROP_NOTE", "AUTO_SCROLL", "BROWSER", "TRANSLATE"),
            actions.map { it.id },
        )
    }
}
