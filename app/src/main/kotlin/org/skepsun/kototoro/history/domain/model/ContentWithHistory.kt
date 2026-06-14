package org.skepsun.kototoro.history.domain.model

import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.parsers.model.Content

data class ContentWithHistory(
	val manga: Content,
	val history: ContentHistory,
	val entityId: Long?,
	val preferredLocalMangaId: Long?,
)
