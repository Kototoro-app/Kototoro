package org.skepsun.kototoro.reader.novel.compose

internal typealias NovelChapterRowState = NovelChapterReadState

internal fun novelChapterRowState(index: Int, currentIndex: Int): NovelChapterRowState =
    novelChapterReadState(index, currentIndex)
