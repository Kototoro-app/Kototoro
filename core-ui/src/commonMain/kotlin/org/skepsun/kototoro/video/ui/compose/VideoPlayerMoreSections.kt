package org.skepsun.kototoro.video.ui.compose

/**
 * Where an entry belongs in the player's overflow menu.
 *
 * The player has exactly one overflow menu on both hosts and it grows quietly: reload, cast, screenshot, download and a
 * raw diagnostic dump all end up as neighbours in the same grid. This type exists so the technical entries sort to the
 * *end* instead of sitting between the things a viewer uses while watching.
 */
enum class VideoPlayerMoreSection {
    /** Reached while watching: quality, markers, subtitle/audio choice, reload. */
    FREQUENT,

    /** Reached between sessions: screenshots, notes, downloads, cast, picture-in-picture. */
    TOOLS,

    /** Read when something is wrong: codec, renderer, counters. Never needed to watch anything. */
    DIAGNOSTICS,
}

/**
 * Stable-partitions [items] by [sectionOf]: sections appear in declaration order, and items inside a section keep the
 * order the host gave them. A menu that is already ordered comes back unchanged, so hosting this rule cannot reorder
 * anything a platform deliberately placed.
 */
fun <T> orderVideoPlayerMoreItems(
    items: List<T>,
    sectionOf: (T) -> VideoPlayerMoreSection,
): List<T> {
    if (items.size < 2) return items
    return items.withIndex()
        .sortedWith(compareBy({ sectionOf(it.value).ordinal }, { it.index }))
        .map { it.value }
}
