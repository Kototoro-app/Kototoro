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
    }

    @Test
    fun `novel page is gone so its former targets land on the common page`() {
        // 小说阅读页已随阅读器删除，分页器只剩“通用 / 漫画”两页；
        // 残留的搜索目标必须落到仍然存在的页上，否则搜索跳转会越界。
        listOf("replace_rules", "novel_theme", "novel_font_size")
            .forEach { assertEquals(0, readerSettingsPageForKey(it), it) }
    }
}
