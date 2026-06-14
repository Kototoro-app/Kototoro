package org.skepsun.kototoro.tracker.data

import androidx.annotation.IntDef
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.skepsun.kototoro.core.db.entity.MangaEntity

@Entity(
	tableName = "tracks",
	indices = [
		Index(value = ["manga_id"], unique = true),
		Index(value = ["entity_id"]),
	],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
class TrackEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "owner_id") val ownerId: Long,
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "entity_id") val entityId: Long? = null,
	@ColumnInfo(name = "last_chapter_id") val lastChapterId: Long,
	@ColumnInfo(name = "chapters_new") val newChapters: Int,
	@ColumnInfo(name = "last_check_time") val lastCheckTime: Long,
	@ColumnInfo(name = "last_chapter_date") val lastChapterDate: Long,
	@TrackerResult
	@ColumnInfo(name = "last_result") val lastResult: Int,
	@ColumnInfo(name = "last_error") val lastError: String?,
) {

	@IntDef(RESULT_NONE, RESULT_HAS_UPDATE, RESULT_NO_UPDATE, RESULT_FAILED, RESULT_EXTERNAL_MODIFICATION)
	@Retention(AnnotationRetention.SOURCE)
	annotation class TrackerResult

	companion object {

		const val RESULT_NONE = 0
		const val RESULT_HAS_UPDATE = 1
		const val RESULT_NO_UPDATE = 2
		const val RESULT_FAILED = 3
		const val RESULT_EXTERNAL_MODIFICATION = 4

		fun create(mangaId: Long, entityId: Long? = null) = TrackEntity(
			ownerId = resolveTrackOwnerId(entityId, mangaId),
			mangaId = mangaId,
			entityId = entityId,
			lastChapterId = 0L,
			newChapters = 0,
			lastCheckTime = 0L,
			lastChapterDate = 0,
			lastResult = RESULT_NONE,
			lastError = null,
		)
	}
}

fun resolveTrackOwnerId(entityId: Long?, mangaId: Long): Long {
	return entityId ?: mangaId.takeIf { it != 0L }?.let { -it } ?: 0L
}
