package org.skepsun.kototoro.reader.ui

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.ui.ReaderTvKeyDispatcher.Action.*

class ReaderTvKeyDispatcherTest {
    private val dispatcher = ReaderTvKeyDispatcher()

    @Test
    fun `confirm opens controls once and consumes repeat and release`() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            assertEquals(DISPATCH_TO_READER, down(key))
            assertEquals(CONSUME, down(key, controlsVisible = true, repeatCount = 1))
            assertEquals(CONSUME, up(key, controlsVisible = true))
            assertEquals(DELEGATE, down(key, controlsVisible = true))
            assertEquals(DELEGATE, up(key, controlsVisible = true))
        }
    }

    @Test
    fun `held confirm never toggles again even if controls close`() {
        assertEquals(DISPATCH_TO_READER, down(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(CONSUME, down(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1))
        assertEquals(CONSUME, up(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(DISPATCH_TO_READER, down(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun `direction holds repeat page movement until controls open`() {
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )) {
            assertEquals(DISPATCH_TO_READER, down(key))
            assertEquals(DISPATCH_TO_READER, down(key, repeatCount = 1))
            assertEquals(CONSUME, down(key, controlsVisible = true, repeatCount = 2))
            assertEquals(CONSUME, up(key, controlsVisible = true))
        }
    }

    @Test
    fun `press started in controls remains theirs after controls close`() {
        val key = KeyEvent.KEYCODE_DPAD_RIGHT
        assertEquals(DELEGATE, down(key, controlsVisible = true))
        assertEquals(DELEGATE, down(key, repeatCount = 1))
        assertEquals(DELEGATE, up(key))
        assertEquals(DISPATCH_TO_READER, down(key))
    }

    @Test
    fun `pause clears ownership and ignores orphaned repeat`() {
        val key = KeyEvent.KEYCODE_DPAD_RIGHT
        assertEquals(DISPATCH_TO_READER, down(key))
        dispatcher.reset()
        assertEquals(DELEGATE, down(key, repeatCount = 1))
        assertEquals(DELEGATE, up(key))
        assertEquals(DISPATCH_TO_READER, down(key))
    }

    @Test
    fun `keys outside TV navigation are delegated`() {
        for (key in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_A)) {
            assertEquals(DELEGATE, down(key))
            assertEquals(DELEGATE, up(key))
        }
        assertEquals(DELEGATE, dispatcher.dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, false, false))
        assertEquals(DELEGATE, dispatcher.dispatch(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_MULTIPLE, true, false))
    }

    @Test
    fun `simultaneous keys retain independent release ownership`() {
        assertEquals(DISPATCH_TO_READER, down(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(DISPATCH_TO_READER, down(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(CONSUME, up(KeyEvent.KEYCODE_DPAD_CENTER, controlsVisible = true))
        assertEquals(CONSUME, up(KeyEvent.KEYCODE_DPAD_LEFT, controlsVisible = true))
        assertEquals(DELEGATE, up(KeyEvent.KEYCODE_DPAD_LEFT, controlsVisible = true))
    }

    private fun down(key: Int, controlsVisible: Boolean = false, repeatCount: Int = 0) =
        dispatcher.dispatch(key, KeyEvent.ACTION_DOWN, true, controlsVisible, repeatCount)

    private fun up(key: Int, controlsVisible: Boolean = false) =
        dispatcher.dispatch(key, KeyEvent.ACTION_UP, true, controlsVisible)
}
