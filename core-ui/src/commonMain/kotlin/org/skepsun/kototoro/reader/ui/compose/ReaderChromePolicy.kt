package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

data class ReaderChromeVisibility(
    val visible: Boolean,
    val progressVisible: Boolean,
    val chapterTitleVisible: Boolean,
    val floatingControlsVisible: Boolean = false,
)

/**
 * Splits "the reader chrome is on screen" into its three parts.
 *
 * [floatingControlsPinned] keeps only the floating column up while the rest follows the tap:
 * the progress dock and the bottom title pill are bar-shaped and sit where the page is read,
 * so pinning them would eat the page, while the buttons hug the edge and are the ones a reader
 * keeps reaching for (issue #683). When pinned, [ReaderChromeVisibility.visible] stays true for
 * the host to mount the column, so hosts must gate each part on its own field instead of on
 * [ReaderChromeVisibility.visible] alone.
 */
fun resolveReaderChromeVisibility(
    controlsVisible: Boolean,
    progressAvailable: Boolean,
    chapterTitleAtBottom: Boolean,
    floatingControlsAvailable: Boolean = false,
    floatingControlsAllowed: Boolean = true,
    floatingControlsPinned: Boolean = false,
): ReaderChromeVisibility {
    val barsVisible = controlsVisible && (progressAvailable || chapterTitleAtBottom)
    val floatingVisible = (controlsVisible || floatingControlsPinned) &&
        floatingControlsAvailable &&
        floatingControlsAllowed
    return ReaderChromeVisibility(
        visible = barsVisible || floatingVisible,
        progressVisible = barsVisible && progressAvailable,
        chapterTitleVisible = barsVisible && chapterTitleAtBottom,
        floatingControlsVisible = floatingVisible,
    )
}

fun EnterTransition.withReaderChromeAnimations(enabled: Boolean): EnterTransition =
    if (enabled) this else EnterTransition.None

fun ExitTransition.withReaderChromeAnimations(enabled: Boolean): ExitTransition =
    if (enabled) this else ExitTransition.None
