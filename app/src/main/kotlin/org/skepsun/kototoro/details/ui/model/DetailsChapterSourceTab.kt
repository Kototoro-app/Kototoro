package org.skepsun.kototoro.details.ui.model

import org.skepsun.kototoro.details.ui.isStructurallySameSource
import org.skepsun.kototoro.details.ui.structuralSourceHashCode
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

data class DetailsChapterSourceTab(
    val key: String,
    val source: ContentSource? = null,
    val trackingService: ScrobblerService? = null,
    val targetMangaId: Long? = null,
    val remoteId: Long? = null,
    val url: String? = null,
    val chapters: List<ContentChapter> = emptyList(),
    val isSelected: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetailsChapterSourceTab) return false
        return key == other.key &&
            source.isStructurallySameSource(other.source) &&
            trackingService == other.trackingService &&
            targetMangaId == other.targetMangaId &&
            remoteId == other.remoteId &&
            url == other.url &&
            chapters == other.chapters &&
            isSelected == other.isSelected
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + source.structuralSourceHashCode()
        result = 31 * result + (trackingService?.hashCode() ?: 0)
        result = 31 * result + (targetMangaId?.hashCode() ?: 0)
        result = 31 * result + (remoteId?.hashCode() ?: 0)
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + chapters.hashCode()
        result = 31 * result + isSelected.hashCode()
        return result
    }
}
