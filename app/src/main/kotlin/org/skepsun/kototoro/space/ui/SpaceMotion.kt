package org.skepsun.kototoro.space.ui

enum class SpaceMotionMode {
	FULL,
	REDUCED,
	DISABLED,
}

object SpaceMotion {
	const val IconCrossfadeMillis = 120
	const val NavigationSlideMillis = 220
	const val ReducedNavigationSlideMillis = 100

	fun resolveMode(
		reducedVisualEffects: Boolean,
		animatorDurationScale: Float,
	): SpaceMotionMode = when {
		animatorDurationScale <= 0f -> SpaceMotionMode.DISABLED
		reducedVisualEffects -> SpaceMotionMode.REDUCED
		else -> SpaceMotionMode.FULL
	}
}
