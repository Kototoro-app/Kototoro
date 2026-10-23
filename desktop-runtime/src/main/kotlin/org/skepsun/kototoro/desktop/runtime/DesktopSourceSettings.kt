package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.source.SourcePreferenceValue

/**
 * Android's `SourcesSortOrder` reduced to the orders Windows can honor: it has no manual reorder editor, so the
 * third option sorts by the source language instead of a stored hand-picked sequence.
 */
enum class DesktopSourceSort { DEFAULT, TITLE, LANGUAGE }

/**
 * Android's `AdultContentFilterTarget` reduced to the surfaces Windows actually renders. A target hides 18+ works
 * from that shelf; the browse target hides 18+ *sources* in the rail, which is what the rail can decide.
 */
enum class DesktopNsfwTarget { SOURCES, FAVOURITES, HISTORY }

/** Windows counterparts of Android's source settings, stored under their own keys in `desktop_background`. */
data class DesktopSourceSettings(
    val sort: DesktopSourceSort = DesktopSourceSort.DEFAULT,
    val grid: Boolean = true,
    val groupByLanguage: Boolean = false,
    val nsfwTargets: Set<DesktopNsfwTarget> = emptySet(),
) {
    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_SORT to SourcePreferenceValue.Text(sort.name),
        KEY_GRID to SourcePreferenceValue.Toggle(grid),
        KEY_GROUP_LANGUAGE to SourcePreferenceValue.Toggle(groupByLanguage),
        KEY_NSFW_TARGETS to SourcePreferenceValue.TextSet(nsfwTargets.mapTo(LinkedHashSet()) { it.name }),
    )

    /** The rail order: Android's alphabetical and language orders; the default keeps the installation order. */
    fun sorted(sources: List<SourceListing>): List<SourceListing> = when (sort) {
        DesktopSourceSort.DEFAULT -> sources
        DesktopSourceSort.TITLE -> sources.sortedBy { it.displayName.lowercase() }
        DesktopSourceSort.LANGUAGE -> sources.sortedWith(
            compareBy({ it.source.locale.ifBlank { "zz" } }, { it.displayName.lowercase() }),
        )
    }

    /** Android groups the rail by language; a blank locale collects under one "multilingual" header. */
    fun groups(sources: List<SourceListing>): List<Pair<String, List<SourceListing>>> =
        if (!groupByLanguage) listOf("" to sources)
        else sources.groupBy { it.source.locale.ifBlank { "" } }.toList()

    fun hidesNsfw(target: DesktopNsfwTarget): Boolean = target in nsfwTargets

    companion object {
        const val KEY_SORT = "sources_sort"
        const val KEY_GRID = "sources_grid"
        const val KEY_GROUP_LANGUAGE = "sources_group_language"
        const val KEY_NSFW_TARGETS = "sources_nsfw_targets"

        fun from(values: Map<String, SourcePreferenceValue>) = DesktopSourceSettings(
            sort = DesktopSourceSort.entries.firstOrNull {
                it.name == (values[KEY_SORT] as? SourcePreferenceValue.Text)?.value
            } ?: DesktopSourceSort.DEFAULT,
            grid = (values[KEY_GRID] as? SourcePreferenceValue.Toggle)?.value ?: true,
            groupByLanguage = (values[KEY_GROUP_LANGUAGE] as? SourcePreferenceValue.Toggle)?.value ?: false,
            nsfwTargets = (values[KEY_NSFW_TARGETS] as? SourcePreferenceValue.TextSet)?.values
                ?.mapNotNullTo(LinkedHashSet()) { name -> DesktopNsfwTarget.entries.firstOrNull { it.name == name } }
                .orEmpty(),
        )
    }
}

/** Android marks adult sources by content type; a rail entry carries the same signal. */
fun SourceListing.isNsfwSource(): Boolean = source.contentType.startsWith("HENTAI_")
