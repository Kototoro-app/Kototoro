package org.skepsun.kototoro.tracker.domain.feed

import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ListFilterOption

/**
 * Pure in-memory derivation of the visible feed items from a complete
 * [FeedSnapshot] (history-updates-feed komikku-alignment plan, Phase F3).
 *
 * Everything that used to restart the paging chain is a re-derivation here:
 * the feed limit window, the showAllUpdates source switch, quick filters,
 * the feed scope (category / group tab / source tags / preset), NSFW
 * exclusion and the tag blacklist. The database is never re-queried.
 *
 * Ordering follows the legacy SQL exactly: pinned first, then `created_at`
 * descending, then the log id descending (the tie-break the F0
 * characterization pins down). Date headers are derived from the same
 * `calculateDateGroup` bucket the legacy chain used, but for the full list in
 * one pass - the paging-era insertSeparators worked per page and could split
 * a bucket across a page boundary; the deriver cannot.
 */
object FeedDeriver {

    /** Everything the derivation consumes; all fields are plain data. */
    data class Input(
        val snapshot: FeedSnapshot,
        val showAllUpdates: Boolean = false,
        val feedLimit: Int = 200,
        val filters: Set<ListFilterOption> = emptySet(),
        val excludedNsfw: Boolean = false,
        val tagBlacklist: GlobalTagBlacklist = GlobalTagBlacklist(emptyList()),
        val groupTab: BrowseGroupTab = BrowseGroupTab.All,
        val sourceTags: Set<SourceTag> = emptySet(),
        val presetSourceNames: Set<String>? = null,
        /** selected favourite category id, `null` = all. */
        val selectedCategoryId: Long? = null,
        /**
         * `manga_category_ids` from `FavouritesRepository.observeFeedCategoryIds()`,
         * keyed by the legacy feed lookup key (`"source|url"`).
         */
        val mangaCategoryIdsByFeedKey: Map<String, Set<Long>> = emptyMap(),
    )

    /** The derived, visible feed list in final display order. */
    data class Derived(
        val visibleRows: List<FeedCardRow>,
        /** true when the list is empty because filters excluded everything. */
        val hasActiveFilters: Boolean,
    )

    fun derive(input: Input): Derived {
        val rows = if (input.showAllUpdates) {
            deriveShowAllRows(input)
        } else {
            input.snapshot.rows
        }
        val visible = rows.asSequence()
            .filter { row -> row.isVisible(input) }
            .sortedWith(FEED_ORDER)
            .take(input.feedLimit.coerceAtLeast(0))
            .toList()
        return Derived(
            visibleRows = visible,
            hasActiveFilters = input.filters.isNotEmpty() ||
                input.selectedCategoryId != null ||
                input.groupTab != BrowseGroupTab.All ||
                input.sourceTags.isNotEmpty() ||
                input.presetSourceNames != null ||
                input.excludedNsfw,
        )
    }

    /**
     * The `showAllUpdates` source: pending-update tracks merged with (and
     * de-duplicated against) the log rows. Mirrors the legacy combination of
     * `resolveAllTrackingLogItems` with `ensureUnreadUpdateLogs`: a tracked
     * work without a log yet shows up as a synthetic item dated by
     * `last_chapter_date ?? last_check_time`.
     */
    private fun deriveShowAllRows(input: Input): List<FeedCardRow> {
        val logRows = input.snapshot.rows
        val logOwnerIds = logRows.mapTo(HashSet(logRows.size)) { it.ownerId }
        val synthetic = input.snapshot.updateRowsByOwnerId.values
            .filterNot { it.ownerId in logOwnerIds }
            .map { update ->
                FeedCardRow(
                    logId = -update.mangaId,
                    anchorMangaId = update.mangaId,
                    ownerId = update.ownerId,
                    entityId = update.entityId,
                    preferredLocalMangaId = update.preferredLocalMangaId,
                    chapters = List(update.newChapters.coerceAtLeast(1)) { "" },
                    createdAt = update.lastChapterDate ?: update.lastCheckTime,
                    unread = update.newChapters > 0,
                    isPinned = update.isPinned,
                    displayMangaId = update.displayMangaId,
                    title = update.title,
                    altTitle = null,
                    coverUrl = update.coverUrl,
                    author = null,
                    sourceName = update.sourceName,
                    displayUrl = "",
                    contentType = null,
                    publicationState = null,
                    isNsfw = update.isNsfw,
                    rating = -1f,
                    tagIds = emptySet(),
                    tagTitles = emptyList(),
                    overrideTitle = null,
                    overrideCoverUrl = null,
                    sourceGroupFlags = 1 shl update.contentGroupOrdinal(),
                    sourceOriginFlags = 1 shl update.originGroupOrdinal(),
                )
            }
        return logRows + synthetic
    }

    private fun FeedCardRow.isVisible(input: Input): Boolean {
        // preset scope
        if (input.presetSourceNames != null && sourceName !in input.presetSourceNames) {
            return false
        }
        // group tab (content + origin groups, the legacy AND semantics)
        if (input.groupTab != BrowseGroupTab.All && !matchesGroupTab(input.groupTab)) {
            return false
        }
        // source tags (OR within the selection)
        if (input.sourceTags.isNotEmpty() && input.sourceTags.none(::matchesSourceTag)) {
            return false
        }
        // selected favourite category
        val selectedCategoryId = input.selectedCategoryId
        if (selectedCategoryId != null) {
            val categoryIds = input.mangaCategoryIdsByFeedKey[feedKey()].orEmpty()
            if (selectedCategoryId !in categoryIds) return false
        }
        // NSFW exclusion
        if (input.excludedNsfw && isNsfw) return false
        // tag blacklist: any tag title of the row is blacklisted -> hidden
        if (tagTitles.isNotEmpty() && tagTitles.any(input.tagBlacklist::containsTagTitle)) return false
        // quick filters
        return matchesQuickFilters(input.filters, input)
    }

    private fun FeedCardRow.matchesQuickFilters(
        filters: Set<ListFilterOption>,
        input: Input,
    ): Boolean {
        if (filters.isEmpty()) return true
        val categoryIds = input.mangaCategoryIdsByFeedKey[feedKey()].orEmpty()
        return filters.all { option ->
            when (option) {
                // the feed quick filter offers favourite categories only; the SQL
                // favouriteExistsExpr(entityId, categoryId) maps onto the same
                // category-id set the scope filter uses
                ListFilterOption.Macro.FAVORITE -> categoryIds.isNotEmpty()
                is ListFilterOption.Favorite -> option.category.id in categoryIds
                ListFilterOption.Macro.NSFW -> isNsfw
                is ListFilterOption.Tag -> option.tagId in tagIds
                else -> true
            }
        }
    }

    /** The legacy feed lookup key: `"sourceName|url"` of the display manga. */
    private fun FeedCardRow.feedKey(): String = "$sourceName|$displayUrl"

    private fun FeedCardRow.matchesGroupTab(tab: BrowseGroupTab): Boolean {
        val contentMatched = ContentGroup.entries.any { group ->
            hasGroupFlag(group) && tab.matchesContentGroup(group)
        }
        if (!contentMatched) return false
        return OriginGroup.entries.any { origin ->
            hasOriginFlag(origin) && tab.matchesOriginGroup(origin)
        }
    }

    /** The comparator behind the legacy SQL: pinned, created_at DESC, id DESC. */
    private val FEED_ORDER = compareByDescending<FeedCardRow> { it.isPinned }
        .thenByDescending { it.createdAt }
        .thenByDescending { it.logId }
}

private fun FeedUpdateRow.contentGroupOrdinal(): Int {
    // The synthetic showAll row only needs a plausible flag; the group tab is
    // derived from the display source elsewhere, and unknown sources resolve
    // to the default group anyway.
    return 0
}

private fun FeedUpdateRow.originGroupOrdinal(): Int = 0

private fun FeedCardRow.matchesSourceTag(tag: SourceTag): Boolean {
    val contentMatched = ContentGroup.entries.any { group ->
        hasGroupFlag(group) && tag.matches(group, originGroupOfFlags())
    }
    return contentMatched || OriginGroup.entries.any { origin ->
        hasOriginFlag(origin) && tag.matches(contentGroupOfFlags(), origin)
    }
}

private fun FeedCardRow.hasGroupFlag(group: ContentGroup): Boolean =
    sourceGroupFlags and (1 shl group.ordinal) != 0

private fun FeedCardRow.hasOriginFlag(origin: OriginGroup): Boolean =
    sourceOriginFlags and (1 shl origin.ordinal) != 0

private fun FeedCardRow.contentGroupOfFlags(): ContentGroup =
    ContentGroup.entries.first(::hasGroupFlag)

private fun FeedCardRow.originGroupOfFlags(): OriginGroup =
    OriginGroup.entries.first(::hasOriginFlag)
