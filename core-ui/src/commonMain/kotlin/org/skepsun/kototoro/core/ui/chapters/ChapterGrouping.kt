package org.skepsun.kototoro.core.ui.chapters

/*
 * Android's chapter branch and volume grouping rules, generic over the platform chapter model so the
 * Android details screen and the Windows host build the same list.
 */

/** A locale the user reads, as Android matches it against branch names. */
data class ChapterBranchLocale(val displayLanguage: String, val displayName: String)

/**
 * The branch to open: the one holding the history chapter, the only branch, the largest branch whose name
 * mentions a preferred locale (in preference order), else the largest branch.
 */
fun <T> resolvePreferredChapterBranch(
    chapters: List<T>,
    branch: (T) -> String?,
    id: (T) -> Long,
    historyChapterId: Long?,
    locales: List<ChapterBranchLocale>,
): String? {
    if (chapters.isEmpty()) return null
    if (historyChapterId != null) {
        chapters.firstOrNull { id(it) == historyChapterId }?.let { return branch(it) }
    }
    val groups = chapters.groupBy(branch)
    if (groups.size == 1) return groups.keys.first()
    for (locale in locales) {
        val matched = groups.filterKeys { name ->
            name != null && (
                name.contains(locale.displayLanguage, ignoreCase = true) ||
                    name.contains(locale.displayName, ignoreCase = true)
                )
        }
        if (matched.isNotEmpty()) return matched.maxBy { it.value.size }.key
    }
    return groups.maxByOrNull { it.value.size }?.key
}

data class ChapterBranchOption(val name: String?, val chaptersCount: Int)

/**
 * Branch chips are offered only when a work has more than one branch, sorted by [comparator]; an inconsistent
 * locale comparator keeps the source order instead of failing, as Android's `sortedWithSafe` does in release.
 */
fun <T> chapterBranchOptions(
    chapters: List<T>,
    branch: (T) -> String?,
    comparator: Comparator<String?> = nullsFirst(naturalOrder()),
): List<ChapterBranchOption> {
    val groups = chapters.groupBy(branch)
    if (groups.size <= 1) return emptyList()
    val entries = groups.entries.toList()
    val sorted = try {
        entries.sortedWith(compareBy(comparator) { it.key })
    } catch (_: IllegalArgumentException) {
        entries
    }
    return sorted.map { ChapterBranchOption(it.key, it.value.size) }
}

/** The selected branch's chapters, falling back to the largest branch if the selection no longer exists. */
fun <T> chaptersOfBranch(chapters: List<T>, branch: (T) -> String?, selected: String?): List<T> {
    val groups = chapters.groupBy(branch)
    if (groups.size <= 1) return chapters
    return groups[selected] ?: groups.maxBy { it.value.size }.value
}

sealed interface ChapterSection<out T> {
    /** A volume header: [customName] (the chapter group) wins; [volume] <= 0 means an unknown volume. */
    data class Header(val volume: Int, val customName: String?) : ChapterSection<Nothing>
    data class Item<T>(val chapter: T) : ChapterSection<T>
}

/** A lone "Unknown volume" header says nothing; only group when some chapter has a volume or group name. */
fun <T> shouldShowVolumeHeaders(chapters: List<T>, volume: (T) -> Int, scanlator: (T) -> String?): Boolean =
    chapters.any { volume(it) > 0 || !scanlator(it).isNullOrBlank() }

/** Inserts a header whenever the volume changes or a new group name starts, in list order. */
fun <T> withVolumeSections(
    chapters: List<T>,
    volume: (T) -> Int,
    scanlator: (T) -> String?,
): List<ChapterSection<T>> {
    if (!shouldShowVolumeHeaders(chapters, volume, scanlator)) return chapters.map { ChapterSection.Item(it) }
    var previousVolume = -1
    var previousCustom: String? = null
    val result = ArrayList<ChapterSection<T>>((chapters.size * 1.4).toInt())
    for (chapter in chapters) {
        val custom = scanlator(chapter)?.takeIf { it.isNotBlank() }
        val chapterVolume = volume(chapter)
        if (chapterVolume != previousVolume || (custom != null && custom != previousCustom)) {
            result += ChapterSection.Header(chapterVolume, custom)
            previousVolume = chapterVolume
            previousCustom = custom
        }
        result += ChapterSection.Item(chapter)
    }
    return result
}
