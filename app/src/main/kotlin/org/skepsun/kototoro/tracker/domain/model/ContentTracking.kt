package org.skepsun.kototoro.tracker.domain.model

import org.skepsun.kototoro.parsers.model.Content
import java.time.Instant

data class ContentTracking(
	val anchorMangaId: Long,
	val entityId: Long?,
	val preferredLocalMangaId: Long?,
	val manga: Content,
	val lastChapterId: Long,
	val lastCheck: Instant?,
	val lastChapterDate: Instant?,
	val newChapters: Int,
) {

	fun isEmpty(): Boolean {
		return lastChapterId == 0L
	}
}
