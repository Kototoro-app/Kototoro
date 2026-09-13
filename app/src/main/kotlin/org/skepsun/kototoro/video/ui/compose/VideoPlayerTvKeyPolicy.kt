package org.skepsun.kototoro.video.ui.compose

import android.view.KeyEvent

/**
 * The part of player key routing that is independent from Media3 and the Activity lifecycle.
 *
 * Controls own directional keys while they are visible. When the chrome is hidden, the same
 * keys are interpreted as a small seek or as a request to reveal the controls, which leaves the
 * Activity responsible only for the side effect.
 */
internal enum class VideoPlayerTvKeyAction {
    PASS_TO_FOCUS,
    SHOW_CONTROLS,
    TOGGLE_PLAYBACK,
    SEEK_BACKWARD,
    SEEK_FORWARD,
    CONSUME,
}

internal fun resolveVideoPlayerTvKeyAction(
    keyCode: Int,
    controlsVisible: Boolean,
    screenLocked: Boolean,
): VideoPlayerTvKeyAction {
    if (screenLocked) return VideoPlayerTvKeyAction.CONSUME
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        -> if (controlsVisible) {
            VideoPlayerTvKeyAction.PASS_TO_FOCUS
        } else {
            VideoPlayerTvKeyAction.SHOW_CONTROLS
        }

        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        -> VideoPlayerTvKeyAction.TOGGLE_PLAYBACK

        KeyEvent.KEYCODE_MEDIA_REWIND -> VideoPlayerTvKeyAction.SEEK_BACKWARD
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> VideoPlayerTvKeyAction.SEEK_FORWARD

        KeyEvent.KEYCODE_DPAD_LEFT -> if (controlsVisible) {
            VideoPlayerTvKeyAction.PASS_TO_FOCUS
        } else {
            VideoPlayerTvKeyAction.SEEK_BACKWARD
        }

        KeyEvent.KEYCODE_DPAD_RIGHT -> if (controlsVisible) {
            VideoPlayerTvKeyAction.PASS_TO_FOCUS
        } else {
            VideoPlayerTvKeyAction.SEEK_FORWARD
        }

        else -> VideoPlayerTvKeyAction.PASS_TO_FOCUS
    }
}
