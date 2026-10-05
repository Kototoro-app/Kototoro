package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertTextEquals
import org.skepsun.kototoro.reader.core.ZoomMode

// The Windows manga reader uses Android's chrome and options panel; these drive them as a user would.

/** A key pressed in the reader (the viewport takes focus first, the reader surface handles the shortcut). */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.readerKey(key: Key) {
    onNodeWithTag("reader-viewport").requestFocus()
    onNodeWithTag("reader-surface").performKeyInput { pressKey(key) }
    waitForIdle()
}

/** Runs [block] in Android's options panel, then closes it with Esc. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.readerOptions(block: ComposeUiTest.() -> Unit) {
    onNodeWithTag("reader-options").performClick()
    waitForIdle()
    block()
    readerKey(Key.Escape)
}

/** Android's reading mode from the panel's icon bar (Android's mode names). */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.readerMode(androidName: String) = readerOptions {
    onNodeWithContentDescription(AndroidStrings[androidName]).performClick()
    waitForIdle()
}

/** Toggles landscape double pages in the panel. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.readerDoublePages() = readerOptions {
    onNodeWithTag("reader-options-double-page").performScrollTo().performClick()
    waitForIdle()
}

/** Picks a page fit in the panel's scale-mode chips. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.readerFit(fit: ZoomMode) = readerOptions {
    onNode(hasText(AndroidStrings.array("zoom_modes")[fit.ordinal]) and hasAnyAncestor(hasTestTag("reader-options-zoom")))
        .performScrollTo().performClick()
    waitForIdle()
}

/** Runs [block] in "更多阅读设置" (the options panel's settings button), then closes it. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.readerMore(block: ComposeUiTest.() -> Unit) {
    onNodeWithTag("reader-options").performClick()
    waitForIdle()
    onNodeWithTag("reader-options-settings").performClick()
    waitForIdle()
    block()
    onNodeWithTag("reader-panel-close").performClick()
    waitForIdle()
}

/** Reloads the current page from "更多阅读设置". */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.readerReload() = readerMore {
    onNodeWithTag("reader-reload").performScrollTo().performClick()
}

/** The visible pages in Android's info bar, which shows while the chrome is hidden (H toggles it). */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertReaderProgress(text: String) {
    readerKey(Key.H)
    onNodeWithTag("reader-progress").assertTextEquals(text)
    readerKey(Key.H)
}
