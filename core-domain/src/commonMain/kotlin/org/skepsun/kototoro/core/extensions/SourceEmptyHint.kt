package org.skepsun.kototoro.core.extensions

/**
 * What the internal source picker (Android's Explore, Windows' Browse) has to tell the user when it
 * would otherwise render nothing.
 *
 * The three states are deliberately distinct because they owe the user different sentences and a
 * different next step: a page that is still fetching must stay a skeleton, a page with no installable
 * source must offer the extension manager, and a page whose own search/filters removed everything
 * must offer to reset those filters. Collapsing any two of them into "blank page" is what made the
 * Explore tab look broken when no source was installed.
 *
 * Shared by Android and Windows: the rule is about *why* nothing is on screen, so it must not drift
 * between hosts.
 */
enum class SourceEmptyHintKind {
    /** Sources are on screen (or still arriving): render nothing extra. */
    NONE,

    /** No source is available at all, so nothing can ever load here until one is added. */
    NO_SOURCES,

    /** Sources exist, but the selected tab / tag / preset / space scope / search hides every one. */
    HIDDEN_BY_FILTERS,
}

/**
 * @param totalSourceCount enabled sources before the page's own tab/tag/preset/search filtering.
 * @param visibleSourceCount sources that survived that filtering.
 * @param isLoading true while the source list is still being resolved.
 */
fun sourceEmptyHintKind(
    totalSourceCount: Int,
    visibleSourceCount: Int,
    isLoading: Boolean,
): SourceEmptyHintKind = when {
    isLoading -> SourceEmptyHintKind.NONE
    totalSourceCount > 0 && visibleSourceCount > 0 -> SourceEmptyHintKind.NONE
    totalSourceCount > 0 -> SourceEmptyHintKind.HIDDEN_BY_FILTERS
    else -> SourceEmptyHintKind.NO_SOURCES
}
