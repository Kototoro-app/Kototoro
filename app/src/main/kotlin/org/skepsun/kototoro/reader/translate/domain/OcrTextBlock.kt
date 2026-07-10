package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Rect

data class OcrTextBlock(
	val text: String,
	val boundingBox: Rect?,
	val firstSymbolBox: Rect? = null,
	val confidence: Float = 1f,
	val directionHint: TextDirectionHint = inferTextDirectionHint(boundingBox, text),
	val angleHintDegrees: Float = inferTextAngleHintDegrees(boundingBox, text),
	val isAxisAligned: Boolean = inferAxisAlignedHint(boundingBox),
	val quadPoints: TextQuad? = boundingBox?.let(::rectToTextQuad),
	val detectorId: String = "",
)
