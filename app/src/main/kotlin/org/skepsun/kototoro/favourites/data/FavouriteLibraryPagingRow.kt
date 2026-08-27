package org.skepsun.kototoro.favourites.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity

/** One library row with the data already joined by the paging query. */
data class FavouriteLibraryPagingRow(
    @Embedded val favourite: WorkFavouriteEntity,
    @ColumnInfo(name = "preferred_local_manga_id") val preferredLocalMangaId: Long?,
    @Embedded(prefix = "display_") val displayManga: MangaEntity?,
    @Embedded(prefix = "history_") val history: WorkHistoryEntity?,
    @ColumnInfo(name = "tracking_anchor_manga_id") val trackingAnchorMangaId: Long?,
    @ColumnInfo(name = "tracking_last_chapter_id") val trackingLastChapterId: Long?,
    @ColumnInfo(name = "tracking_new_chapters") val trackingNewChapters: Int?,
    @ColumnInfo(name = "tracking_last_check_time") val trackingLastCheckTime: Long?,
    @ColumnInfo(name = "tracking_last_chapter_date") val trackingLastChapterDate: Long?,
)
