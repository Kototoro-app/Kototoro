package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.source.SourcePreferenceValue

/**
 * Android's `ListMode` narrowed to the two layouts the Windows shelves render. Android also has detailed lists and
 * the video poster grids; both need artwork/description the desktop shelves do not load yet.
 */
enum class DesktopListMode { GRID, LIST }

/** Android keeps the list mode in its app settings; Windows stores it under its own key in `desktop_library`. */
data class DesktopLibraryDisplay(val listMode: DesktopListMode = DesktopListMode.GRID) {
    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_LIST_MODE to SourcePreferenceValue.Text(listMode.name),
    )

    companion object {
        const val KEY_LIST_MODE = "library_list_mode"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopLibraryDisplay(
            listMode = DesktopListMode.entries.firstOrNull {
                it.name == (values[KEY_LIST_MODE] as? SourcePreferenceValue.Text)?.value
            } ?: DesktopListMode.GRID,
        )
    }
}
