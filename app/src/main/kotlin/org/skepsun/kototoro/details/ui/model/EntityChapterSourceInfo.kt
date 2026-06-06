package org.skepsun.kototoro.details.ui.model

import org.skepsun.kototoro.parsers.model.ContentSource

data class EntityChapterSourceInfo(
    val source: ContentSource?,
    val projectionTitle: String? = null,
    val projectionCount: Int = 0,
    val activeProjectionMangaId: Long? = null,
    val currentReadingProjectionMangaId: Long? = null,
)
