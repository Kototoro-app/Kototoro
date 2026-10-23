package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryEntry
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot

/** Labels are resolved per access, so a language switch relabels the chips without a restart. */
enum class DesktopLibraryOrder {
    RECENT, TITLE, LAST_READ, PROGRESS;

    val title: String get() = when (this) {
        RECENT -> desktopText("library.order.recent")
        TITLE -> desktopText("library.order.title")
        LAST_READ -> desktopText("library.order.lastRead")
        PROGRESS -> desktopText("library.order.progress")
    }
}

enum class DesktopLibraryReading {
    ALL, UNREAD, READING, FINISHED;

    val title: String get() = when (this) {
        ALL -> desktopText("library.progress.all")
        UNREAD -> desktopText("library.progress.unread")
        READING -> desktopText("library.progress.reading")
        FINISHED -> desktopText("library.progress.finished")
    }
}

/** Android's `_downloaded` group, shown as radios because "on device" and "not yet" are exclusive. */
enum class DesktopLibraryDownload {
    ALL, DOWNLOADED, MISSING;

    val title: String get() = when (this) {
        ALL -> desktopText("library.download.all")
        DOWNLOADED -> desktopText("library.download.only")
        MISSING -> desktopText("library.download.missing")
    }

    /** [onDevice] is true while at least one chapter of the work is stored locally. */
    fun accepts(onDevice: Boolean): Boolean = when (this) {
        ALL -> true
        DOWNLOADED -> onDevice
        MISSING -> !onDevice
    }
}

data class DesktopLibrarySelection(
    val query: String = "",
    val categoryId: Long? = null,
    val sources: Set<String> = emptySet(),
    val reading: DesktopLibraryReading = DesktopLibraryReading.ALL,
    val order: DesktopLibraryOrder = DesktopLibraryOrder.RECENT,
    /** Android's top bar source-type and content-type filters. */
    val sourceFilter: DesktopSourceFilter = DesktopSourceFilter(),
    /** Android's tag group: options inside the group are OR-ed, other groups are AND-ed. */
    val tags: Set<SourceTag> = emptySet(),
    /** Android's `_publication_state` group: content-state names, OR-ed inside the group. */
    val states: Set<String> = emptySet(),
    val download: DesktopLibraryDownload = DesktopLibraryDownload.ALL,
) {
    val filterCount: Int get() = sources.size + tags.size + states.size +
        (if (categoryId != null) 1 else 0) +
        (if (reading != DesktopLibraryReading.ALL) 1 else 0) +
        (if (download != DesktopLibraryDownload.ALL) 1 else 0)

    /**
     * [ecosystems] maps installed source names; works from uninstalled sources match no source-type tag.
     * [downloadedContentIds] are works that hold at least one stored chapter.
     */
    fun select(snapshot: DesktopLibrarySnapshot,
        ecosystems: Map<String, SourceEcosystem> = emptyMap(),
        downloadedContentIds: Set<Long> = emptySet()): List<DesktopLibraryEntry> {
        val text = query.trim()
        val entries = snapshot.entries.filter { row ->
            val content = row.content
            val progress = row.progressPercent
            (categoryId == null || categoryId in row.categoryIds) &&
                (sources.isEmpty() || content.source.name in sources) &&
                (tags.isEmpty() || content.tags.any { it in tags }) &&
                (states.isEmpty() || content.state in states) &&
                download.accepts(content.id in downloadedContentIds) &&
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
