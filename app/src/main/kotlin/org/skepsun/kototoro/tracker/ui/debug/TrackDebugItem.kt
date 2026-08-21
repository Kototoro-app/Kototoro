package org.skepsun.kototoro.tracker.ui.debug

import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.parsers.model.Content
import java.time.Instant

data class TrackDebugItem(
    val manga: Content,
    val lastChapterId: Long,
    val newChapters: Int,
    val lastCheckTime: Instant?,
    val lastChapterDate: Instant?,
    val lastResult: Int,
    val lastError: String?,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is TrackDebugItem && other.manga.id == manga.id
    }
}
