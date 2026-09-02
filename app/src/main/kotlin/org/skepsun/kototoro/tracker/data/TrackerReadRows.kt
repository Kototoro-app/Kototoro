package org.skepsun.kototoro.tracker.data

import androidx.room.ColumnInfo

/**
 * One row per `track_logs` entry: the narrow read model of the subscription feed
 * (history-updates-feed komikku-alignment plan, Phase F1).
 *
 * Field budget is pinned by `FeedLogSemanticsCharacterizationTest` (Phase F0): every
 * column has a real consumer — the feed card (`FeedItem`: title/cover/chapter count/
 * unread dot), the date-bucket grouping (`createdAt`), the details navigation
 * (`entityId` / `preferredLocalMangaId` / `anchorMangaId`), the entity grouping keys
 * and the read/new-chapter actions. Anything without a consumer is deleted.
 *
 * `display_*` columns follow the same representative-projection rule as the favourites
 * library: `preferred_local_manga_id` when the entity prefers a local projection that
 * exists, otherwise the log's own `manga_id` (the anchor). The chapters string stays
 * raw (the legacy split('\n') happens in the store, once per snapshot, not per row).
 */
data class FeedLogRow(
    @ColumnInfo(name = "log_id") val logId: Long,
    @ColumnInfo(name = "anchor_manga_id") val anchorMangaId: Long,
    @ColumnInfo(name = "owner_id") val ownerId: Long,
    @ColumnInfo(name = "entity_id") val entityId: Long?,
    @ColumnInfo(name = "chapters") val chapters: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "unread") val unread: Boolean,
    // resolved identity (the COALESCE(tracks.entity_id, binding lookup) semantics)
    @ColumnInfo(name = "preferred_local_manga_id") val preferredLocalMangaId: Long?,
    // pinned flag of the resolved entity's favourite (pinned sorts first in the feed)
    @ColumnInfo(name = "entity_pinned") val entityPinned: Boolean,
    // display projection columns (COALESCE(preferred, anchor))
    @ColumnInfo(name = "display_manga_id") val displayMangaId: Long?,
    @ColumnInfo(name = "display_title") val displayTitle: String?,
    @ColumnInfo(name = "display_alt_title") val displayAltTitle: String?,
    @ColumnInfo(name = "display_cover_url") val displayCoverUrl: String?,
    @ColumnInfo(name = "display_author") val displayAuthor: String?,
    @ColumnInfo(name = "display_source") val displaySource: String?,
    @ColumnInfo(name = "display_url") val displayUrl: String?,
    @ColumnInfo(name = "display_content_type") val displayContentType: String?,
    @ColumnInfo(name = "display_state") val displayState: String?,
    @ColumnInfo(name = "display_nsfw") val displayNsfw: Boolean?,
    @ColumnInfo(name = "display_rating") val displayRating: Float?,
) {
    /** True when the display projection exists (a dangling anchor yields a broken row). */
    val hasDisplay: Boolean
        get() = displayMangaId != null
}

/**
 * One row per tracked work with pending new chapters (`chapters_new > 0`): the
 * `showAllUpdates` branch of the feed and the updates page base row. Narrow on
 * purpose — display + identity + tracking summary only, matching what the card
 * and the entity grouping actually read.
 */
data class UpdateTrackRow(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "owner_id") val ownerId: Long,
    @ColumnInfo(name = "entity_id") val entityId: Long?,
    @ColumnInfo(name = "new_chapters") val newChapters: Int,
    @ColumnInfo(name = "last_chapter_date") val lastChapterDate: Long?,
    @ColumnInfo(name = "last_check_time") val lastCheckTime: Long,
    @ColumnInfo(name = "last_chapter_id") val lastChapterId: Long,
    // resolved identity
    @ColumnInfo(name = "preferred_local_manga_id") val preferredLocalMangaId: Long?,
    @ColumnInfo(name = "entity_pinned") val entityPinned: Boolean,
    // display metadata authority: the cached tracking-site item behind the
    // entity's 'tracking' metadata selection (same join as the favourites row)
    @ColumnInfo(name = "metadata_tracking_service") val metadataTrackingService: Int?,
    @ColumnInfo(name = "metadata_tracking_title") val metadataTrackingTitle: String?,
    @ColumnInfo(name = "metadata_tracking_cover_url") val metadataTrackingCoverUrl: String?,
    // display projection columns (COALESCE(preferred, anchor))
    @ColumnInfo(name = "display_manga_id") val displayMangaId: Long?,
    @ColumnInfo(name = "display_title") val displayTitle: String?,
    @ColumnInfo(name = "display_alt_title") val displayAltTitle: String?,
    @ColumnInfo(name = "display_cover_url") val displayCoverUrl: String?,
    @ColumnInfo(name = "display_author") val displayAuthor: String?,
    @ColumnInfo(name = "display_source") val displaySource: String?,
    @ColumnInfo(name = "display_url") val displayUrl: String?,
    @ColumnInfo(name = "display_content_type") val displayContentType: String?,
    @ColumnInfo(name = "display_state") val displayState: String?,
    @ColumnInfo(name = "display_nsfw") val displayNsfw: Boolean?,
    @ColumnInfo(name = "display_rating") val displayRating: Float?,
)

/** Binding facet: an actively bound local projection of a tracked entity. */
data class TrackedBindingFacetRow(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "content_type") val contentType: String?,
)

/** Tag facet on the representative (preferred/anchor) manga — the tag filter key. */
data class TrackedTagFacetRow(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
    @ColumnInfo(name = "tag_title") val tagTitle: String,
)

/** Manual title/cover override of a tracked manga (the `preferences` fallback). */
data class TrackedOverrideRow(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "cover_override") val coverOverride: String?,
)

/** Chapter count per manga (the feed card's `totalChapters`). */
data class TrackedChapterCountRow(
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int,
)
