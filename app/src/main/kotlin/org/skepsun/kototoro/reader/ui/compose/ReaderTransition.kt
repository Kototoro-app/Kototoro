package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

internal fun EnterTransition.whenReaderAnimationsEnabled(enabled: Boolean): EnterTransition =
    withReaderChromeAnimations(enabled)

internal fun ExitTransition.whenReaderAnimationsEnabled(enabled: Boolean): ExitTransition =
    withReaderChromeAnimations(enabled)
