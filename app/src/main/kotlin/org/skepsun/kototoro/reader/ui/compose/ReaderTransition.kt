package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

internal fun EnterTransition.whenReaderAnimationsEnabled(enabled: Boolean): EnterTransition =
	if (enabled) this else EnterTransition.None

internal fun ExitTransition.whenReaderAnimationsEnabled(enabled: Boolean): ExitTransition =
	if (enabled) this else ExitTransition.None
