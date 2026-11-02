package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig

/**
 * Puts the ring on the first control of a sheet that opens in a window of its own.
 *
 * A `ModalBottomSheet` is a dialog window, and a fresh window starts with nothing focused: the
 * sheet's own drag handle is the only candidate, so the first direction press either lands on a
 * control that does nothing when clicked or is lost entirely - the remote looks broken until the
 * user presses something random. Compose also drops a programmatic request while the window is in
 * touch mode, and the sheet is measured and slid into place over several frames, so the request is
 * retried until the control reports the focus and holds it for a few frames (or the retries run out,
 * as on a screen that never gets one).
 *
 * The retries wait for the sheet's window to take the focus first: a request that lands while the
 * window is still unfocused succeeds and is then replaced by the window's own default focus, so the
 * ring ends up on whatever happens to be first in the content.
 *
 * Apply the returned requester - before the clickable/toggleable of the control - together with
 * [org.skepsun.kototoro.core.ui.adaptive.tvFocusable] and an `onFocusChanged` that feeds
 * [hasFocus], so the retries stop the moment the ring is really there.
 *
 * Off television this is a no-op requester that nobody requests.
 */
@Composable
internal fun rememberTvSheetStartFocus(hasFocus: () -> Boolean): FocusRequester {
    val requester = remember { FocusRequester() }
    val isTv = LocalUiPresentationConfig.current.isTv
    val view = LocalView.current
    LaunchedEffect(requester) {
        if (!isTv) return@LaunchedEffect
        var settledFrames = 0
        repeat(START_FOCUS_FRAMES) {
            withFrameNanos { }
            // The sheet is a window of its own and it slides in unfocused, so a request issued before
            // it gains the focus is accepted and then quietly replaced by the window's own default
            // focus (the first focusable in the content - usually the close button). Measured on the
            // auto-scroll sheet: the request reported success, the target held the ring for three
            // frames, and the close button had it once the window finished taking focus.
            if (!view.hasWindowFocus()) return@repeat
            if (hasFocus()) {
                settledFrames++
                if (settledFrames >= START_FOCUS_SETTLED_FRAMES) return@LaunchedEffect
            } else {
                settledFrames = 0
                // The window is in touch mode after the tap or key that opened the sheet, and Compose
                // silently drops a request there; taking the Android focus back first makes it land.
                view.requestFocusFromTouch()
                runCatching { requester.requestFocus() }
                delay(70)
            }
        }
    }
    return requester
}

/** Frames to keep asking: the sheet's slide-in plus the window-focus hand-off afterwards. */
private const val START_FOCUS_FRAMES = 40

/** Consecutive frames the ring has to hold before the request counts as landed. */
private const val START_FOCUS_SETTLED_FRAMES = 3
