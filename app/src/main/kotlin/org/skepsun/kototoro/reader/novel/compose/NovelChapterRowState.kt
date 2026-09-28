package org.skepsun.kototoro.reader.novel.compose

internal enum class NovelChapterRowState { READ, CURRENT, UNREAD }

internal fun novelChapterRowState(index: Int, currentIndex: Int): NovelChapterRowState = when {
    index == currentIndex -> NovelChapterRowState.CURRENT
    index < currentIndex -> NovelChapterRowState.READ
    else -> NovelChapterRowState.UNREAD
}
