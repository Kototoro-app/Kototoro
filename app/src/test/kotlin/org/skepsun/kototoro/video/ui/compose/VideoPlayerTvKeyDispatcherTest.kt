package org.skepsun.kototoro.video.ui.compose

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoPlayerTvKeyDispatcherTest {

    private val dispatcher = VideoPlayerTvKeyDispatcher()

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
