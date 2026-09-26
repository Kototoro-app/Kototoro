package org.skepsun.kototoro.history.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Narrow read-only projections of the history library
 * (history-updates-feed komikku-alignment plan, projection-first).
 */
@Dao
abstract class HistoryLibraryReadDao {

    /**
     * Every active history row with display projection,
     * tracking summary, favourite/pinned membership and metadata authority.
     */
    @Query(
        """
        SELECT
            wh.manga_id AS entity_id,
            wh.manga_id AS anchor_manga_id,
            wh.updated_at AS updated_at,
            wh.created_at AS created_at,
            wh.percent AS percent,
            wh.chapters AS chapters,
            wh.chapter_id AS chapter_id,
            wh.manga_id AS preferred_local_manga_id,
            m.content_type AS anchor_content_type,
            m.content_type AS entity_content_type,
            tracking.new_chapters AS new_chapters,
            tracking.last_chapter_date AS last_chapter_date,
            EXISTS (
                SELECT 1 FROM favourites wf
                WHERE wf.manga_id = wh.manga_id
                    AND wf.deleted_at = 0
                    AND wf.pinned > 0
            ) AS is_pinned,
            metadata.service AS metadata_tracking_service,
            metadata.title AS metadata_tracking_title,
            metadata.cover_url AS metadata_tracking_cover_url,
            m.manga_id AS display_manga_id,
            m.title AS display_title,
            m.alt_title AS display_alt_title,
            m.cover_url AS display_cover_url,
            m.large_cover_url AS display_large_cover_url,
            m.author AS display_author,
            m.source AS display_source,
            m.state AS display_state,
            m.nsfw AS display_nsfw,
            m.rating AS display_rating,
            m.content_type AS display_content_type
        FROM history wh
        LEFT JOIN preferences p ON p.manga_id = wh.manga_id
        LEFT JOIN manga m ON m.manga_id = wh.manga_id
        LEFT JOIN (
            SELECT
                manga_id,
                SUM(chapters_new) AS new_chapters,
                MAX(last_chapter_date) AS last_chapter_date
            FROM tracks
            GROUP BY manga_id
        ) tracking ON tracking.manga_id = wh.manga_id
        LEFT JOIN tracking_site_items metadata ON metadata.service = p.metadata_source_service
            AND metadata.remote_id = p.metadata_source_remote_id
            AND p.metadata_source_kind = 'tracking'
        WHERE wh.deleted_at = 0
        ORDER BY wh.manga_id ASC
        """,
    )
    abstract fun observeHistoryCardBaseRows(): Flow<List<HistoryCardRow>>

    /**
     * Tags of the history display projections.
     */
    @Query(
        """
        SELECT
            mt.manga_id AS manga_id,
            t.title AS tag_title,
            t.key AS tag_key
        FROM (
            SELECT DISTINCT manga_id
            FROM history
            WHERE deleted_at = 0
        ) display_ids
        INNER JOIN manga_tags mt ON mt.manga_id = display_ids.manga_id
        INNER JOIN tags t ON t.tag_id = mt.tag_id
        """,
    )
    abstract fun observeHistoryTagFacets(): Flow<List<HistoryTagFacetRow>>

    /** Content projections per history entry. */
    @Query(
        """
        SELECT
            wh.manga_id AS entity_id,
            wh.manga_id AS manga_id,
            sm.source AS manga_source,
            sm.content_type AS manga_content_type,
            sm.content_type AS entity_content_type
        FROM history wh
        INNER JOIN manga sm ON sm.manga_id = wh.manga_id
        WHERE wh.deleted_at = 0
        """,
    )
    abstract fun observeHistoryBindingFacets(): Flow<List<HistoryBindingFacetRow>>

    /** Favourite-category memberships of history items. */
    @Query(
        """
        SELECT
            wf.manga_id AS entity_id,
            wf.category_id AS category_id
        FROM favourites wf
        INNER JOIN history wh ON wh.manga_id = wf.manga_id
        WHERE wf.deleted_at = 0
            AND wh.deleted_at = 0
        """,
    )
    abstract fun observeHistoryCategoryFacets(): Flow<List<HistoryCategoryFacetRow>>

    /**
     * Downloaded history items via the local download index.
     */
    @Query(
        """
        SELECT
            wh.manga_id AS entity_id,
            li.manga_id AS manga_id
        FROM (
            SELECT DISTINCT manga_id
            FROM history
            WHERE deleted_at = 0
        ) wh
        INNER JOIN local_index li ON li.manga_id = wh.manga_id
        """,
    )
    abstract fun observeHistoryDownloadedRows(): Flow<List<HistoryDownloadedRow>>

    /**
     * Manual title/cover overrides of history items.
     */
    @Query(
        """
        SELECT
            p.manga_id AS manga_id,
            p.title_override AS title_override,
            p.cover_override AS cover_override
        FROM preferences p
        INNER JOIN history wh ON wh.manga_id = p.manga_id
            AND wh.deleted_at = 0
        WHERE p.title_override IS NOT NULL OR p.cover_override IS NOT NULL
        """,
    )
    abstract fun observeHistoryOverrides(): Flow<List<HistoryOverrideRow>>
}
