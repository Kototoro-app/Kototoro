package org.skepsun.kototoro.favourites.ui.container

import androidx.compose.runtime.Immutable
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.domain.library.FavouriteCardRow
import org.skepsun.kototoro.favourites.domain.library.FavouriteLibrarySnapshot
import org.skepsun.kototoro.favourites.domain.library.FavouriteMembership
import org.skepsun.kototoro.favourites.domain.library.FavouriteQuickFilterMetadata
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceId

/**
 * Screen-level state of the favourites library owned by [FavouritesContainerViewModel]
 * (favourites-komikku-alignment plan, section 6.1).
 *
 * `isInitialized` separates "not loaded yet" from an actually empty library; the
 * category slices only carry entity ids — the card rows live once in [rowsByEntityId].
 */
@Immutable
data class FavouriteLibraryUiState(
    val isInitialized: Boolean = false,
    val rowsByEntityId: Map<Long, FavouriteCardRow> = emptyMap(),
    val visibleIdsByCategory: Map<Long, List<Long>> = emptyMap(),
    val pinnedIdsByCategory: Map<Long, Set<Long>> = emptyMap(),
    /**
     * Memberships of the (unfiltered) snapshot per category plus every favourite entity:
     * what the quick-filter chips count, so applying a chip never hides its siblings.
     * Direct snapshot references — the maps are shared, never copied.
     */
    val membershipsByCategory: Map<Long, List<FavouriteMembership>> = emptyMap(),
    val allEntityIds: List<Long> = emptyList(),
    val categoryCounts: Map<Long, Int> = emptyMap(),
    val totalCount: Int = 0,
    val quickFilterMetadata: FavouriteQuickFilterMetadata = FavouriteQuickFilterMetadata.Empty,
) {
    val isEmpty: Boolean
        get() = isInitialized && rowsByEntityId.isEmpty()
}

/**
 * The user intent side of the derivation — everything except the snapshot itself.
 * Assembled from GlobalFavoritesState / settings / space binding / category orders by
 * the container; pure data so the assembly below is unit-testable.
 */
data class FavouriteLibraryParams(
    val groupTab: BrowseGroupTab = BrowseGroupTab.All,
    val sourceTags: Set<SourceTag> = emptySet(),
    val preset: org.skepsun.kototoro.explore.data.SourcePreset? = null,
    val spaceId: SpaceId? = null,
    val filters: Set<ListFilterOption> = emptySet(),
    val excludeNsfw: Boolean = false,
    val blacklist: Collection<String> = emptyList(),
    val ordersByCategory: Map<Long, ListSortOrder> = emptyMap(),
    val defaultOrder: ListSortOrder = ListSortOrder.NEWEST,
)

/**
 * Pure state assembly (favourites-komikku-alignment Phase 4): derives the UI state
 * from a snapshot and the current user intent. No I/O — the container only wires
 * flows into this function, which keeps the whole pipeline testable as data-in /
 * data-out (see `FavouriteLibraryUiStateTest`).
 */
internal fun buildFavouriteLibraryUiState(
    snapshot: FavouriteLibrarySnapshot,
    params: FavouriteLibraryParams,
    spaceContentPolicy: SpaceContentPolicy,
): FavouriteLibraryUiState {
    val spaceId = params.spaceId
    val derived = org.skepsun.kototoro.favourites.domain.library.deriveFavouriteLibraryState(
        snapshot = snapshot,
        input = org.skepsun.kototoro.favourites.domain.library.FavouriteLibraryDerivationInput(
            groupTab = params.groupTab,
            sourceTags = params.sourceTags,
            sourcePresetNames = params.preset?.sources?.takeIf { it.isNotEmpty() },
            allowedContentTypes = spaceId?.let(spaceContentPolicy::allowedTypes)?.takeIf { it.isNotEmpty() },
            allowedSourceNames = spaceId?.let(spaceContentPolicy::allowedSourceNames),
            excludeNsfw = params.excludeNsfw,
            filters = params.filters,
            globalTagBlacklistTags = params.blacklist,
            ordersByCategory = params.ordersByCategory,
            defaultOrder = params.defaultOrder,
        ),
    )
    return FavouriteLibraryUiState(
        isInitialized = true,
        rowsByEntityId = snapshot.rowsByEntityId,
        visibleIdsByCategory = derived.visibleIdsByCategory,
        pinnedIdsByCategory = derived.pinnedIdsByCategory,
        membershipsByCategory = snapshot.membershipsByCategory,
        allEntityIds = snapshot.allEntityIds,
        categoryCounts = derived.visibleIdsByCategory.mapValues { it.value.size },
        totalCount = derived.allVisibleIds.size,
        quickFilterMetadata = snapshot.quickFilterMetadata,
    )
}
