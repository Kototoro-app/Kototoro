package org.skepsun.kototoro.core.ui.preview

/** Resolved display values; localization, HTML, source models and image loading belong to the host. */
data class TabletPreviewData(
    val id: Long,
    val title: String,
    val authors: String,
    val sourceTitle: String,
    val description: String,
    val tags: List<String>,
    val ratingText: String?,
    val chapterCountText: String?,
    val statusLabel: String?,
    val chapterSections: List<TabletPreviewSection>,
)

data class TabletPreviewSection(
    val title: String,
    val chapters: List<TabletPreviewChapter>,
    val countText: String? = null,
)

data class TabletPreviewChapter(val id: Long, val title: String, val metadata: String)

data class TabletPreviewLabels(
    val close: String,
    val read: String,
    val details: String,
    val addToFavorites: String,
    val unavailable: String,
    val retry: String,
)

enum class TabletPreviewIcon { CLOSE, PLAY, FAVOURITE, RATING }
