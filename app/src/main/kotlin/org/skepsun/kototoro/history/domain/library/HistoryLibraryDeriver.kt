package org.skepsun.kototoro.history.domain.library

import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.list.domain.ReadingProgress

/**
 * Pure in-memory derivation of the visible history list from a
 * [HistorySnapshot] (history-updates-feed komikku-alignment plan, Phase H3).
 *
 * Everything that used to restart the paging chain — the sort order, the quick
 * filters (SQL-level ones re-played in memory), the space binding filter, the
 * type tab, the source preset, the source tags, NSFW exclusion and the tag
 * blacklist — is a re-derivation here; the database is never re-queried.
 *
 * Ordering reproduces the legacy SQL `ORDER BY` of `WorkHistoryDao.pagingSource`
 * (10 orders, tie-break `wh.entity_id ASC`); the row set is already
 * entity-unique, so the paging-era adjacent fold degenerates to the identity
 * re-labelling the store already applied (uiId encodes entity + content type).
 */
object HistoryLibraryDeriver {

    /**
     * @param filters quick-filter options (SQL-level filters re-played in memory)
     * @param excludedNsfw NSFW exclusion (the history setting or the global one)
     * @param space the space scope: allowed types / classified types / allowed
     * source names, `null` when not bound to a space (then [groupTab] pushes
     * its type filter down here instead of into SQL)
     */
    data class Input(
        val snapshot: HistorySnapshot,
        val order: ListSortOrder = ListSortOrder.LAST_READ,
        val filters: Set<ListFilterOption> = emptySet(),
        val excludedNsfw: Boolean = false,
        val tagBlacklist: GlobalTagBlacklist = GlobalTagBlacklist(emptyList()),
        val groupTab: BrowseGroupTab = BrowseGroupTab.All,
        val sourceTags: Set<SourceTag> = emptySet(),
        val presetSources: Set<String>? = null,
        val space: SpaceScope? = null,
    )

    data class SpaceScope(
        val allowedTypes: Set<ContentType> = emptySet(),
        val classifiedTypes: Set<ContentType> = emptySet(),
        val allowedSources: Set<String>? = null,
    )

    data class Derived(
        val visibleRows: List<HistoryCardEntry>,
        val hasActiveFilters: Boolean,
    )

    fun derive(input: Input): Derived {
        val visible = input.snapshot.rows.asSequence()
            .filter { row -> row.isVisible(input) }
            .sortedWith(orderComparator(input.order))
            .toList()
        return Derived(
            visibleRows = visible,
            hasActiveFilters = input.filters.isNotEmpty() ||
                input.groupTab != BrowseGroupTab.All ||
                input.sourceTags.isNotEmpty() ||
                input.excludedNsfw ||
                input.presetSources != null ||
                input.space != null,
        )
    }

    // ---------------------------------------------------------------- visibility

    private fun HistoryCardEntry.isVisible(input: Input): Boolean {
        if (!matchesSpace(input.space)) return false
        if (!matchesTab(input)) return false
        if (input.presetSources != null && sourceName !in input.presetSources) return false
        if (input.sourceTags.isNotEmpty() && input.sourceTags.none(::matchesSourceTag)) return false
        if (input.excludedNsfw && isNsfw) return false
        if (tags.isNotEmpty() && tags.any { input.tagBlacklist.containsTagTitle(it.title) }) return false
        return matchesQuickFilters(input.filters)
    }

    /**
     * Memory equivalent of the SQL space filter: the entity must have a local
     * binding whose (manga || entity) content type is allowed, no local binding
     * with a classified type outside the allowed set, and (when constrained)
     * at least one binding from an allowed source.
     */
    private fun HistoryCardEntry.matchesSpace(space: SpaceScope?): Boolean {
        if (space == null) return true
        val allowed = space.allowedTypes
        if (allowed.isNotEmpty()) {
            val hasAllowed = bindings.any { binding -> binding.contentType in allowed }
            if (!hasAllowed) return false
            val classified = space.classifiedTypes
            if (classified.isNotEmpty()) {
                val hasDisallowed = bindings.any { binding ->
                    binding.contentType in classified && binding.contentType !in allowed
                }
                if (hasDisallowed) return false
            }
        }
        val allowedSources = space.allowedSources ?: return true
        return bindings.any { binding -> binding.source in allowedSources }
    }

    /**
     * The type chip: inside a space the SQL already constrained the types, so
     * the chip only narrows by the authoritative entity type; outside a space
     * the row matches when the source group OR the persisted content type
     * matches (the persisted type is authoritative — the source-group heuristic
     * mislabels novels and videos from anonymous/legacy sources).
     */
    private fun HistoryCardEntry.matchesTab(input: Input): Boolean {
        val tab = input.groupTab
        if (tab == BrowseGroupTab.All) return true
        val typeMatches = tab.matchesContentType(contentType ?: ContentType.MANGA)
        if (input.space != null) {
            return typeMatches
        }
        val contentMatched = ContentGroup.entries.any { group ->
            contentGroup(group) && tab.matchesContentGroup(group)
        }
        if (!contentMatched && !typeMatches) return false
        return OriginGroup.entries.any { origin ->
            originGroup(origin) && tab.matchesOriginGroup(origin)
        }
    }

    /**
     * Memory re-play of the SQL-level filters (`HistoryRepository
     * .matchesHistoryFilters`): every option must hold.
     */
    private fun HistoryCardEntry.matchesQuickFilters(filters: Set<ListFilterOption>): Boolean {
        if (filters.isEmpty()) return true
        return filters.all { option ->
            when (option) {
                ListFilterOption.Downloaded -> isDownloaded
                ListFilterOption.Macro.COMPLETED -> ReadingProgress.isCompleted(percent)
                ListFilterOption.Macro.NEW_CHAPTERS -> newChapters > 0
                ListFilterOption.Macro.MULTI_PROJECTION -> true
                ListFilterOption.Macro.BROKEN_PROJECTION -> true
                ListFilterOption.Macro.FAVORITE -> categoryIds.isNotEmpty()
                ListFilterOption.Macro.NSFW -> isNsfw
                ListFilterOption.NOT_FAVORITE -> categoryIds.isEmpty()
                is ListFilterOption.Inverted -> when (option.option) {
                    ListFilterOption.Macro.NSFW -> !isNsfw
                    ListFilterOption.Macro.FAVORITE -> categoryIds.isEmpty()
                    else -> true
                }
                is ListFilterOption.Tag -> tags.any { tag ->
                    tag.title == option.tag.title && tag.key == option.tag.key
                }
                is ListFilterOption.Source -> sourceName == option.mangaSource.name
                is ListFilterOption.PublicationState -> publicationState == option.state
                is ListFilterOption.ReadingStatus -> true
                is ListFilterOption.Favorite -> option.category.id in categoryIds
                // The paging path never loaded chapters, so the legacy branch
                // filter matched nothing; Branch chips are not offered here.
                is ListFilterOption.Branch -> false
            }
        }
    }

    // ---------------------------------------------------------------- ordering

    private fun orderComparator(order: ListSortOrder): Comparator<HistoryCardEntry> {
        val base = when (order) {
            ListSortOrder.LAST_READ -> compareByDescending<HistoryCardEntry> { it.updatedAt }
            ListSortOrder.LONG_AGO_READ -> compareBy<HistoryCardEntry> { it.updatedAt }
            ListSortOrder.NEWEST -> compareByDescending<HistoryCardEntry> { it.createdAt }
            ListSortOrder.OLDEST -> compareBy<HistoryCardEntry> { it.createdAt }
            ListSortOrder.PROGRESS -> compareByDescending<HistoryCardEntry> { it.percent }
            ListSortOrder.UNREAD -> compareBy<HistoryCardEntry> { it.percent }
            ListSortOrder.NEW_CHAPTERS -> compareByDescending<HistoryCardEntry> { it.newChapters }
                .thenByDescending { it.lastChapterDate ?: 0L }
            ListSortOrder.UPDATED -> compareByDescending<HistoryCardEntry> { it.lastChapterDate ?: 0L }
            ListSortOrder.ALPHABETIC -> compareBy<HistoryCardEntry> { it.title.lowercase() }
            ListSortOrder.ALPHABETIC_REVERSE -> compareByDescending<HistoryCardEntry> { it.title.lowercase() }
            else -> compareByDescending<HistoryCardEntry> { it.updatedAt }
        }
        // the SQL tie-break: wh.entity_id ASC (stable for the whole list)
        return base.thenBy { it.entityId }
    }
}

private fun HistoryCardEntry.contentGroup(group: ContentGroup): Boolean =
    sourceGroupFlags and (1 shl group.ordinal) != 0

private fun HistoryCardEntry.originGroup(origin: OriginGroup): Boolean =
    sourceOriginFlags and (1 shl origin.ordinal) != 0

private fun originOfFlags(flags: Int): OriginGroup =
    OriginGroup.entries.first { flags and (1 shl it.ordinal) != 0 }

private fun contentOfFlags(flags: Int): ContentGroup =
    ContentGroup.entries.first { flags and (1 shl it.ordinal) != 0 }

private fun HistoryCardEntry.matchesSourceTag(tag: SourceTag): Boolean {
    val contentMatched = ContentGroup.entries.any { group ->
        contentGroup(group) && tag.matches(group, originOfFlags(sourceOriginFlags))
    }
    if (contentMatched) return true
    return OriginGroup.entries.any { origin ->
        originGroup(origin) && tag.matches(contentOfFlags(sourceGroupFlags), origin)
    }
}
