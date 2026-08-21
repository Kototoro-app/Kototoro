package org.skepsun.kototoro.alternatives.ui

import org.skepsun.kototoro.core.model.chaptersCount
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.parsers.model.Content

data class ContentAlternativeModel(
    val mangaModel: ContentGridModel,
    private val referenceChapters: Int,
) : ListModel {

    val manga: Content
        get() = mangaModel.manga

    val chaptersCount = manga.chaptersCount()

    val chaptersDiff: Int
        get() = if (referenceChapters == 0 || chaptersCount == 0) 0 else chaptersCount - referenceChapters

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is ContentAlternativeModel && other.manga.id == manga.id
    }

    override fun getChangePayload(previousState: ListModel): Any? = if (previousState is ContentAlternativeModel) {
        mangaModel.getChangePayload(previousState.mangaModel)
    } else {
        null
    }
}
