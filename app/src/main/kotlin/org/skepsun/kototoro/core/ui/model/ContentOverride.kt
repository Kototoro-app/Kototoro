package org.skepsun.kototoro.core.ui.model

import org.skepsun.kototoro.parsers.model.ContentRating

data class ContentOverride(
    val coverUrl: String?,
    val title: String?,
    val contentRating: ContentRating?,
)
