package org.skepsun.kototoro.history.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Narrow read-only projections of the work-history library
 * (history-updates-feed komikku-alignment plan, Phase H1).
 *
 * Same contract as [org.skepsun.kototoro.favourites.data.FavouriteLibraryReadDao]
 * and the tracker read DAO: parameterless observe() flows over Room invalidation,
 * every row already carries what a history card reads. No filters, no order —
 * the whole history list is filtered, folded, sorted and grouped in memory
 * (Phase H3); the store's row order is only the stable tie-break source.
 */
@Dao
abstract class HistoryLibraryReadDao {

    /**
     * Every active work_history row (one per entity) with display projection,
     * tracking summary, favourite/pinned membership and metadata authority.
     */
    @Query(
        """
        SELECT
            wh.entity_id AS entity_id,
            wh.anchor_manga_id AS anchor_manga_id,
            wh.updated_at AS updated_at,
            wh.created_at AS created_at,
            wh.percent AS percent,
            wh.chapters AS chapters,
            wh.chapter_id AS chapter_id,
            ep.preferred_local_manga_id AS preferred_local_manga_id,
            m.content_type AS anchor_content_type,
            e.content_type AS entity_content_type,
            tracking.new_chapters AS new_chapters,
            tracking.last_chapter_date AS last_chapter_date,
            EXISTS (
                SELECT 1 FROM work_favourites wf
                WHERE wf.entity_id = wh.entity_id
                    AND wf.anchor_manga_id IS NOT NULL
                    AND wf.deleted_at = 0
            ) AS is_favourite,
            EXISTS (
                SELECT 1 FROM work_favourites wf
                WHERE wf.entity_id = wh.entity_id
                    AND wf.anchor_manga_id IS NOT NULL
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
            m.content_rating AS display_content_rating,
            m.rating AS display_rating,
            m.content_type AS display_content_type
        FROM work_history wh
        LEFT JOIN entity e ON e.id = wh.entity_id
        LEFT JOIN entity_preferences ep ON ep.entity_id = wh.entity_id
        LEFT JOIN manga m ON m.manga_id = COALESCE(ep.preferred_local_manga_id, wh.anchor_manga_id)
        LEFT JOIN (
            SELECT
                entity_id,
                MAX(manga_id) AS anchor_manga_id,
                MAX(last_chapter_id) AS last_chapter_id,
                SUM(chapters_new) AS new_chapters,
                MAX(last_check_time) AS last_check_time,
                MAX(last_chapter_date) AS last_chapter_date
            FROM tracks
            WHERE entity_id IS NOT NULL
            GROUP BY entity_id
        ) tracking ON tracking.entity_id = wh.entity_id
        -- display metadata authority: only a 'tracking' selection has a cached site
        -- item whose primary key is (service, remote_id), so this join can never
        -- multiply base rows (same join as the favourites row).
        LEFT JOIN tracking_site_items metadata ON metadata.service = COALESCE(
                CAST(ep.metadata_binding_source AS INTEGER), ep.metadata_source_service
            )
            AND metadata.remote_id = COALESCE(
                CAST(ep.metadata_binding_external_id AS INTEGER), ep.metadata_source_remote_id
            )
            AND ep.metadata_source_kind = 'tracking'
        WHERE wh.deleted_at = 0
        ORDER BY wh.entity_id ASC
        """,
    )
    abstract fun observeHistoryCardBaseRows(): Flow<List<HistoryCardRow>>

    /** Tags of the history display projections (the Tag quick filter's key). */
    @Query(
        """
        SELECT
            mt.manga_id AS manga_id,
            t.title AS tag_title,
            t.key AS tag_key
        FROM manga_tags mt
        INNER JOIN tags t ON t.tag_id = mt.tag_id
        INNER JOIN work_history wh ON wh.anchor_manga_id = mt.manga_id
            OR mt.manga_id = COALESCE(
                (SELECT ep.preferred_local_manga_id FROM entity_preferences ep WHERE ep.entity_id = wh.entity_id),
                wh.anchor_manga_id
            )
        WHERE wh.deleted_at = 0
        """,
    )
    abstract fun observeHistoryTagFacets(): Flow<List<HistoryTagFacetRow>>

    /** Active local bindings per history entity (the space filter's data). */
    @Query(
        """
        SELECT
            eb.entity_id AS entity_id,
            CAST(eb.external_id AS INTEGER) AS manga_id,
            sm.source AS manga_source,
            sm.content_type AS manga_content_type,
            e.content_type AS entity_content_type
        FROM entity_binding eb
        INNER JOIN manga sm ON sm.manga_id = CAST(eb.external_id AS INTEGER)
        INNER JOIN entity e ON e.id = eb.entity_id
        INNER JOIN work_history wh ON wh.entity_id = eb.entity_id
        WHERE eb.source IN ('local_manga', '0')
            AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
            AND wh.deleted_at = 0
        """,
    )
    abstract fun observeHistoryBindingFacets(): Flow<List<HistoryBindingFacetRow>>

    /** Favourite-category memberships of history entities. */
    @Query(
        """
        SELECT
            wf.entity_id AS entity_id,
            wf.category_id AS category_id
        FROM work_favourites wf
        INNER JOIN work_history wh ON wh.entity_id = wf.entity_id
        WHERE wf.anchor_manga_id IS NOT NULL
            AND wf.deleted_at = 0
            AND wh.deleted_at = 0
        """,
    )
    abstract fun observeHistoryCategoryFacets(): Flow<List<HistoryCategoryFacetRow>>

    /**
     * Downloaded history entities via the local download index on bound
     * projections (the `Downloaded` quick filter), same join shape as
     * `observeDownloadedFavouriteRows`.
     */
    @Query(
        """
        SELECT
            wf.entity_id AS entity_id,
            li.manga_id AS manga_id
        FROM (
            SELECT DISTINCT entity_id
            FROM work_history
            WHERE deleted_at = 0
        ) wf
        INNER JOIN entity_binding eb ON eb.entity_id = wf.entity_id
        INNER JOIN local_index li ON li.manga_id = CAST(eb.external_id AS INTEGER)
        WHERE eb.source IN ('local_manga', '0')
            AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
        """,
    )
    abstract fun observeHistoryDownloadedRows(): Flow<List<HistoryDownloadedRow>>

    /** Manual title/cover overrides of the history display projections. */
    @Query(
        """
        SELECT
            p.manga_id AS manga_id,
            p.title_override AS title_override,
            p.cover_override AS cover_override
        FROM preferences p
        INNER JOIN work_history wh ON wh.anchor_manga_id = p.manga_id
            OR p.manga_id = COALESCE(
                (SELECT ep.preferred_local_manga_id FROM entity_preferences ep WHERE ep.entity_id = wh.entity_id),
                wh.anchor_manga_id
            )
        WHERE wh.deleted_at = 0
            AND (p.title_override IS NOT NULL OR p.cover_override IS NOT NULL)
        """,
    )
    abstract fun observeHistoryOverrides(): Flow<List<HistoryOverrideRow>>
}
