package org.skepsun.kototoro.favourites.data

import androidx.room.ColumnInfo

/**
 * One row per active favourite entity — the narrow card read model that replaces the
 * wide `FavouriteLibraryPagingRow` (which embedded a full `MangaEntity`, a full
 * `WorkHistoryEntity` and tracking columns for every row).
 *
 * Field budget is pinned by `FavouriteCardFieldContractTest` (Phase 0): every column
 * here has a real consumer in GRID / COMPACT_GRID / LIST / DETAILED_LIST, a sort order,
 * a quick filter, or the action routing. Anything without a consumer is deleted.
 *
 * `display_*` columns follow the representative projection: `preferred_local_manga_id`
 * when it points at an existing manga of this entity, otherwise `anchor_manga_id`.
 * Both can dangle (see [hasDisplay]); the row survives so the user can still reach
 * entity organize.
 */
data class FavouriteCardBaseRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "display_manga_id") val displayMangaId: Long?,
    @ColumnInfo(name = "display_title") val displayTitle: String?,
    @ColumnInfo(name = "display_alt_title") val displayAltTitle: String?,
    @ColumnInfo(name = "display_cover_url") val displayCoverUrl: String?,
    @ColumnInfo(name = "display_author") val displayAuthor: String?,
    @ColumnInfo(name = "display_source") val displaySource: String?,
    @ColumnInfo(name = "display_content_type") val displayContentType: String?,
    @ColumnInfo(name = "display_state") val displayState: String?,
    @ColumnInfo(name = "display_nsfw") val displayNsfw: Boolean?,
    @ColumnInfo(name = "display_rating") val displayRating: Float?,
    @ColumnInfo(name = "preferred_local_manga_id") val preferredLocalMangaId: Long?,
    @ColumnInfo(name = "entity_content_type") val entityContentType: String?,
    @ColumnInfo(name = "reading_status") val readingStatus: String?,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "cover_override") val coverOverride: String?,
    // representative membership attributes (pinned sorts first in every order)
    @ColumnInfo(name = "representative_pinned") val representativePinned: Boolean,
    @ColumnInfo(name = "representative_created_at") val representativeCreatedAt: Long,
    @ColumnInfo(name = "representative_updated_at") val representativeUpdatedAt: Long,
    // work_history (progress sort + progress badge)
    @ColumnInfo(name = "history_percent") val historyPercent: Float?,
    @ColumnInfo(name = "history_chapters") val historyChapters: Int?,
    @ColumnInfo(name = "history_updated_at") val historyUpdatedAt: Long?,
    // tracks summary (new chapters sort + counter badge + quick filter)
    @ColumnInfo(name = "tracking_new_chapters") val trackingNewChapters: Int?,
    @ColumnInfo(name = "tracking_last_chapter_date") val trackingLastChapterDate: Long?,
    // display metadata authority: the cached site item behind the entity's 'tracking'
    // metadata selection (contract field `metadataTrackingService` + the title/cover the
    // legacy chain rendered through ContentListMapper.resolveDisplayOverride). Null when
    // the entity has no tracking authority or the cache holds nothing for it.
    @ColumnInfo(name = "metadata_tracking_service") val metadataTrackingService: Int?,
    @ColumnInfo(name = "metadata_tracking_title") val metadataTrackingTitle: String?,
    @ColumnInfo(name = "metadata_tracking_cover_url") val metadataTrackingCoverUrl: String?,
) {
    /** True when the display projection itself is missing (dangling preferred/anchor). */
    val hasDisplay: Boolean
        get() = displayMangaId != null
}
