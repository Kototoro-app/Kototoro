package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.runtime.staticCompositionLocalOf
import org.skepsun.kototoro.core.prefs.UiPresentationMode

/** Resolved presentation state shared by every Compose root. */
data class UiPresentationConfig(
    val requestedMode: UiPresentationMode,
    val effectiveMode: UiPresentationMode,
) {
    val mode: UiPresentationMode
        get() = effectiveMode

    val isTv: Boolean
        get() = effectiveMode == UiPresentationMode.TV

    val isManualTv: Boolean
        get() = requestedMode == UiPresentationMode.TV

    val canRestoreStandard: Boolean
        get() = isManualTv
}

/** CompositionLocal used by each Activity's own Compose root. */
val LocalUiPresentationConfig = staticCompositionLocalOf {
    UiPresentationConfig(
        requestedMode = UiPresentationMode.STANDARD,
        effectiveMode = UiPresentationMode.STANDARD,
    )
}
