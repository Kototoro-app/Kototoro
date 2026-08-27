package org.skepsun.kototoro.list.ui.compose

import androidx.compose.runtime.Immutable
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

/** Immutable projection containing only values read while a content card is drawn. */
@Immutable
data class ContentCardRenderModel(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val subtitle: String?,
    val supportingText: String?,
    val authorText: String,
    val tagsText: String,
    val counter: Int,
    val projectionCount: Int,
    val progress: ReadingProgress?,
    val isFavorite: Boolean,
    val isSaved: Boolean,
    val isPinned: Boolean,
    val metadataTrackingService: ScrobblerService?,
    val scoreText: String?,
    val isNsfw: Boolean,
)

internal fun ContentListModel.toContentCardRenderModel(): ContentCardRenderModel = ContentCardRenderModel(
    id = id,
    title = title,
    coverUrl = coverUrl,
    subtitle = when (this) {
        is ContentCompactListModel -> subtitle
        is ContentDetailedListModel -> subtitle
        is ContentGridModel -> subtitle
    },
    supportingText = when (this) {
        is ContentCompactListModel -> supportingText
        is ContentDetailedListModel -> supportingText
        is ContentGridModel -> null
    },
    authorText = manga.authors.joinToString(", "),
    tagsText = when (this) {
        is ContentDetailedListModel -> tags.joinToString(", ") { it.title?.toString().orEmpty() }
        else -> ""
    },
    counter = counter,
    projectionCount = projectionCount,
    progress = when (this) {
        is ContentCompactListModel -> progress
        is ContentDetailedListModel -> progress
        is ContentGridModel -> progress
    },
    isFavorite = when (this) {
        is ContentDetailedListModel -> isFavorite
        is ContentGridModel -> isFavorite
        is ContentCompactListModel -> false
    },
    isSaved = when (this) {
        is ContentDetailedListModel -> isSaved
        is ContentGridModel -> isSaved
        is ContentCompactListModel -> false
    },
    isPinned = isPinned,
    metadataTrackingService = metadataTrackingService,
    scoreText = scoreText,
    isNsfw = manga.isNsfw(),
)
