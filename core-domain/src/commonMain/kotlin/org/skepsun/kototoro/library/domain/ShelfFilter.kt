package org.skepsun.kototoro.library.domain

/**
 * Android's library list filter groups (`ListFilterCriteria`) as one pure rule for the hosts that filter in memory
 * (iOS now; Windows' `DesktopLibrarySelection` has the same groups): options inside a group are OR-ed, groups are
 * AND-ed, an empty group is unrestricted, and each macro chip is a group of its own.
 */
data class ShelfFilter(
    val sources: Set<String> = emptySet(),
    /** Tag titles, compared case-insensitively. */
    val tags: Set<String> = emptySet(),
    /** Publication state names (ONGOING, FINISHED, …). */
    val states: Set<String> = emptySet(),
    val reading: ShelfReading = ShelfReading.ALL,
    val download: ShelfDownload = ShelfDownload.ALL,
    val macros: Set<ShelfMacro> = emptySet(),
) {
    val count: Int get() = sources.size + tags.size + states.size + macros.size +
        (if (reading != ShelfReading.ALL) 1 else 0) + (if (download != ShelfDownload.ALL) 1 else 0)

    fun accepts(row: ShelfRow): Boolean =
        (sources.isEmpty() || row.sourceName in sources) &&
            (tags.isEmpty() || row.tags.any { tag -> tags.any { it.equals(tag, ignoreCase = true) } }) &&
            (states.isEmpty() || row.state in states) &&
            reading.accepts(row.progress) &&
            download.accepts(row.onDevice) &&
            macros.all { it.accepts(row) }
}

/** What the filter reads of one work on a shelf. */
data class ShelfRow(
    val sourceName: String,
    val tags: List<String>,
    val state: String?,
    /** History progress 0–1, null when the work was never opened. */
    val progress: Float?,
    /** At least one chapter is stored on the device. */
    val onDevice: Boolean,
    val newChapters: Int,
    val isNsfw: Boolean,
)

enum class ShelfReading {
    ALL, UNREAD, READING, FINISHED;

    /** Completion snaps at 99.9%, as Android's `resolveReadingStatus` does; "reading" ends at the same point. */
    fun accepts(progress: Float?): Boolean = when (this) {
        ALL -> true
        UNREAD -> progress == null || progress <= 0f
        READING -> progress != null && progress > 0f && progress < COMPLETED
        FINISHED -> progress != null && progress >= COMPLETED
    }

    companion object {
        const val COMPLETED = 0.999f
    }
}

enum class ShelfDownload {
    ALL, DOWNLOADED, MISSING;

    fun accepts(onDevice: Boolean): Boolean = when (this) {
        ALL -> true
        DOWNLOADED -> onDevice
        MISSING -> !onDevice
    }
}

/** Android's quick chips that apply to a favourites shelf (the favourite/not-favourite pair only fits history). */
enum class ShelfMacro {
    NEW_CHAPTERS, NSFW, SFW;

    fun accepts(row: ShelfRow): Boolean = when (this) {
        NEW_CHAPTERS -> row.newChapters > 0
        NSFW -> row.isNsfw
        SFW -> !row.isNsfw
    }
}
