package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.download.DownloadPolicy
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.nio.file.Path

class DesktopDownloadSettingsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `an empty store reads Android's defaults except for the request spacing`() {
        val settings = DesktopDownloadSettings.from(emptyMap())
        assertEquals(DownloadPolicy.THREADS_DEFAULT, settings.threads)
        assertEquals(DownloadPolicy.MAX_ACTIVE_SERIES_DEFAULT, settings.maxActiveSeries)
        assertEquals(0, settings.requestDelayMs, "Android's spacing is gated by a source flag Windows cannot see")
        assertEquals(DownloadPolicy.RETRY_COUNT_DEFAULT, settings.retryCount)
        assertEquals(DownloadPolicy.RETRY_DELAY_DEFAULT_MS, settings.retryDelayMs)
        assertEquals(DownloadPolicy.MAX_ACTIVE_SERIES_DEFAULT, settings.activeSeriesLimit)
        assertFalse(settings.isSeriesUnlimited)
    }

    @Test
    fun `stored values survive a reload under Android's key names`() {
        FileSourcePreferenceStore(directory.resolve("prefs")).use { store ->
            val preferences = DesktopDownloadPreferences(store.open("desktop_download_settings"))
            val saved = DesktopDownloadSettings(
                threads = 7,
                maxActiveSeries = DownloadPolicy.MAX_ACTIVE_SERIES_UNLIMITED,
                requestDelayMs = 900,
                retryCount = 5,
                retryDelayMs = 3000,
            )
            assertTrue(preferences.save(saved))
            assertEquals(saved, DesktopDownloadPreferences(store.open("desktop_download_settings")).current())
            assertEquals(saved, preferences.settings.value)
            assertTrue(preferences.current().isSeriesUnlimited)
            assertNull(preferences.current().activeSeriesLimit)
            val snapshot = store.open("desktop_download_settings").snapshot()
            assertEquals(setOf("downloads_threads", "downloads_max_active_series", "downloads_request_delay",
                "downloads_retry_count", "downloads_retry_delay"), snapshot.keys)
        }
    }

    @Test
    fun `hand edited values are clamped when they are read back`() {
        val settings = DesktopDownloadSettings.from(mapOf(
            DesktopDownloadSettings.KEY_THREADS to SourcePreferenceValue.Integer(400),
            DesktopDownloadSettings.KEY_MAX_ACTIVE_SERIES to SourcePreferenceValue.Integer(-3),
            DesktopDownloadSettings.KEY_REQUEST_DELAY to SourcePreferenceValue.Integer(90_000),
            DesktopDownloadSettings.KEY_RETRY_COUNT to SourcePreferenceValue.Integer(0),
            DesktopDownloadSettings.KEY_RETRY_DELAY to SourcePreferenceValue.Integer(1),
        ))
        assertEquals(DownloadPolicy.THREADS_MAX, settings.threads)
        assertEquals(1, settings.maxActiveSeries)
        assertEquals(DownloadPolicy.REQUEST_DELAY_MAX_MS, settings.requestDelayMs)
        assertEquals(DownloadPolicy.RETRY_COUNT_MIN, settings.retryCount)
        assertEquals(DownloadPolicy.RETRY_DELAY_MIN_MS, settings.retryDelayMs)
    }

    @Test
    fun `a value of the wrong type falls back to the default instead of failing`() {
        val settings = DesktopDownloadSettings.from(mapOf(
            DesktopDownloadSettings.KEY_THREADS to SourcePreferenceValue.Text("six"),
        ))
        assertEquals(DownloadPolicy.THREADS_DEFAULT, settings.threads)
    }
}
