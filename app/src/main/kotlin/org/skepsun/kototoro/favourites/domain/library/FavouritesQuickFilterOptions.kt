package org.skepsun.kototoro.favourites.domain.library

import androidx.compose.runtime.Immutable
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

/** How many tag chips one slice shows (the legacy per-category query limit). */
private const val TagChipLimit = 3

/**
 * Everything the quick-filter chips of one favourites slice are built from. Pure data:
 * the snapshot carries the facet dictionary, the slice decides what is counted (plan
 * section 6.3 — chips never touch the database).
 */
@Immutable
data class FavouritesQuickFilterInput(
    val categoryId: Long,
    val membershipsByCategory: Map<Long, List<FavouriteMembership>>,
    val allEntityIds: List<Long>,
    val rows: Map<Long, FavouriteCardRow>,
    val metadata: FavouriteQuickFilterMetadata,
    val excludeNsfw: Boolean,
    val isTrackerEnabled: Boolean,
)

/**
 * Quick-filter options of one category, in the order the chips are drawn.
 *
 * The static part follows the appearance/tracker settings; the tag and source chips count
 * the entities of *this* slice — the membership list, not the filtered slice, so applying
 * a chip never hides the sibling chips. Tags keep the legacy limit of three, ordered by
 * entity count then title ignoring case; sources are ordered by entity count then name.
 * Both count the field the corresponding filter matches on (the row's tag set and the
 * display projection's source), so a chip never filters everything away.
 */
internal fun buildFavouritesFilterOptions(input: FavouritesQuickFilterInput): List<ListFilterOption> {
    val entityIds: List<Long> =
        if (input.categoryId == FavouriteLibraryAllCategoryId) {
            input.allEntityIds
        } else {
            input.membershipsByCategory[input.categoryId]?.map(FavouriteMembership::entityId).orEmpty()
        }
    val tagCounts = HashMap<Long, Int>(64)
    val sourceCounts = HashMap<String, Int>(16)
    for (entityId in entityIds) {
        val row = input.rows[entityId] ?: continue
        for (tagId in row.tagIds) {
            tagCounts.merge(tagId, 1, Int::plus)
        }
        if (row.sourceName.isNotBlank()) {
            sourceCounts.merge(row.sourceName, 1, Int::plus)
        }
    }
    val tagsById = HashMap<Long, FavouriteFacetTag>(input.metadata.tags.size)
    for (tag in input.metadata.tags) {
        tagsById[tag.tagId] = tag
    }
    return buildList {
        add(ListFilterOption.Downloaded)
        if (!input.excludeNsfw) {
            add(ListFilterOption.SFW)
            add(ListFilterOption.Macro.NSFW)
        }
        if (input.isTrackerEnabled) {
            add(ListFilterOption.Macro.NEW_CHAPTERS)
        }
        add(ListFilterOption.Macro.MULTI_PROJECTION)
        add(ListFilterOption.Macro.BROKEN_PROJECTION)
        ScrobblingStatus.entries.mapTo(this) { ListFilterOption.ReadingStatus(it) }
        ContentState.entries.mapTo(this) { ListFilterOption.PublicationState(it) }
        tagCounts.entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, Int>> { it.value }
                    .thenBy { tagsById[it.key]?.title?.lowercase().orEmpty() },
            )
            .take(TagChipLimit)
            .mapNotNullTo(this) { entry ->
                tagsById[entry.key]?.let { ListFilterOption.Tag(it.toContentTag()) }
            }
        sourceCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .mapNotNullTo(this) { entry ->
                val source = ContentSource(entry.key)
                if (input.excludeNsfw && source.isNsfw()) {
                    null
                } else {
                    ListFilterOption.Source(source)
                }
            }
    }
}
