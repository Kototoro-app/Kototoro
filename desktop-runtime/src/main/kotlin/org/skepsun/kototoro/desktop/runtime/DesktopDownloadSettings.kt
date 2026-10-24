package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.download.DownloadPolicy
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences

/**
 * Android's "下载速度与重试" section as one value: how many pages one chapter fetches at a time, how many series may run
 * at once, the spacing between requests and how a failed page is retried. The keys are Android's
 * (`downloads_threads`, ...) and every value passes through the shared clamps, so a hand-edited store cannot ask for
 * eleven threads or a 20-minute retry delay.
 *
 * The spacing default is 0, unlike Android's 1600 ms: Android only applies that delay to sources that declare
 * `isSlowdownEnabled`, a flag the shared source contract does not expose, so a default-on delay here would throttle
 * every Windows download. The slider starts at "off" and the user can still space requests per title.
 */
data class DesktopDownloadSettings(
    val threads: Int = DownloadPolicy.THREADS_DEFAULT,
    val maxActiveSeries: Int = DownloadPolicy.MAX_ACTIVE_SERIES_DEFAULT,
    val requestDelayMs: Int = 0,
    val retryCount: Int = DownloadPolicy.RETRY_COUNT_DEFAULT,
    val retryDelayMs: Int = DownloadPolicy.RETRY_DELAY_DEFAULT_MS,
) {
    /** The series cap the queue enforces; `null` when the user picked "no limit". */
    val activeSeriesLimit: Int? get() = DownloadPolicy.activeSeriesLimit(maxActiveSeries)

    val isSeriesUnlimited: Boolean get() = activeSeriesLimit == null

    fun toPreferences(): Map<String, SourcePreferenceValue?> = mapOf(
        KEY_THREADS to SourcePreferenceValue.Integer(threads),
        KEY_MAX_ACTIVE_SERIES to SourcePreferenceValue.Integer(maxActiveSeries),
        KEY_REQUEST_DELAY to SourcePreferenceValue.Integer(requestDelayMs),
        KEY_RETRY_COUNT to SourcePreferenceValue.Integer(retryCount),
        KEY_RETRY_DELAY to SourcePreferenceValue.Integer(retryDelayMs),
    )

    companion object {
        // Android's own keys, so a store written by either platform reads back the same numbers.
        const val KEY_THREADS = "downloads_threads"
        const val KEY_MAX_ACTIVE_SERIES = "downloads_max_active_series"
        const val KEY_REQUEST_DELAY = "downloads_request_delay"
        const val KEY_RETRY_COUNT = "downloads_retry_count"
        const val KEY_RETRY_DELAY = "downloads_retry_delay"

        fun from(values: Map<String, SourcePreferenceValue>): DesktopDownloadSettings {
            fun number(key: String, fallback: Int): Int =
                (values[key] as? SourcePreferenceValue.Integer)?.value ?: fallback

            return DesktopDownloadSettings(
                threads = DownloadPolicy.threads(number(KEY_THREADS, DownloadPolicy.THREADS_DEFAULT)),
                maxActiveSeries = DownloadPolicy.maxActiveSeries(
                    number(KEY_MAX_ACTIVE_SERIES, DownloadPolicy.MAX_ACTIVE_SERIES_DEFAULT),
                ),
                requestDelayMs = DownloadPolicy.requestDelayMs(number(KEY_REQUEST_DELAY, 0)),
                retryCount = DownloadPolicy.retryCount(number(KEY_RETRY_COUNT, DownloadPolicy.RETRY_COUNT_DEFAULT)),
                retryDelayMs = DownloadPolicy.retryDelayMs(
                    number(KEY_RETRY_DELAY, DownloadPolicy.RETRY_DELAY_DEFAULT_MS),
                ),
            )
        }
    }
}

/**
 * The stored copy of [DesktopDownloadSettings]. Its own store (`desktop_download_settings`) because
 * [org.skepsun.kototoro.source.host.FileSourcePreferenceStore] already holds the download *records* under
 * `desktop_downloads`; sharing that name would make the record loader read settings entries as chapter manifests.
 *
 * The queue reads [settings] when it starts a chapter or a page, so a change on the settings page applies to the next
 * request without restarting anything.
 */
class DesktopDownloadPreferences(private val preferences: SourcePreferences) {
    private val mutableSettings = MutableStateFlow(DesktopDownloadSettings.from(preferences.snapshot()))
    val settings: StateFlow<DesktopDownloadSettings> = mutableSettings.asStateFlow()

    fun current(): DesktopDownloadSettings = mutableSettings.value

    fun save(value: DesktopDownloadSettings): Boolean {
        val persisted = preferences.edit(SourcePreferenceEdit(changes = value.toPreferences()))
        mutableSettings.value = value
        return persisted
    }

    fun reload() {
        mutableSettings.value = DesktopDownloadSettings.from(preferences.snapshot())
    }
}
