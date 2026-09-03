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
 * One entity↔tag relation of the favourites library: ids only. A heavily-tagged library has
 * over 100k of these, so the tag strings deliberately do not ride along — they come from
 * [FavouriteTagDictionaryRow] once per tag instead of once per relation.
 */
data class FavouriteTagIdRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

/**
 * Tag identity and display title, once per tag. Identity is `tag_id`;
 * ListFilterOption.Tag is built from (key, source) and its id must equal tagId, which is
 * what the in-memory filter matches on.
 */
data class FavouriteTagDictionaryRow(
    @ColumnInfo(name = "tag_id") val tagId: Long,
    @ColumnInfo(name = "tag_title") val tagTitle: String,
    @ColumnInfo(name = "tag_key") val tagKey: String,
    @ColumnInfo(name = "tag_source") val tagSource: String,
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
