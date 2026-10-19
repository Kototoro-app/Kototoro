package org.skepsun.kototoro.reader.novel.tts

import android.content.SharedPreferences
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * TTS speed is applied by the player (time-stretch keeps the pitch), so every engine honours it,
 * already synthesized audio and the cache stay valid, and a change takes effect immediately.
 */
object TtsPlaybackSpeed {

    const val KEY = "tts_speed"
    const val DEFAULT = 1f
    const val MIN = 0.5f
    const val MAX = 3f

    val OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    fun read(prefs: SharedPreferences): Float = normalize(
        runCatching { prefs.getFloat(KEY, DEFAULT) }.getOrDefault(DEFAULT),
    )

    fun write(prefs: SharedPreferences, speed: Float) {
        prefs.edit().putFloat(KEY, normalize(speed)).apply()
    }

    fun normalize(speed: Float): Float = if (speed.isFinite()) speed.coerceIn(MIN, MAX) else DEFAULT

    fun label(speed: Float): String {
        val format = DecimalFormat("0.0#", DecimalFormatSymbols.getInstance(Locale.ROOT))
        return format.format(normalize(speed)) + "×"
    }
}
