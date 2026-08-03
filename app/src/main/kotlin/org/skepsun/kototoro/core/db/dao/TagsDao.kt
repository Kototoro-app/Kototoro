package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.entity.TagEntity

@Dao
abstract class TagsDao {

	@Query("SELECT * FROM tags WHERE source = :source")
	abstract suspend fun findTags(source: String): List<TagEntity>

	@Query("SELECT DISTINCT title FROM tags WHERE TRIM(title) != '' ORDER BY title COLLATE NOCASE")
	abstract fun observeAllTitles(): Flow<List<String>>

	@Query("SELECT * FROM tags WHERE tag_id IN (:ids)")
	protected abstract suspend fun findByIdsImpl(ids: Collection<Long>): List<TagEntity>

	suspend fun findByIds(ids: Collection<Long>): List<TagEntity> {
		return ids.flatMapSqliteQueryChunks(::findByIdsImpl)
	}

	@Query(
		"""SELECT tags.* FROM tags
		LEFT JOIN manga_tags ON tags.tag_id = manga_tags.tag_id
		WHERE manga_tags.manga_id IN (
			SELECT anchor_manga_id FROM work_history WHERE deleted_at = 0
			UNION
			SELECT anchor_manga_id FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0
		)
		GROUP BY tags.title 
		ORDER BY COUNT(manga_id) DESC 
		LIMIT :limit""",
	)
	abstract suspend fun findPopularTags(limit: Int): List<TagEntity>

	@Query(
		"""SELECT tags.* FROM tags
		LEFT JOIN manga_tags ON tags.tag_id = manga_tags.tag_id 
		WHERE tags.source = :source  
		GROUP BY tags.title
		ORDER BY COUNT(manga_id) DESC 
		LIMIT :limit""",
	)
	abstract suspend fun findPopularTags(source: String, limit: Int): List<TagEntity>

	@Query(
		"""SELECT tags.* FROM tags
		LEFT JOIN manga_tags ON tags.tag_id = manga_tags.tag_id 
		WHERE tags.source = :source  
		GROUP BY tags.title
		ORDER BY COUNT(manga_id) ASC 
		LIMIT :limit""",
	)
	abstract suspend fun findRareTags(source: String, limit: Int): List<TagEntity>

	@Query(
		"""SELECT tags.* FROM tags
		LEFT JOIN manga_tags ON tags.tag_id = manga_tags.tag_id 
		WHERE tags.source = :source AND title LIKE :query 
		GROUP BY tags.title
		ORDER BY COUNT(manga_id) DESC 
		LIMIT :limit""",
	)
	abstract suspend fun findTags(source: String, query: String, limit: Int): List<TagEntity>

	@Query(
		"""SELECT tags.* FROM tags
		LEFT JOIN manga_tags ON tags.tag_id = manga_tags.tag_id 
		WHERE title LIKE :query
			AND manga_tags.manga_id IN (
				SELECT anchor_manga_id FROM work_history WHERE deleted_at = 0
				UNION
				SELECT anchor_manga_id FROM work_favourites WHERE anchor_manga_id IS NOT NULL AND deleted_at = 0
			)
		GROUP BY tags.title
		ORDER BY COUNT(manga_id) DESC 
		LIMIT :limit""",
	)
	abstract suspend fun findTags(query: String, limit: Int): List<TagEntity>

	@Query(
		"""
		SELECT tags.* FROM manga_tags 
		LEFT JOIN tags ON tags.tag_id = manga_tags.tag_id 
		WHERE manga_tags.manga_id IN (SELECT manga_id FROM manga_tags WHERE tag_id = :tagId)
		GROUP BY tags.tag_id 
		ORDER BY COUNT(manga_id) DESC;
	""",
	)
	abstract suspend fun findRelatedTags(tagId: Long): List<TagEntity>

	@Query(
		"""
		SELECT tags.* FROM manga_tags 
		LEFT JOIN tags ON tags.tag_id = manga_tags.tag_id 
		WHERE manga_tags.manga_id IN (SELECT manga_id FROM manga_tags WHERE tag_id IN (:ids))
		GROUP BY tags.tag_id 
		ORDER BY COUNT(manga_id) DESC;
	""",
	)
	abstract suspend fun findRelatedTags(ids: Set<Long>): List<TagEntity>

	@Upsert
	abstract suspend fun upsert(tags: Iterable<TagEntity>)
}
