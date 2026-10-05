package org.skepsun.kototoro.reader.ui.compose

internal typealias ReaderBottomChromeVisibility = ReaderChromeVisibility

internal fun resolveReaderBottomChromeVisibility(
    controlsVisible: Boolean,
    progressAvailable: Boolean,
    chapterTitleAtBottom: Boolean,
    floatingControlsAvailable: Boolean = false,
    floatingControlsAllowed: Boolean = true,
): ReaderBottomChromeVisibility = resolveReaderChromeVisibility(
    controlsVisible, progressAvailable, chapterTitleAtBottom, floatingControlsAvailable, floatingControlsAllowed,
)
