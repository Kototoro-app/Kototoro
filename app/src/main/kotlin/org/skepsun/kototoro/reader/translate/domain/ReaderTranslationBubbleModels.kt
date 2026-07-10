package org.skepsun.kototoro.reader.translate.domain

import android.text.StaticLayout
import android.graphics.Rect

internal data class BubbleInput(
	val rect: Rect,
	val sourceText: String,
	val verticalPreferred: Boolean,
	val classId: Int = 0,
	val detectorAnchored: Boolean = false,
	val sourceContentRect: Rect? = null,
	val sourceContentRects: List<Rect> = emptyList(),
)

internal data class BubbleDebugOverlay(
	val sourceRect: Rect,
	val contentRect: Rect?,
	val preparedRect: Rect,
	val contentAreaRect: Rect,
	val detectorAnchored: Boolean,
	val verticalPreferred: Boolean,
	val diagnosis: String,
)

internal data class GroupedBubbleSource(
	val fragments: List<TextFragment>,
	val bubbleRect: Rect?,
	val classId: Int = 0,
	val detectorAnchored: Boolean = false,
)

internal data class BubbleGroupingResult(
	val groups: List<GroupedBubbleSource>,
	val detectorCandidateCount: Int,
	val detectorMatchedFragmentCount: Int,
	val detectorUsedGroupCount: Int,
	val detectorSubdividedGroupCount: Int,
	val detectorSubdividedFragmentCount: Int,
	val detectorCoverageRate: Float,
	val detectorEngine: String,
	val detectorModelId: String,
	val detectorRawBoxCount: Int,
	val detectorTotalMs: Long,
	val detectorFallbackReason: String,
	val fallbackFragmentCount: Int,
	val fallbackGroupCount: Int,
	val fallbackMode: String,
)

internal data class BubbleDetectorOutcome(
	val groups: List<GroupedBubbleSource>,
	val matchedFragmentIndices: Set<Int>,
	val candidateCount: Int,
	val matchedFragmentCount: Int,
	val subdividedGroupCount: Int,
	val subdividedFragmentCount: Int,
	val engine: String,
	val modelId: String,
	val rawBoxCount: Int,
	val totalMs: Long,
	val fallbackReason: String,
)

internal data class DetectedBubbleCandidate(
	val rect: Rect,
	val fragmentIndices: List<Int>,
	val score: Float,
	val classId: Int,
) {
	fun isBetterThan(other: DetectedBubbleCandidate): Boolean {
		if (score != other.score) return score > other.score
		return rectArea(rect) < rectArea(other.rect)
	}

	private fun rectArea(rect: Rect): Float {
		return (rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)).toFloat()
	}
}

internal data class BubbleRoiOcrResult(
	val textsByGroupIndex: Map<Int, String>,
	val requestCount: Int,
	val successCount: Int,
	val fallbackCount: Int,
	val totalMs: Long,
	val coverageArea: Float,
)

internal data class TextFragment(
	val rect: Rect,
	val text: String,
	val directionHint: TextDirectionHint = inferTextDirectionHint(rect, text),
	val angleHintDegrees: Float = inferTextAngleHintDegrees(rect, text),
	val isAxisAligned: Boolean = inferAxisAlignedHint(rect),
	val quadPoints: TextQuad = rectToTextQuad(rect),
	val detectorId: String = "",
)

internal data class PreparedBubble(
	val rect: Rect,
	val padding: Int,
	val contentWidth: Int,
	val contentHeight: Int,
	val layout: StaticLayout?,
	val verticalPlan: VerticalLayoutPlan?,
	val segments: List<PreparedBubbleSegment> = emptyList(),
	val debugOverlay: BubbleDebugOverlay? = null,
)

internal data class PreparedBubbleSegment(
	val backgroundRect: Rect,
	val contentRect: Rect,
	val layout: StaticLayout? = null,
	val verticalPlan: VerticalLayoutPlan? = null,
)

internal data class VerticalLayoutPlan(
	val glyphs: List<String>,
	val textSize: Float,
	val cellSize: Int,
	val rowCapacity: Int,
)

internal data class BubbleRenderPreparationResult(
	val preparedBubbles: List<PreparedBubble>,
	val nonEmptyTranslatedCount: Int,
)
