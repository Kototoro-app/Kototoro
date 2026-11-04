package org.skepsun.kototoro.download.ui.worker

import android.content.Context
import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.SourceSettings
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSlowdownDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mangaRepositoryFactory: ContentRepository.Factory,
    private val settings: AppSettings,
) {
    private val timeMap = MutableObjectLongMap<ContentSource>()

    suspend fun delay(source: ContentSource) {
        if (!isSlowdownEnabled(source)) {
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

    /**
     * Spacing applies only to a source whose "slow down downloads" switch is on. Only parser repositories read that
     * switch themselves; every other repository (Mihon, Aniyomi, Legado, ...) inherits a default that is true for any
     * remote source, which serialized all of their page requests at the request delay regardless of the thread count.
     * The switch lives in the same per-source preferences for every kind of source, so read it here.
     */
    private fun isSlowdownEnabled(source: ContentSource): Boolean =
        mangaRepositoryFactory.create(source).isSlowdownEnabled() && SourceSettings(context, source).isSlowdownEnabled
}
