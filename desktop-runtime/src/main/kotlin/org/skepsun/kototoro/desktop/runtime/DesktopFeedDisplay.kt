package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.source.SourcePreferenceValue

/**
 * The Android feed screen's display settings the Windows timeline can honour.
 *
 * `feed_header` is the "show updated" item of Android's feed menu (`R.menu.opt_feed`): it hides the block of tracked
 * works with new chapters above the timeline. Windows keeps the same key so a shared backup keeps the same look, and
 * stores it in `desktop_library` next to the list mode.
 */
data class DesktopFeedDisplay(val showUpdated: Boolean = true) {
    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_SHOW_UPDATED to SourcePreferenceValue.Toggle(showUpdated),
    )

    companion object {
        /** Android's key for the "show updated" menu item. */
        const val KEY_SHOW_UPDATED = "feed_header"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopFeedDisplay(
            showUpdated = (values[KEY_SHOW_UPDATED] as? SourcePreferenceValue.Toggle)?.value ?: true,
        )
    }
}
