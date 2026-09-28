package org.skepsun.kototoro.download.ui.worker

import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import kotlinx.coroutines.delay
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSlowdownDispatcher @Inject constructor(
    private val mangaRepositoryFactory: ContentRepository.Factory,
    private val settings: AppSettings,
) {
    private val timeMap = MutableObjectLongMap<ContentSource>()

    suspend fun delay(source: ContentSource) {
        val repo = mangaRepositoryFactory.create(source)
        if (!repo.isSlowdownEnabled()) {
            return
        }
        val delayMs = DownloadPolicy.sourceDelayMs(settings.downloadRequestDelayMs)
        if (delayMs <= 0L) {
            return
        }
        val waitMs = synchronized(timeMap) {
            val now = SystemClock.elapsedRealtime()
            val nextAllowed = timeMap.getOrDefault(source, 0L)
            val scheduledAt = maxOf(now, nextAllowed)
            timeMap[source] = scheduledAt + delayMs
            scheduledAt - now
        }
        if (waitMs > 0L) {
            delay(waitMs)
        }
    }
}
