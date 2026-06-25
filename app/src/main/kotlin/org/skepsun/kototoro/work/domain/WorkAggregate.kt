package org.skepsun.kototoro.work.domain

import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.parsers.model.Content

data class WorkStatsSummary(
	val totalPages: Int = 0,
	val averageTimePerPage: Long = 0L,
	val entryCount: Int = 0,
)

data class WorkTrackingSummary(
	val anchorMangaId: Long,
	val lastChapterId: Long,
	val newChapters: Int,
	val lastCheckTime: Long,
	val lastChapterDate: Long,
)

data class WorkAggregate(
	val identity: WorkIdentity,
	val displayProjection: Content?,
	val projections: List<Content>,
	val categories: Set<FavouriteCategory> = emptySet(),
	val history: WorkHistoryEntity? = null,
	val favourite: WorkFavouriteEntity? = null,
	val stats: WorkStatsSummary? = null,
	val tracking: WorkTrackingSummary? = null,
)
