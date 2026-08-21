package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri

data class TextQuad(
    val points: List<Pair<Float, Float>>,
) {
    init {
        require(points.size == 4) { "TextQuad requires exactly 4 points" }
    }
}

enum class TextDirectionHint {
    UNKNOWN,
    HORIZONTAL,
    VERTICAL,
}

data class TextRegion(
    val rect: Rect,
    val confidence: Float = 1f,
    val detectorId: String = "",
    val directionHint: TextDirectionHint = inferTextDirectionHint(rect = rect),
    val angleHintDegrees: Float = inferTextAngleHintDegrees(rect = rect),
    val isAxisAligned: Boolean = inferAxisAlignedHint(rect = rect),
    val quadPoints: TextQuad = rectToTextQuad(rect),
)

interface ReaderTextDetector {

    suspend fun detect(sourceUri: Uri): List<TextRegion>

    suspend fun detect(bitmap: Bitmap): List<TextRegion>
}

interface ReaderTextRecognizer {

    suspend fun recognize(sourceUri: Uri, regions: List<TextRegion>): List<OcrTextBlock>

    suspend fun recognize(bitmap: Bitmap, regions: List<TextRegion>): List<OcrTextBlock>
}

fun inferTextDirectionHint(
    rect: Rect?,
    text: String = "",
): TextDirectionHint {
    if (rect == null) return TextDirectionHint.UNKNOWN
    val width = rect.width().coerceAtLeast(1)
    val height = rect.height().coerceAtLeast(1)
    if (height > width * 13 / 10) {
        return TextDirectionHint.VERTICAL
    }
    if (width > height * 13 / 10) {
        return TextDirectionHint.HORIZONTAL
    }
    if (text.any { it == '\n' }) {
        return TextDirectionHint.VERTICAL
    }
    return TextDirectionHint.UNKNOWN
}

fun inferTextAngleHintDegrees(
    rect: Rect?,
    text: String = "",
): Float {
    return when (inferTextDirectionHint(rect, text)) {
        TextDirectionHint.VERTICAL -> 90f
        TextDirectionHint.HORIZONTAL -> 0f
        TextDirectionHint.UNKNOWN -> 0f
    }
}

fun inferAxisAlignedHint(
    rect: Rect?,
): Boolean {
    return rect != null
}

fun rectToTextQuad(rect: Rect): TextQuad {
    return TextQuad(
        points = listOf(
            rect.left.toFloat() to rect.top.toFloat(),
            rect.right.toFloat() to rect.top.toFloat(),
            rect.right.toFloat() to rect.bottom.toFloat(),
            rect.left.toFloat() to rect.bottom.toFloat(),
        ),
    )
}

fun textQuadToBoundingRect(quad: TextQuad): Rect {
    val xs = quad.points.map { it.first }
    val ys = quad.points.map { it.second }
    val left = xs.minOrNull()?.toInt() ?: 0
    val top = ys.minOrNull()?.toInt() ?: 0
    val right = xs.maxOrNull()?.toInt() ?: left
    val bottom = ys.maxOrNull()?.toInt() ?: top
    return Rect(left, top, right, bottom)
}

fun isAxisAlignedQuad(quad: TextQuad, tolerance: Float = 1f): Boolean {
    if (quad.points.size != 4) return false
    val points = quad.points.map { PointF(it.first, it.second) }
    val top = points[0]
    val right = points[1]
    val bottom = points[2]
    val left = points[3]
    return kotlin.math.abs(top.y - right.y) <= tolerance &&
        kotlin.math.abs(bottom.y - left.y) <= tolerance &&
        kotlin.math.abs(top.x - left.x) <= tolerance &&
        kotlin.math.abs(right.x - bottom.x) <= tolerance
}
