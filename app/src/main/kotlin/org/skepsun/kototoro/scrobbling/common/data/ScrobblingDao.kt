package org.skepsun.kototoro.scrobbling.common.data

import androidx.room.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

@Dao
abstract class ScrobblingDao {

	private companion object {
		const val PREFERRED_ORDER = """
			CASE WHEN entity_id IS NOT NULL THEN 0 ELSE 1 END,
			CASE WHEN manga_id != 0 THEN 0 ELSE 1 END,
			CASE WHEN rating > 0 THEN 0 ELSE 1 END,
			CASE WHEN comment IS NOT NULL AND comment != '' THEN 0 ELSE 1 END,
			CASE WHEN chapter > 0 THEN 0 ELSE 1 END,
			CASE WHEN media_type != '' THEN 0 ELSE 1 END,
			CASE
				WHEN (remote_title IS NOT NULL AND remote_title != '')
					OR (remote_cover_url IS NOT NULL AND remote_cover_url != '')
					OR (remote_url IS NOT NULL AND remote_url != '')
				THEN 0 ELSE 1
			END,
			manga_id DESC,
			id DESC
		"""
	}

	@Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId")
	abstract suspend fun findByLocalManga(scrobbler: Int, mangaId: Long): ScrobblingEntity?

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND entity_id = :entityId
		ORDER BY $PREFERRED_ORDER
		LIMIT 1
		""",
	)
	abstract suspend fun findByEntity(scrobbler: Int, entityId: Long): ScrobblingEntity?

	@Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId")
	abstract fun observeByLocalManga(scrobbler: Int, mangaId: Long): Flow<ScrobblingEntity?>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND entity_id = :entityId
		ORDER BY $PREFERRED_ORDER
		LIMIT 1
		""",
	)
	abstract fun observeByEntity(scrobbler: Int, entityId: Long): Flow<ScrobblingEntity?>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler
			AND entity_id = :entityId
			AND target_id = :targetId
			AND media_type = :mediaType
		ORDER BY $PREFERRED_ORDER
		LIMIT 1
		""",
	)
	abstract suspend fun findPreferredByEntityTargetAndMediaType(
		scrobbler: Int,
		entityId: Long,
		targetId: Long,
		mediaType: String,
	): ScrobblingEntity?

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler
			AND entity_id = :entityId
			AND target_id = :targetId
			AND media_type = :mediaType
		ORDER BY $PREFERRED_ORDER
		LIMIT 1
		""",
	)
	abstract fun observePreferredByEntityTargetAndMediaType(
		scrobbler: Int,
		entityId: Long,
		targetId: Long,
		mediaType: String,
	): Flow<ScrobblingEntity?>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND manga_id IN (:mangaIds)
		ORDER BY $PREFERRED_ORDER
		""",
	)
	abstract suspend fun findByLocalMangaIds(scrobbler: Int, mangaIds: List<Long>): List<ScrobblingEntity>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND entity_id IN (:entityIds)
		ORDER BY $PREFERRED_ORDER
		""",
	)
	abstract suspend fun findByEntityIds(scrobbler: Int, entityIds: List<Long>): List<ScrobblingEntity>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND manga_id IN (:mangaIds)
		ORDER BY $PREFERRED_ORDER
		""",
	)
	abstract fun observeByLocalMangaIds(scrobbler: Int, mangaIds: List<Long>): Flow<List<ScrobblingEntity>>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND entity_id IN (:entityIds)
		ORDER BY $PREFERRED_ORDER
		""",
	)
	abstract fun observeByEntityIds(scrobbler: Int, entityIds: List<Long>): Flow<List<ScrobblingEntity>>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND target_id = :targetId
		ORDER BY $PREFERRED_ORDER
		LIMIT 1
		""",
	)
	abstract suspend fun findByTargetId(scrobbler: Int, targetId: Long): ScrobblingEntity?

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND target_id = :targetId
		ORDER BY $PREFERRED_ORDER
		LIMIT 1
		""",
	)
	abstract fun observeByTargetId(scrobbler: Int, targetId: Long): Flow<ScrobblingEntity?>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler
			AND target_id = :targetId
			AND media_type = :mediaType
		ORDER BY $PREFERRED_ORDER
		LIMIT 1
		""",
	)
	abstract suspend fun findByTargetIdAndMediaType(
		scrobbler: Int,
		targetId: Long,
		mediaType: String,
	): ScrobblingEntity?

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND target_id = :targetId
		ORDER BY $PREFERRED_ORDER
		""",
	)
	abstract suspend fun findAllByTargetId(scrobbler: Int, targetId: Long): List<ScrobblingEntity>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler AND target_id = :targetId
		ORDER BY $PREFERRED_ORDER
		""",
	)
	abstract fun observeAllByTargetId(scrobbler: Int, targetId: Long): Flow<List<ScrobblingEntity>>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler
		ORDER BY $PREFERRED_ORDER
		""",
	)
	abstract fun observe(scrobbler: Int): Flow<List<ScrobblingEntity>>

	@Query(
		"""
		SELECT * FROM scrobblings
		WHERE scrobbler = :scrobbler
		ORDER BY $PREFERRED_ORDER
		""",
	)
	abstract suspend fun findAllByScrobbler(scrobbler: Int): List<ScrobblingEntity>

	@Query("SELECT * FROM scrobblings")
	abstract suspend fun findAllByScrobblerEntries(): List<ScrobblingEntity>

	@Upsert
	abstract suspend fun upsert(entity: ScrobblingEntity)

	@Query("DELETE FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId")
	abstract suspend fun deleteByLocalManga(scrobbler: Int, mangaId: Long)

	@Query("DELETE FROM scrobblings WHERE scrobbler = :scrobbler AND entity_id = :entityId")
	abstract suspend fun deleteByEntity(scrobbler: Int, entityId: Long)

	@Delete
	abstract suspend fun delete(entity: ScrobblingEntity)

	@Query("DELETE FROM scrobblings WHERE scrobbler = :scrobbler")
	abstract suspend fun deleteByScrobbler(scrobbler: Int)

	@Query("DELETE FROM scrobblings")
	abstract suspend fun deleteAll()

	@Query("SELECT * FROM scrobblings ORDER BY scrobbler LIMIT :limit OFFSET :offset")
	protected abstract suspend fun findAll(offset: Int, limit: Int): List<ScrobblingEntity>

	fun dumpEnabled(): Flow<ScrobblingEntity> = flow {
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
}
