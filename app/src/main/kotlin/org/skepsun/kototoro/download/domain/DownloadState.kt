package org.skepsun.kototoro.download.domain

import androidx.work.Data
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.download.ui.worker.DownloadTaskKind
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.find
import java.time.Instant

data class DownloadState(
	val manga: Content,
	val displayMangaId: Long? = null,
	val isIndeterminate: Boolean,
	val taskKind: DownloadTaskKind = DownloadTaskKind.DOWNLOAD,
	val isPaused: Boolean = false,
	val isStopped: Boolean = false,
	val error: Throwable? = null,
	val errorMessage: String? = null,
	val totalChapters: Int = 0,
	val currentChapter: Int = 0,
	val totalPages: Int = 0,
	val currentPage: Int = 0,
	val eta: Long = -1L,
	val isStuck: Boolean = false,
	val localContent: LocalContent? = null,
	val downloadedChapters: Int = 0,
	val timestamp: Long = System.currentTimeMillis(),
	val isCompleted: Boolean = false,
) {

	val max: Int = totalChapters * totalPages

	val progress: Int = totalPages * currentChapter + currentPage + 1

	val percent: Float = if (max > 0) progress.toFloat() / max else PROGRESS_NONE

	val isFinalState: Boolean
		get() = isCompleted || localContent != null || (error != null && !isPaused)

	val isParticularProgress: Boolean
		get() = !isCompleted && localContent == null && error == null && !isPaused && !isStopped && max > 0 && !isIndeterminate

	fun toWorkData() = Data.Builder()
		.putLong(DATA_MANGA_ID, manga.id)
		.putLong(DATA_DISPLAY_MANGA_ID, displayMangaId ?: 0L)
		.putString(DATA_TASK_KIND, taskKind.name)
		.putInt(DATA_MAX, max)
		.putInt(DATA_PROGRESS, progress)
		.putLong(DATA_ETA, eta)
		.putBoolean(DATA_STUCK, isStuck)
		.putLong(DATA_TIMESTAMP, timestamp)
		.putString(DATA_ERROR, errorMessage)
		.putInt(DATA_CHAPTERS, downloadedChapters)
		.putBoolean(DATA_INDETERMINATE, isIndeterminate)
		.putBoolean(DATA_PAUSED, isPaused)
		.putBoolean(DATA_COMPLETED, isCompleted)
		.build()

	companion object {

		private const val DATA_MANGA_ID = "manga_id"
		private const val DATA_DISPLAY_MANGA_ID = "display_manga_id"
		private const val DATA_TASK_KIND = "task_kind"
		private const val DATA_MAX = "max"
		private const val DATA_PROGRESS = "progress"
		private const val DATA_CHAPTERS = "chapter_cnt"
		private const val DATA_ETA = "eta"
		private const val DATA_STUCK = "stuck"
		const val DATA_TIMESTAMP = "timestamp"
		private const val DATA_ERROR = "error"
		private const val DATA_INDETERMINATE = "indeterminate"
		private const val DATA_PAUSED = "paused"
		private const val DATA_COMPLETED = "completed"

		fun getContentId(data: Data): Long = data.getLong(DATA_MANGA_ID, 0L)

		fun getExecutionContentId(data: Data): Long = getContentId(data)

		fun getDisplayContentId(data: Data): Long? = data.getLong(DATA_DISPLAY_MANGA_ID, 0L).takeIf { it != 0L }

		fun isIndeterminate(data: Data): Boolean = data.getBoolean(DATA_INDETERMINATE, false)

		fun isPaused(data: Data): Boolean = data.getBoolean(DATA_PAUSED, false)

		fun isCompleted(data: Data): Boolean = data.getBoolean(DATA_COMPLETED, false)

		fun getMax(data: Data): Int = data.getInt(DATA_MAX, 0)

		fun getError(data: Data): String? = data.getString(DATA_ERROR)

		fun getProgress(data: Data): Int = data.getInt(DATA_PROGRESS, 0)

		fun getEta(data: Data): Long = data.getLong(DATA_ETA, -1L)

		fun isStuck(data: Data): Boolean = data.getBoolean(DATA_STUCK, false)

		fun getTimestamp(data: Data): Instant = Instant.ofEpochMilli(data.getLong(DATA_TIMESTAMP, 0L))

		fun getDownloadedChapters(data: Data): Int = data.getInt(DATA_CHAPTERS, 0)

		fun getTaskKind(data: Data): DownloadTaskKind =
			data.getString(DATA_TASK_KIND)?.let { DownloadTaskKind.entries.find(it) } ?: DownloadTaskKind.DOWNLOAD
	}
}
