package org.skepsun.kototoro.video.performance

import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.VideoSuperResolutionMode

data class EffectiveVideoPlaybackConfig(
    val superResolutionMode: VideoSuperResolutionMode,
    val allowShaderPipeline: Boolean,
)

object VideoPlaybackPolicy {
    fun resolve(settings: AppSettings, deviceInfo: DevicePerformanceInfo): EffectiveVideoPlaybackConfig {
        val rememberedEnabled = settings.videoEnhancementRememberAcrossVideos &&
            settings.videoEnhancementRememberedEnabled
        val allowEnhancement = deviceInfo.tier != DevicePerformanceTier.LOW && rememberedEnabled
        return EffectiveVideoPlaybackConfig(
            superResolutionMode = if (allowEnhancement) {
                when (settings.videoAnime4KPreset) {
                    org.skepsun.kototoro.core.prefs.Anime4KPreset.FAST -> VideoSuperResolutionMode.PERFORMANCE
                    org.skepsun.kototoro.core.prefs.Anime4KPreset.QUALITY -> VideoSuperResolutionMode.QUALITY
                }
            } else {
                VideoSuperResolutionMode.OFF
            },
            allowShaderPipeline = deviceInfo.tier != DevicePerformanceTier.LOW,
        )
    }
}
