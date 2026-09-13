package org.skepsun.kototoro.video.ui.compose

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoPlayerTvKeyPolicyTest {

    @Test
    fun `confirm reveals hidden controls`() {
        assertEquals(
            VideoPlayerTvKeyAction.SHOW_CONTROLS,
            resolveVideoPlayerTvKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                controlsVisible = false,
                screenLocked = false,
            ),
        )
    }

    @Test
    fun `visible controls keep directional keys in focus system`() {
        assertEquals(
            VideoPlayerTvKeyAction.PASS_TO_FOCUS,
            resolveVideoPlayerTvKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                controlsVisible = true,
                screenLocked = false,
            ),
        )
    }

    @Test
    fun `hidden controls turn dpad horizontal input into seek`() {
        assertEquals(
            VideoPlayerTvKeyAction.SEEK_BACKWARD,
            resolveVideoPlayerTvKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                controlsVisible = false,
                screenLocked = false,
            ),
        )
        assertEquals(
            VideoPlayerTvKeyAction.SEEK_FORWARD,
            resolveVideoPlayerTvKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                controlsVisible = false,
                screenLocked = false,
            ),
        )
    }

    @Test
    fun `media playback keys always toggle playback`() {
        assertEquals(
            VideoPlayerTvKeyAction.TOGGLE_PLAYBACK,
            resolveVideoPlayerTvKeyAction(
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                controlsVisible = false,
                screenLocked = false,
            ),
        )
    }

    @Test
    fun `screen lock consumes remote controls`() {
        assertEquals(
            VideoPlayerTvKeyAction.CONSUME,
            resolveVideoPlayerTvKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                controlsVisible = true,
                screenLocked = true,
            ),
        )
    }
}
