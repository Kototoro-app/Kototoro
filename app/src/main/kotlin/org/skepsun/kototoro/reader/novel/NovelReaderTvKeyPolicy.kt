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
 * Determines whether a D-pad key on TV presentation should bring the reader chrome back.
 *
 * Reading mode hides the chrome and hands every D-pad key to the content, and BACK already means
 * "leave the reader", so without this rule the menu could not be reopened from reading mode at
 * all. Television apps universally use UP to reveal the controls.
 */
internal fun shouldShowNovelReaderTvChrome(
    isTvPresentation: Boolean,
    controlsVisible: Boolean,
    hasSubOverlay: Boolean,
    keyCode: Int,
): Boolean = isTvPresentation &&
    !controlsVisible &&
    !hasSubOverlay &&
    keyCode == KeyEvent.KEYCODE_DPAD_UP

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
