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
    /** Checks failed in a row ([org.skepsun.kototoro.tracker.data.TrackScheduleEntity.failCount]). */
    val failCount: Int = 0,
    /** When background checks next look at the work; null before its first scheduled check. */
    val nextCheckTime: Instant? = null,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is TrackDebugItem && other.manga.id == manga.id
    }
}
