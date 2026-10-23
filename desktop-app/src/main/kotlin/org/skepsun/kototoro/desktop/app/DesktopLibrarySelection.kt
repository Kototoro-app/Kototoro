package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.model.TagBlacklist
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.desktop.runtime.DesktopLibraryEntry
import org.skepsun.kototoro.desktop.runtime.DesktopLibrarySnapshot
import org.skepsun.kototoro.list.domain.ReadingProgress

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

/**
 * Android's quick-filter macro chips. Every macro is its own group there, so separate macros are AND-ed;
 * `notFavorite`/`sfw` are Android's `Inverted` options. Android's `COMPLETED` macro is not repeated here:
 * the reading-progress group above already filters on the same progress threshold, and Android's chip list
 * only differs because its own reading-status filter reads scrobbling state instead of progress.
 */
enum class DesktopLibraryMacro {
    NEW_CHAPTERS, FAVORITE, NOT_FAVORITE, NSFW, SFW;

    val title: String get() = when (this) {
        NEW_CHAPTERS -> desktopText("library.quick.newChapters")
        FAVORITE -> desktopText("library.quick.favorite")
        NOT_FAVORITE -> desktopText("library.quick.notFavorite")
        NSFW -> desktopText("library.quick.nsfw")
        SFW -> desktopText("library.quick.sfw")
    }

    fun accepts(entry: DesktopLibraryEntry): Boolean = when (this) {
        NEW_CHAPTERS -> entry.newChapters > 0
        FAVORITE -> entry.categoryIds.isNotEmpty()
        NOT_FAVORITE -> entry.categoryIds.isEmpty()
        NSFW -> entry.isNsfw
        SFW -> !entry.isNsfw
    }

    companion object {
        /** The favourites shelf holds nothing but favourites, so its chips stay off that shelf. */
        fun choices(history: Boolean): List<DesktopLibraryMacro> =
            if (history) entries else entries - FAVORITE - NOT_FAVORITE
    }
}

/** Android's `resolveReadingStatus` and `FavouriteLibraryDeriver` both snap completion at 99.9%. */
internal const val CompletedProgress = 0.999f

/**
 * Android's cover counter badge. `FavouritesCardMapper` keeps the tracking count but zeroes it once
 * `ReadingProgress.isCompleted()` (99.999%) holds, so a read-through favourite loses the corner badge
 * while its `tracks.chapters_new` row — and therefore the "new chapters" macro — keeps counting.
 * History cards never show it (`HistoryCardMapper` maps `counter = 0` on that shelf).
 */
internal fun newChaptersBadge(entry: DesktopLibraryEntry, history: Boolean): Int = when {
    history -> 0
    entry.progressPercent?.let(ReadingProgress::isCompleted) == true -> 0
    else -> entry.newChapters
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
    /** Android's macro chips; each macro is its own group, so they are AND-ed with everything else. */
    val macros: Set<DesktopLibraryMacro> = emptySet(),
) {
    val filterCount: Int get() = sources.size + tags.size + states.size + macros.size +
        (if (categoryId != null) 1 else 0) +
        (if (reading != DesktopLibraryReading.ALL) 1 else 0) +
        (if (download != DesktopLibraryDownload.ALL) 1 else 0)

    /**
     * [ecosystems] maps installed source names; works from uninstalled sources match no source-type tag.
     * [downloadedContentIds] are works that hold at least one stored chapter. [hideNsfw] carries Android's adult
     * filter target for this shelf: it is a display rule, so it is applied last like the other filters.
     * [pinnedFirst] is Android's ranking of the favourites shelf: a pinned work leads every order, so it is applied
     * before the chosen comparator and never as a filter. [tagBlacklist] is Android's global tag blacklist, which the
     * shelf derivers apply to a work's own tag titles before the search and the macro chips.
     */
    fun select(snapshot: DesktopLibrarySnapshot,
        ecosystems: Map<String, SourceEcosystem> = emptyMap(),
        downloadedContentIds: Set<Long> = emptySet(),
        hideNsfw: Boolean = false,
        pinnedFirst: Boolean = false,
        tagBlacklist: TagBlacklist = TagBlacklist.Empty): List<DesktopLibraryEntry> {
        val text = query.trim()
        val entries = snapshot.entries.filter { row ->
            val content = row.content
            val progress = row.progressPercent
            (categoryId == null || categoryId in row.categoryIds) &&
                (sources.isEmpty() || content.source.name in sources) &&
                (tags.isEmpty() || content.tags.any { it in tags }) &&
                (states.isEmpty() || content.state in states) &&
                download.accepts(content.id in downloadedContentIds) &&
                (!hideNsfw || !row.isNsfw) &&
                !content.tags.any { tagBlacklist.containsTagTitle(it.title) } &&
                macros.all { it.accepts(row) } &&
                sourceFilter.accepts(ecosystems[content.source.name], content.source.contentType) &&
                (text.isEmpty() || content.title.contains(text, true) ||
                    content.altTitles.any { it.contains(text, true) } || content.authors.any { it.contains(text, true) }) &&
                when (reading) {
                    DesktopLibraryReading.ALL -> true
                    DesktopLibraryReading.UNREAD -> progress == null || progress <= 0f
                    DesktopLibraryReading.READING -> progress != null && progress > 0f && progress < 1f
                    DesktopLibraryReading.FINISHED -> progress != null && progress >= CompletedProgress
                }
        }
        val comparator: Comparator<DesktopLibraryEntry> = when (order) {
            DesktopLibraryOrder.RECENT -> compareByDescending { it.updatedAt }
            DesktopLibraryOrder.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.content.title }
            DesktopLibraryOrder.LAST_READ -> compareByDescending { it.lastReadAt ?: 0L }
            DesktopLibraryOrder.PROGRESS -> compareByDescending { it.progressPercent ?: 0f }
        }
        return if (pinnedFirst) {
            entries.sortedWith(compareByDescending<DesktopLibraryEntry> { it.isPinned }
                .then(comparator).thenBy { it.content.id })
        } else {
            entries.sortedWith(comparator.thenBy { it.content.id })
        }
    }
}
