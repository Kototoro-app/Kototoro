package org.skepsun.kototoro.details.domain

import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.data.ReadingTime
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.stats.data.StatsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.roundToInt

class ReadingTimeUseCase @Inject constructor(
    private val settings: AppSettings,
    private val statsRepository: StatsRepository,
) {

    suspend operator fun invoke(manga: ContentDetails?, branch: String?, history: ContentHistory?): ReadingTime? {
        if (!settings.isReadingTimeEstimationEnabled) {
            return null
        }
        // Use all chapters for global reading time estimation
        val chapters = manga?.allChapters
        if (chapters.isNullOrEmpty()) {
            return null
        }
        val isOnHistoryBranch = history != null && chapters.findById(history.chapterId) != null
        // Impossible task, I guess. Good luck on this.
        var averageTimeSec: Int = 20 /* pages */ * getSecondsPerPage(manga.id) * chapters.size
        if (isOnHistoryBranch) {
            averageTimeSec = (averageTimeSec * (1f - history.percent)).roundToInt()
        }
        if (averageTimeSec < 60) {
            return null
        }
        return ReadingTime(
            minutes = (averageTimeSec / 60) % 60,
            hours = averageTimeSec / 3600,
            isContinue = isOnHistoryBranch,
        )
    }

    private suspend fun getSecondsPerPage(mangaId: Long): Int {
        var time = if (settings.isStatsEnabled) {
            TimeUnit.MILLISECONDS.toSeconds(statsRepository.getTimePerPage(mangaId)).toInt()
        } else {
            0
        }
        if (time == 0) {
            time = 10 // default
        }
        return time
    }
}
