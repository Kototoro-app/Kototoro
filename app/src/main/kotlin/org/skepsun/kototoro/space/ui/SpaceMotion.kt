package org.skepsun.kototoro.space.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween

enum class SpaceMotionMode {
	FULL,
	REDUCED,
	DISABLED,
}

object SpaceMotion {
	const val ExitMillis = 90
	const val EnterDelayMillis = 70
	const val EnterMillis = 190
	const val ReducedCrossfadeMillis = 120
	const val IconCrossfadeMillis = 120
	const val InactiveScale = 0.98f

	fun resolveMode(
		reducedVisualEffects: Boolean,
		animatorDurationScale: Float,
	): SpaceMotionMode = when {
		animatorDurationScale <= 0f -> SpaceMotionMode.DISABLED
		reducedVisualEffects -> SpaceMotionMode.REDUCED
		else -> SpaceMotionMode.FULL
	}

	fun contentTransform(mode: SpaceMotionMode): ContentTransform = when (mode) {
		SpaceMotionMode.DISABLED -> EnterTransition.None togetherWith ExitTransition.None
		SpaceMotionMode.REDUCED -> fadeIn(
			animationSpec = tween(ReducedCrossfadeMillis, easing = LinearEasing),
		) togetherWith fadeOut(
			animationSpec = tween(ReducedCrossfadeMillis, easing = LinearEasing),
		)
		SpaceMotionMode.FULL -> (
			fadeIn(
				animationSpec = tween(
					durationMillis = EnterMillis,
					delayMillis = EnterDelayMillis,
					easing = LinearEasing,
				),
			) + scaleIn(
				initialScale = InactiveScale,
				animationSpec = tween(
					durationMillis = EnterMillis,
					delayMillis = EnterDelayMillis,
					easing = LinearEasing,
				),
			)
		) togetherWith (
			fadeOut(animationSpec = tween(ExitMillis, easing = LinearEasing)) +
				scaleOut(
					targetScale = InactiveScale,
					animationSpec = tween(ExitMillis, easing = LinearEasing),
				)
		)
	}
}
