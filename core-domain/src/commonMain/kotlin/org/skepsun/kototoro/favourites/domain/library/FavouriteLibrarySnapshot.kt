package org.skepsun.kototoro.favourites.domain.library

import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType

/**
 * The single immutable card row of the favourites library snapshot.
 *
 * Field budget is enforced by `FavouriteCardFieldContractTest` (Phase 0) and the row is
 * assembled by [FavouriteLibrarySnapshotStore] from the narrow read flows — Room
 * entities, `WorkAggregate` and the entity graph never cross this boundary.
 *
 * Semantics kept from the characterization suites:
 * - one row per favourite manga: [entityId], [displayMangaId] and the single entry of
 *   [localMangaIds] are that manga's id;
 * - [sourceGroupFlags] / [sourceOriginFlags] are pre-normalized filter dimensions
 *   (bit sets), not display strings — localized source titles resolve at UI mapping
 *   time so a language change does not rebuild the snapshot.
 */
data class FavouriteCardRow(
    val entityId: Long,
    val displayMangaId: Long?,
    val localMangaIds: Set<Long>,
    val title: String,
    val altTitle: String?,
    val coverUrl: String?,
    val author: String?,
    val sourceName: String,
    val sourceGroupFlags: Int,
    val sourceOriginFlags: Int,
    val contentType: ContentType?,
    val publicationState: ContentState?,
    val isNsfw: Boolean,
    val rating: Float,
    /** Persisted tracking status name; UI enums are resolved by the platform. */
    val readingStatus: String,
    val newChapters: Int,
    val lastChapterDate: Long,
    val progressPercent: Float?,
    val progressTotalChapters: Int?,
    val lastReadAt: Long?,
    val tagIds: Set<Long>,
    val displayTags: List<FavouriteCardTag>,
    val isDownloaded: Boolean,
    val overrideTitle: String?,
    val overrideCoverUrl: String?,
    // display metadata authority: id of the tracking service whose cached site item feeds
    // the card ([metadataTrackingTitle] / [metadataTrackingCoverUrl]) plus the badge it
    // draws. Null when the entity has no tracking metadata authority.
    val metadataTrackingService: Int?,
    val metadataTrackingTitle: String?,
    val metadataTrackingCoverUrl: String?,
    // representative membership attributes (All-slice ordering context)
    val isPinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Display title after the entity-level manual override. */
    val resolvedTitle: String
        get() = overrideTitle?.takeIf { it.isNotBlank() } ?: title

    /** Display cover after the entity-level manual override. */
    val resolvedCoverUrl: String?
        get() = overrideCoverUrl?.takeIf { it.isNotBlank() } ?: coverUrl

    /** True when a tracking site is the display metadata authority of this entity. */
    val hasMetadataTracking: Boolean
        get() = metadataTrackingService != null
}

/** Limited tag payload for the detailed-list card chips and the compact subtitle. */
data class FavouriteCardTag(
    val tagId: Long,
    val title: String,
)

/**
 * Tag of the library facets: the card payload plus the tag identity the quick-filter
 * chips need. The platform's tag mapping round-trips to exactly [tagId] — the invariant the
 * in-memory tag filter matches on (`"${'$'}{key}_${'$'}{source}".longHashCode()`).
 */
data class FavouriteFacetTag(
    val tagId: Long,
    val title: String,
    val key: String,
    val source: String,
)

/**
 * One active `(entityId, categoryId)` membership. Pinned / created / updated belong to
 * the membership (the `work_favourites` primary key is the pair), so category slices
 * carry their own ordering attributes without copying card fields.
 */
data class FavouriteMembership(
    val entityId: Long,
    val categoryId: Long,
    val isPinned: Boolean,
    val sortKey: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Quick-filter metadata derived from the snapshot facets (available tags / sources with
 * the entity counts of the current library) — the popup and the quick filter chips no
 longer query the database per category.
 */
data class FavouriteQuickFilterMetadata(
    val tags: List<FavouriteFacetTag>,
    val tagEntityCounts: Map<Long, Int>,
    val sources: List<String>,
    val sourceEntityCounts: Map<String, Int>,
) {
    companion object {
        val Empty = FavouriteQuickFilterMetadata(
            tags = emptyList(),
            tagEntityCounts = emptyMap(),
            sources = emptyList(),
            sourceEntityCounts = emptyMap(),
        )
    }
}

/**
 * The complete, self-consistent favourites library snapshot.
 *
 * Invariants (asserted by `FavouriteLibrarySnapshotStoreTest`):
 * - every entity appears in [rowsByEntityId] at most once and [allEntityIds] contains
 *   exactly those keys (sorted ascending, stable);
 * - every `entityId` referenced by any membership / facet-derived field exists in
 *   [rowsByEntityId];
 * - [membershipsByCategory] only references existing rows;
 * - an empty snapshot means "library is empty", never "not loaded yet" — the store
 *   only emits after the flows delivered a consistent read.
 */
data class FavouriteLibrarySnapshot(
    val rowsByEntityId: Map<Long, FavouriteCardRow>,
    val allEntityIds: List<Long>,
    val membershipsByCategory: Map<Long, List<FavouriteMembership>>,
    val quickFilterMetadata: FavouriteQuickFilterMetadata,
) {
    companion object {
        val Empty = FavouriteLibrarySnapshot(
            rowsByEntityId = emptyMap(),
            allEntityIds = emptyList(),
            membershipsByCategory = emptyMap(),
            quickFilterMetadata = FavouriteQuickFilterMetadata.Empty,
        )
    }
}
