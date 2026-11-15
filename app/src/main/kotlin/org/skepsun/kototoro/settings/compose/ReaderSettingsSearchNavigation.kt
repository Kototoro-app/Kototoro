package org.skepsun.kototoro.settings.compose

/** The reader settings pager uses Common, Manga, Novel in this order. */
internal fun readerSettingsPageForKey(key: String): Int = when {
    key == "replace_rules" || key.startsWith("novel_") -> 2
    key.startsWith("eink_") || key in setOf(
        "reader_tap_actions", "reader_volume_buttons", "reader_navigation_inverted", "reader_control_labels",
    ) -> 0
    else -> 1
}
