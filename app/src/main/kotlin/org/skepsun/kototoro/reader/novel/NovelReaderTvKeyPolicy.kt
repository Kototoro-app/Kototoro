package org.skepsun.kototoro.reader.novel

import android.view.KeyEvent

/**
 * Determines whether a BACK key on TV presentation should be intercepted by NovelReaderActivity
 * to dismiss visible controls before allowing the Activity to exit.
 */
internal fun shouldInterceptNovelReaderTvBack(
    isTvPresentation: Boolean,
    controlsVisible: Boolean,
    hasSubOverlay: Boolean,
): Boolean = isTvPresentation && controlsVisible && !hasSubOverlay

/**
 * Resolves page step delta for PAGE_UP and PAGE_DOWN keys on TV presentation.
 */
internal fun resolveNovelReaderPageKeyDelta(
    keyCode: Int,
    isNavigationInverted: Boolean,
): Int? = when (keyCode) {
    KeyEvent.KEYCODE_PAGE_UP -> if (isNavigationInverted) 1 else -1
    KeyEvent.KEYCODE_PAGE_DOWN -> if (isNavigationInverted) -1 else 1
    else -> null
}
