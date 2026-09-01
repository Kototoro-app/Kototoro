package org.skepsun.kototoro.favourites.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Narrow read-only DAO for the favourites library snapshot
 * (favourites-komikku-alignment plan, section 5.1).
 *
 * Design rules (enforced by `FavouriteLibraryReadDaoTest`):
 * - one row per entity in the base query; memberships / facets / tags are separate
 *   batch flows, never per-entity lookups (fixed DAO-call count per emission);
 * - every ORDER BY happens in memory later — the queries here only guarantee the
 *   representative membership selection, which must stay deterministic:
 *   `pinned DESC, created_at DESC, updated_at DESC, category_id ASC`;
 * - no filter parameters: Quick Filters / Space / category / sort are derived in
 *   memory from the complete snapshot;
 * - read-only: nothing here writes, not even `entity_preferences` repair.
 */
@Dao
abstract class FavouriteLibraryReadDao {

    /**
     * Base card row per active favourite entity. The representative membership is
     * picked with the same ranking as the legacy paging SQL (`pinned DESC,
     * created_at DESC, updated_at DESC, category_id ASC` — expressed as an anti-join
     * because Room's SQL parser predates window functions); the display projection is
     * `COALESCE(preferred_local_manga_id, anchor_manga_id)` — dangling references keep
     * the row alive with NULL display fields (broken rows stay reachable for entity
     * organize).
     */
    @Query(
        """
        WITH selected AS (
            SELECT wf.*
            FROM work_favourites wf
            INNER JOIN entity e ON e.id = wf.entity_id
            WHERE wf.anchor_manga_id IS NOT NULL
                AND wf.deleted_at = 0
                AND NOT EXISTS (
                    SELECT 1
                    FROM work_favourites candidate
                    WHERE candidate.entity_id = wf.entity_id
                        AND candidate.anchor_manga_id IS NOT NULL
                        AND candidate.deleted_at = 0
                        AND (
                            candidate.pinned > wf.pinned
                            OR (candidate.pinned = wf.pinned AND candidate.created_at > wf.created_at)
                            OR (candidate.pinned = wf.pinned AND candidate.created_at = wf.created_at
                                AND candidate.updated_at > wf.updated_at)
                            OR (candidate.pinned = wf.pinned AND candidate.created_at = wf.created_at
                                AND candidate.updated_at = wf.updated_at AND candidate.category_id < wf.category_id)
                        )
                )
        )
        SELECT
            selected.entity_id AS entity_id,
            dm.manga_id AS display_manga_id,
            dm.title AS display_title,
            dm.alt_title AS display_alt_title,
            dm.cover_url AS display_cover_url,
            dm.author AS display_author,
            dm.source AS display_source,
            dm.content_type AS display_content_type,
            dm.state AS display_state,
            dm.nsfw AS display_nsfw,
            dm.rating AS display_rating,
            ep.preferred_local_manga_id AS preferred_local_manga_id,
            e.content_type AS entity_content_type,
            ep.reading_status AS reading_status,
            ep.title_override AS title_override,
            ep.cover_override AS cover_override,
            selected.pinned AS representative_pinned,
            selected.created_at AS representative_created_at,
            selected.updated_at AS representative_updated_at,
            wh.percent AS history_percent,
            wh.chapters AS history_chapters,
            wh.updated_at AS history_updated_at,
            tracking.new_chapters AS tracking_new_chapters,
            tracking.last_chapter_date AS tracking_last_chapter_date,
            metadata.service AS metadata_tracking_service,
            metadata.title AS metadata_tracking_title,
            metadata.cover_url AS metadata_tracking_cover_url
        FROM selected
        INNER JOIN entity e ON e.id = selected.entity_id
        LEFT JOIN entity_preferences ep ON ep.entity_id = selected.entity_id
        LEFT JOIN manga dm ON dm.manga_id = COALESCE(ep.preferred_local_manga_id, selected.anchor_manga_id)
        LEFT JOIN work_history wh ON wh.entity_id = selected.entity_id AND wh.deleted_at = 0
        LEFT JOIN (
            SELECT
                entity_id,
                SUM(chapters_new) AS new_chapters,
                MAX(last_chapter_date) AS last_chapter_date
            FROM tracks
            WHERE entity_id IS NOT NULL
            GROUP BY entity_id
        ) tracking ON tracking.entity_id = selected.entity_id
        -- display metadata authority (`ContentDataRepository.getMetadataSourceSelections`):
        -- only a 'tracking' selection has a cached site item, whose primary key is
        -- (service, remote_id), so this join can never multiply base rows. The numeric
        -- preference columns mirror the binding ones written by the same code path.
        LEFT JOIN tracking_site_items metadata ON metadata.service = COALESCE(
                CAST(ep.metadata_binding_source AS INTEGER), ep.metadata_source_service
            )
            AND metadata.remote_id = COALESCE(
                CAST(ep.metadata_binding_external_id AS INTEGER), ep.metadata_source_remote_id
            )
            AND ep.metadata_source_kind = 'tracking'
        """,
    )
    abstract fun observeFavouriteCardBaseRows(): Flow<List<FavouriteCardBaseRow>>

    /** Every active `(entityId, categoryId)` membership for category slices. */
    @Query(
        """
        SELECT
            wf.entity_id AS entity_id,
            wf.category_id AS category_id,
            wf.pinned AS pinned,
            wf.sort_key AS sort_key,
            wf.created_at AS created_at,
            wf.updated_at AS updated_at
        FROM work_favourites wf
        INNER JOIN entity e ON e.id = wf.entity_id
        WHERE wf.anchor_manga_id IS NOT NULL
            AND wf.deleted_at = 0
        """,
    )
    abstract fun observeFavouriteMembershipRows(): Flow<List<FavouriteMembershipRow>>

    /**
     * Projection facets: every actively bound local projection of a favourite entity.
     * The projection set is binding-based only — the favourites anchor never inflates
     * the count (the MULTI_PROJECTION semantics `WorkPagingDaoTest` pins down).
     */
    @Query(
        """
        SELECT
            wf.entity_id AS entity_id,
            CAST(eb.external_id AS INTEGER) AS manga_id,
            m.source AS source,
            m.content_type AS content_type
        FROM (
            SELECT DISTINCT entity_id
            FROM work_favourites
            WHERE anchor_manga_id IS NOT NULL
                AND deleted_at = 0
        ) wf
        INNER JOIN entity_binding eb ON eb.entity_id = wf.entity_id
        INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
        WHERE eb.source IN ('local_manga', '0')
            AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
        """,
    )
    abstract fun observeFavouriteProjectionFacets(): Flow<List<FavouriteProjectionFacetRow>>

    /**
     * Tag facets for filtering (tag identity) and the detailed-list tag chips (title).
     * Tags of every bound projection count, matching the legacy tag filter which
     * matched the display manga OR any bound projection.
     */
    @Query(
        """
        SELECT
            wf.entity_id AS entity_id,
            mt.tag_id AS tag_id,
            t.title AS tag_title,
            t.key AS tag_key,
            t.source AS tag_source,
            mt.manga_id AS manga_id
        FROM (
            SELECT DISTINCT entity_id
            FROM work_favourites
            WHERE anchor_manga_id IS NOT NULL
                AND deleted_at = 0
        ) wf
        INNER JOIN entity_binding eb ON eb.entity_id = wf.entity_id
        INNER JOIN manga_tags mt ON mt.manga_id = CAST(eb.external_id AS INTEGER)
        INNER JOIN tags t ON t.tag_id = mt.tag_id
        WHERE eb.source IN ('local_manga', '0')
            AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
        """,
    )
    abstract fun observeFavouriteTagFacets(): Flow<List<FavouriteTagFacetRow>>

    /** Downloaded favourite entities via the local download index on bound projections. */
    @Query(
        """
        SELECT
            wf.entity_id AS entity_id,
            li.manga_id AS manga_id
        FROM (
            SELECT DISTINCT entity_id
            FROM work_favourites
            WHERE anchor_manga_id IS NOT NULL
                AND deleted_at = 0
        ) wf
        INNER JOIN entity_binding eb ON eb.entity_id = wf.entity_id
        INNER JOIN local_index li ON li.manga_id = CAST(eb.external_id AS INTEGER)
        WHERE eb.source IN ('local_manga', '0')
            AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
        """,
    )
    abstract fun observeDownloadedFavouriteRows(): Flow<List<FavouriteDownloadedRow>>

    /**
     * Legacy per-manga title/cover overrides (the `preferences` fallback of
     * `ContentDataRepository.getOverridesForWorkItems`).
     */
    @Query(
        """
        SELECT
            p.manga_id AS manga_id,
            p.title_override AS title_override,
            p.cover_override AS cover_override
        FROM preferences p
        WHERE p.title_override IS NOT NULL OR p.cover_override IS NOT NULL
        """,
    )
    abstract fun observeFavouriteLegacyOverrides(): Flow<List<FavouriteLegacyOverrideRow>>

    /** Tag facets of the display projection only (detailed-list chips + compact subtitle). */
    @Query(
        """
        SELECT
            wf.entity_id AS entity_id,
            mt.tag_id AS tag_id,
            t.title AS tag_title,
            t.key AS tag_key,
            t.source AS tag_source,
            mt.manga_id AS manga_id
        FROM work_favourites wf
        INNER JOIN manga_tags mt ON mt.manga_id = wf.anchor_manga_id
        INNER JOIN tags t ON t.tag_id = mt.tag_id
        WHERE wf.anchor_manga_id IS NOT NULL
            AND wf.deleted_at = 0
        """,
    )
    abstract fun observeFavouriteAnchorTagRows(): Flow<List<FavouriteTagFacetRow>>
}
