package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NovelReaderQuickActionsTest {

    @Test
    fun `five actions with translation toggle last and auto scroll highlighted when active`() {
        val actions = novelQuickActions(translationEnabled = true)
        assertEquals(
            listOf("TTS", "BOOKMARK", "MARKINGS", "AUTO_SCROLL", "TRANSLATE"),
            actions.map { it.id },
        )
        actions.dropLast(1).forEach { assertNull(it.toggled) }
        assertEquals(true, actions.last().toggled)
        assertEquals(false, novelQuickActions(translationEnabled = false).last().toggled)

        val activeAutoScroll = novelQuickActions(translationEnabled = false, autoScrollActive = true)
        assertEquals(true, activeAutoScroll.first { it.id == "AUTO_SCROLL" }.toggled)
    }
}
