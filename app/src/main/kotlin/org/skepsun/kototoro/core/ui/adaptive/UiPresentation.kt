package org.skepsun.kototoro.core.ui.adaptive

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState

/** Platform facts used by the pure presentation-mode decision. */
data class UiDeviceCharacteristics(
    val isTelevision: Boolean = false,
    val hasLeanbackFeature: Boolean = false,
    val uiModeType: Int = Configuration.UI_MODE_TYPE_UNDEFINED,
) {
    val isTvDevice: Boolean
        get() = isTelevision ||
            hasLeanbackFeature ||
            uiModeType == Configuration.UI_MODE_TYPE_TELEVISION

    companion object {
        fun from(context: Context): UiDeviceCharacteristics {
            val configuration = context.resources.configuration
            val uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
            return UiDeviceCharacteristics(
                isTelevision = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION,
                hasLeanbackFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
                uiModeType = uiModeType,
            )
        }
    }
}

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

/** Pure decision table for the AUTO/STANDARD/TV preference. */
object UiPresentationModeResolver {

    fun resolve(
        requestedMode: UiPresentationMode,
        device: UiDeviceCharacteristics,
    ): UiPresentationMode = when (requestedMode) {
        UiPresentationMode.TV -> UiPresentationMode.TV
        UiPresentationMode.STANDARD -> UiPresentationMode.STANDARD
        UiPresentationMode.AUTO -> if (device.isTvDevice) {
            UiPresentationMode.TV
        } else {
            UiPresentationMode.STANDARD
        }
    }

    fun resolveConfig(
        requestedMode: UiPresentationMode,
        device: UiDeviceCharacteristics,
    ): UiPresentationConfig = UiPresentationConfig(
        requestedMode = requestedMode,
        effectiveMode = resolve(requestedMode, device),
    )
}

/** Alias kept short for call sites that need a mode decision without the config object. */
fun resolvePresentationMode(
    requestedMode: UiPresentationMode,
    device: UiDeviceCharacteristics,
): UiPresentationMode = UiPresentationModeResolver.resolve(requestedMode, device)

/** CompositionLocal used by each Activity's own Compose root. */
val LocalUiPresentationConfig = staticCompositionLocalOf {
    UiPresentationConfig(
        requestedMode = UiPresentationMode.STANDARD,
        effectiveMode = UiPresentationMode.STANDARD,
    )
}

@Composable
fun rememberUiPresentationConfig(settings: AppSettings): UiPresentationConfig {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val requestedMode by settings.observeAsState(AppSettings.KEY_UI_PRESENTATION_MODE) {
        uiPresentationMode
    }
    val device = remember(
        context,
        configuration.uiMode,
    ) {
        UiDeviceCharacteristics.from(context)
    }
    return remember(requestedMode, device) {
        UiPresentationModeResolver.resolveConfig(requestedMode, device)
    }
}

fun resolveUiPresentationConfig(
    context: Context,
    settings: AppSettings,
): UiPresentationConfig = UiPresentationModeResolver.resolveConfig(
    requestedMode = settings.uiPresentationMode,
    device = UiDeviceCharacteristics.from(context),
)
