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
        val delayMs = if (settings.isDownloadAlignedWithReader) {
            0L
        } else {
            settings.downloadRequestDelayMs.toLong()
        }
        if (delayMs <= 0L) {
            return
        }
        val lastRequest = synchronized(timeMap) {
            val res = timeMap.getOrDefault(source, 0L)
            timeMap[source] = SystemClock.elapsedRealtime()
            res
        }
        if (lastRequest != 0L) {
            val waitMs = lastRequest + delayMs - SystemClock.elapsedRealtime()
            if (waitMs > 0L) {
                delay(waitMs)
            }
        }
    }
}
