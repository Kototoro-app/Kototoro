package org.skepsun.kototoro.core.prefs

object VideoEnhancementCompatibility {

	fun superResolutionForRenderer(
		rendererMode: VideoRendererMode,
		superResolutionMode: VideoSuperResolutionMode,
	): VideoSuperResolutionMode = if (rendererMode == VideoRendererMode.GPU_NEXT) {
		VideoSuperResolutionMode.OFF
	} else {
		superResolutionMode
	}

	fun rendererForSuperResolution(
		superResolutionMode: VideoSuperResolutionMode,
		rendererMode: VideoRendererMode,
	): VideoRendererMode = if (
		superResolutionMode != VideoSuperResolutionMode.OFF && rendererMode == VideoRendererMode.GPU_NEXT
	) {
		VideoRendererMode.GPU
	} else {
		rendererMode
	}
}
