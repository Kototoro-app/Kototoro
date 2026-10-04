package org.skepsun.kototoro.tracker.domain.updates

import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentType

/** One raw track row as assembled before grouping (store-internal). */
internal data class TrackRowSeed(
    val ownerId: Long,
    val mangaId: Long,
    val entityId: Long?,
    val preferredLocalMangaId: Long?,
    val newChapters: Int,
    val lastChapterDate: Long?,
    val lastCheckTime: Long,
    val isPinned: Boolean,
    val displayMangaId: Long?,
    val title: String,
    val altTitle: String?,
    val coverUrl: String?,
    val author: String?,
    val sourceName: String,
    val contentType: ContentType?,
    val displayContentTypeOrdinal: Int,
    val publicationState: ContentState?,
    val isNsfw: Boolean,
    val rating: Float,
    val tags: List<UpdateCardTag>,
    val overrideTitle: String?,
    val overrideCoverUrl: String?,
    val metadataTrackingService: Int?,
    val metadataTrackingTitle: String?,
    val metadataTrackingCoverUrl: String?,
    val sourceGroupFlags: Int,
    val sourceOriginFlags: Int,
)
