package org.skepsun.kototoro.settings.compose

/**
 * The reader settings pager now has only Common and Manga pages; the Novel page was removed
 * with the novel reader, so its former search targets fall back to the Common page.
 */
internal fun readerSettingsPageForKey(key: String): Int = when {
    key == "replace_rules" || key.startsWith("novel_") -> 0
    key.startsWith("eink_") || key in setOf(
        "reader_tap_actions", "reader_volume_buttons", "reader_navigation_inverted", "reader_control_labels",
    ) -> 0
    else -> 1
}
