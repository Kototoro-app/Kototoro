package org.skepsun.kototoro.reader.ui.pager

import android.content.res.Resources
import org.skepsun.kototoro.core.model.getLocalizedTitle
import org.skepsun.kototoro.parsers.model.ContentChapter

data class ReaderUiState(
    val mangaName: String?,
    val chapter: ContentChapter,
    val chapterIndex: Int,
    val chaptersTotal: Int,
    val currentPage: Int,
    val totalPages: Int,
    val percent: Float,
    val incognito: Boolean,
) {

    val chapterNumber: Int
        get() = chapterIndex + 1

    fun hasNextChapter(): Boolean = chapterNumber < chaptersTotal

    fun hasPreviousChapter(): Boolean = chapterIndex > 0

    fun isSliderAvailable(): Boolean = totalPages > 1 && currentPage < totalPages

    fun getChapterTitle(resources: Resources) = chapter.getLocalizedTitle(resources, chapterIndex)
}
