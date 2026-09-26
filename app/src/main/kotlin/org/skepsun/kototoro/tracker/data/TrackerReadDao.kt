package org.skepsun.kototoro.tracker.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Narrow read-only DAO for the tracker feed and updates snapshots
 * (projection-first architecture).
 */
@Dao
abstract class TrackerReadDao {

    /**
     * The feed's primary source: every `track_logs` row.
     */
    @Query(
        """
        SELECT
            tl.id AS log_id,
            tl.manga_id AS anchor_manga_id,
            tl.manga_id AS owner_id,
            tl.manga_id AS entity_id,
            tl.chapters AS chapters,
            tl.created_at AS created_at,
            tl.unread AS unread,
            tl.manga_id AS preferred_local_manga_id,
            IFNULL(pinned.pinned, 0) AS entity_pinned,
            dm.manga_id AS display_manga_id,
            dm.title AS display_title,
            dm.alt_title AS display_alt_title,
            dm.cover_url AS display_cover_url,
            dm.author AS display_author,
            dm.source AS display_source,
            dm.url AS display_url,
            dm.content_type AS display_content_type,
            dm.state AS display_state,
            dm.nsfw AS display_nsfw,
            dm.rating AS display_rating
        FROM track_logs tl
        LEFT JOIN manga dm ON dm.manga_id = tl.manga_id
        LEFT JOIN (
            SELECT f.manga_id AS manga_id, MAX(f.pinned) AS pinned
            FROM favourites f
            WHERE f.deleted_at = 0
            GROUP BY f.manga_id
        ) pinned ON pinned.manga_id = tl.manga_id
        """,
    )
    abstract fun observeFeedLogRows(): Flow<List<FeedLogRow>>

    /**
     * Every tracked work with pending new chapters: the updates data set.
     */
    @Query(
        """
        SELECT
            t.manga_id AS manga_id,
            t.manga_id AS owner_id,
            t.manga_id AS entity_id,
            t.chapters_new AS new_chapters,
            t.last_chapter_date AS last_chapter_date,
            t.last_check_time AS last_check_time,
            t.last_chapter_id AS last_chapter_id,
            t.manga_id AS preferred_local_manga_id,
            IFNULL(pinned.pinned, 0) AS entity_pinned,
            metadata.service AS metadata_tracking_service,
            metadata.title AS metadata_tracking_title,
            metadata.cover_url AS metadata_tracking_cover_url,
            dm.manga_id AS display_manga_id,
            dm.title AS display_title,
            dm.alt_title AS display_alt_title,
            dm.cover_url AS display_cover_url,
            dm.author AS display_author,
            dm.source AS display_source,
            dm.content_type AS display_content_type,
            dm.state AS display_state,
            dm.nsfw AS display_nsfw,
            dm.rating AS display_rating
        FROM tracks t
        LEFT JOIN preferences p ON p.manga_id = t.manga_id
        LEFT JOIN manga dm ON dm.manga_id = t.manga_id
        LEFT JOIN (
            SELECT f.manga_id AS manga_id, MAX(f.pinned) AS pinned
            FROM favourites f
            WHERE f.deleted_at = 0
            GROUP BY f.manga_id
        ) pinned ON pinned.manga_id = t.manga_id
        LEFT JOIN tracking_site_items metadata ON metadata.service = p.metadata_source_service
            AND metadata.remote_id = p.metadata_source_remote_id
            AND p.metadata_source_kind = 'tracking'
        WHERE t.chapters_new > 0
        """,
    )
    abstract fun observeUpdateTrackRows(): Flow<List<UpdateTrackRow>>

    /** Tags of tracked manga. */
    @Query(
        """
        SELECT
            mt.manga_id AS manga_id,
            mt.tag_id AS tag_id,
            t.title AS tag_title
        FROM manga_tags mt
        INNER JOIN tags t ON t.tag_id = mt.tag_id
        WHERE mt.manga_id IN (
            SELECT t.manga_id FROM tracks t WHERE t.chapters_new > 0
            UNION
            SELECT tl.manga_id FROM track_logs tl WHERE :includeFeedLogs
        )
        """,
    )
    abstract fun observeTrackedTagFacets(includeFeedLogs: Boolean): Flow<List<TrackedTagFacetRow>>

    /** Favourite-category ids of every tracked manga with pending updates. */
    @Query(
        """
        SELECT DISTINCT
            t.manga_id AS entity_id,
            f.category_id AS category_id
        FROM tracks t
        INNER JOIN favourites f ON f.manga_id = t.manga_id
            AND f.deleted_at = 0
        WHERE t.chapters_new > 0
        """,
    )
    abstract fun observeTrackedEntityCategoryFacets(): Flow<List<TrackedEntityCategoryFacetRow>>

    /** Manual overrides of tracked manga. */
    @Query(
        """
        SELECT
            p.manga_id AS manga_id,
            p.title_override AS title_override,
            p.cover_override AS cover_override
        FROM preferences p
        WHERE (p.title_override IS NOT NULL OR p.cover_override IS NOT NULL)
            AND p.manga_id IN (
                SELECT manga_id FROM tracks WHERE chapters_new > 0
                UNION
                SELECT manga_id FROM track_logs WHERE :includeFeedLogs
            )
        """,
    )
    abstract fun observeTrackedOverrides(includeFeedLogs: Boolean): Flow<List<TrackedOverrideRow>>

    /** Chapter counts of tracked manga. */
    @Query(
        """
        SELECT
            c.manga_id AS manga_id,
            COUNT(*) AS chapter_count
        FROM chapters c
        WHERE c.manga_id IN (
            SELECT t.manga_id FROM tracks t WHERE t.chapters_new > 0
            UNION
            SELECT tl.manga_id FROM track_logs tl WHERE :includeFeedLogs
        )
        GROUP BY c.manga_id
        """,
    )
    abstract fun observeTrackedChapterCounts(includeFeedLogs: Boolean): Flow<List<TrackedChapterCountRow>>
}
