package org.skepsun.kototoro.list.ui

/**
 * A page whose display options (layout, grid size, extra card info) are stored on their own.
 *
 * Until a page's option is changed it follows the general value, so existing setups look the same.
 */
enum class ListDisplaySection(val id: String) {
    FAVORITES("favorites"),
    HISTORY("history"),
    SUGGESTIONS("suggestions"),
    UPDATED("updated"),
    BROWSE("browse"),
    SEARCH("search"),
    LOCAL("local"),
    FEED("feed"),
}
