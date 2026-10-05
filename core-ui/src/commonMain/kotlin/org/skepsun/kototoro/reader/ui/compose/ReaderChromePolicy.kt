package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

data class ReaderChromeVisibility(
    val visible: Boolean,
    val progressVisible: Boolean,
    val chapterTitleVisible: Boolean,
    val floatingControlsVisible: Boolean = false,
)

fun resolveReaderChromeVisibility(
    controlsVisible: Boolean,
    progressAvailable: Boolean,
    chapterTitleAtBottom: Boolean,
    floatingControlsAvailable: Boolean = false,
    floatingControlsAllowed: Boolean = true,
): ReaderChromeVisibility {
    val floatingVisible = controlsVisible && floatingControlsAvailable && floatingControlsAllowed
    val visible = controlsVisible && (progressAvailable || chapterTitleAtBottom || floatingVisible)
    return ReaderChromeVisibility(
        visible, visible && progressAvailable, visible && chapterTitleAtBottom, floatingVisible,
    )
}

fun EnterTransition.withReaderChromeAnimations(enabled: Boolean): EnterTransition =
    if (enabled) this else EnterTransition.None

fun ExitTransition.withReaderChromeAnimations(enabled: Boolean): ExitTransition =
    if (enabled) this else ExitTransition.None
