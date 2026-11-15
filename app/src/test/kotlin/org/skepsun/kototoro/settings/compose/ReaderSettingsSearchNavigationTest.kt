package org.skepsun.kototoro.settings.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderSettingsSearchNavigationTest {
    @Test
    fun `search goes to the tab that owns the preference`() {
        listOf("reader_tap_actions", "reader_volume_buttons", "reader_navigation_inverted", "eink_refresh")
            .forEach { assertEquals(0, readerSettingsPageForKey(it), it) }
        listOf("reader_threads", "reader_mode", "avif_animation_memory_limit_mb", "reader_bar")
            .forEach { assertEquals(1, readerSettingsPageForKey(it), it) }
        listOf("replace_rules", "novel_theme", "novel_font_size")
            .forEach { assertEquals(2, readerSettingsPageForKey(it), it) }
    }
}
