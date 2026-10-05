package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryEntry
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot

enum class DesktopLibraryOrder(val title: String) {
    RECENT("默认顺序"), TITLE("标题"), LAST_READ("最近阅读"), PROGRESS("阅读进度"),
}

enum class DesktopLibraryReading(val title: String) {
    ALL("全部"), UNREAD("未读"), READING("阅读中"), FINISHED("已读完"),
}

data class DesktopLibrarySelection(
    val query: String = "",
    val categoryId: Long? = null,
    val sources: Set<String> = emptySet(),
    val reading: DesktopLibraryReading = DesktopLibraryReading.ALL,
    val order: DesktopLibraryOrder = DesktopLibraryOrder.RECENT,
    /** Android's top bar source-type and content-type filters. */
    val sourceFilter: DesktopSourceFilter = DesktopSourceFilter(),
) {
    val filterCount: Int get() = sources.size + (if (categoryId != null) 1 else 0) +
        (if (reading != DesktopLibraryReading.ALL) 1 else 0)

    /** [ecosystems] maps installed source names; works from uninstalled sources match no source-type tag. */
    fun select(snapshot: DesktopLibrarySnapshot,
        ecosystems: Map<String, SourceEcosystem> = emptyMap()): List<DesktopLibraryEntry> {
        val text = query.trim()
        val entries = snapshot.entries.filter { row ->
            val content = row.content
            val progress = row.progressPercent
            (categoryId == null || categoryId in row.categoryIds) &&
                (sources.isEmpty() || content.source.name in sources) &&
                sourceFilter.accepts(ecosystems[content.source.name], content.source.contentType) &&
                (text.isEmpty() || content.title.contains(text, true) ||
                    content.altTitles.any { it.contains(text, true) } || content.authors.any { it.contains(text, true) }) &&
                when (reading) {
                    DesktopLibraryReading.ALL -> true
                    DesktopLibraryReading.UNREAD -> progress == null || progress <= 0f
                    DesktopLibraryReading.READING -> progress != null && progress > 0f && progress < 1f
                    DesktopLibraryReading.FINISHED -> progress != null && progress >= 1f
                }
        }
        val comparator: Comparator<DesktopLibraryEntry> = when (order) {
            DesktopLibraryOrder.RECENT -> compareByDescending { it.updatedAt }
            DesktopLibraryOrder.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.content.title }
            DesktopLibraryOrder.LAST_READ -> compareByDescending { it.lastReadAt ?: 0L }
            DesktopLibraryOrder.PROGRESS -> compareByDescending { it.progressPercent ?: 0f }
        }
        return entries.sortedWith(comparator.thenBy { it.content.id })
    }
}
