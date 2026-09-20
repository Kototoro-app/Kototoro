package org.skepsun.kototoro.reader.ui

import android.content.res.Resources
import android.view.KeyEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.reader.data.TapGridSettings

/**
 * Improvement plan 2026-09 section 5.1, row "input": the DPAD, keyboard and volume-key mapping.
 *
 * The chain these keys feed is device-covered elsewhere — a page turn becomes a programmatic page
 * request for the paged host ([ScenePagedViewportResizeTest]) and a viewport-sized scroll for the
 * continuous one — but the mapping itself, including the boundary cases the plan calls out ("边界动作
 * 不产生错误进度"), had no test at all. A wrong mapping here looks exactly like a reader bug from the
 * outside, so it is pinned where it is cheap to pin.
 *
 * Each case builds its own delegate and listener: the listener is where every assertion counts calls,
 * so a shared one would accumulate them across a loop and quietly turn "once" into "once per
 * iteration".
 */
class ReaderControlDelegateKeyTest {

    private val settings = mockk<AppSettings>(relaxed = true)

    private class Case {
        val settings = mockk<AppSettings>(relaxed = true)
        val listener = mockk<ReaderControlDelegate.OnInteractionListener>(relaxed = true)
        val delegate = ReaderControlDelegate(
            resources = mockk<Resources>(relaxed = true),
            settings = settings,
            tapGridSettings = mockk<TapGridSettings>(relaxed = true),
            listener = listener,
        )
    }

    private fun case(): Case = Case()

    // --- page turns: the keys that must advance or rewind exactly one step ------------------------

    @Test
    fun `page turning keys advance the reader in the expected direction`() {
        val forward = listOf(
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_R,
        )
        val backward = listOf(
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_L,
        )
        forward.forEach { key ->
            val case = case()
            every { case.listener.scrollBy(any(), any()) } returns false
            case.delegate.onKeyDown(key, null)
            verify(exactly = 1) { case.listener.switchPageBy(1) }
        }
        backward.forEach { key ->
            val case = case()
            every { case.listener.scrollBy(any(), any()) } returns false
            case.delegate.onKeyDown(key, null)
            verify(exactly = 1) { case.listener.switchPageBy(-1) }
        }
    }

    @Test
    fun `horizontal dpad follows the reading direction and the inverted setting`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_RIGHT to false,
            KeyEvent.KEYCODE_DPAD_RIGHT to true,
            KeyEvent.KEYCODE_DPAD_LEFT to false,
            KeyEvent.KEYCODE_DPAD_LEFT to true,
        ).forEach { (key, inverted) ->
            val case = case()
            every { case.listener.scrollBy(any(), any()) } returns false
            every { case.settings.isReaderNavigationInverted } returns inverted
            case.delegate.onKeyDown(key, null)
            // Right advances unless the setting inverts it; left is the mirror image.
            val expected = if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (inverted) -1 else 1
            } else {
                if (inverted) 1 else -1
            }
            verify(exactly = 1) { case.listener.switchPageBy(expected) }
        }
    }

    // --- vertical keys: scroll first, and only fall back to a page turn at the boundary -----------

    @Test
    fun `vertical keys scroll and do not turn a page while the scroll is accepted`() {
        val case = case()
        every { case.listener.scrollBy(any(), any()) } returns true
        val handled = case.delegate.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, null)

        assertTrue(handled)
        verify(exactly = 1) { case.listener.scrollBy(any(), smooth = true) }
        verify(exactly = 0) { case.listener.switchPageBy(any()) }
    }

    @Test
    fun `vertical keys hand off to a page turn when the scroll is refused`() {
        val down = case()
        every { down.listener.scrollBy(any(), any()) } returns false
        down.delegate.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, null)
        verify(exactly = 1) { down.listener.switchPageBy(1) }

        val up = case()
        every { up.listener.scrollBy(any(), any()) } returns false
        up.delegate.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, null)
        verify(exactly = 1) { up.listener.switchPageBy(-1) }
    }

    // --- volume keys: opt-in, and a no-op that is not swallowed when the setting is off -----------

    @Test
    fun `volume keys turn pages only when the setting is enabled`() {
        val disabled = case()
        every { disabled.listener.scrollBy(any(), any()) } returns false
        every { disabled.settings.isReaderVolumeButtonsEnabled } returns false
        assertFalse(disabled.delegate.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, null))
        verify(exactly = 0) { disabled.listener.switchPageBy(any()) }

        val enabled = case()
        every { enabled.listener.scrollBy(any(), any()) } returns false
        every { enabled.settings.isReaderVolumeButtonsEnabled } returns true
        assertTrue(enabled.delegate.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, null))
        verify(exactly = 1) { enabled.listener.switchPageBy(1) }
    }

    @Test
    fun `volume up is a rewind unless navigation is inverted`() {
        val normal = case()
        every { normal.listener.scrollBy(any(), any()) } returns false
        every { normal.settings.isReaderVolumeButtonsEnabled } returns true
        every { normal.settings.isReaderNavigationInverted } returns false
        normal.delegate.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, null)
        verify(exactly = 1) { normal.listener.switchPageBy(-1) }

        val inverted = case()
        every { inverted.listener.scrollBy(any(), any()) } returns false
        every { inverted.settings.isReaderVolumeButtonsEnabled } returns true
        every { inverted.settings.isReaderNavigationInverted } returns true
        inverted.delegate.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, null)
        verify(exactly = 1) { inverted.listener.switchPageBy(1) }
    }

    // --- the keys that must not move the reader at all --------------------------------------------

    @Test
    fun `unknown keys are not consumed and produce no progress`() {
        val case = case()
        assertFalse(case.delegate.onKeyDown(KeyEvent.KEYCODE_A, null))
        assertFalse(case.delegate.onKeyDown(KeyEvent.KEYCODE_BACK, null))
        verify(exactly = 0) { case.listener.switchPageBy(any()) }
        verify(exactly = 0) { case.listener.switchChapterBy(any()) }
        verify(exactly = 0) { case.listener.scrollBy(any(), any()) }
    }

    @Test
    fun `confirm keys only reach the reader under the tv presentation`() {
        val phone = case()
        every { phone.listener.isTvPresentation } returns false
        listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER).forEach { key ->
            assertFalse(phone.delegate.onKeyDown(key, null))
        }
        verify(exactly = 0) { phone.listener.toggleUiVisibility() }

        val tv = case()
        every { tv.listener.isTvPresentation } returns true
        listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER).forEach { key ->
            assertTrue(tv.delegate.onKeyDown(key, null))
        }
        verify(exactly = 2) { tv.listener.toggleUiVisibility() }
    }

    @Test
    fun `dpad center toggles the controls`() {
        val case = case()
        assertTrue(case.delegate.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, null))
        verify(exactly = 1) { case.listener.toggleUiVisibility() }
    }

    // --- TV controls own the navigation keys while they are visible -------------------------------

    @Test
    fun `visible tv controls take the navigation keys away from the reader`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        ).forEach { key ->
            val case = case()
            every { case.listener.isTvPresentation } returns true
            every { case.listener.isReaderControlsVisible } returns true
            assertFalse(case.delegate.onKeyDown(key, null), "key $key should go to the controls")
            verify(exactly = 0) { case.listener.switchPageBy(any()) }
            verify(exactly = 0) { case.listener.scrollBy(any(), any()) }
        }
    }

    // --- volume key release is only consumed while the feature is on ------------------------------

    @Test
    fun `volume key release is consumed exactly when the feature is enabled`() {
        val disabled = case()
        every { disabled.settings.isReaderVolumeButtonsEnabled } returns false
        assertFalse(disabled.delegate.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, null))

        val enabled = case()
        every { enabled.settings.isReaderVolumeButtonsEnabled } returns true
        assertTrue(enabled.delegate.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, null))
        assertTrue(enabled.delegate.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, null))
        assertFalse(enabled.delegate.onKeyUp(KeyEvent.KEYCODE_PAGE_DOWN, null))
    }
}
