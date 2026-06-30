package org.skepsun.kototoro.tracker.domain.model

import org.skepsun.kototoro.parsers.model.Content
import java.time.Instant

data class TrackingLogItem(
	val id: Long,
	val anchorMangaId: Long,
	val entityId: Long?,
	val preferredLocalMangaId: Long?,
	val manga: Content,
	val chapters: List<String>,
	val createdAt: Instant,
	val isNew: Boolean,
	val count: Int? = null,
)
