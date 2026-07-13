package org.skepsun.kototoro.tracker.domain

import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.tracker.data.resolveTrackOwnerId
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.domain.model.TrackingLogItem
import java.time.Instant

private const val FEED_CHAPTER_PREVIEW_LIMIT = 10

object TrackingLogItemMapper {

	fun fromAllTrackedContent(
		tracks: List<ContentTracking>,
		chapters: List<ChapterEntity>,
		unreadOwnerIds: Set<Long>? = null,
	): List<TrackingLogItem> {
		if (tracks.isEmpty()) {
			return emptyList()
		}
		val chaptersByMangaId = chapters.groupBy { it.mangaId }
		return tracks.map { track ->
			val chapterTitles = chaptersByMangaId[track.manga.id].orEmpty()
				.takeLast(FEED_CHAPTER_PREVIEW_LIMIT)
				.reversed()
				.map { it.title }
			TrackingLogItem(
				id = -track.manga.id,
				anchorMangaId = track.manga.id,
				entityId = track.entityId,
				preferredLocalMangaId = track.preferredLocalMangaId,
				manga = track.manga,
				chapters = chapterTitles,
				createdAt = track.lastChapterDate ?: track.lastCheck ?: Instant.EPOCH,
				isNew = if (unreadOwnerIds != null) {
					track.newChapters > 0 && resolveTrackOwnerId(track.entityId, track.manga.id) in unreadOwnerIds
				} else {
					track.newChapters > 0
				},
				count = track.newChapters,
			)
		}
	}
}
