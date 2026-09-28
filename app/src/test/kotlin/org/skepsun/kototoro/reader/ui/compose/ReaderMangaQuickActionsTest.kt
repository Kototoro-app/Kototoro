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
}
