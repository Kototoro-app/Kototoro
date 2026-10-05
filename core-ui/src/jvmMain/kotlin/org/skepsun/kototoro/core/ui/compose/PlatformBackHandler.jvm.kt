package org.skepsun.kototoro.core.ui.compose

import androidx.compose.runtime.Composable

/** Desktop hosts close sheets on Esc in their own key handling. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
