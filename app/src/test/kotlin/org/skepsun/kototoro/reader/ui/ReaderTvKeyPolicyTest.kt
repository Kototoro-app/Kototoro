package org.skepsun.kototoro.reader.ui

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderTvKeyPolicyTest {

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
