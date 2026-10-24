package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourceListing

/** Android's `UnifiedEnabledFilter`: which side of the per-source switch a row has to be on to be listed. */
enum class DesktopSourceEnabledFilter { ALL, ENABLED, DISABLED }

/**
 * The source-switch dialog's visible rows: Android's search (title or source name, case-insensitive) and its enabled
 * filter, combined with AND and kept in the host's own order. The bulk enable/disable buttons act on exactly this
 * list, which is what Android's "enable/disable all in the current filter" confirmation counts.
 */
fun filterSourceSwitchEntries(
    sources: List<SourceListing>,
    query: String,
    filter: DesktopSourceEnabledFilter,
    disabled: Set<String>,
): List<SourceListing> {
    val needle = query.trim()
    if (needle.isEmpty() && filter == DesktopSourceEnabledFilter.ALL) return sources
    return sources.filter { listing ->
        val matchesQuery = needle.isEmpty() ||
            listing.displayName.contains(needle, ignoreCase = true) ||
            listing.source.name.contains(needle, ignoreCase = true)
        if (!matchesQuery) return@filter false
        when (filter) {
            DesktopSourceEnabledFilter.ALL -> true
            DesktopSourceEnabledFilter.ENABLED -> listing.source.name !in disabled
            DesktopSourceEnabledFilter.DISABLED -> listing.source.name in disabled
        }
    }
}
