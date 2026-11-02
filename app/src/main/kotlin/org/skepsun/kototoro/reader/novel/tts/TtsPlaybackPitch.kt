package org.skepsun.kototoro.reader.novel.tts

import android.content.SharedPreferences

/** Pitch changes apply during playback without invalidating synthesized audio. */
object TtsPlaybackPitch {
    const val KEY = "tts_pitch"
    const val DEFAULT = 1f
    const val MIN = 0.5f
    const val MAX = 2f

    val OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    fun read(prefs: SharedPreferences): Float = normalize(
        runCatching { prefs.getFloat(KEY, DEFAULT) }.getOrDefault(DEFAULT),
    )

    fun write(prefs: SharedPreferences, pitch: Float) {
        prefs.edit().putFloat(KEY, normalize(pitch)).apply()
    }

    fun normalize(pitch: Float): Float = if (pitch.isFinite()) pitch.coerceIn(MIN, MAX) else DEFAULT

    fun label(pitch: Float): String = TtsPlaybackSpeed.label(normalize(pitch))
}
