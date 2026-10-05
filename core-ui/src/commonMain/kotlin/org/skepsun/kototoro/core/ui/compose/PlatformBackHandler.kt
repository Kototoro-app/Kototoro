package org.skepsun.kototoro.core.ui.compose

import androidx.compose.runtime.Composable

/** The platform's back gesture/button while [enabled]; a no-op where the host routes back keys itself. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
