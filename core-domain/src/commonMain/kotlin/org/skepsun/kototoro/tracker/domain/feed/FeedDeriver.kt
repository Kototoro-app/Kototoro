package org.skepsun.kototoro.tracker.domain.feed

import org.skepsun.kototoro.list.domain.LibraryTextQuery
import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.model.TagBlacklist
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.core.db.ListFilterCriteria
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.domain.dailyUpdateGroups
import org.skepsun.kototoro.tracker.domain.encodeUpdateChapters

/**
 * Pure in-memory derivation of the visible feed items from a complete
 * [FeedSnapshot] (history-updates-feed komikku-alignment plan, Phase F3).
 *
 * Everything that used to restart the paging chain is a re-derivation here:
 * the feed limit window, quick filters,
 * the feed scope (category / group tab / source tags / preset), NSFW
 * exclusion and the tag blacklist. The database is never re-queried.
 *
 * Ordering uses the discovery timestamp `created_at`
 * descending, then the log id descending (the tie-break the F0
 * characterization pins down). Date headers are derived from the same
 * `calculateDateGroup` bucket the legacy chain used, but for the full list in
 * one pass - the paging-era insertSeparators worked per page and could split
 * a bucket across a page boundary; the deriver cannot.
 */
object FeedDeriver {

    /** Snapshot and value filters, plus a platform-resolved tag matcher. */
    data class Input(
        val snapshot: FeedSnapshot,
        val feedLimit: Int = 200,
        val filters: Set<ListFilterCriteria> = emptySet(),
        val excludedNsfw: Boolean = false,
        val tagBlacklist: TagBlacklist = TagBlacklist.Empty,
        val groupTab: BrowseGroupTab = BrowseGroupTab.All,
        val sourceTags: Set<SourceTag> = emptySet(),
        val presetSourceNames: Set<String>? = null,
        /** Free text typed in the page-scoped search (see [LibraryTextQuery]). */
        val query: String = "",
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
        val rows = input.snapshot.rows
        val byLogId = rows.associateBy { it.logId }
        val grouped = dailyUpdateGroups(rows.filter { it.logId > 0 }.map { row ->
            TrackLogEntity(row.logId, row.ownerId, row.chapters.joinToString("\n"), row.createdAt,
                row.unread, row.chapterMetadata)
        }).map { group ->
            val latest = group.logs.maxWith(compareBy<TrackLogEntity> { it.createdAt }.thenBy { it.id })
            byLogId.getValue(latest.id).copy(
                chapters = group.names, createdAt = group.discoveredAt, unread = group.isUnseen,
                logIds = group.logs.map { it.id }, pendingCount = group.pendingCount,
                chapterMetadata = if (group.chapters.isEmpty()) "" else encodeUpdateChapters(group.chapters),
                updateCount = group.count,
                updateChapters = group.chapters,
                key = group.key,
            )
        } + rows.filter { it.logId <= 0 }
        val textQuery = LibraryTextQuery(input.query)
        val visible = grouped.asSequence()
            .filter { row -> row.isVisible(input) && row.matchesText(textQuery) }
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
                input.excludedNsfw ||
                !textQuery.isEmpty,
        )
    }

    private fun FeedCardRow.matchesText(query: LibraryTextQuery): Boolean =
        query.isEmpty || query.matches(
            sequenceOf(overrideTitle, title, altTitle, author) + tagTitles.asSequence() + chapters.asSequence(),
        )

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
            val categoryIds = resolveCategoryIds(input)
            if (selectedCategoryId !in categoryIds) return false
        }
        // NSFW exclusion
        if (input.excludedNsfw && isNsfw) return false
        // tag blacklist: any tag title of the row is blacklisted -> hidden
        if (tagTitles.isNotEmpty() && tagTitles.any(input.tagBlacklist::containsTagTitle)) return false
        // quick filters
        return matchesQuickFilters(input.filters, input)
    }

    private fun FeedCardRow.resolveCategoryIds(input: Input): Set<Long> {
        val set = LinkedHashSet<Long>()
        entityId?.let { id -> input.mangaCategoryIdsByFeedKey["entity:$id"]?.let(set::addAll) }
        displayMangaId?.let { id -> input.mangaCategoryIdsByFeedKey["manga:$id"]?.let(set::addAll) }
        anchorMangaId.takeIf { it != 0L }?.let { id -> input.mangaCategoryIdsByFeedKey["manga:$id"]?.let(set::addAll) }
        input.mangaCategoryIdsByFeedKey[feedKey()]?.let(set::addAll)
        return set
    }

    private fun FeedCardRow.matchesQuickFilters(
        filters: Set<ListFilterCriteria>,
        input: Input,
    ): Boolean {
        if (filters.isEmpty()) return true
        val categoryIds = resolveCategoryIds(input)
        return filters.all { option ->
            when (option) {
                // the feed quick filter offers favourite categories only; the SQL
                // favouriteExistsExpr(entityId, categoryId) maps onto the same
                // category-id set the scope filter uses
                ListFilterCriteria.Macro.FAVORITE -> categoryIds.isNotEmpty()
                is ListFilterCriteria.Favorite -> option.categoryId in categoryIds
                ListFilterCriteria.Macro.NSFW -> isNsfw
                is ListFilterCriteria.Tag -> option.tagId in tagIds
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

    /** Timeline order is discovery time DESC, id DESC, independent of a work's pinned state. */
    private val FEED_ORDER = compareByDescending<FeedCardRow> { it.createdAt }
        .thenByDescending { it.logId }
}

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
