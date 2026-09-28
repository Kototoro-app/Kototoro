package org.skepsun.kototoro.reader.novel.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NovelReaderQuickActionsTest {

    @Test
    fun `four actions with translation as the only toggle`() {
        val actions = novelQuickActions(translationEnabled = true)
        assertEquals(listOf("TTS", "BOOKMARK", "MARKINGS", "TRANSLATE"), actions.map { it.id })
        actions.dropLast(1).forEach { assertNull(it.toggled) }
        assertEquals(true, actions.last().toggled)
        assertEquals(false, novelQuickActions(translationEnabled = false).last().toggled)
    }
}
