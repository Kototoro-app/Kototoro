package org.skepsun.kototoro.history.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import org.skepsun.kototoro.core.db.entity.MangaEntity

/**
 * One positional history-page row with display manga, preferred projection and
 * tracking summary already joined by the paging query, mirroring
 * [org.skepsun.kototoro.favourites.data.FavouriteLibraryPagingRow].
 *
 * The history record is the page's primary key and carries everything the History
 * page renders; the display manga is the preferred (or anchor) projection and the
 * tracking columns feed the new-chapter counters and the NEW_CHAPTERS/UPDATED sort
 * without a per-page `resolveProjectionSet`.
 */
data class HistoryLibraryPagingRow(
	@Embedded val history: WorkHistoryEntity,
	@ColumnInfo(name = "preferred_local_manga_id") val preferredLocalMangaId: Long?,
	@Embedded(prefix = "display_") val displayManga: MangaEntity?,
	@ColumnInfo(name = "tracking_anchor_manga_id") val trackingAnchorMangaId: Long?,
	@ColumnInfo(name = "tracking_last_chapter_id") val trackingLastChapterId: Long?,
	@ColumnInfo(name = "tracking_new_chapters") val trackingNewChapters: Int?,
	@ColumnInfo(name = "tracking_last_check_time") val trackingLastCheckTime: Long?,
	@ColumnInfo(name = "tracking_last_chapter_date") val trackingLastChapterDate: Long?,
)
