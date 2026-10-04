package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.source.SourceContent

/** Desktop projections of the shared schema; persistence types do not cross into Compose. */
data class DesktopBookmark(
    val contentId: Long,
    val pageId: Long,
    val chapterId: Long,
    val page: Int,
    val scroll: Int,
    val createdAt: Long,
    val percent: Float,
)

data class DesktopLibraryEntry(
    val content: SourceContent,
    val categoryIds: Set<Long>,
    val updatedAt: Long,
    val lastReadAt: Long?,
    val progressPercent: Float?,
)

data class DesktopLibraryCategory(val id: Long, val title: String)

data class DesktopLibrarySnapshot(
    val entries: List<DesktopLibraryEntry> = emptyList(),
    val categories: List<DesktopLibraryCategory> = emptyList(),
)
