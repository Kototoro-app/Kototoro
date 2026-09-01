package org.skepsun.kototoro.favourites.domain.library

import org.skepsun.kototoro.core.jsonsource.ContentGroup
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.Locale

/**
 * Pure in-memory derivation inputs — everything the deriver needs except the snapshot
 * itself. All fields are value types; no DAO, no context, no singletons.
 */
data class FavouriteLibraryDerivationInput(
    val groupTab: BrowseGroupTab = BrowseGroupTab.All,
    val sourceTags: Set<SourceTag> = emptySet(),
    val sourcePresetNames: Set<String>? = null,
    val allowedContentTypes: Set<ContentType>? = null,
    val allowedSourceNames: Set<String>? = null,
    val excludeNsfw: Boolean = false,
    val filters: Set<ListFilterOption> = emptySet(),
    val globalTagBlacklistTags: Collection<String> = emptyList(),
    val ordersByCategory: Map<Long, ListSortOrder> = emptyMap(),
    val defaultOrder: ListSortOrder = ListSortOrder.NEWEST,
)

/**
 * The result of the derivation: entity id lists per category (and the "all" slice),
 * guaranteed duplicate-free and referencing only snapshot rows.
 *
 * [pinnedIdsByCategory] carries the *membership* pinned flag per slice (pinned is a
 * property of the `(entityId, categoryId)` pair, so a work pinned in one category is
 * not pinned in another) — the card mapping needs it for the pin badge while the
 * slices themselves stay id-only.
 */
data class FavouriteLibraryDerivedState(
    val visibleIdsByCategory: Map<Long, List<Long>>,
    val allVisibleIds: List<Long>,
    val pinnedIdsByCategory: Map<Long, Set<Long>>,
) {
    companion object {
        val Empty = FavouriteLibraryDerivedState(
            visibleIdsByCategory = emptyMap(),
            allVisibleIds = emptyList(),
            pinnedIdsByCategory = emptyMap(),
        )
    }
}

/** The category id of the synthetic "All favourites" tab. */
val FavouriteLibraryAllCategoryId: Long = -1L

/**
 * Ordering context of one slice: card fields come from the snapshot rows while
 * pinned / created / updated come from the slice's own memberships — the "All" slice
 * uses the representative membership the base row already carries.
 */
internal class FavouriteOrderingContext private constructor(
    private val rows: Map<Long, FavouriteCardRow>,
    private val pinnedByEntity: Map<Long, Boolean>,
    private val createdAtByEntity: Map<Long, Long>,
    private val updatedAtByEntity: Map<Long, Long>,
) {
    companion object {
        fun forAllSlice(snapshot: FavouriteLibrarySnapshot): FavouriteOrderingContext {
            return FavouriteOrderingContext(
                rows = snapshot.rowsByEntityId,
                pinnedByEntity = snapshot.rowsByEntityId.mapValues { it.value.isPinned },
                createdAtByEntity = snapshot.rowsByEntityId.mapValues { it.value.createdAt },
                updatedAtByEntity = snapshot.rowsByEntityId.mapValues { it.value.updatedAt },
            )
        }

        fun forCategorySlice(
            snapshot: FavouriteLibrarySnapshot,
            memberships: List<FavouriteMembership>,
        ): FavouriteOrderingContext {
            val pinned = HashMap<Long, Boolean>(memberships.size)
            val created = HashMap<Long, Long>(memberships.size)
            val updated = HashMap<Long, Long>(memberships.size)
            for (membership in memberships) {
                pinned[membership.entityId] = membership.isPinned
                created[membership.entityId] = membership.createdAt
                updated[membership.entityId] = membership.updatedAt
            }
            return FavouriteOrderingContext(snapshot.rowsByEntityId, pinned, created, updated)
        }
    }

    fun pinned(entityId: Long): Boolean = pinnedByEntity[entityId] == true
    fun createdAt(entityId: Long): Long = createdAtByEntity[entityId] ?: 0L
    fun updatedAt(entityId: Long): Long = updatedAtByEntity[entityId] ?: 0L
    fun row(entityId: Long): FavouriteCardRow? = rows[entityId]

    /** Membership-pinned subset of [ids], in the order they are given. */
    fun pinnedIds(ids: Collection<Long>): Set<Long> {
        val result = LinkedHashSet<Long>(ids.size)
        for (id in ids) {
            if (pinned(id)) result.add(id)
        }
        return result
    }
}

/**
 * Stage 1 — visibility: space / preset / group tab / source tags / NSFW exclusion /
 * global tag blacklist.
 */
internal fun applyVisibility(
    snapshot: FavouriteLibrarySnapshot,
    input: FavouriteLibraryDerivationInput,
): Set<Long> {
    if (snapshot.rowsByEntityId.isEmpty()) {
        return emptySet()
    }
    val blacklist = if (input.globalTagBlacklistTags.isEmpty()) {
        null
    } else {
        GlobalTagBlacklist(input.globalTagBlacklistTags)
    }
    val groupFlag = input.groupTab.contentGroupFlagOrNull()
    val originFlags = input.sourceTags.mapNotNullTo(ArrayList<Long>(input.sourceTags.size)) { tag ->
        tag.originFlagOrNull()
    }

    val visible = HashSet<Long>(snapshot.rowsByEntityId.size)
    outer@ for (entityId in snapshot.allEntityIds) {
        val row = snapshot.rowsByEntityId.getValue(entityId)

        // Space content types apply to the row's resolved content type.
        if (input.allowedContentTypes != null && row.contentType !in input.allowedContentTypes) {
            continue@outer
        }
        // Space source names apply to the bound projection sources.
        if (input.allowedSourceNames != null && row.projectionSourceNames.none { it in input.allowedSourceNames }) {
            continue@outer
        }
        // Source presets apply to the displayed projection source (legacy semantics).
        if (input.sourcePresetNames != null && row.sourceName !in input.sourcePresetNames) {
            continue@outer
        }
        // Content-type chips: the persisted entity type is authoritative, the
        // source-group heuristic is the fallback (legacy `typeMatches || sourceGroupMatches`).
        if (groupFlag != null) {
            val typeMatches = row.contentType?.let(input.groupTab::matchesContentType) == true
            val sourceGroupMatches = row.sourceGroupFlags and groupFlag != 0
            if (!typeMatches && !sourceGroupMatches) continue@outer
        }
        if (originFlags.isNotEmpty()) {
            var matches = false
            for (flag in originFlags) {
                if (row.sourceOriginFlags.toLong() and flag != 0L) {
                    matches = true
                    break
                }
            }
            if (!matches) continue@outer
        }
        if (input.excludeNsfw && row.isNsfw) {
            continue@outer
        }
        if (blacklist != null && row.matchesTagBlacklist(blacklist)) {
            continue@outer
        }
        visible.add(entityId)
    }
    return visible
}

/**
 * Stage 2 — quick filters. OR within a filter group, AND across groups; semantics
 * mirror the characterization suites (see class docs of [FavouriteCardRow]).
 */
internal fun applyQuickFilters(
    snapshot: FavouriteLibrarySnapshot,
    visible: Set<Long>,
    input: FavouriteLibraryDerivationInput,
): Set<Long> {
    if (input.filters.isEmpty() || visible.isEmpty()) {
        return visible
    }
    val publicationStates = input.filters.filterIsInstance<ListFilterOption.PublicationState>()
        .mapTo(HashSet()) { it.state }
    val readingStatuses = input.filters.filterIsInstance<ListFilterOption.ReadingStatus>()
        .mapTo(HashSet()) { it.status }
    val tagIds = input.filters.filterIsInstance<ListFilterOption.Tag>()
        .mapTo(HashSet()) { it.tagId }
    val sourceNames = input.filters.filterIsInstance<ListFilterOption.Source>()
        .mapTo(HashSet()) { it.mangaSource.name }
    val nsfwMode: Int = when {
        ListFilterOption.Macro.NSFW in input.filters -> 1
        input.filters.any { it is ListFilterOption.Inverted && it.option == ListFilterOption.Macro.NSFW } -> 0
        else -> -1
    }
    val requireDownloaded = ListFilterOption.Downloaded in input.filters
    val requireNewChapters = ListFilterOption.Macro.NEW_CHAPTERS in input.filters
    val requireCompleted = ListFilterOption.Macro.COMPLETED in input.filters
    val requireMultiProjection = ListFilterOption.Macro.MULTI_PROJECTION in input.filters
    val requireBrokenProjection = ListFilterOption.Macro.BROKEN_PROJECTION in input.filters

    val result = HashSet<Long>(visible.size)
    outer@ for (entityId in visible) {
        val row = snapshot.rowsByEntityId.getValue(entityId)
        if (requireDownloaded && !row.isDownloaded) continue@outer
        if (nsfwMode == 1 && !row.isNsfw) continue@outer
        if (nsfwMode == 0 && row.isNsfw) continue@outer
        if (requireNewChapters && row.newChapters <= 0) continue@outer
        if (requireCompleted && !(row.progressPercent != null && row.progressPercent >= COMPLETED_THRESHOLD)) continue@outer
        if (requireMultiProjection && row.projectionCount <= 1) continue@outer
        if (requireBrokenProjection && !row.hasBrokenProjection) continue@outer
        if (publicationStates.isNotEmpty() && row.publicationState !in publicationStates) continue@outer
        if (readingStatuses.isNotEmpty() && row.readingStatus !in readingStatuses) continue@outer
        if (tagIds.isNotEmpty() && row.tagIds.none { it in tagIds }) continue@outer
        if (sourceNames.isNotEmpty() && row.sourceName !in sourceNames &&
            row.projectionSourceNames.none { it in sourceNames }
        ) {
            continue@outer
        }
        result.add(entityId)
    }
    return result
}

/**
 * Stage 3 — grouping and sorting per category. Produces only entity id lists; every
 * slice orders by its own membership attributes with `entity_id` as the final
 * tie-breaker, so the output order is total and deterministic.
 */
internal fun groupAndSort(
    snapshot: FavouriteLibrarySnapshot,
    visible: Set<Long>,
    input: FavouriteLibraryDerivationInput,
): FavouriteLibraryDerivedState {
    if (visible.isEmpty()) {
        return FavouriteLibraryDerivedState.Empty
    }

    val byCategory = HashMap<Long, MutableList<Long>>(snapshot.membershipsByCategory.size + 1)
    val pinnedByCategory = HashMap<Long, Set<Long>>(snapshot.membershipsByCategory.size + 1)

    val allContext = FavouriteOrderingContext.forAllSlice(snapshot)
    val allIds = visible.sortedWith(input.defaultOrder.comparator(allContext))
    byCategory[FavouriteLibraryAllCategoryId] = allIds.toMutableList()
    pinnedByCategory[FavouriteLibraryAllCategoryId] = allContext.pinnedIds(allIds)

    for ((categoryId, memberships) in snapshot.membershipsByCategory) {
        val order = input.ordersByCategory[categoryId] ?: input.defaultOrder
        val context = FavouriteOrderingContext.forCategorySlice(snapshot, memberships)
        val ids = memberships.asSequence()
            .map(FavouriteMembership::entityId)
            .filter { it in visible }
            .distinct()
            .sortedWith(order.comparator(context))
            .toMutableList()
        byCategory[categoryId] = ids
        pinnedByCategory[categoryId] = context.pinnedIds(ids)
    }

    return FavouriteLibraryDerivedState(
        visibleIdsByCategory = byCategory,
        allVisibleIds = byCategory.getValue(FavouriteLibraryAllCategoryId),
        pinnedIdsByCategory = pinnedByCategory,
    )
}

/** Convenience entry: visibility -> quick filters -> group & sort. */
fun deriveFavouriteLibraryState(
    snapshot: FavouriteLibrarySnapshot,
    input: FavouriteLibraryDerivationInput,
): FavouriteLibraryDerivedState {
    val visible = applyVisibility(snapshot, input)
    val filtered = applyQuickFilters(snapshot, visible, input)
    return groupAndSort(snapshot, filtered, input)
}

// ---------------------------------------------------------------------- helpers

private const val COMPLETED_THRESHOLD = 0.999f

private fun FavouriteCardRow.matchesTagBlacklist(blacklist: GlobalTagBlacklist): Boolean {
    for (tag in displayTags) {
        if (blacklist.containsTagTitle(tag.title)) {
            return true
        }
    }
    return false
}

private fun BrowseGroupTab.contentGroupFlagOrNull(): Int? = when (this) {
    BrowseGroupTab.All -> null
    BrowseGroupTab.Content -> groupMask(ContentGroup.MANGA) or groupMask(ContentGroup.HENTAI_MANGA)
    BrowseGroupTab.Novel -> groupMask(ContentGroup.NOVEL) or groupMask(ContentGroup.HENTAI_NOVEL)
    BrowseGroupTab.Video -> groupMask(ContentGroup.VIDEO) or groupMask(ContentGroup.HENTAI_VIDEO)
}

private fun groupMask(group: ContentGroup): Int = 1 shl group.ordinal

private fun SourceTag.originFlagOrNull(): Long? = when (this) {
    SourceTag.BUILTIN -> OriginGroup.NATIVE.ordinal
    SourceTag.MIHON -> OriginGroup.MIHON.ordinal
    SourceTag.ANIYOMI -> OriginGroup.ANIYOMI.ordinal
    SourceTag.LEGADO -> OriginGroup.LEGADO_JSON.ordinal
    SourceTag.TVBOX -> OriginGroup.TVBOX_JSON.ordinal
    SourceTag.IREADER -> OriginGroup.IREADER.ordinal
    SourceTag.CLOUDSTREAM -> OriginGroup.CLOUDSTREAM.ordinal
    SourceTag.LNREADER -> OriginGroup.LNREADER_JSON.ordinal
    SourceTag.TSUNDOKU -> OriginGroup.TSUNDOKU.ordinal
    SourceTag.PINNED -> null // matches every origin (see SourceTag.matches)
}?.let { flag -> 1L shl flag }

private fun ListSortOrder.comparator(context: FavouriteOrderingContext): Comparator<Long> {
    // pinned first in every order (characterization: `ORDER BY selected.pinned DESC`)
    val byPinned = compareByDescending<Long> { context.pinned(it) }
    return byPinned.then(
        when (this) {
            ListSortOrder.RATING -> compareByDescending { context.row(it)?.rating ?: -1f }
            ListSortOrder.NEWEST -> compareByDescending { context.createdAt(it) }
            ListSortOrder.OLDEST -> compareBy { context.createdAt(it) }
            ListSortOrder.PROGRESS -> compareByDescending { context.row(it)?.progressPercent ?: 0f }
            ListSortOrder.UNREAD -> compareBy { context.row(it)?.progressPercent ?: 0f }
            ListSortOrder.LAST_READ -> compareByDescending { context.row(it)?.lastReadAt ?: 0L }
            ListSortOrder.LONG_AGO_READ -> compareBy { context.row(it)?.lastReadAt ?: 0L }
            ListSortOrder.NEW_CHAPTERS -> compareByDescending<Long> { context.row(it)?.newChapters ?: 0 }
                .thenByDescending { context.row(it)?.lastChapterDate ?: 0L }
            ListSortOrder.UPDATED -> compareByDescending { context.row(it)?.lastChapterDate ?: 0L }
            ListSortOrder.ALPHABETIC -> compareBy(sortTitleOf(context))
            ListSortOrder.ALPHABETIC_REVERSE -> compareBy(sortTitleOf(context)).reversed()
            else -> compareByDescending { context.updatedAt(it) }
        },
    ).thenBy { it }
}

/**
 * Alphabetic sort key: case-insensitive (the legacy `COLLATE NOCASE`), with broken
 * rows sorting last instead of first — the legacy SQL let NULL titles lead the
 * ascending order, which the snapshot replaces with a deterministic empty-title
 * placement (documented in the Phase 0 baseline).
 */
private fun sortTitleOf(context: FavouriteOrderingContext): (Long) -> String = { entityId ->
    context.row(entityId)?.resolvedTitle.orEmpty().lowercase(Locale.ROOT)
}
