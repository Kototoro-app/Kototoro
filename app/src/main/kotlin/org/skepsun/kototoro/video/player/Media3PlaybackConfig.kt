package org.skepsun.kototoro.video.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters

/** Media3 defaults aligned with CloudStream's internal player where they are source-agnostic. */
@OptIn(UnstableApi::class)
internal object Media3PlaybackConfig {
    const val SEEK_TOLERANCE_US = 300_000L
    const val BACK_BUFFER_MS = 30_000
    const val PREFERRED_LIVE_OFFSET_MS = 5_000L
    const val MIN_LIVE_PLAYBACK_SPEED = 0.97f
    const val MAX_LIVE_PLAYBACK_SPEED = 1.03f

    fun seekParameters(): SeekParameters = SeekParameters(SEEK_TOLERANCE_US, SEEK_TOLERANCE_US)

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
        .setBackBuffer(BACK_BUFFER_MS, true)
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()

    fun livePlaybackSpeedControl(): DefaultLivePlaybackSpeedControl =
        DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(MIN_LIVE_PLAYBACK_SPEED)
            .setFallbackMaxPlaybackSpeed(MAX_LIVE_PLAYBACK_SPEED)
            .build()
}
