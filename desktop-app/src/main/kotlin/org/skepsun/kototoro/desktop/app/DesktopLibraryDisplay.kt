package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.list.ui.compose.GridSizeRange

/**
 * Android's display options for the shelves: the shared [ListMode] and the grid size. The desktop draws the two lists
 * and the two poster grids; the movie grids need horizontal artwork the shelves do not load, so they are not offered.
 */
/** The shelves that keep their own display options; the history shelf follows the favourites one until it is set. */
enum class DesktopDisplayPage(val modeKey: String, val sizeKey: String) {
    LIBRARY("library_display_mode", "library_grid_size"),
    HISTORY("history_display_mode", "history_grid_size"),
}

data class DesktopLibraryDisplay(val listMode: ListMode = ListMode.COMPACT_GRID, val gridSize: Int = 100) {
    fun toPreferences(page: DesktopDisplayPage = DesktopDisplayPage.LIBRARY): Map<String, SourcePreferenceValue?> = mapOf(
        page.modeKey to SourcePreferenceValue.Text(listMode.name),
        page.sizeKey to SourcePreferenceValue.Integer(gridSize),
    )

    companion object {
        const val KEY_LIST_MODE = "library_display_mode"
        const val KEY_GRID_SIZE = "library_grid_size"
        /**
         * The earlier two-value key. Its GRID was drawn with the title over the poster, which is Android's compact
         * grid, so it is read as that; Android's GRID (title below) only comes from the new key.
         */
        const val KEY_LEGACY_LIST_MODE = "library_list_mode"
        val MODES = listOf(ListMode.LIST, ListMode.DETAILED_LIST, ListMode.GRID, ListMode.COMPACT_GRID)

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopLibraryDisplay(
            listMode = MODES.firstOrNull { it.name == (values[KEY_LIST_MODE] as? SourcePreferenceValue.Text)?.value }
                ?: when ((values[KEY_LEGACY_LIST_MODE] as? SourcePreferenceValue.Text)?.value) {
                    "LIST" -> ListMode.LIST
                    else -> ListMode.COMPACT_GRID
                },
            gridSize = ((values[KEY_GRID_SIZE] as? SourcePreferenceValue.Integer)?.value ?: 100).coerceIn(GridSizeRange),
        )

        /** The history shelf's own options, each falling back to the favourites shelf's until it is changed. */
        fun history(values: Map<String, SourcePreferenceValue>): DesktopLibraryDisplay {
            val library = from(values)
            val page = DesktopDisplayPage.HISTORY
            return DesktopLibraryDisplay(
                listMode = MODES.firstOrNull { it.name == (values[page.modeKey] as? SourcePreferenceValue.Text)?.value }
                    ?: library.listMode,
                gridSize = ((values[page.sizeKey] as? SourcePreferenceValue.Integer)?.value ?: library.gridSize)
                    .coerceIn(GridSizeRange),
            )
        }
    }
}
