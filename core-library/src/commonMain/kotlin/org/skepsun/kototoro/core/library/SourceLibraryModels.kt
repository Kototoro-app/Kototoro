package org.skepsun.kototoro.core.library

import org.skepsun.kototoro.core.source.SourceContent

/** Desktop projections of the shared schema; persistence types do not cross into Compose. */
data class SourceBookmark(
    val contentId: Long,
    val pageId: Long,
    val chapterId: Long,
    val page: Int,
    val scroll: Int,
    val createdAt: Long,
    val percent: Float,
    val preview: String = "",
)

data class SourceLibraryEntry(
    val content: SourceContent,
    val categoryIds: Set<Long>,
    val updatedAt: Long,
    val lastReadAt: Long?,
    val progressPercent: Float?,
    /** History only: the stored chapter last read, for "continue reading" captions. */
    val lastChapter: SourceLibraryChapter? = null,
    /** The persisted `manga.nsfw` flag, so the NSFW/SFW macro filters match Android's stored row. */
    val isNsfw: Boolean = false,
    /** `tracks.chapters_new`: chapters the update checker found since the last read. */
    val newChapters: Int = 0,
    /** `favourites.pinned`: Android's batch pin, which lifts the work to the top of every shelf order. */
    val isPinned: Boolean = false,
)

data class SourceLibraryChapter(val title: String, val number: Float)

data class SourceLibraryCategory(val id: Long, val title: String)

data class SourceLibrarySnapshot(
    val entries: List<SourceLibraryEntry> = emptyList(),
    val categories: List<SourceLibraryCategory> = emptyList(),
)
