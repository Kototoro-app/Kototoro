package org.skepsun.kototoro.video.ui.compose

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoPlayerTvKeyDispatcherTest {

    private val dispatcher = VideoPlayerTvKeyDispatcher()

    @Test
    fun `locking during held seek cancels repeats until release`() {
        val key = KeyEvent.KEYCODE_DPAD_RIGHT
        assertEquals(
            VideoPlayerTvKeyDispatcher.DispatchResult.EXECUTE_ACTION,
            dispatcher.dispatch(key, KeyEvent.ACTION_DOWN, true, false, false).first,
        )
        assertEquals(
            VideoPlayerTvKeyDispatcher.DispatchResult.CONSUME,
            dispatcher.dispatch(key, KeyEvent.ACTION_DOWN, true, true, true, 1).first,
        )
        // Unlocking must not revive the same held press.
        assertEquals(
            VideoPlayerTvKeyDispatcher.DispatchResult.CONSUME,
            dispatcher.dispatch(key, KeyEvent.ACTION_DOWN, true, true, false, 2).first,
        )
        assertEquals(
            VideoPlayerTvKeyDispatcher.DispatchResult.CONSUME,
            dispatcher.dispatch(key, KeyEvent.ACTION_UP, true, true, false).first,
        )
        assertEquals(
            VideoPlayerTvKeyDispatcher.DispatchResult.EXECUTE_ACTION,
            dispatcher.dispatch(key, KeyEvent.ACTION_DOWN, true, false, false).first,
        )
    }

    @Test
    fun `locked back press and release reach the unlock handler`() {
        for (eventAction in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val result = dispatcher.dispatch(
                keyCode = KeyEvent.KEYCODE_BACK,
                action = eventAction,
                isTvPresentation = true,
                controlsVisible = false,
                screenLocked = true,
            )
            assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.DELEGATE, result.first)
        }
    }

    @Test
    fun `media playback and chapter keys execute once and own their release`() {
        for ((keyCode, expectedAction) in listOf(
            KeyEvent.KEYCODE_MEDIA_NEXT to VideoPlayerTvKeyAction.NEXT_CHAPTER,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to VideoPlayerTvKeyAction.PREVIOUS_CHAPTER,
            KeyEvent.KEYCODE_MEDIA_PLAY to VideoPlayerTvKeyAction.PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE to VideoPlayerTvKeyAction.PAUSE,
        )) {
            for ((eventAction, repeatCount, expectedResult) in listOf(
                Triple(KeyEvent.ACTION_DOWN, 0, VideoPlayerTvKeyDispatcher.DispatchResult.EXECUTE_ACTION),
                Triple(KeyEvent.ACTION_DOWN, 1, VideoPlayerTvKeyDispatcher.DispatchResult.CONSUME),
                Triple(KeyEvent.ACTION_UP, 0, VideoPlayerTvKeyDispatcher.DispatchResult.CONSUME),
            )) {
                assertEquals(
                    expectedResult to expectedAction,
                    dispatcher.dispatch(keyCode, eventAction, true, false, false, repeatCount),
                )
            }
        }
    }

    @Test
    fun `hidden controls own center press through release`() {
        val down = dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_DOWN,
            isTvPresentation = true,
            controlsVisible = false,
            screenLocked = false,
        )
        assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.EXECUTE_ACTION, down.first)
        assertEquals(VideoPlayerTvKeyAction.SHOW_CONTROLS, down.second)

        // Controls are now visible, but release must still be consumed by player
        val up = dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_UP,
            isTvPresentation = true,
            controlsVisible = true,
            screenLocked = false,
        )
        assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.CONSUME, up.first)
        assertEquals(VideoPlayerTvKeyAction.SHOW_CONTROLS, up.second)
    }

    @Test
    fun `visible controls delegate center key to focus system`() {
        val down = dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_DOWN,
            isTvPresentation = true,
            controlsVisible = true,
            screenLocked = false,
        )
        assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.DELEGATE, down.first)

        val up = dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_UP,
            isTvPresentation = true,
            controlsVisible = true,
            screenLocked = false,
        )
        assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.DELEGATE, up.first)
    }

    @Test
    fun `hidden controls allow seek keys to repeat`() {
        val initial = dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_DOWN,
            isTvPresentation = true,
            controlsVisible = false,
            screenLocked = false,
            repeatCount = 0,
        )
        assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.EXECUTE_ACTION, initial.first)
        assertEquals(VideoPlayerTvKeyAction.SEEK_FORWARD, initial.second)

        val repeat = dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_DOWN,
            isTvPresentation = true,
            controlsVisible = false,
            screenLocked = false,
            repeatCount = 1,
        )
        assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.EXECUTE_ACTION, repeat.first)
        assertEquals(VideoPlayerTvKeyAction.SEEK_FORWARD, repeat.second)

        val up = dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_UP,
            isTvPresentation = true,
            controlsVisible = false,
            screenLocked = false,
        )
        assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.CONSUME, up.first)
    }

    @Test
    fun `standard presentation always delegates`() {
        val down = dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_DOWN,
            isTvPresentation = false,
            controlsVisible = false,
            screenLocked = false,
        )
        assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.DELEGATE, down.first)
    }

    @Test
    fun `reset clears key ownership`() {
        dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_DOWN,
            isTvPresentation = true,
            controlsVisible = false,
            screenLocked = false,
        )
        dispatcher.reset()

        val up = dispatcher.dispatch(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_UP,
            isTvPresentation = true,
            controlsVisible = false,
            screenLocked = false,
        )
        assertEquals(VideoPlayerTvKeyDispatcher.DispatchResult.DELEGATE, up.first)
    }
}
