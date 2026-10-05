package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.runtime.Immutable

/** The host resolves local-book grouping; directory rows keep indices in the original chapter list. */
@Immutable
data class NovelChapterDirectoryEntry(
    val id: Long,
    val title: String?,
    val volume: Int = 0,
    val groupTitle: String = "",
    val searchAliases: List<String> = emptyList(),
)

sealed interface NovelChapterDirectoryItem {
    val key: String
    data class Header(val title: String, val occurrence: Int) : NovelChapterDirectoryItem {
        override val key = "header:$title:$occurrence"
    }
    data class Chapter(val chapter: NovelChapterDirectoryEntry, val originalIndex: Int) : NovelChapterDirectoryItem {
        override val key = "chapter:${chapter.id}:$originalIndex"
    }
}

enum class NovelChapterReadState { READ, CURRENT, UNREAD }

fun novelChapterReadState(index: Int, currentIndex: Int): NovelChapterReadState = when {
    index == currentIndex -> NovelChapterReadState.CURRENT
    index < currentIndex -> NovelChapterReadState.READ
    else -> NovelChapterReadState.UNREAD
}

fun novelDirectoryPositionForCurrent(items: List<NovelChapterDirectoryItem>, currentIndex: Int): Int =
    items.indexOfFirst { it is NovelChapterDirectoryItem.Chapter && it.originalIndex == currentIndex }

fun buildNovelChapterDirectoryItems(
    chapters: List<NovelChapterDirectoryEntry>,
    reversed: Boolean,
    query: String,
    volumeTitle: (Int) -> String = { "Volume $it" },
): List<NovelChapterDirectoryItem> {
    val indexed = chapters.withIndex().let { if (reversed) it.reversed() else it }
    val filtered = indexed.filter { (_, chapter) ->
        query.isBlank() || (listOfNotNull(chapter.title) + chapter.searchAliases)
            .any { it.contains(query, ignoreCase = true) }
    }
    val result = mutableListOf<NovelChapterDirectoryItem>()
    var previousGroup: String? = null
    var previousVolume: Int? = null
    var headerOccurrence = 0
    filtered.forEach { (index, chapter) ->
        val group = chapter.groupTitle
        if (group != previousGroup) {
            if (group.isNotEmpty()) result += NovelChapterDirectoryItem.Header(group, headerOccurrence++)
            previousVolume = null
        }
        val volume = chapter.volume.takeIf { it > 0 }
        if (volume != null && volume != previousVolume) {
            result += NovelChapterDirectoryItem.Header(volumeTitle(volume), headerOccurrence++)
        }
        result += NovelChapterDirectoryItem.Chapter(chapter, index)
        previousGroup = group
        previousVolume = volume
    }
    return result
}
