package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity

@Dao
abstract class PreferencesDao {

	@Query("SELECT * FROM preferences WHERE manga_id = :mangaId")
	abstract suspend fun find(mangaId: Long): MangaPrefsEntity?

	@Query("SELECT * FROM preferences WHERE manga_id = :mangaId")
	abstract fun observe(mangaId: Long): Flow<MangaPrefsEntity?>

	@Query("SELECT * FROM preferences")
	abstract fun observeAll(): Flow<List<MangaPrefsEntity>>

	@Query("SELECT * FROM preferences WHERE title_override IS NOT NULL OR cover_override IS NOT NULL OR content_rating_override IS NOT NULL")
	abstract suspend fun getOverrides(): List<MangaPrefsEntity>

	@Query(
		"""
		SELECT * FROM preferences
		WHERE title_override IS NOT NULL
			OR cover_override IS NOT NULL
			OR content_rating_override IS NOT NULL
			OR metadata_source_kind IS NOT NULL
		ORDER BY manga_id ASC
		""",
	)
	abstract suspend fun findDisplayPreferenceRows(): List<MangaPrefsEntity>

	// Legacy fallback only. Work/entity-owned metadata authority should be read from entity prefs first.
	@Query("SELECT * FROM preferences WHERE manga_id IN (:mangaIds) AND metadata_source_kind IS NOT NULL")
	abstract suspend fun getLegacyMetadataSourceSelections(mangaIds: List<Long>): List<MangaPrefsEntity>

	@Query("UPDATE preferences SET cf_brightness = 0, cf_contrast = 0, cf_invert = 0, cf_grayscale = 0")
	abstract suspend fun resetColorFilters()

	@Upsert
	abstract suspend fun upsert(pref: MangaPrefsEntity)
}
