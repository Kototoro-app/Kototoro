package org.skepsun.kototoro.favourites.data

import androidx.room.ColumnInfo

/**
 * One active `(entityId, categoryId)` membership. Pinned/created/updated belong to the
 * membership, not the entity (`work_favourites` is keyed by the pair), so category
 * slices keep their own attributes without duplicating card fields.
 */
data class FavouriteMembershipRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "pinned") val isPinned: Boolean,
    @ColumnInfo(name = "sort_key") val sortKey: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Batch projection facet for one entity: how many local projections are bound and from
 * which sources. Feeds MULTI_PROJECTION / BROKEN_PROJECTION filters, the projection
 * count badge and the per-source quick filter — without loading any `Content`.
 */
data class FavouriteProjectionFacetRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "content_type") val contentType: String?,
)

/**
 * Tag facet for one entity: the tag identity used for filtering plus the title shown
 * by the detailed list card. Identity is `tag_id`; titles are only carried for display.
 */
data class FavouriteTagFacetRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
    @ColumnInfo(name = "tag_title") val tagTitle: String,
    // tag identity: ListFilterOption.Tag is built from (key, source) and its id must
    // equal tagId, which is what the in-memory filter matches on
    @ColumnInfo(name = "tag_key") val tagKey: String,
    @ColumnInfo(name = "tag_source") val tagSource: String,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
)

/**
 * Downloaded favourite id: the mapping of the local download index onto favourite
 * entity ids. `manga_id` is any bound projection present in `local_index`.
 */
data class FavouriteDownloadedRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
)

/**
 * Legacy per-manga override (title / cover) from the `preferences` table, used as the
 * fallback when the entity preferences carry no override.
 */
data class FavouriteLegacyOverrideRow(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "cover_override") val coverOverride: String?,
)
