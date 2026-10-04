package org.skepsun.kototoro.favourites.data

import androidx.room.ColumnInfo

/**
 * One active `(mangaId, categoryId)` membership from `favourites`. Pinned/created/updated
 * belong to the membership, so category slices keep their own attributes without
 * duplicating card fields. The query exposes manga_id as entity_id for the read model.
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
 * One manga↔tag relation of the favourites library: ids only. A heavily-tagged library has
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
 * Downloaded favourite id: a favourite manga present in the local download index.
 */
data class FavouriteDownloadedRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
)

/**
 * Per-manga override (title / cover) from the `preferences` table.
 */
data class FavouriteLegacyOverrideRow(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "cover_override") val coverOverride: String?,
)
