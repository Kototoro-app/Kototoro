package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.graphics.TransformOrigin
import org.skepsun.kototoro.core.prefs.ReaderAnimation

internal data class ComposeReaderPageTransform(
	val translationFactor: Float = 0f,
	val alpha: Float = 1f,
	val rotationX: Float = 0f,
	val rotationY: Float = 0f,
	val transformOrigin: TransformOrigin = TransformOrigin.Center,
)

internal fun resolveComposeReaderPageTransform(
	animation: ReaderAnimation,
	pageOffset: Float,
	isVertical: Boolean,
	isReversed: Boolean,
): ComposeReaderPageTransform = when (animation) {
	ReaderAnimation.DEFAULT -> ComposeReaderPageTransform()
	ReaderAnimation.NONE -> ComposeReaderPageTransform(
		translationFactor = when {
			pageOffset in -0.5f..0.5f -> -pageOffset
			pageOffset > 0f -> 1f
			else -> -1f
		},
	)
	ReaderAnimation.ADVANCED -> resolveAdvancedPageTransform(pageOffset, isVertical, isReversed)
}

private fun resolveAdvancedPageTransform(
	pageOffset: Float,
	isVertical: Boolean,
	isReversed: Boolean,
): ComposeReaderPageTransform {
	if (pageOffset !in -1f..1f) {
		return ComposeReaderPageTransform(
			translationFactor = -pageOffset,
			alpha = 0f,
		)
	}
	if (isVertical) {
		return ComposeReaderPageTransform(
			translationFactor = -pageOffset,
			rotationX = if (pageOffset <= 0f) -120f * pageOffset else 0f,
			transformOrigin = TransformOrigin(0.5f, 0.2f),
		)
	}
	return ComposeReaderPageTransform(
		translationFactor = -pageOffset,
		rotationY = when {
			isReversed && pageOffset > 0f -> 120f * pageOffset
			!isReversed && pageOffset <= 0f -> 120f * pageOffset
			else -> 0f
		},
		transformOrigin = TransformOrigin(if (isReversed) 1f else 0f, 0.5f),
	)
}

internal const val READER_PAGE_CAMERA_DISTANCE = 20_000f
