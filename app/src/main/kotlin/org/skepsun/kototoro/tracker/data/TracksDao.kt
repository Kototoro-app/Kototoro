package org.skepsun.kototoro.tracker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.MangaQueryBuilder
import org.skepsun.kototoro.list.domain.ListFilterOption

@Dao
abstract class TracksDao : MangaQueryBuilder.ConditionCallback {

	@Transaction
	@Query("SELECT * FROM tracks ORDER BY last_check_time ASC LIMIT :limit OFFSET :offset")
	abstract suspend fun findAll(offset: Int, limit: Int): List<TrackWithContent>

	@Transaction
	@Query("SELECT * FROM tracks ORDER BY last_check_time DESC")
	abstract fun observeAll(): Flow<List<TrackWithContent>>

	@Query("SELECT manga_id FROM tracks")
	abstract suspend fun findAllIds(): LongArray

	@Query("SELECT * FROM tracks WHERE manga_id = :mangaId")
	abstract suspend fun find(mangaId: Long): TrackEntity?

	@Query("SELECT IFNULL(chapters_new,0) FROM tracks WHERE manga_id = :mangaId")
	abstract suspend fun findNewChapters(mangaId: Long): Int

	@Query("SELECT manga_id, IFNULL(chapters_new, 0) AS chapters_new FROM tracks WHERE manga_id IN (:mangaIds)")
	abstract suspend fun findNewChapters(mangaIds: List<Long>): List<NewChaptersCountEntry>

	@Query("SELECT COUNT(*) FROM tracks")
	abstract suspend fun getTracksCount(): Int

	@Query("SELECT COUNT(*) FROM tracks WHERE chapters_new > 0")
	abstract fun observeUpdateContentCount(): Flow<Int>

	@Query("SELECT IFNULL(chapters_new, 0) FROM tracks WHERE manga_id = :mangaId")
	abstract fun observeNewChapters(mangaId: Long): Flow<Int>

	@Transaction
	@Query("SELECT * FROM tracks WHERE chapters_new > 0 ORDER BY last_chapter_date DESC")
	abstract fun observeUpdatedContent(): Flow<List<ContentWithTrack>>

	fun observeUpdatedContent(
		limit: Int,
		filterOptions: Set<ListFilterOption>,
	): Flow<List<ContentWithTrack>> = observeContentImpl(
		MangaQueryBuilder("tracks", this)
			.where("chapters_new > 0")
			.filters(filterOptions)
			.limit(limit)
			.orderBy("IFNULL((SELECT MAX(pinned) FROM favourites WHERE favourites.manga_id = tracks.manga_id AND deleted_at = 0), 0) DESC, last_chapter_date DESC")
			.build(),
	)

	@Query("DELETE FROM tracks")
	abstract suspend fun clear()

	@Query("UPDATE tracks SET chapters_new = 0")
	abstract suspend fun clearCounters()

	@Query("UPDATE tracks SET chapters_new = 0 WHERE manga_id = :mangaId")
	abstract suspend fun clearCounter(mangaId: Long)

	@Query("DELETE FROM tracks WHERE manga_id = :mangaId")
	abstract suspend fun delete(mangaId: Long)

	@Query("DELETE FROM tracks WHERE manga_id NOT IN (SELECT manga_id FROM history WHERE history.deleted_at = 0 UNION SELECT manga_id FROM favourites WHERE favourites.deleted_at = 0 AND category_id IN (SELECT category_id FROM favourite_categories WHERE favourite_categories.deleted_at = 0 AND track = 1))")
	abstract suspend fun gc()

	@Upsert
	abstract suspend fun upsert(entity: TrackEntity)

	@Transaction
	@RawQuery(observedEntities = [TrackEntity::class])
	protected abstract fun observeContentImpl(query: SupportSQLiteQuery): Flow<List<ContentWithTrack>>

	override fun getCondition(option: ListFilterOption): String? = when (option) {
		ListFilterOption.Macro.FAVORITE -> "EXISTS(SELECT * FROM favourites WHERE favourites.manga_id = tracks.manga_id)"
		is ListFilterOption.Favorite -> "EXISTS(SELECT * FROM favourites WHERE favourites.manga_id = tracks.manga_id AND favourites.category_id = ${option.category.id})"
		is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags WHERE manga_tags.manga_id = tracks.manga_id AND tag_id = ${option.tagId})"
		ListFilterOption.Macro.NSFW -> "(SELECT nsfw FROM manga WHERE manga.manga_id = tracks.manga_id) = 1"
		else -> null
	}
}
