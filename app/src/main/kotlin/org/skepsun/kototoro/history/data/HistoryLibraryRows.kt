package org.skepsun.kototoro.history.data

import androidx.room.ColumnInfo

/**
 * One row per active work_history entity — the history page base row
 * (history-updates-feed komikku-alignment plan, Phase H1).
 *
 * Narrow on purpose: history progress + identity + the display projection +
 * the per-entity tracking summary + favourite/pinned membership + the metadata
 * authority, all resolved once in SQL. No filters, no order — those are derived
 * in memory (Phase H3).
 */
data class HistoryCardRow(
    // work_history columns
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "anchor_manga_id") val anchorMangaId: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "percent") val percent: Float,
    @ColumnInfo(name = "chapters") val chaptersCount: Int,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    // resolved identity
    @ColumnInfo(name = "preferred_local_manga_id") val preferredLocalMangaId: Long?,
    // authoritative content type: anchor manga type first, entity type second
    @ColumnInfo(name = "anchor_content_type") val anchorContentType: String?,
    @ColumnInfo(name = "entity_content_type") val entityContentType: String?,
    // per-entity tracking summary (NULL when untracked)
    @ColumnInfo(name = "new_chapters") val newChapters: Int?,
    @ColumnInfo(name = "last_chapter_date") val lastChapterDate: Long?,
    // membership
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    // display metadata authority (the cached tracking-site item behind a
    // 'tracking' metadata selection, same join as the favourites row)
    @ColumnInfo(name = "metadata_tracking_service") val metadataTrackingService: Int?,
    @ColumnInfo(name = "metadata_tracking_title") val metadataTrackingTitle: String?,
    @ColumnInfo(name = "metadata_tracking_cover_url") val metadataTrackingCoverUrl: String?,
    // display projection columns (COALESCE(preferred, anchor))
    @ColumnInfo(name = "display_manga_id") val displayMangaId: Long?,
    @ColumnInfo(name = "display_title") val displayTitle: String?,
    @ColumnInfo(name = "display_alt_title") val displayAltTitle: String?,
    @ColumnInfo(name = "display_cover_url") val displayCoverUrl: String?,
    @ColumnInfo(name = "display_large_cover_url") val displayLargeCoverUrl: String?,
    @ColumnInfo(name = "display_author") val displayAuthor: String?,
    @ColumnInfo(name = "display_source") val displaySource: String?,
    @ColumnInfo(name = "display_state") val displayState: String?,
    @ColumnInfo(name = "display_nsfw") val displayNsfw: Boolean?,
    @ColumnInfo(name = "display_rating") val displayRating: Float?,
    @ColumnInfo(name = "display_content_type") val displayContentType: String?,
)

/** Tag facet of a history display projection — the Tag quick filter's key (title + key). */
data class HistoryTagFacetRow(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "tag_title") val tagTitle: String,
    @ColumnInfo(name = "tag_key") val tagKey: String,
)

/** Local-binding facet: the space filter's EXISTS/NOT EXISTS data per entity. */
data class HistoryBindingFacetRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "manga_source") val mangaSource: String,
    @ColumnInfo(name = "manga_content_type") val mangaContentType: String?,
    @ColumnInfo(name = "entity_content_type") val entityContentType: String?,
)

/** Favourite-category membership facet (FAVORITE / Favorite(category) filters). */
data class HistoryCategoryFacetRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
)

/**
 * Downloaded history entities via the local download index on bound projections
 * (the `Downloaded` quick filter). Mirrors [FavouriteDownloadedRow].
 */
data class HistoryDownloadedRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
)

/** Manual title/cover override of a history display projection. */
data class HistoryOverrideRow(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "cover_override") val coverOverride: String?,
)
