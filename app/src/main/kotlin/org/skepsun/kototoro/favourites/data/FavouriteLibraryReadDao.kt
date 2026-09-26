package org.skepsun.kototoro.favourites.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Narrow read-only DAO for the favourites library snapshot
 * (favourites-komikku-alignment plan, projection-first).
 */
@Dao
abstract class FavouriteLibraryReadDao {

    /**
     * Base card row per active favourite manga. The representative membership is
     * picked with the same ranking: `pinned DESC, created_at DESC, updated_at DESC, category_id ASC`.
     */
    @Query(
        """
        WITH selected AS (
            SELECT f.*
            FROM favourites f
            WHERE f.deleted_at = 0
                AND NOT EXISTS (
                    SELECT 1
                    FROM favourites candidate
                    WHERE candidate.manga_id = f.manga_id
                        AND candidate.deleted_at = 0
                        AND (
                            candidate.pinned > f.pinned
                            OR (candidate.pinned = f.pinned AND candidate.created_at > f.created_at)
                            OR (candidate.pinned = f.pinned AND candidate.created_at = f.created_at
                                AND candidate.updated_at > f.updated_at)
                            OR (candidate.pinned = f.pinned AND candidate.created_at = f.created_at
                                AND candidate.updated_at = f.updated_at AND candidate.category_id < f.category_id)
                        )
                )
        )
        SELECT
            selected.manga_id AS entity_id,
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
            selected.manga_id AS preferred_local_manga_id,
            dm.content_type AS entity_content_type,
            p.reading_status AS reading_status,
            p.title_override AS title_override,
            p.cover_override AS cover_override,
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
        INNER JOIN manga dm ON dm.manga_id = selected.manga_id
        LEFT JOIN preferences p ON p.manga_id = selected.manga_id
        LEFT JOIN history wh ON wh.manga_id = selected.manga_id AND wh.deleted_at = 0
        LEFT JOIN (
            SELECT
                manga_id,
                SUM(chapters_new) AS new_chapters,
                MAX(last_chapter_date) AS last_chapter_date
            FROM tracks
            GROUP BY manga_id
        ) tracking ON tracking.manga_id = selected.manga_id
        LEFT JOIN tracking_site_items metadata ON metadata.service = p.metadata_source_service
            AND metadata.remote_id = p.metadata_source_remote_id
            AND p.metadata_source_kind = 'tracking'
        """,
    )
    abstract fun observeFavouriteCardBaseRows(): Flow<List<FavouriteCardBaseRow>>

    /** Every active `(mangaId, categoryId)` membership for category slices. */
    @Query(
        """
        SELECT
            f.manga_id AS entity_id,
            f.category_id AS category_id,
            f.pinned AS pinned,
            f.sort_key AS sort_key,
            f.created_at AS created_at,
            f.updated_at AS updated_at
        FROM favourites f
        WHERE f.deleted_at = 0
        """,
    )
    abstract fun observeFavouriteMembershipRows(): Flow<List<FavouriteMembershipRow>>

    /** Projection facets: every active favourite manga. */
    @Query(
        """
        SELECT
            f.manga_id AS entity_id,
            f.manga_id AS manga_id,
            m.source AS source,
            m.content_type AS content_type
        FROM (
            SELECT DISTINCT manga_id
            FROM favourites
            WHERE deleted_at = 0
        ) f
        INNER JOIN manga m ON m.manga_id = f.manga_id
        """,
    )
    abstract fun observeFavouriteProjectionFacets(): Flow<List<FavouriteProjectionFacetRow>>

    /** Manga↔tag relations of the favourites library. */
    @Query(
        """
        SELECT
            f.manga_id AS entity_id,
            mt.tag_id AS tag_id
        FROM (
            SELECT DISTINCT manga_id
            FROM favourites
            WHERE deleted_at = 0
        ) f
        INNER JOIN manga_tags mt ON mt.manga_id = f.manga_id
        """,
    )
    abstract fun observeFavouriteTagIdRows(): Flow<List<FavouriteTagIdRow>>

    /** Tag identity and titles, once per tag. */
    @Query(
        """
        SELECT
            tag_id AS tag_id,
            title AS tag_title,
            key AS tag_key,
            source AS tag_source
        FROM tags
        """,
    )
    abstract fun observeFavouriteTagDictionary(): Flow<List<FavouriteTagDictionaryRow>>

    /** Downloaded favourite manga via the local download index. */
    @Query(
        """
        SELECT
            f.manga_id AS entity_id,
            li.manga_id AS manga_id
        FROM (
            SELECT DISTINCT manga_id
            FROM favourites
            WHERE deleted_at = 0
        ) f
        INNER JOIN local_index li ON li.manga_id = f.manga_id
        """,
    )
    abstract fun observeDownloadedFavouriteRows(): Flow<List<FavouriteDownloadedRow>>

    /** Title/cover overrides of favourite manga. */
    @Query(
        """
        SELECT
            p.manga_id AS manga_id,
            p.title_override AS title_override,
            p.cover_override AS cover_override
        FROM preferences p
        INNER JOIN (
            SELECT DISTINCT manga_id
            FROM favourites
            WHERE deleted_at = 0
        ) favourite_manga ON favourite_manga.manga_id = p.manga_id
        WHERE p.title_override IS NOT NULL OR p.cover_override IS NOT NULL
        """,
    )
    abstract fun observeFavouriteLegacyOverrides(): Flow<List<FavouriteLegacyOverrideRow>>
}
