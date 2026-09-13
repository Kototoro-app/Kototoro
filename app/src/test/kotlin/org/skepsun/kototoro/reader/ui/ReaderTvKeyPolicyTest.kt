package org.skepsun.kototoro.reader.ui

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderTvKeyPolicyTest {

    @Test
    fun `hidden reader owns navigation before focused pager across controls transitions`() {
        val keys = listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        // Opening dual-page settings and returning must give the keys back to content.
        listOf(false, true, false, true, false).forEach { controlsVisible ->
            keys.forEach { key ->
                val intercepted = shouldInterceptReaderTvKey(true, controlsVisible, key)
                if (controlsVisible) assertFalse(intercepted) else assertTrue(intercepted)
                assertFalse(shouldInterceptReaderTvKey(false, controlsVisible, key))
            }
        }
        assertFalse(shouldInterceptReaderTvKey(true, false, KeyEvent.KEYCODE_BACK))
        assertFalse(shouldInterceptReaderTvKey(true, false, KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `tv controls own dpad keys while visible`() {
        assertTrue(
            shouldDelegateReaderKeyToTvControls(
                isTvPresentation = true,
                controlsVisible = true,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            ),
        )
        assertTrue(
            shouldDelegateReaderKeyToTvControls(
                isTvPresentation = true,
                controlsVisible = true,
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            ),
        )
    }

    @Test
    fun `hidden controls keep dpad keys on reader content`() {
        assertFalse(
            shouldDelegateReaderKeyToTvControls(
                isTvPresentation = true,
                controlsVisible = false,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            ),
        )
    }

    @Test
    fun `standard reader behavior is unchanged`() {
        assertFalse(
            shouldDelegateReaderKeyToTvControls(
                isTvPresentation = false,
                controlsVisible = true,
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            ),
        )
    }
}
