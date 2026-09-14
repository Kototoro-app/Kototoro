package org.skepsun.kototoro.video.ui.compose

import android.view.KeyEvent

/**
 * Owns TV key events in the video player so that opening controls cannot redirect
 * key release or repeats to the newly focused Compose controls.
 */
internal class VideoPlayerTvKeyDispatcher {
    enum class DispatchResult {
        DELEGATE,
        CONSUME,
        EXECUTE_ACTION,
    }

    private val interceptedKeys = mutableMapOf<Int, VideoPlayerTvKeyAction>()

    fun dispatch(
        keyCode: Int,
        action: Int,
        isTvPresentation: Boolean,
        controlsVisible: Boolean,
        screenLocked: Boolean,
        repeatCount: Int = 0,
    ): Pair<DispatchResult, VideoPlayerTvKeyAction?> {
        if (!isTvPresentation) return DispatchResult.DELEGATE to null

        if (action == KeyEvent.ACTION_UP) {
            val owned = interceptedKeys.remove(keyCode)
            return if (owned != null && owned != VideoPlayerTvKeyAction.PASS_TO_FOCUS) {
                DispatchResult.CONSUME to owned
            } else {
                DispatchResult.DELEGATE to null
            }
        }

        if (action != KeyEvent.ACTION_DOWN) return DispatchResult.DELEGATE to null

        val previous = interceptedKeys[keyCode]
        if (previous != null) {
            if (screenLocked && previous != VideoPlayerTvKeyAction.PASS_TO_FOCUS) {
                // Once interrupted by lock, this press stays cancelled even if unlocked before release.
                interceptedKeys[keyCode] = VideoPlayerTvKeyAction.CONSUME
                return DispatchResult.CONSUME to VideoPlayerTvKeyAction.CONSUME
            }
            return if (previous == VideoPlayerTvKeyAction.PASS_TO_FOCUS) {
                DispatchResult.DELEGATE to null
            } else {
                val canRepeat = previous == VideoPlayerTvKeyAction.SEEK_BACKWARD ||
                    previous == VideoPlayerTvKeyAction.SEEK_FORWARD
                if (canRepeat) {
                    DispatchResult.EXECUTE_ACTION to previous
                } else {
                    DispatchResult.CONSUME to previous
                }
            }
        }

        if (repeatCount > 0) {
            return DispatchResult.DELEGATE to null
        }

        val keyAction = resolveVideoPlayerTvKeyAction(
            keyCode = keyCode,
            controlsVisible = controlsVisible,
            screenLocked = screenLocked,
        )

        interceptedKeys[keyCode] = keyAction

        return when (keyAction) {
            VideoPlayerTvKeyAction.PASS_TO_FOCUS -> DispatchResult.DELEGATE to null
            VideoPlayerTvKeyAction.CONSUME -> DispatchResult.CONSUME to keyAction
            else -> DispatchResult.EXECUTE_ACTION to keyAction
        }
    }

    fun reset() {
        interceptedKeys.clear()
    }
}
