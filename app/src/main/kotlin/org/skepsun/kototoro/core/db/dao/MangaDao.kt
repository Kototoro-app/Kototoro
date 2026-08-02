package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaTagsEntity
import org.skepsun.kototoro.core.db.entity.MangaWithTags
import org.skepsun.kototoro.core.db.entity.TagEntity

@Dao
abstract class MangaDao {

	data class MissingContentTypeProjection(
		val id: Long,
		val source: String,
	)

	@Transaction
	@Query("SELECT * FROM manga WHERE manga_id = :id")
	abstract suspend fun find(id: Long): MangaWithTags?

	@Query("SELECT EXISTS(SELECT * FROM manga WHERE manga_id = :id)")
	abstract suspend operator fun contains(id: Long): Boolean

	@Query("SELECT MIN(manga_id) FROM manga")
	abstract suspend fun findMinId(): Long?

	@Transaction
	@Query("SELECT * FROM manga WHERE public_url = :publicUrl")
	abstract suspend fun findByPublicUrl(publicUrl: String): MangaWithTags?

	@Transaction
	@Query("SELECT * FROM manga WHERE source = :source AND url = :url LIMIT 1")
	abstract suspend fun findBySourceAndUrl(source: String, url: String): MangaWithTags?

	@Transaction
	@Query("SELECT * FROM manga WHERE source = :source AND public_url = :publicUrl LIMIT 1")
	abstract suspend fun findBySourceAndPublicUrl(source: String, publicUrl: String): MangaWithTags?

	@Transaction
	@Query("SELECT * FROM manga WHERE source = :source")
	abstract suspend fun findAllBySource(source: String): List<MangaWithTags>

	@Query("SELECT * FROM manga WHERE manga_id IN (:ids)")
	protected abstract suspend fun findEntitiesByIdsImpl(ids: Collection<Long>): List<MangaEntity>

	suspend fun findEntitiesByIds(ids: Collection<Long>): List<MangaEntity> {
		return ids.flatMapSqliteQueryChunks(::findEntitiesByIdsImpl)
	}

	@Transaction
	@Query("SELECT * FROM manga WHERE manga_id IN (:ids)")
	protected abstract suspend fun findWithTagsByIdsImpl(ids: Collection<Long>): List<MangaWithTags>

	suspend fun findWithTagsByIds(ids: Collection<Long>): List<MangaWithTags> {
		return ids.flatMapSqliteQueryChunks(::findWithTagsByIdsImpl)
	}

	@Query(
		"""
		SELECT manga_id AS id, source
		FROM manga
		WHERE content_type IS NULL AND source IN (:sources)
		ORDER BY manga_id
		LIMIT :limit
		""",
	)
	abstract suspend fun findMissingContentTypes(
		sources: Collection<String>,
		limit: Int,
	): List<MissingContentTypeProjection>

	@Query("UPDATE manga SET content_type = :contentType WHERE manga_id = :id AND content_type IS NULL")
	abstract suspend fun setContentTypeIfMissing(id: Long, contentType: String): Int

	@Query("SELECT content_type FROM manga WHERE manga_id = :id")
	protected abstract suspend fun findContentType(id: Long): String?

	@Query("UPDATE manga SET content_type = :contentType WHERE content_type IS NULL AND source IN (:sources)")
	abstract suspend fun setContentTypeIfMissingForSources(
		sources: Collection<String>,
		contentType: String,
	): Int

	@Query("SELECT * FROM manga_tags WHERE manga_id IN (:ids)")
	protected abstract suspend fun findTagRelationsByMangaIdsImpl(ids: Collection<Long>): List<MangaTagsEntity>

	suspend fun findTagRelationsByMangaIds(ids: Collection<Long>): List<MangaTagsEntity> {
		return ids.flatMapSqliteQueryChunks(::findTagRelationsByMangaIdsImpl)
	}

	@Query("SELECT author FROM manga WHERE author LIKE :query GROUP BY author ORDER BY COUNT(author) DESC LIMIT :limit")
	abstract suspend fun findAuthors(query: String, limit: Int): List<String>

    @Query("SELECT author FROM manga WHERE manga.source = :source AND author IS NOT NULL AND author != '' GROUP BY author ORDER BY COUNT(author) DESC LIMIT :limit")
    abstract suspend fun findAuthorsBySource(source: String, limit: Int): List<String>

	@Transaction
	@Query(
		"""
		SELECT * FROM manga
		WHERE (title LIKE :query OR alt_title LIKE :query)
			AND (
				EXISTS(SELECT 1 FROM work_history WHERE work_history.anchor_manga_id = manga.manga_id AND work_history.deleted_at = 0)
				OR EXISTS(SELECT 1 FROM work_favourites WHERE work_favourites.anchor_manga_id = manga.manga_id AND work_favourites.deleted_at = 0)
				OR EXISTS(SELECT 1 FROM work_stats WHERE work_stats.anchor_manga_id = manga.manga_id)
				OR EXISTS(SELECT 1 FROM entity_preferences WHERE entity_preferences.preferred_local_manga_id = manga.manga_id)
				OR EXISTS(
					SELECT 1 FROM entity_binding
					WHERE entity_binding.source IN ('local_manga', '0')
						AND entity_binding.external_id = CAST(manga.manga_id AS TEXT)
						AND entity_binding.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
				)
			)
		LIMIT :limit
		""",
	)
	abstract suspend fun searchByTitle(query: String, limit: Int): List<MangaWithTags>

	@Transaction
	@Query(
		"""
		SELECT * FROM manga
		WHERE (title LIKE :query OR alt_title LIKE :query)
			AND source = :source
			AND (
				EXISTS(SELECT 1 FROM work_history WHERE work_history.anchor_manga_id = manga.manga_id AND work_history.deleted_at = 0)
				OR EXISTS(SELECT 1 FROM work_favourites WHERE work_favourites.anchor_manga_id = manga.manga_id AND work_favourites.deleted_at = 0)
				OR EXISTS(SELECT 1 FROM work_stats WHERE work_stats.anchor_manga_id = manga.manga_id)
				OR EXISTS(SELECT 1 FROM entity_preferences WHERE entity_preferences.preferred_local_manga_id = manga.manga_id)
				OR EXISTS(
					SELECT 1 FROM entity_binding
					WHERE entity_binding.source IN ('local_manga', '0')
						AND entity_binding.external_id = CAST(manga.manga_id AS TEXT)
						AND entity_binding.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
				)
			)
		LIMIT :limit
		""",
	)
	abstract suspend fun searchByTitle(query: String, source: String, limit: Int): List<MangaWithTags>

	@Upsert
	protected abstract suspend fun upsertEntity(manga: MangaEntity)

	@Update(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun update(manga: MangaEntity): Int

	@Query("UPDATE manga SET nsfw = :isNsfw, content_rating = :contentRating WHERE manga_id = :mangaId")
	abstract suspend fun updateContentRating(mangaId: Long, isNsfw: Boolean, contentRating: String?)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertTagRelation(tag: MangaTagsEntity): Long

	@Query("DELETE FROM manga_tags WHERE manga_id = :mangaId")
	abstract suspend fun clearTagRelation(mangaId: Long)

	@Transaction
	@Delete
	abstract suspend fun delete(subjects: Collection<MangaEntity>)

	@Query(
		"""
		DELETE FROM manga
		WHERE NOT EXISTS(SELECT 1 FROM work_history WHERE work_history.anchor_manga_id = manga.manga_id AND work_history.deleted_at = 0)
			AND NOT EXISTS(SELECT 1 FROM work_favourites WHERE work_favourites.anchor_manga_id = manga.manga_id AND work_favourites.deleted_at = 0)
			AND NOT EXISTS(SELECT 1 FROM work_stats WHERE work_stats.anchor_manga_id = manga.manga_id)
			AND NOT EXISTS(SELECT 1 FROM entity_preferences WHERE entity_preferences.preferred_local_manga_id = manga.manga_id)
			AND NOT EXISTS(
				SELECT 1 FROM entity_binding
				WHERE entity_binding.source IN ('local_manga', '0')
					AND entity_binding.external_id = CAST(manga.manga_id AS TEXT)
					AND entity_binding.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
			)
			AND NOT EXISTS(SELECT * FROM bookmarks WHERE bookmarks.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM suggestions WHERE suggestions.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM scrobblings WHERE scrobblings.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM local_index WHERE local_index.manga_id == manga.manga_id)
			AND manga.manga_id NOT IN (:idsToKeep)
		""",
	)
	abstract suspend fun cleanup(idsToKeep: Set<Long>)

	@Query(
		"""
		DELETE FROM manga
		WHERE NOT EXISTS(SELECT 1 FROM work_history WHERE work_history.anchor_manga_id = manga.manga_id AND work_history.deleted_at = 0)
			AND NOT EXISTS(SELECT 1 FROM work_favourites WHERE work_favourites.anchor_manga_id = manga.manga_id AND work_favourites.deleted_at = 0)
			AND NOT EXISTS(SELECT 1 FROM work_stats WHERE work_stats.anchor_manga_id = manga.manga_id)
			AND NOT EXISTS(SELECT 1 FROM entity_preferences WHERE entity_preferences.preferred_local_manga_id = manga.manga_id)
			AND NOT EXISTS(
				SELECT 1 FROM entity_binding
				WHERE entity_binding.source IN ('local_manga', '0')
					AND entity_binding.external_id = CAST(manga.manga_id AS TEXT)
					AND entity_binding.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
			)
			AND NOT EXISTS(SELECT * FROM bookmarks WHERE bookmarks.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM suggestions WHERE suggestions.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM scrobblings WHERE scrobblings.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM local_index WHERE local_index.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM tracks WHERE tracks.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM track_logs WHERE track_logs.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM work_history WHERE work_history.anchor_manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM work_stats WHERE work_stats.anchor_manga_id == manga.manga_id)
			AND NOT EXISTS(
				SELECT * FROM entity_preferences
				WHERE entity_preferences.preferred_local_manga_id == manga.manga_id
			)
			AND NOT EXISTS(
				SELECT * FROM entity_binding
				WHERE entity_binding.source IN ('local_manga', '0')
					AND entity_binding.external_id == CAST(manga.manga_id AS TEXT)
			)
		""",
	)
	abstract suspend fun cleanupSyncResidue(): Int

	@Transaction
	open suspend fun upsert(manga: MangaEntity, tags: Iterable<TagEntity>? = null) {
		val stableManga = if (manga.contentType == null) {
			manga.copy(contentType = findContentType(manga.id))
		} else {
			manga
		}
		upsertEntity(stableManga)
		if (tags != null) {
			clearTagRelation(manga.id)
			tags.map {
				MangaTagsEntity(manga.id, it.id)
			}.forEach {
				insertTagRelation(it)
			}
		}
	}
}
