package org.skepsun.kototoro.history.data

import androidx.room.ColumnInfo

data class HistoryProgressEntry(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "percent") val percent: Float,
	@ColumnInfo(name = "chapters") val chaptersCount: Int,
)
