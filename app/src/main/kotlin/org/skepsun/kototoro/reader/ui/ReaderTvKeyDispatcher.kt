package org.skepsun.kototoro.reader.ui

import android.view.KeyEvent

/** Owns a full TV key press so opening controls cannot redirect its repeat or release. */
internal class ReaderTvKeyDispatcher {
    enum class Action { DELEGATE, CONSUME, DISPATCH_TO_READER }

    private val pressedKeys = mutableMapOf<Int, Boolean>()

    fun dispatch(
        keyCode: Int,
        action: Int,
        isTvPresentation: Boolean,
        controlsVisible: Boolean,
        repeatCount: Int = 0,
    ): Action {
        if (action == KeyEvent.ACTION_UP) {
            return if (pressedKeys.remove(keyCode) == true) Action.CONSUME else Action.DELEGATE
        }
        if (action != KeyEvent.ACTION_DOWN) return Action.DELEGATE
        val previousOwner = pressedKeys[keyCode]
        if (previousOwner == false) return Action.DELEGATE
        if (previousOwner == null && (repeatCount > 0 || !isTvPresentation || !isReaderTvNavigationKey(keyCode))) {
            return Action.DELEGATE
        }
        val contentOwnsKey = shouldInterceptReaderTvKey(isTvPresentation, controlsVisible, keyCode)
        if (previousOwner == null) pressedKeys[keyCode] = contentOwnsKey
        if (!contentOwnsKey) return if (previousOwner == true) Action.CONSUME else Action.DELEGATE
        val isDirection = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        }
        return if (previousOwner == null || isDirection) Action.DISPATCH_TO_READER else Action.CONSUME
    }

    fun reset() {
        pressedKeys.clear()
    }
}
