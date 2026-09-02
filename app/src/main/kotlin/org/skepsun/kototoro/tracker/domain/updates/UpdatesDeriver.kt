package org.skepsun.kototoro.tracker.domain.updates

import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ListFilterOption

/**
 * Pure in-memory derivation of the visible updates list from an
 * [UpdatesSnapshot] (history-updates-feed komikku-alignment plan, Phase U3).
 *
 * Everything that used to restart the paging chain (quick filters, group tab,
 * source tags, NSFW exclusion, tag blacklist, list mode) is a re-derivation
 * here; the database is never re-queried.
 *
 * Ordering reproduces the legacy final order: the paging SQL ordered by
 * `pinned DESC, last_chapter_date DESC, entity_id ASC, manga_id ASC`, then
 * `buildTrackingAggregates` re-sorted the entity aggregates by
 * `lastChapterDate DESC, newChapters DESC` (stable — ties keep the SQL
 * order, which is why the SQL order still matters as the tie-break source).
 */
object UpdatesDeriver {

    data class Input(
        val snapshot: UpdatesSnapshot,
        val filters: Set<ListFilterOption> = emptySet(),
        val excludedNsfw: Boolean = false,
        val tagBlacklist: GlobalTagBlacklist = GlobalTagBlacklist(emptyList()),
        val groupTab: BrowseGroupTab = BrowseGroupTab.All,
        val sourceTags: Set<SourceTag> = emptySet(),
    )

    data class Derived(
        val visibleGroups: List<UpdateGroupRow>,
        val hasActiveFilters: Boolean,
    )

    fun derive(input: Input): Derived {
        val visible = input.snapshot.groups.asSequence()
            .filter { group -> group.isVisible(input) }
            .sortedWith(UPDATES_ORDER)
            .toList()
        return Derived(
            visibleGroups = visible,
            hasActiveFilters = input.filters.isNotEmpty() ||
                input.groupTab != BrowseGroupTab.All ||
                input.sourceTags.isNotEmpty() ||
                input.excludedNsfw,
        )
    }

    private fun UpdateGroupRow.isVisible(input: Input): Boolean {
        if (input.groupTab != BrowseGroupTab.All && !matchesGroupTab(input.groupTab)) {
            return false
        }
        if (input.sourceTags.isNotEmpty() && input.sourceTags.none(::matchesSourceTag)) {
            return false
        }
        if (input.excludedNsfw && isNsfw) return false
        if (tagTitles.isNotEmpty() && tagTitles.any(input.tagBlacklist::containsTagTitle)) return false
        return matchesQuickFilters(input.filters)
    }

    private fun UpdateGroupRow.matchesQuickFilters(filters: Set<ListFilterOption>): Boolean {
        if (filters.isEmpty()) return true
        return filters.all { option ->
            when (option) {
                ListFilterOption.Macro.NSFW -> isNsfw
                is ListFilterOption.Tag -> option.tagId in tagIds
                else -> true
            }
        }
    }

    private fun UpdateGroupRow.matchesGroupTab(tab: BrowseGroupTab): Boolean {
        val contentMatched = ContentGroup.entries.any { group ->
            contentGroup(group) && tab.matchesContentGroup(group)
        }
        if (!contentMatched) return false
        return OriginGroup.entries.any { origin ->
            originGroup(origin) && tab.matchesOriginGroup(origin)
        }
    }

    /**
     * The final visible order: entity `lastChapterDate` DESC, entity new
     * chapters DESC, stable in snapshot order (which mirrors the legacy SQL
     * order: pinned first, then last_chapter_date, entity id, manga id).
     */
    private val UPDATES_ORDER = compareByDescending<UpdateGroupRow> { it.lastChapterDate ?: 0L }
        .thenByDescending { it.totalNewChapters }
}

private fun UpdateGroupRow.matchesSourceTag(tag: SourceTag): Boolean {
        val contentMatched = ContentGroup.entries.any { group ->
            contentGroup(group) && tag.matches(group, originOfFlags(sourceOriginFlags))
        }
        return contentMatched || OriginGroup.entries.any { origin ->
            originGroup(origin) && tag.matches(contentOfFlags(sourceGroupFlags), origin)
        }
    }

private fun originOfFlags(flags: Int): OriginGroup =
    OriginGroup.entries.first { flags and (1 shl it.ordinal) != 0 }

private fun contentOfFlags(flags: Int): ContentGroup =
    ContentGroup.entries.first { flags and (1 shl it.ordinal) != 0 }

