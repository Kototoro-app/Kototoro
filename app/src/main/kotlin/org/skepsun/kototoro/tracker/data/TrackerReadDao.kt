package org.skepsun.kototoro.tracker.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Narrow read-only DAO for the tracker feed and updates snapshots
 * (history-updates-feed komikku-alignment plan, Phase F1 / U1).
 *
 * Design rules (mirroring `FavouriteLibraryReadDao`, enforced by
 * `FeedLogSemanticsCharacterizationTest` / the F1/U1 DAO suites):
 * - every flow returns the complete data set; filters (quick filters, scope,
 *   NSFW, blacklist), the feed limit window and all sorting happen in memory;
 * - a fixed number of DAO calls per emission — batch facet flows, never
 *   per-entity lookups;
 * - read-only: nothing here writes (the `gc()` family stays in the
 *   maintenance repository).
 *
 * Identity resolution follows the legacy SQL exactly: the entity id is
 * `COALESCE(<table>.entity_id, <binding lookup>)` where the binding lookup is
 * the first `entity_binding` row with source `local_manga`/`0` in state
 * MANUAL/CONFIRMED/LEGACY for the anchor manga id. The pinned flag is the
 * `MAX(pinned)` of the entity's active favourites with a non-null anchor.
 */
@Dao
abstract class TrackerReadDao {

    /**
     * The feed's primary source: every `track_logs` row (the table is already bounded
     * by `TRACK_LOG_RETAINED_SIZE` trimming). Ordering is irrelevant here — the
     * store sorts in memory — but the identity/pinned/display resolution must match
     * the legacy paging SQL.
     */
    @Query(
        """
        SELECT
            tl.id AS log_id,
            tl.manga_id AS anchor_manga_id,
            tl.owner_id AS owner_id,
            COALESCE(tl.entity_id, (
                SELECT eb.entity_id FROM entity_binding eb
                WHERE eb.source IN ('local_manga', '0')
                    AND eb.external_id = CAST(tl.manga_id AS TEXT)
                    AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
                LIMIT 1
            )) AS entity_id,
            tl.chapters AS chapters,
            tl.created_at AS created_at,
            tl.unread AS unread,
            ep.preferred_local_manga_id AS preferred_local_manga_id,
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
        LEFT JOIN entity e ON e.id = COALESCE(tl.entity_id, (
                SELECT eb.entity_id FROM entity_binding eb
                WHERE eb.source IN ('local_manga', '0')
                    AND eb.external_id = CAST(tl.manga_id AS TEXT)
                    AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
                LIMIT 1
            ))
        LEFT JOIN entity_preferences ep ON ep.entity_id = e.id
        LEFT JOIN manga dm ON dm.manga_id = COALESCE(ep.preferred_local_manga_id, tl.manga_id)
        LEFT JOIN (
            SELECT wf.entity_id AS entity_id, MAX(wf.pinned) AS pinned
            FROM work_favourites wf
            WHERE wf.anchor_manga_id IS NOT NULL
                AND wf.deleted_at = 0
            GROUP BY wf.entity_id
        ) pinned ON pinned.entity_id = e.id
        """,
    )
    abstract fun observeFeedLogRows(): Flow<List<FeedLogRow>>

    /**
     * Every tracked work with pending new chapters: the updates data set and the
     * `showAllUpdates` feed branch. `chapters_new > 0` is the data-set definition,
     * not a filter.
     */
    @Query(
        """
        SELECT
            t.manga_id AS manga_id,
            t.owner_id AS owner_id,
            COALESCE(t.entity_id, (
                SELECT eb.entity_id FROM entity_binding eb
                WHERE eb.source IN ('local_manga', '0')
                    AND eb.external_id = CAST(t.manga_id AS TEXT)
                    AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
                LIMIT 1
            )) AS entity_id,
            t.chapters_new AS new_chapters,
            t.last_chapter_date AS last_chapter_date,
            t.last_check_time AS last_check_time,
            t.last_chapter_id AS last_chapter_id,
            ep.preferred_local_manga_id AS preferred_local_manga_id,
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
        LEFT JOIN entity e ON e.id = COALESCE(t.entity_id, (
                SELECT eb.entity_id FROM entity_binding eb
                WHERE eb.source IN ('local_manga', '0')
                    AND eb.external_id = CAST(t.manga_id AS TEXT)
                    AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
                LIMIT 1
            ))
        LEFT JOIN entity_preferences ep ON ep.entity_id = e.id
        LEFT JOIN manga dm ON dm.manga_id = COALESCE(ep.preferred_local_manga_id, t.manga_id)
        LEFT JOIN (
            SELECT wf.entity_id AS entity_id, MAX(wf.pinned) AS pinned
            FROM work_favourites wf
            WHERE wf.anchor_manga_id IS NOT NULL
                AND wf.deleted_at = 0
            GROUP BY wf.entity_id
        ) pinned ON pinned.entity_id = e.id
        LEFT JOIN tracking_site_items metadata ON metadata.service = COALESCE(
                CAST(ep.metadata_binding_source AS INTEGER), ep.metadata_source_service
            )
            AND metadata.remote_id = COALESCE(
                CAST(ep.metadata_binding_external_id AS INTEGER), ep.metadata_source_remote_id
            )
            AND ep.metadata_source_kind = 'tracking'
        WHERE t.chapters_new > 0
        """,
    )
    abstract fun observeUpdateTrackRows(): Flow<List<UpdateTrackRow>>

    /** Tags of the representative manga of each tracked work (tag filter key). */
    @Query(
        """
        SELECT
            mt.manga_id AS manga_id,
            mt.tag_id AS tag_id,
            t.title AS tag_title
        FROM manga_tags mt
        INNER JOIN tags t ON t.tag_id = mt.tag_id
        WHERE mt.manga_id IN (
            SELECT COALESCE(ep.preferred_local_manga_id, t.manga_id)
            FROM tracks t
            LEFT JOIN entity e ON e.id = COALESCE(t.entity_id, (
                SELECT eb.entity_id FROM entity_binding eb
                WHERE eb.source IN ('local_manga', '0')
                    AND eb.external_id = CAST(t.manga_id AS TEXT)
                    AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
                LIMIT 1
            ))
            LEFT JOIN entity_preferences ep ON ep.entity_id = e.id
            WHERE t.chapters_new > 0
            UNION
            SELECT COALESCE(ep.preferred_local_manga_id, tl.manga_id)
            FROM track_logs tl
            LEFT JOIN entity e ON e.id = COALESCE(tl.entity_id, (
                SELECT eb.entity_id FROM entity_binding eb
                WHERE eb.source IN ('local_manga', '0')
                    AND eb.external_id = CAST(tl.manga_id AS TEXT)
                    AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
                LIMIT 1
            ))
            LEFT JOIN entity_preferences ep ON ep.entity_id = e.id
            WHERE :includeFeedLogs
        )
        """,
    )
    abstract fun observeTrackedTagFacets(includeFeedLogs: Boolean): Flow<List<TrackedTagFacetRow>>

    /** Favourite-category ids of every tracked entity with pending updates. */
    @Query(
        """
        SELECT DISTINCT
            t.entity_id AS entity_id,
            wf.category_id AS category_id
        FROM tracks t
        INNER JOIN work_favourites wf ON wf.entity_id = t.entity_id
            AND wf.anchor_manga_id IS NOT NULL
            AND wf.deleted_at = 0
        WHERE t.chapters_new > 0
        """,
    )
    abstract fun observeTrackedEntityCategoryFacets(): Flow<List<TrackedEntityCategoryFacetRow>>

    /** Manual overrides of tracked manga (title/cover of the feed/updates card). */
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

    /** Chapter counts of the representative manga (the feed card's total). */
    @Query(
        """
        SELECT
            c.manga_id AS manga_id,
            COUNT(*) AS chapter_count
        FROM chapters c
        WHERE c.manga_id IN (
            SELECT COALESCE(ep.preferred_local_manga_id, t.manga_id)
            FROM tracks t
            LEFT JOIN entity e ON e.id = COALESCE(t.entity_id, (
                SELECT eb.entity_id FROM entity_binding eb
                WHERE eb.source IN ('local_manga', '0')
                    AND eb.external_id = CAST(t.manga_id AS TEXT)
                    AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
                LIMIT 1
            ))
            LEFT JOIN entity_preferences ep ON ep.entity_id = e.id
            WHERE t.chapters_new > 0
            UNION
            SELECT COALESCE(ep.preferred_local_manga_id, tl.manga_id)
            FROM track_logs tl
            LEFT JOIN entity e ON e.id = COALESCE(tl.entity_id, (
                SELECT eb.entity_id FROM entity_binding eb
                WHERE eb.source IN ('local_manga', '0')
                    AND eb.external_id = CAST(tl.manga_id AS TEXT)
                    AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
                LIMIT 1
            ))
            LEFT JOIN entity_preferences ep ON ep.entity_id = e.id
        )
        GROUP BY c.manga_id
        """,
    )
    abstract fun observeTrackedChapterCounts(): Flow<List<TrackedChapterCountRow>>
}
