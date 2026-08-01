package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

internal class ReaderBubbleRenderCoordinator(
	private val isLikelyGarbledText: (String) -> Boolean,
	private val shouldSuppressRenderedBubble: (String, String, String) -> Boolean,
	private val isLikelySpeechBubbleRegion: (Bitmap, android.graphics.Rect) -> Boolean,
	private val prepareTranslatedBubble: (BubbleInput, String, Int, Int, Boolean) -> PreparedBubble?,
	private val qualityFilterEnabled: () -> Boolean,
	private val log: (() -> String) -> Unit,
	private val oneLine: (String, Int) -> String,
) {

	fun prepareBubbles(
		bubbleInputs: List<BubbleInput>,
		translatedMap: Map<String, String>,
		targetLang: String,
		bitmap: Bitmap,
	): BubbleRenderPreparationResult {
		val preparedBubbles = mutableListOf<PreparedBubble>()
		var nonEmptyTranslatedCount = 0
		for (bubble in bubbleInputs) {
			val translated = translatedMap[bubble.sourceText].orEmpty().trim()
			log {
				"bubble translate src=${oneLine(bubble.sourceText, 140)} out=${oneLine(translated, 140)} box=${bubble.rect}"
			}
			if (translated.isBlank()) continue
			nonEmptyTranslatedCount++
			if (qualityFilterEnabled() && isLikelyGarbledText(translated)) {
				log {
					"bubble render skipped_garbled src=${oneLine(bubble.sourceText, 140)} out=${oneLine(translated, 140)} box=${bubble.rect}"
				}
				continue
			}
			if (shouldSuppressRenderedBubble(bubble.sourceText, translated, targetLang)) {
				log {
					"bubble render suppressed src=${oneLine(bubble.sourceText, 140)} out=${oneLine(translated, 140)} box=${bubble.rect}"
				}
				continue
			}
			val bubbleLikeRegion = if (bubble.detectorAnchored) {
				true
			} else {
				when (bubble.classId) {
					2 -> false // RT-DETR text_free
					1 -> true  // RT-DETR text_bubble
					else -> isLikelySpeechBubbleRegion(bitmap, bubble.rect) // Legacy Model fallback (classId=0)
				}
			}
			val prepared = prepareTranslatedBubble(
				bubble,
				translated,
				bitmap.width,
				bitmap.height,
				bubbleLikeRegion,
			)
			if (prepared == null) {
				log {
					"bubble render skipped_layout src=${oneLine(bubble.sourceText, 140)} out=${oneLine(translated, 140)} box=${bubble.rect} verticalPreferred=${bubble.verticalPreferred} bubbleLike=$bubbleLikeRegion"
				}
				continue
			}
			prepared.debugOverlay?.let { overlay ->
				log {
					"bubble debug diagnosis=${overlay.diagnosis} detectorAnchored=${overlay.detectorAnchored} vertical=${overlay.verticalPreferred} " +
						"source=${overlay.sourceRect} content=${overlay.contentRect} prepared=${overlay.preparedRect} contentArea=${overlay.contentAreaRect}"
				}
			}
			preparedBubbles.add(prepared)
		}
		val filteredBubbles = suppressSparseOverlappingBubbles(preparedBubbles)
		return BubbleRenderPreparationResult(
			preparedBubbles = filteredBubbles,
			nonEmptyTranslatedCount = nonEmptyTranslatedCount,
		)
	}

	private fun suppressSparseOverlappingBubbles(
		bubbles: List<PreparedBubble>,
	): List<PreparedBubble> {
		if (bubbles.size < 2) return bubbles
		val footprints = bubbles.mapIndexed { index, bubble ->
			val backgroundRect = bubbleBackgroundRect(bubble)
			val contentRect = bubbleContentRect(bubble)
			val backgroundArea = rectArea(backgroundRect)
			val contentArea = rectArea(contentRect).coerceAtMost(backgroundArea)
			BubbleFootprint(
				index = index,
				bubble = bubble,
				backgroundRect = backgroundRect,
				contentRect = contentRect,
				backgroundArea = backgroundArea,
				contentArea = contentArea,
				fillRatio = contentArea.toFloat() / backgroundArea.toFloat(),
			)
		}
		val removed = BooleanArray(footprints.size)
		for (i in footprints.indices) {
			if (removed[i]) continue
			for (j in i + 1 until footprints.size) {
				if (removed[j]) continue
				val first = footprints[i]
				val second = footprints[j]
				val overlap = overlapArea(first.backgroundRect, second.backgroundRect)
				if (overlap <= 0) continue
				val overlapRatio = overlap.toFloat() / min(first.backgroundArea, second.backgroundArea).toFloat()
				if (overlapRatio < OVERLAP_SUPPRESSION_MIN_RATIO) continue
				val loser = when {
					shouldSuppressOverlapCandidate(first, second) -> first
					shouldSuppressOverlapCandidate(second, first) -> second
					else -> null
				} ?: continue
				removed[loser.index] = true
				log {
					"bubble render suppressed_overlap sparseFill=${"%.2f".format(loser.fillRatio)} " +
						"background=${loser.backgroundRect} content=${loser.contentRect}"
				}
			}
		}
		return footprints.filterNot { removed[it.index] }.map { it.bubble }
	}

	private fun shouldSuppressOverlapCandidate(
		candidate: BubbleFootprint,
		other: BubbleFootprint,
	): Boolean {
		if (candidate.backgroundArea < other.backgroundArea * SPARSE_OVERLAP_MIN_AREA_MULTIPLIER) return false
		if (candidate.fillRatio > SPARSE_OVERLAP_MAX_FILL_RATIO) return false
		if (other.fillRatio < candidate.fillRatio + SPARSE_OVERLAP_MIN_FILL_RATIO_GAP) return false
		if (candidate.contentArea > other.contentArea * SPARSE_OVERLAP_MAX_CONTENT_AREA_MULTIPLIER) return false
		val candidateContainsOtherContent = candidate.backgroundRect.contains(other.contentRect)
		val contentOverlap = overlapArea(candidate.contentRect, other.contentRect)
		return candidateContainsOtherContent || contentOverlap > 0
	}

	private fun bubbleBackgroundRect(bubble: PreparedBubble): Rect {
		if (bubble.segments.isEmpty()) return Rect(bubble.rect)
		return mergeRects(bubble.segments.map { it.backgroundRect }) ?: Rect(bubble.rect)
	}

	private fun bubbleContentRect(bubble: PreparedBubble): Rect {
		if (bubble.segments.isNotEmpty()) {
			return mergeRects(bubble.segments.map { it.contentRect }) ?: bubbleContentRectFromPadding(bubble)
		}
		return bubbleContentRectFromPadding(bubble)
	}

	private fun bubbleContentRectFromPadding(bubble: PreparedBubble): Rect {
		return Rect(
			bubble.rect.left + bubble.padding,
			bubble.rect.top + bubble.padding,
			bubble.rect.left + bubble.padding + bubble.contentWidth,
			bubble.rect.top + bubble.padding + bubble.contentHeight,
		)
	}

	private fun mergeRects(rects: List<Rect>): Rect? {
		if (rects.isEmpty()) return null
		var left = rects.first().left
		var top = rects.first().top
		var right = rects.first().right
		var bottom = rects.first().bottom
		for (rect in rects.drop(1)) {
			left = min(left, rect.left)
			top = min(top, rect.top)
			right = max(right, rect.right)
			bottom = max(bottom, rect.bottom)
		}
		return Rect(left, top, right, bottom)
	}

	private fun rectArea(rect: Rect): Int {
		return rect.width().coerceAtLeast(1) * rect.height().coerceAtLeast(1)
	}

	private fun overlapArea(a: Rect, b: Rect): Int {
		val left = max(a.left, b.left)
		val top = max(a.top, b.top)
		val right = min(a.right, b.right)
		val bottom = min(a.bottom, b.bottom)
		if (right <= left || bottom <= top) return 0
		return (right - left) * (bottom - top)
	}

	private data class BubbleFootprint(
		val index: Int,
		val bubble: PreparedBubble,
		val backgroundRect: Rect,
		val contentRect: Rect,
		val backgroundArea: Int,
		val contentArea: Int,
		val fillRatio: Float,
	)

	companion object {
		private const val OVERLAP_SUPPRESSION_MIN_RATIO = 0.58f
		private const val SPARSE_OVERLAP_MIN_AREA_MULTIPLIER = 1.9f
		private const val SPARSE_OVERLAP_MAX_FILL_RATIO = 0.34f
		private const val SPARSE_OVERLAP_MIN_FILL_RATIO_GAP = 0.16f
		private const val SPARSE_OVERLAP_MAX_CONTENT_AREA_MULTIPLIER = 1.1f
	}
}
