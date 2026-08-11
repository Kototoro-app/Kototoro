package org.skepsun.kototoro.video.performance

import org.skepsun.kototoro.core.prefs.VideoSuperResolutionMode

enum class PlaybackFallbackReason {
	SUPER_RES_DISABLED,
	RENDERER_DOWNGRADED,
	CONSERVATIVE_MODE,
}

enum class PlaybackFailureCategory {
	NETWORK_OR_SOURCE,
	COMPATIBILITY,
	UNKNOWN,
}

data class PlaybackFallbackDecision(
	val config: EffectiveVideoPlaybackConfig,
	val reason: PlaybackFallbackReason,
)

object PlaybackFallbackController {

	fun classifyFailure(detail: String?, trigger: String): PlaybackFailureCategory {
		if (trigger == "startup_timeout") {
			return PlaybackFailureCategory.UNKNOWN
		}
		val normalized = detail?.lowercase().orEmpty()
		if (normalized.isBlank()) {
			return PlaybackFailureCategory.UNKNOWN
		}
		if (
			"http error" in normalized ||
			"403" in normalized ||
			"404" in normalized ||
			"forbidden" in normalized ||
			"unauthorized" in normalized ||
			"network" in normalized ||
			"dns" in normalized ||
			"tls" in normalized ||
			"certificate" in normalized ||
			"resolve" in normalized ||
			"timed out" in normalized
		) {
			return PlaybackFailureCategory.NETWORK_OR_SOURCE
		}
		if (
			"shader" in normalized ||
			"vulkan" in normalized ||
			"opengl" in normalized ||
			"gpu" in normalized ||
			"vo=" in normalized ||
			"hwdec" in normalized ||
			"decoder" in normalized ||
			"surface" in normalized ||
			"mediacodec" in normalized
		) {
			return PlaybackFailureCategory.COMPATIBILITY
		}
		return PlaybackFailureCategory.UNKNOWN
	}

	fun nextConfig(current: EffectiveVideoPlaybackConfig): PlaybackFallbackDecision? {
		return when {
			current.superResolutionMode != VideoSuperResolutionMode.OFF -> PlaybackFallbackDecision(
				config = current.copy(
					superResolutionMode = VideoSuperResolutionMode.OFF,
					allowShaderPipeline = false,
				),
				reason = PlaybackFallbackReason.SUPER_RES_DISABLED,
			)

			else -> null
		}
	}
}
