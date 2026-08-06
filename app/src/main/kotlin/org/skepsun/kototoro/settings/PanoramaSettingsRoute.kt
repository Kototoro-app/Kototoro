package org.skepsun.kototoro.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.settings.compose.PanoramaEffectPreset
import org.skepsun.kototoro.settings.compose.PanoramaLayoutMode
import org.skepsun.kototoro.settings.compose.PanoramaSettingsScreen
import org.skepsun.kototoro.settings.compose.PanoramaSettingsUiState
import org.skepsun.kototoro.settings.compose.resolvePanoramaEffectPreset

@Composable
fun PanoramaSettingsRoute(settings: AppSettings) {
    val enabled by settings.observeAsState(AppSettings.KEY_PANORAMA_ENABLED) { isPanoramaCoverEnabled }
    val blurPercent by settings.observeAsState(AppSettings.KEY_PANORAMA_BLUR) { panoramaCoverBlur }
    val transitionRangePercent by settings.observeAsState(AppSettings.KEY_PANORAMA_TRANSITION_INTENSITY) {
        panoramaTransitionRange
    }
    val topOpacityPercent by settings.observeAsState(AppSettings.KEY_PANORAMA_TOP_OPACITY) { panoramaTopOpacity }
    val animationEnabled by settings.observeAsState(AppSettings.KEY_PANORAMA_ANIMATION_ENABLED) {
        isPanoramaCoverAnimationEnabled
    }
    val layoutMode by settings.observeAsState(AppSettings.KEY_DETAILS_PANORAMA_LIMIT_TO_INFO_CARD_MIDPOINT) {
        if (isDetailsPanoramaLimitedToInfoCardMidpoint) {
            PanoramaLayoutMode.HALF_SCREEN
        } else {
            PanoramaLayoutMode.FULL_SCREEN
        }
    }
    val scrollLinked by settings.observeAsState(AppSettings.KEY_DETAILS_PANORAMA_SCROLL_LINKED) {
        isDetailsPanoramaScrollLinkedEnabled
    }
    val reducedVisualEffects by settings.observeAsState(AppSettings.KEY_REDUCED_VISUAL_EFFECTS) {
        isReducedVisualEffectsEnabled
    }

    PanoramaSettingsScreen(
        state = PanoramaSettingsUiState(
            enabled = enabled,
            layoutMode = layoutMode,
            preset = resolvePanoramaEffectPreset(layoutMode, blurPercent, transitionRangePercent, topOpacityPercent),
            blurPercent = blurPercent,
            transitionRangePercent = transitionRangePercent,
            topOpacityPercent = topOpacityPercent,
            animationEnabled = animationEnabled,
            animationSettingsEnabled = !reducedVisualEffects,
            scrollLinked = scrollLinked,
        ),
        onEnabledChange = { settings.isPanoramaCoverEnabled = it },
        onLayoutModeChange = { nextMode ->
            val currentPreset = resolvePanoramaEffectPreset(
                layoutMode,
                blurPercent,
                transitionRangePercent,
                topOpacityPercent,
            )
            settings.isDetailsPanoramaLimitedToInfoCardMidpoint = nextMode == PanoramaLayoutMode.HALF_SCREEN
            currentPreset.valuesFor(nextMode)?.let { values ->
                settings.panoramaCoverBlur = values.blurPercent
                settings.panoramaTransitionRange = values.transitionRangePercent
                settings.panoramaTopOpacity = values.topOpacityPercent
            }
        },
        onPresetChange = { preset ->
            preset.valuesFor(layoutMode)?.let { values ->
                settings.panoramaCoverBlur = values.blurPercent
                settings.panoramaTransitionRange = values.transitionRangePercent
                settings.panoramaTopOpacity = values.topOpacityPercent
            }
        },
        onScrollLinkedChange = { settings.isDetailsPanoramaScrollLinkedEnabled = it },
        onAnimationEnabledChange = { settings.isPanoramaCoverAnimationEnabled = it },
        onBlurChange = { settings.panoramaCoverBlur = it },
        onTransitionRangeChange = { settings.panoramaTransitionRange = it },
        onTopOpacityChange = { settings.panoramaTopOpacity = it },
        onReset = {
            PanoramaEffectPreset.BALANCED.valuesFor(layoutMode)?.let { values ->
                settings.panoramaCoverBlur = values.blurPercent
                settings.panoramaTransitionRange = values.transitionRangePercent
                settings.panoramaTopOpacity = values.topOpacityPercent
            }
        },
    )
}
