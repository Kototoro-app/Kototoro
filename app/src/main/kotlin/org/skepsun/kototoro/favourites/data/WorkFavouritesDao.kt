package org.skepsun.kototoro.favourites.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.paging.PagingSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.skepsun.kototoro.core.db.entity.TagEntity

private const val FULL_LIST_PAGE_SIZE = 500

@Dao
abstract class WorkFavouritesDao {

    @Query(
        """
		SELECT
			m.manga_id AS manga_id,
			wf.category_id AS category_id,
			m.source AS source,
			m.nsfw AS nsfw
		FROM work_favourites wf
		INNER JOIN manga m ON m.manga_id = wf.anchor_manga_id
		WHERE wf.anchor_manga_id IS NOT NULL
			AND wf.deleted_at = 0
        """,
    )
    abstract fun observeCategoryCountEntries(): Flow<List<FavouriteCategoryCountEntry>>

    @Query(
        """
		WITH selected AS (
			SELECT wf.*
			FROM work_favourites wf
			WHERE wf.anchor_manga_id IS NOT NULL
				AND wf.deleted_at = 0
				AND (:categoryId = -1 OR wf.category_id = :categoryId)
				AND wf.category_id = (
					SELECT wf2.category_id
					FROM work_favourites wf2
					WHERE wf2.entity_id = wf.entity_id
						AND wf2.anchor_manga_id IS NOT NULL
						AND wf2.deleted_at = 0
						AND (:categoryId = -1 OR wf2.category_id = :categoryId)
					ORDER BY wf2.pinned DESC, wf2.created_at DESC, wf2.updated_at DESC, wf2.category_id ASC
					LIMIT 1
				)
		)
		SELECT
			selected.*,
			ep.preferred_local_manga_id AS preferred_local_manga_id,
			m.manga_id AS display_manga_id,
			m.title AS display_title,
			m.alt_title AS display_alt_title,
			m.url AS display_url,
			m.public_url AS display_public_url,
			m.rating AS display_rating,
			m.nsfw AS display_nsfw,
			m.content_rating AS display_content_rating,
			m.cover_url AS display_cover_url,
			m.large_cover_url AS display_large_cover_url,
			m.state AS display_state,
			m.author AS display_author,
			m.source AS display_source,
			m.description AS display_description,
			m.content_type AS display_content_type,
			m.source_data AS display_source_data,
			wh.entity_id AS history_entity_id,
			wh.anchor_manga_id AS history_anchor_manga_id,
			wh.created_at AS history_created_at,
			wh.updated_at AS history_updated_at,
			wh.chapter_id AS history_chapter_id,
			wh.page AS history_page,
			wh.scroll AS history_scroll,
			wh.percent AS history_percent,
			wh.deleted_at AS history_deleted_at,
			wh.chapters AS history_chapters,
			wh.parent_chapter_id AS history_parent_chapter_id,
			tracking.anchor_manga_id AS tracking_anchor_manga_id,
			tracking.last_chapter_id AS tracking_last_chapter_id,
			tracking.new_chapters AS tracking_new_chapters,
			tracking.last_check_time AS tracking_last_check_time,
			tracking.last_chapter_date AS tracking_last_chapter_date
		FROM selected
		LEFT JOIN entity_preferences ep ON ep.entity_id = selected.entity_id
		LEFT JOIN manga m ON m.manga_id = COALESCE(ep.preferred_local_manga_id, selected.anchor_manga_id)
		LEFT JOIN work_history wh ON wh.entity_id = selected.entity_id AND wh.deleted_at = 0
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
		) tracking ON tracking.entity_id = selected.entity_id
		WHERE (:applySpaceFilter = 0 OR (
			EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga sm ON sm.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = selected.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND sm.content_type IN (:allowedTypes)
					AND (:applySourceFilter = 0 OR sm.source IN (:allowedSources))
			)
			AND NOT EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga sm ON sm.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = selected.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND sm.content_type IN (:classifiedTypes)
					AND sm.content_type NOT IN (:allowedTypes)
			)
		))
			AND (:applyContentTypeFilter = 0 OR m.content_type IS NULL OR m.content_type IN (:contentTypes))
			AND (:applyPublicationStateFilter = 0 OR m.state IN (:publicationStates))
			AND (:nsfwMode = -1 OR m.nsfw = :nsfwMode)
			AND (:requireDownloaded = 0 OR EXISTS (
				SELECT 1 FROM local_index li WHERE li.manga_id = m.manga_id
			))
			AND (:requireNewChapters = 0 OR COALESCE(tracking.new_chapters, 0) > 0)
			AND (:applyExactSourceFilter = 0 OR m.source IN (:exactSources) OR EXISTS (
				SELECT 1 FROM entity_binding source_binding
				INNER JOIN manga source_manga ON source_manga.manga_id = CAST(source_binding.external_id AS INTEGER)
				WHERE source_binding.entity_id = selected.entity_id
					AND source_binding.source IN ('local_manga', '0')
					AND source_binding.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND source_manga.source IN (:exactSources)
			))
			AND (:applyTagFilter = 0 OR EXISTS (
				SELECT 1 FROM manga_tags display_tags
				WHERE display_tags.manga_id = m.manga_id AND display_tags.tag_id IN (:tagIds)
			) OR EXISTS (
				SELECT 1 FROM entity_binding tag_binding
				INNER JOIN manga_tags projection_tags
					ON projection_tags.manga_id = CAST(tag_binding.external_id AS INTEGER)
				WHERE tag_binding.entity_id = selected.entity_id
					AND tag_binding.source IN ('local_manga', '0')
					AND tag_binding.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND projection_tags.tag_id IN (:tagIds)
			))
		ORDER BY
			selected.pinned DESC,
			CASE WHEN :orderName = 'RATING' THEN m.rating END DESC,
			CASE WHEN :orderName = 'NEWEST' THEN selected.created_at END DESC,
			CASE WHEN :orderName = 'OLDEST' THEN selected.created_at END ASC,
			CASE WHEN :orderName = 'PROGRESS' THEN wh.percent END DESC,
			CASE WHEN :orderName = 'UNREAD' THEN wh.percent END ASC,
			CASE WHEN :orderName = 'LAST_READ' THEN wh.updated_at END DESC,
			CASE WHEN :orderName = 'LONG_AGO_READ' THEN wh.updated_at END ASC,
			CASE WHEN :orderName = 'NEW_CHAPTERS' THEN tracking.new_chapters END DESC,
			CASE WHEN :orderName IN ('NEW_CHAPTERS', 'UPDATED') THEN tracking.last_chapter_date END DESC,
			CASE WHEN :orderName = 'ALPHABETIC' THEN m.title END COLLATE NOCASE ASC,
			CASE WHEN :orderName = 'ALPHABETIC_REVERSE' THEN m.title END COLLATE NOCASE DESC,
			CASE WHEN :orderName NOT IN (
				'RATING', 'NEWEST', 'OLDEST', 'PROGRESS', 'UNREAD', 'LAST_READ', 'LONG_AGO_READ',
				'NEW_CHAPTERS', 'UPDATED', 'ALPHABETIC', 'ALPHABETIC_REVERSE'
			) THEN selected.updated_at END DESC,
			selected.entity_id ASC
        """,
    )
    abstract fun pagingSource(
        categoryId: Long,
        orderName: String,
        applySpaceFilter: Boolean,
        allowedTypes: Collection<String>,
        classifiedTypes: Collection<String>,
        applySourceFilter: Boolean,
        allowedSources: Collection<String>,
        applyContentTypeFilter: Boolean,
        contentTypes: Collection<String>,
        applyPublicationStateFilter: Boolean,
        publicationStates: Collection<String>,
        nsfwMode: Int,
        requireDownloaded: Boolean,
        requireNewChapters: Boolean,
        applyExactSourceFilter: Boolean,
        exactSources: Collection<String>,
        applyTagFilter: Boolean,
        tagIds: Collection<Long>,
    ): PagingSource<Int, FavouriteLibraryPagingRow>

    suspend fun findList(
        categoryId: Long,
        orderName: String,
        applySpaceFilter: Boolean,
        allowedTypes: Collection<String>,
        classifiedTypes: Collection<String>,
        applySourceFilter: Boolean,
        allowedSources: Collection<String>,
        applyContentTypeFilter: Boolean,
        contentTypes: Collection<String>,
        applyPublicationStateFilter: Boolean,
        publicationStates: Collection<String>,
        nsfwMode: Int,
        requireDownloaded: Boolean,
        requireNewChapters: Boolean,
        applyExactSourceFilter: Boolean,
        exactSources: Collection<String>,
        applyTagFilter: Boolean,
        tagIds: Collection<Long>,
    ): List<WorkFavouriteEntity> {
        val source = pagingSource(
            categoryId = categoryId,
            orderName = orderName,
            applySpaceFilter = applySpaceFilter,
            allowedTypes = allowedTypes,
            classifiedTypes = classifiedTypes,
            applySourceFilter = applySourceFilter,
            allowedSources = allowedSources,
            applyContentTypeFilter = applyContentTypeFilter,
            contentTypes = contentTypes,
            applyPublicationStateFilter = applyPublicationStateFilter,
            publicationStates = publicationStates,
            nsfwMode = nsfwMode,
            requireDownloaded = requireDownloaded,
            requireNewChapters = requireNewChapters,
            applyExactSourceFilter = applyExactSourceFilter,
            exactSources = exactSources,
            applyTagFilter = applyTagFilter,
            tagIds = tagIds,
        )
        val result = ArrayList<WorkFavouriteEntity>()
        var nextKey: Int? = null
        var isRefresh = true
        do {
            val params = if (isRefresh) {
                PagingSource.LoadParams.Refresh(nextKey, FULL_LIST_PAGE_SIZE, false)
            } else {
                PagingSource.LoadParams.Append(requireNotNull(nextKey), FULL_LIST_PAGE_SIZE, false)
            }
            when (val loaded = source.load(params)) {
                is PagingSource.LoadResult.Page -> {
                    result += loaded.data.map(FavouriteLibraryPagingRow::favourite)
                    nextKey = loaded.nextKey
                }
                is PagingSource.LoadResult.Error -> throw loaded.throwable
                is PagingSource.LoadResult.Invalid -> error("Favourite query invalidated while collecting the full list")
            }
            isRefresh = false
        } while (nextKey != null)
        return result
    }

    @Query(
        """
		SELECT wf.*
		FROM work_favourites wf
		WHERE wf.anchor_manga_id IS NOT NULL
			AND wf.deleted_at = 0
			AND (:categoryId = -1 OR wf.category_id = :categoryId)
			AND NOT EXISTS (
				SELECT 1
				FROM work_favourites candidate
				WHERE candidate.entity_id = wf.entity_id
					AND candidate.anchor_manga_id IS NOT NULL
					AND candidate.deleted_at = 0
					AND (:categoryId = -1 OR candidate.category_id = :categoryId)
					AND (
						candidate.pinned > wf.pinned
						OR (candidate.pinned = wf.pinned AND candidate.created_at > wf.created_at)
						OR (candidate.pinned = wf.pinned AND candidate.created_at = wf.created_at
							AND candidate.updated_at > wf.updated_at)
						OR (candidate.pinned = wf.pinned AND candidate.created_at = wf.created_at
							AND candidate.updated_at = wf.updated_at AND candidate.category_id < wf.category_id)
					)
			)
		ORDER BY wf.pinned DESC, wf.updated_at DESC, wf.entity_id ASC
        """,
    )
    abstract suspend fun findListRepresentatives(categoryId: Long): List<WorkFavouriteEntity>

    @Query(
        """
		SELECT wf.*, ep.preferred_local_manga_id
		FROM work_favourites wf
		LEFT JOIN entity_preferences ep ON ep.entity_id = wf.entity_id
		WHERE wf.anchor_manga_id IS NOT NULL
			AND wf.deleted_at = 0
			AND (:categoryId = -1 OR wf.category_id = :categoryId)
			AND NOT EXISTS (
				SELECT 1
				FROM work_favourites candidate
				WHERE candidate.entity_id = wf.entity_id
					AND candidate.anchor_manga_id IS NOT NULL
					AND candidate.deleted_at = 0
					AND (:categoryId = -1 OR candidate.category_id = :categoryId)
					AND (
						candidate.pinned > wf.pinned
						OR (candidate.pinned = wf.pinned AND candidate.created_at > wf.created_at)
						OR (candidate.pinned = wf.pinned AND candidate.created_at = wf.created_at
							AND candidate.updated_at > wf.updated_at)
						OR (candidate.pinned = wf.pinned AND candidate.created_at = wf.created_at
							AND candidate.updated_at = wf.updated_at AND candidate.category_id < wf.category_id)
					)
			)
		ORDER BY wf.pinned DESC, wf.updated_at DESC, wf.entity_id ASC
        """,
    )
    abstract suspend fun findLibraryRepresentatives(categoryId: Long): List<FavouriteLibraryRepresentative>

    @Query(
        """
		WITH favorite_projections AS (
			SELECT wf.entity_id, wf.anchor_manga_id AS manga_id
			FROM work_favourites wf
			WHERE wf.anchor_manga_id IS NOT NULL
				AND wf.deleted_at = 0
				AND (:categoryId = -1 OR wf.category_id = :categoryId)
			UNION
			SELECT wf.entity_id, CAST(eb.external_id AS INTEGER) AS manga_id
			FROM work_favourites wf
			INNER JOIN entity_binding eb ON eb.entity_id = wf.entity_id
			WHERE wf.anchor_manga_id IS NOT NULL
				AND wf.deleted_at = 0
				AND (:categoryId = -1 OR wf.category_id = :categoryId)
				AND eb.source IN ('local_manga', '0')
				AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
		)
		SELECT m.source
		FROM favorite_projections fp
		INNER JOIN manga m ON m.manga_id = fp.manga_id
		GROUP BY m.source
		ORDER BY COUNT(DISTINCT fp.entity_id) DESC, m.source ASC
        """,
    )
    abstract suspend fun findQuickFilterSourceNames(categoryId: Long): List<String>

    @Query(
        """
		WITH favorite_projections AS (
			SELECT wf.entity_id, wf.anchor_manga_id AS manga_id
			FROM work_favourites wf
			WHERE wf.anchor_manga_id IS NOT NULL
				AND wf.deleted_at = 0
				AND (:categoryId = -1 OR wf.category_id = :categoryId)
			UNION
			SELECT wf.entity_id, CAST(eb.external_id AS INTEGER) AS manga_id
			FROM work_favourites wf
			INNER JOIN entity_binding eb ON eb.entity_id = wf.entity_id
			WHERE wf.anchor_manga_id IS NOT NULL
				AND wf.deleted_at = 0
				AND (:categoryId = -1 OR wf.category_id = :categoryId)
				AND eb.source IN ('local_manga', '0')
				AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
		)
		SELECT tags.*
		FROM favorite_projections fp
		INNER JOIN manga_tags mt ON mt.manga_id = fp.manga_id
		INNER JOIN tags ON tags.tag_id = mt.tag_id
		GROUP BY tags.tag_id
		ORDER BY COUNT(DISTINCT fp.entity_id) DESC, tags.title COLLATE NOCASE ASC
		LIMIT :limit
        """,
    )
    abstract suspend fun findQuickFilterTags(categoryId: Long, limit: Int): List<TagEntity>

    @Query(
        """
		SELECT wf.* FROM work_favourites wf
		WHERE wf.anchor_manga_id IS NOT NULL AND wf.deleted_at = 0
			AND (:categoryId IS NULL OR wf.category_id = :categoryId)
			AND EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wf.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND m.content_type IN (:allowedTypes)
			)
			AND NOT EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wf.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND m.content_type IN (:classifiedTypes)
					AND m.content_type NOT IN (:allowedTypes)
			)
		ORDER BY
			CASE WHEN :oldestFirst = 1 THEN wf.created_at END ASC,
			CASE WHEN :oldestFirst = 0 THEN wf.created_at END DESC,
			wf.updated_at DESC
		LIMIT :limit
        """,
    )
    abstract suspend fun findActiveForSpace(
        categoryId: Long?,
        allowedTypes: Collection<String>,
        classifiedTypes: Collection<String>,
        oldestFirst: Boolean,
        limit: Int,
    ): List<WorkFavouriteEntity>

    @Query(
        """
		SELECT wf.* FROM work_favourites wf
		WHERE wf.anchor_manga_id IS NOT NULL AND wf.deleted_at = 0
			AND (:categoryId IS NULL OR wf.category_id = :categoryId)
			AND EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wf.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND m.content_type IN (:allowedTypes)
					AND m.source IN (:allowedSources)
			)
			AND NOT EXISTS (
				SELECT 1 FROM entity_binding eb
				INNER JOIN manga m ON m.manga_id = CAST(eb.external_id AS INTEGER)
				WHERE eb.entity_id = wf.entity_id
					AND eb.source IN ('local_manga', '0')
					AND eb.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND m.content_type IN (:classifiedTypes)
					AND m.content_type NOT IN (:allowedTypes)
			)
		ORDER BY
			CASE WHEN :oldestFirst = 1 THEN wf.created_at END ASC,
			CASE WHEN :oldestFirst = 0 THEN wf.created_at END DESC,
			wf.updated_at DESC
		LIMIT :limit
        """,
    )
    abstract suspend fun findActiveForSpaceAndSources(
        categoryId: Long?,
        allowedTypes: Collection<String>,
        classifiedTypes: Collection<String>,
        allowedSources: Collection<String>,
        oldestFirst: Boolean,
        limit: Int,
    ): List<WorkFavouriteEntity>

    @Query("SELECT DISTINCT category_id FROM work_favourites WHERE entity_id = :entityId AND anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY created_at ASC")
    abstract suspend fun findCategoriesIds(entityId: Long): List<Long>

    @Query(
        """
		SELECT entity_id AS entityId, category_id AS categoryId
		FROM work_favourites
		WHERE entity_id IN (:entityIds)
			AND anchor_manga_id IS NOT NULL
			AND deleted_at = 0
        """,
    )
    abstract suspend fun findCategoryMemberships(entityIds: List<Long>): List<WorkFavouriteCategoryMembership>

    @Query("SELECT * FROM work_favourites WHERE entity_id = :entityId AND category_id = :categoryId LIMIT 1")
    abstract suspend fun find(entityId: Long, categoryId: Long): WorkFavouriteEntity?

    @Query("SELECT COUNT(category_id) FROM work_favourites WHERE entity_id = :entityId AND anchor_manga_id IS NOT NULL AND deleted_at = 0")
    abstract suspend fun findCategoriesCount(entityId: Long): Int

    @Query("SELECT COUNT(*) FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0")
    abstract suspend fun countActive(): Int

    @Query(
        """
		SELECT COUNT(*)
		FROM work_favourites wf
		LEFT JOIN `entity` e ON e.id = wf.entity_id
		WHERE e.id IS NULL
        """,
    )
    abstract suspend fun countDanglingEntityRefs(): Int

    @Query(
        """
		SELECT COUNT(*)
		FROM work_favourites wf
		LEFT JOIN favourite_categories fc
			ON fc.category_id = wf.category_id
			AND fc.deleted_at = 0
		WHERE wf.anchor_manga_id IS NOT NULL
			AND wf.deleted_at = 0
			AND fc.category_id IS NULL
        """,
    )
    abstract suspend fun countActiveDanglingCategoryRefs(): Int

    @Query("SELECT COUNT(DISTINCT entity_id) FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0")
    abstract suspend fun countActiveWorks(): Int

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY updated_at DESC")
    abstract suspend fun findActive(): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NULL AND deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit")
    abstract suspend fun findActiveWithoutAnchor(limit: Int): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE entity_id = :entityId AND anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY updated_at DESC LIMIT 1")
    abstract suspend fun findActiveForEntity(entityId: Long): WorkFavouriteEntity?

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 AND category_id = :categoryId ORDER BY updated_at DESC")
    abstract suspend fun findActive(categoryId: Long): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY updated_at DESC LIMIT :limit")
    abstract suspend fun findActiveUpdated(limit: Int): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 AND category_id = :categoryId ORDER BY updated_at DESC LIMIT :limit")
    abstract suspend fun findActiveUpdated(categoryId: Long, limit: Int): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY created_at DESC LIMIT :limit")
    abstract suspend fun findActiveNewest(limit: Int): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 AND category_id = :categoryId ORDER BY created_at DESC LIMIT :limit")
    abstract suspend fun findActiveNewest(categoryId: Long, limit: Int): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 ORDER BY created_at ASC LIMIT :limit")
    abstract suspend fun findActiveOldest(limit: Int): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0 AND category_id = :categoryId ORDER BY created_at ASC LIMIT :limit")
    abstract suspend fun findActiveOldest(categoryId: Long, limit: Int): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE anchor_manga_id = :anchorMangaId AND deleted_at = 0 ORDER BY updated_at DESC")
    abstract suspend fun findActiveByAnchorMangaId(anchorMangaId: Long): List<WorkFavouriteEntity>

    @Query("SELECT DISTINCT anchor_manga_id FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0")
    abstract suspend fun findActiveAnchorMangaIds(): List<Long>

    @Query("SELECT MAX(pinned) FROM work_favourites WHERE entity_id IN (:entityIds) AND anchor_manga_id IS NOT NULL AND deleted_at = 0")
    abstract suspend fun isPinned(entityIds: List<Long>): Boolean?

    @Query("SELECT DISTINCT entity_id FROM work_favourites WHERE entity_id IN (:entityIds) AND anchor_manga_id IS NOT NULL AND pinned = 1 AND deleted_at = 0")
    abstract suspend fun findPinnedEntityIds(entityIds: List<Long>): List<Long>

    @Query(
        """
		SELECT DISTINCT wf.entity_id
		FROM work_favourites wf
		INNER JOIN favourite_categories fc ON fc.category_id = wf.category_id
		WHERE wf.deleted_at = 0
			AND wf.anchor_manga_id IS NOT NULL
			AND fc.deleted_at = 0
			AND fc.track = 1
        """,
    )
    abstract suspend fun findTrackedEntityIds(): List<Long>

    @Upsert
    abstract suspend fun upsert(entity: WorkFavouriteEntity)

    @Query("UPDATE work_favourites SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE entity_id = :entityId")
    abstract suspend fun setDeletedAt(entityId: Long, deletedAt: Long, updatedAt: Long)

    @Query("UPDATE work_favourites SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE entity_id = :entityId AND category_id = :categoryId")
    abstract suspend fun setDeletedAt(entityId: Long, categoryId: Long, deletedAt: Long, updatedAt: Long)

    @Query("UPDATE work_favourites SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE category_id = :categoryId AND deleted_at = 0")
    abstract suspend fun setDeletedAtAll(categoryId: Long, deletedAt: Long, updatedAt: Long)

    @Query(
        """
		UPDATE work_favourites
		SET anchor_manga_id = :newAnchorMangaId,
			updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id = :oldAnchorMangaId
        """,
    )
    abstract suspend fun replaceAnchorMangaId(
        entityId: Long,
        oldAnchorMangaId: Long,
        newAnchorMangaId: Long,
        updatedAt: Long,
    )

    @Query(
        """
		UPDATE work_favourites
		SET anchor_manga_id = :anchorMangaId,
			updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id IS NULL
			AND deleted_at = 0
        """,
    )
    abstract suspend fun fillMissingAnchorMangaId(
        entityId: Long,
        anchorMangaId: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
		UPDATE work_favourites
		SET deleted_at = :updatedAt,
			updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id IS NULL
			AND deleted_at = 0
        """,
    )
    abstract suspend fun deactivateActiveWithoutAnchor(
        entityId: Long,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM work_favourites")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM work_favourites WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
    abstract suspend fun gc(maxDeletionTime: Long)

    @Query("UPDATE work_favourites SET entity_id = :newEntityId WHERE entity_id = :oldEntityId")
    protected abstract suspend fun remapEntityIdRaw(oldEntityId: Long, newEntityId: Long)

    @Query("SELECT * FROM work_favourites WHERE entity_id = :entityId")
    protected abstract suspend fun findAllForEntity(entityId: Long): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE entity_id = :entityId AND anchor_manga_id = :anchorMangaId")
    protected abstract suspend fun findAllForEntityAndAnchor(entityId: Long, anchorMangaId: Long): List<WorkFavouriteEntity>

    @Query("DELETE FROM work_favourites WHERE entity_id = :entityId AND category_id = :categoryId")
    protected abstract suspend fun deleteRow(entityId: Long, categoryId: Long)

    /**
     * Move every row from [oldEntityId] to [newEntityId]. When a target
     * `(newEntityId, category_id)` row already exists the two are merged via
     * [mergeRestoredWorkFavourites] instead of letting the bulk UPDATE hit the
     * `(entity_id, category_id)` primary-key constraint (the restore crash that
     * surfaced as SQLITE_CONSTRAINT_PRIMARYKEY on work_favourites).
     */
    @Transaction
    open suspend fun remapEntityId(oldEntityId: Long, newEntityId: Long) {
        if (oldEntityId == newEntityId) return
        val sources = findAllForEntity(oldEntityId)
        if (sources.isEmpty()) return
        val targetsByCategory = findAllForEntity(newEntityId).associateBy { it.categoryId }
        if (targetsByCategory.isEmpty()) {
            remapEntityIdRaw(oldEntityId, newEntityId)
            return
        }
        for (source in sources) {
            val moved = source.copy(entityId = newEntityId)
            val target = targetsByCategory[source.categoryId]
            if (target == null) {
                deleteRow(oldEntityId, source.categoryId)
                upsert(moved)
            } else {
                deleteRow(oldEntityId, source.categoryId)
                upsert(mergeRestoredWorkFavourites(target, moved))
            }
        }
    }

    @Transaction
    open suspend fun moveAnchorToEntity(oldEntityId: Long, newEntityId: Long, anchorMangaId: Long): Int {
        if (oldEntityId == newEntityId) return 0
        val sources = findAllForEntityAndAnchor(oldEntityId, anchorMangaId)
        if (sources.isEmpty()) return 0
        val targetsByCategory = findAllForEntity(newEntityId).associateBy { it.categoryId }
        for (source in sources) {
            val moved = source.copy(entityId = newEntityId)
            val target = targetsByCategory[source.categoryId]
            deleteRow(oldEntityId, source.categoryId)
            if (target == null) {
                upsert(moved)
            } else {
                upsert(mergeRestoredWorkFavourites(target, moved))
            }
        }
        return sources.size
    }

    @Transaction
    open suspend fun copyActiveCategoriesToEntity(
        oldEntityId: Long,
        newEntityId: Long,
        anchorMangaId: Long,
    ) {
        if (oldEntityId == newEntityId) return
        val now = System.currentTimeMillis()
        val sources = findAllForEntity(oldEntityId)
            .filter { it.anchorMangaId != null && it.deletedAt == 0L }
        if (sources.isEmpty()) return
        val targetsByCategory = findAllForEntity(newEntityId).associateBy { it.categoryId }
        for (source in sources) {
            val copied = source.copy(
                entityId = newEntityId,
                anchorMangaId = anchorMangaId,
                updatedAt = now,
            )
            val target = targetsByCategory[source.categoryId]
            if (target == null) {
                upsert(copied)
            } else {
                upsert(mergeRestoredWorkFavourites(target, copied))
            }
        }
    }

    @Query("UPDATE work_favourites SET pinned = :isPinned WHERE entity_id IN (:entityIds)")
    abstract suspend fun setPinned(entityIds: List<Long>, isPinned: Boolean)

    suspend fun delete(entityId: Long) {
        val currentTime = System.currentTimeMillis()
        setDeletedAt(entityId = entityId, deletedAt = currentTime, updatedAt = currentTime)
    }

    suspend fun delete(entityId: Long, categoryId: Long) {
        val currentTime = System.currentTimeMillis()
        setDeletedAt(entityId = entityId, categoryId = categoryId, deletedAt = currentTime, updatedAt = currentTime)
    }

    suspend fun deleteAll(categoryId: Long) {
        val currentTime = System.currentTimeMillis()
        setDeletedAtAll(categoryId = categoryId, deletedAt = currentTime, updatedAt = currentTime)
    }

    suspend fun recover(entityId: Long) {
        val currentTime = System.currentTimeMillis()
        recoverAt(entityId = entityId, updatedAt = currentTime)
    }

    suspend fun recover(entityId: Long, categoryId: Long) {
        val currentTime = System.currentTimeMillis()
        recoverAt(entityId = entityId, categoryId = categoryId, updatedAt = currentTime)
    }

    @Query(
        """
		UPDATE work_favourites
		SET deleted_at = 0, updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND anchor_manga_id IS NOT NULL
        """,
    )
    protected abstract suspend fun recoverAt(entityId: Long, updatedAt: Long)

    @Query(
        """
		UPDATE work_favourites
		SET deleted_at = 0, updated_at = :updatedAt
		WHERE entity_id = :entityId
			AND category_id = :categoryId
			AND anchor_manga_id IS NOT NULL
        """,
    )
    protected abstract suspend fun recoverAt(entityId: Long, categoryId: Long, updatedAt: Long)

    @Query(
        """
		SELECT wf.*
		FROM work_favourites wf
		LEFT JOIN favourite_categories fc
			ON fc.category_id = wf.category_id
			AND fc.deleted_at = 0
		WHERE wf.anchor_manga_id IS NOT NULL
			AND wf.deleted_at = 0
			AND fc.category_id IS NULL
		ORDER BY wf.updated_at DESC
        """,
    )
    protected abstract suspend fun findActiveWithDanglingCategory(): List<WorkFavouriteEntity>

    @Query("SELECT * FROM work_favourites WHERE entity_id = :entityId AND category_id = :categoryId LIMIT 1")
    protected abstract suspend fun findIncludingDeleted(entityId: Long, categoryId: Long): WorkFavouriteEntity?

    @Transaction
    open suspend fun repairActiveDanglingCategoryRefs(targetCategoryId: Long): Int {
        val sources = findActiveWithDanglingCategory()
        for (source in sources) {
            val moved = source.copy(categoryId = targetCategoryId)
            val target = findIncludingDeleted(source.entityId, targetCategoryId)
            deleteRow(source.entityId, source.categoryId)
            if (target == null) {
                upsert(moved)
            } else {
                upsert(mergeRestoredWorkFavourites(target, moved))
            }
        }
        return sources.size
    }

    @Query("SELECT * FROM work_favourites ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    protected abstract suspend fun findAll(offset: Int, limit: Int): List<WorkFavouriteEntity>

    fun dump(): Flow<WorkFavouriteEntity> = flow {
        val window = 10
        var offset = 0
        while (currentCoroutineContext().isActive) {
            val list = findAll(offset, window)
            if (list.isEmpty()) {
                break
            }
            offset += window
            list.forEach { emit(it) }
        }
    }

    @Query(
        """
		SELECT wf.*
		FROM work_favourites wf
		INNER JOIN favourite_categories fc ON fc.category_id = wf.category_id
		WHERE fc.deleted_at = 0
		ORDER BY wf.updated_at DESC
		LIMIT :limit OFFSET :offset
        """,
    )
    protected abstract suspend fun findAllWithActiveCategory(offset: Int, limit: Int): List<WorkFavouriteEntity>

    fun dumpWithActiveCategories(): Flow<WorkFavouriteEntity> = flow {
        val window = 10
        var offset = 0
        while (currentCoroutineContext().isActive) {
            val list = findAllWithActiveCategory(offset, window)
            if (list.isEmpty()) {
                break
            }
            offset += window
            list.forEach { emit(it) }
        }
    }
}
