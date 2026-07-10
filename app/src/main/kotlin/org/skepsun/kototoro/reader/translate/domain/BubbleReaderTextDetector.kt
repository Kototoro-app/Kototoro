package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.skepsun.kototoro.core.image.BitmapDecoderCompat
import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleReaderTextDetector @Inject constructor(
	private val settings: AppSettings,
	private val onnxBubbleDetectorEngine: OnnxBubbleDetectorEngine,
) : ReaderTextDetector {

	override suspend fun detect(sourceUri: Uri): List<TextRegion> {
		val bitmap = runInterruptible(Dispatchers.IO) {
			BitmapDecoderCompat.decode(sourceUri.toFile())
		}
		return try {
			detect(bitmap)
		} finally {
			bitmap.recycle()
		}
	}

	override suspend fun detect(bitmap: Bitmap): List<TextRegion> {
		val modelId = settings.readerTranslationPaddleDetModelId
		val attempt = onnxBubbleDetectorEngine.detectAttempt(bitmap, modelId)
		log {
			"metric.ocr.bubble.detector.status=${attempt.status.name.lowercase()} " +
				"model=${attempt.modelId.ifBlank { modelId }} stage=${attempt.stage.ifBlank { "none" }} " +
				"backend=${attempt.backend.ifBlank { "none" }} parser=${attempt.parser.ifBlank { "none" }} " +
				"input=${attempt.inputShape.ifBlank { "none" }} outputs=${attempt.outputNames.ifBlank { "none" }}"
		}
		attempt.result?.let { result ->
			log {
				"metric.ocr.bubble.detector.boxes raw=${result.rawBoxCount} " +
					"decoded=${result.decodedBoxCount} final=${result.finalBoxCount}"
			}
		}
		if (attempt.error.isNotBlank()) {
			log { "metric.ocr.bubble.detector.error=${attempt.error.take(240)}" }
		}
		val result = attempt.result ?: return emptyList()
		val textBoxes = result.boxes.filter { box -> box.isTextDetectionBox(result.modelId) }
		val boxes = suppressDuplicateTextBoxes(textBoxes)
		log {
			"metric.ocr.bubble.detector.classes=${result.boxes.classHistogram()} " +
				"text_boxes=${textBoxes.size} deduped_text_boxes=${boxes.size}"
		}
		return boxes.map { box ->
			TextRegion(
				rect = box.rect,
				confidence = box.score,
				detectorId = "bubble_detector:${result.modelId}",
				directionHint = inferTextDirectionHint(box.rect),
				angleHintDegrees = inferTextAngleHintDegrees(box.rect),
				isAxisAligned = true,
				quadPoints = rectToTextQuad(box.rect),
			)
		}
	}

	private fun suppressDuplicateTextBoxes(
		boxes: List<OnnxBubbleDetectorEngine.DetectedBox>,
	): List<OnnxBubbleDetectorEngine.DetectedBox> {
		if (boxes.size < 2) return boxes
		val kept = mutableListOf<OnnxBubbleDetectorEngine.DetectedBox>()
		for (candidate in boxes.sortedByDescending { it.score }) {
			if (kept.none { existing -> isDuplicateTextBox(existing.rect, candidate.rect) }) {
				kept += candidate
			}
		}
		return kept.sortedWith(compareBy<OnnxBubbleDetectorEngine.DetectedBox> { it.rect.top }.thenBy { it.rect.left })
	}

	private fun isDuplicateTextBox(a: Rect, b: Rect): Boolean {
		val iou = intersectionOverUnion(a, b)
		if (iou >= OCR_TEXT_NMS_IOU_THRESHOLD) return true
		val intersection = intersectionArea(a, b)
		val minArea = minOf(rectArea(a), rectArea(b)).coerceAtLeast(1f)
		if (intersection / minArea >= OCR_TEXT_CONTAINMENT_THRESHOLD) return true
		val minW = minOf(a.width(), b.width()).coerceAtLeast(1).toFloat()
		val minH = minOf(a.height(), b.height()).coerceAtLeast(1).toFloat()
		val centerClose = kotlin.math.abs(a.centerX() - b.centerX()) <= minW * 0.45f &&
			kotlin.math.abs(a.centerY() - b.centerY()) <= minH * 0.18f
		return centerClose && intersection / minArea >= OCR_TEXT_CENTER_OVERLAP_THRESHOLD
	}

	private fun intersectionOverUnion(a: Rect, b: Rect): Float {
		val intersection = intersectionArea(a, b)
		if (intersection <= 0f) return 0f
		val union = rectArea(a) + rectArea(b) - intersection
		return if (union <= 0f) 0f else intersection / union
	}

	private fun intersectionArea(a: Rect, b: Rect): Float {
		val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
		val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
		return (width * height).toFloat()
	}

	private fun rectArea(rect: Rect): Float {
		return (rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)).toFloat()
	}

	private fun OnnxBubbleDetectorEngine.DetectedBox.isTextDetectionBox(modelId: String): Boolean {
		return when (modelId) {
			COMIC_TEXT_AND_BUBBLE_DETECTOR_ID -> classId == 1 || classId == 2
			else -> true
		}
	}

	private fun List<OnnxBubbleDetectorEngine.DetectedBox>.classHistogram(): String {
		if (isEmpty()) return "none"
		return groupingBy { it.classId }
			.eachCount()
			.toSortedMap()
			.entries
			.joinToString(",") { "${it.key}:${it.value}" }
	}

	private inline fun log(message: () -> String) {
		if (settings.isReaderTranslationDebugLogsEnabled) {
			Log.d(LOG_TAG, message())
		}
	}

	private companion object {
		const val LOG_TAG = "ReaderTranslate"
		const val COMIC_TEXT_AND_BUBBLE_DETECTOR_ID = "comic_text_and_bubble_detector_detr"
		const val OCR_TEXT_NMS_IOU_THRESHOLD = 0.35f
		const val OCR_TEXT_CONTAINMENT_THRESHOLD = 0.86f
		const val OCR_TEXT_CENTER_OVERLAP_THRESHOLD = 0.55f
	}
}
