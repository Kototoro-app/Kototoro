package org.skepsun.kototoro.reader.ui.compose

internal typealias ReaderBottomChromeVisibility = ReaderChromeVisibility

internal fun resolveReaderBottomChromeVisibility(
    controlsVisible: Boolean,
    progressAvailable: Boolean,
    chapterTitleAtBottom: Boolean,
    floatingControlsAvailable: Boolean = false,
    floatingControlsAllowed: Boolean = true,
    floatingControlsPinned: Boolean = false,
): ReaderBottomChromeVisibility = resolveReaderChromeVisibility(
    controlsVisible,
    progressAvailable,
    chapterTitleAtBottom,
    floatingControlsAvailable,
    floatingControlsAllowed,
    floatingControlsPinned,
)
