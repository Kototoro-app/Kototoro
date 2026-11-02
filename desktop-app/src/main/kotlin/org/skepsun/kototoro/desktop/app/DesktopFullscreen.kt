package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import org.skepsun.kototoro.desktop.runtime.DesktopPlatform
import java.awt.Frame
import java.awt.Rectangle

/**
 * The reader's and player's fullscreen. Compose's [WindowPlacement.Fullscreen] is AWT's exclusive fullscreen on
 * Windows (`GraphicsDevice.setFullScreenWindow`): the window leaves it, or is minimised, as soon as anything else is
 * activated, which the player's chrome windows and switching apps both do, and the video's GPU surfaces did not
 * survive that. On Windows the window instead loses its frame and covers its monitor, as video players do; elsewhere
 * the platform's own fullscreen stays.
 */
internal class DesktopFullscreen(private val window: ComposeWindow, private val state: WindowState) {
    private var borderless by mutableStateOf<Saved?>(null)
    private var previousPlacement = WindowPlacement.Floating

    val active: Boolean get() = borderless != null || state.placement == WindowPlacement.Fullscreen

    fun toggle() {
        if (DesktopPlatform.current != DesktopPlatform.WINDOWS) {
            if (state.placement == WindowPlacement.Fullscreen) state.placement = previousPlacement
            else { previousPlacement = state.placement; state.placement = WindowPlacement.Fullscreen }
            return
        }
        val saved = borderless
        if (saved == null) enter() else exit(saved)
    }

    private class Saved(val style: Int, val bounds: Rectangle, val extendedState: Int)

    private fun enter() {
        val handle = Native.getWindowPointer(window) ?: return
        val saved = Saved(User32.INSTANCE.GetWindowLongW(handle, GWL_STYLE), window.bounds, window.extendedState)
        // A maximised window keeps its maximised size when its frame goes; fill the monitor from a normal one.
        if (window.extendedState and Frame.MAXIMIZED_BOTH != 0) window.extendedState = Frame.NORMAL
        User32.INSTANCE.SetWindowLongW(handle, GWL_STYLE, saved.style and (WS_CAPTION or WS_THICKFRAME).inv())
        User32.INSTANCE.SetWindowPos(handle, null, 0, 0, 0, 0, SWP_FRAME_ONLY)
        window.bounds = window.graphicsConfiguration.bounds
        borderless = saved
    }

    private fun exit(saved: Saved) {
        val handle = Native.getWindowPointer(window)
        if (handle != null) {
            User32.INSTANCE.SetWindowLongW(handle, GWL_STYLE, saved.style)
            User32.INSTANCE.SetWindowPos(handle, null, 0, 0, 0, 0, SWP_FRAME_ONLY)
        }
        window.bounds = saved.bounds
        if (saved.extendedState and Frame.MAXIMIZED_BOTH != 0) window.extendedState = saved.extendedState
        borderless = null
    }

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun GetWindowLongW(window: Pointer, index: Int): Int
        fun SetWindowLongW(window: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(window: Pointer, after: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean

        companion object {
            val INSTANCE: User32 by lazy { Native.load("user32", User32::class.java) }
        }
    }

    private companion object {
        const val GWL_STYLE = -16
        const val WS_CAPTION = 0x00C00000
        const val WS_THICKFRAME = 0x00040000
        // SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED: only re-read the frame style.
        const val SWP_FRAME_ONLY = 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020
    }
}

@Composable
internal fun rememberDesktopFullscreen(window: ComposeWindow, state: WindowState): DesktopFullscreen =
    remember(window, state) { DesktopFullscreen(window, state) }
