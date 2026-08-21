package org.skepsun.kototoro.favourites.domain.model

import org.skepsun.kototoro.core.model.ContentSource

data class Cover(
    val mangaId: Long,
    val url: String?,
    val source: String,
) {
    val mangaSource by lazy { ContentSource(source) }
}
