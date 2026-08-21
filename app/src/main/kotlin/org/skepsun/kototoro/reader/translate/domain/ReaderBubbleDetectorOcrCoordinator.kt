package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.graphics.Rect
import org.skepsun.kototoro.core.prefs.AppSettings
import kotlin.math.max

internal class ReaderBubbleDetectorOcrCoordinator(
    private val settings: AppSettings,
    private val onnxBubbleDetectorEngine: OnnxBubbleDetectorEngine,
    private val dp: (Float) -> Int,
    private val log: (() -> String) -> Unit,
) {

    private data class BubbleOcrRegion(
        val anchorRect: Rect,
        val cropRegion: TextRegion,
    )

    data class BubbleDetectorOcrResult(
        val textBlocks: List<OcrTextBlock>,
        val detectorRegionCount: Int,
    )

    suspend fun recognize(
        bitmap: Bitmap,
        recognizer: ReaderTextRecognizer,
    ): BubbleDetectorOcrResult {
        if (!settings.isReaderTranslationBubbleDetectorEnabled) {
            return BubbleDetectorOcrResult(emptyList(), 0)
        }
        val detectorBitmap = bitmap.toSoftwareBitmapIfNeeded()
        val attempt = try {
            onnxBubbleDetectorEngine.detectAttempt(detectorBitmap)
        } finally {
            if (detectorBitmap !== bitmap) {
                detectorBitmap.recycle()
            }
        }
        log { "metric.ocr.bubble_detector.status=${attempt.status.name.lowercase()}" }
        log { "metric.ocr.bubble_detector.stage=${attempt.stage.ifBlank { "none" }}" }
        log { "metric.ocr.bubble_detector.backend=${attempt.backend.ifBlank { "none" }}" }
        log { "metric.ocr.bubble_detector.parser=${attempt.parser.ifBlank { "none" }}" }
        log { "metric.ocr.bubble_detector.model=${attempt.modelId.ifBlank { "none" }}" }
        if (attempt.error.isNotBlank()) {
            log { "metric.ocr.bubble_detector.error=${attempt.error}" }
        }
        val result = attempt.result ?: return BubbleDetectorOcrResult(emptyList(), 0)
        log { "metric.ocr.bubble_detector.raw_boxes=${result.rawBoxCount}" }
        log { "metric.ocr.bubble_detector.decoded_boxes=${result.decodedBoxCount}" }
        log { "metric.ocr.bubble_detector.final_boxes=${result.finalBoxCount}" }
        val regions = buildRegions(bitmap, result.boxes)
        log { "metric.ocr.bubble_detector.detected_regions=${regions.size}" }
        if (regions.isEmpty()) {
            return BubbleDetectorOcrResult(emptyList(), 0)
        }
        logRegionStats(regions)
        val recognized = regions.mapNotNull { region ->
            val block = recognizer.recognize(bitmap, listOf(region.cropRegion)).firstOrNull() ?: return@mapNotNull null
            block.copy(
                boundingBox = Rect(region.anchorRect),
                directionHint = inferTextDirectionHint(region.anchorRect, block.text),
                angleHintDegrees = inferTextAngleHintDegrees(region.anchorRect, block.text),
                isAxisAligned = inferAxisAlignedHint(region.anchorRect),
                quadPoints = rectToTextQuad(region.anchorRect),
            )
        }
        log { "metric.ocr.bubble_detector.recognized_blocks=${recognized.size}" }
        val emptyCount = (regions.size - recognized.size).coerceAtLeast(0)
        val emptyRatio = if (regions.isNotEmpty()) emptyCount.toFloat() / regions.size else 0f
        log { "metric.ocr.bubble_detector.empty_blocks=$emptyCount" }
        log { "metric.ocr.bubble_detector.empty_ratio=$emptyRatio" }
        return BubbleDetectorOcrResult(recognized, regions.size)
    }

    private fun buildRegions(
        bitmap: Bitmap,
        boxes: List<OnnxBubbleDetectorEngine.DetectedBox>,
    ): List<BubbleOcrRegion> {
        if (boxes.isEmpty()) return emptyList()
        val bitmapArea = (bitmap.width * bitmap.height).toFloat().coerceAtLeast(1f)
        return boxes.asSequence()
            .map { it.rect }
            .filter { it.width() >= dp(16f) && it.height() >= dp(16f) }
            .filter { (it.width() * it.height()).toFloat() / bitmapArea <= 0.45f }
            .map { anchorRect ->
                val isVertical = anchorRect.height() > anchorRect.width() * 13 / 10
                val padX = if (isVertical) {
                    max(dp(18f), anchorRect.width() / 2)
                } else {
                    max(dp(10f), anchorRect.width() / 6)
                }
                val padY = if (isVertical) {
                    max(dp(10f), anchorRect.height() / 10)
                } else {
                    max(dp(10f), anchorRect.height() / 8)
                }
                val cropRect = Rect(
                    (anchorRect.left - padX).coerceAtLeast(0),
                    (anchorRect.top - padY).coerceAtLeast(0),
                    (anchorRect.right + padX).coerceAtMost(bitmap.width),
                    (anchorRect.bottom + padY).coerceAtMost(bitmap.height),
                )
                BubbleOcrRegion(
                    anchorRect = Rect(anchorRect),
                    cropRegion = TextRegion(
                        rect = cropRect,
                        confidence = 1f,
                        detectorId = "bubble_detector",
                    ),
                )
            }
            .distinctBy { "${it.anchorRect.left},${it.anchorRect.top},${it.anchorRect.right},${it.anchorRect.bottom}" }
            .toList()
    }

    private fun logRegionStats(regions: List<BubbleOcrRegion>) {
        if (regions.isEmpty()) return
        val widths = regions.map { it.anchorRect.width() }
        val heights = regions.map { it.anchorRect.height() }
        val cropWidths = regions.map { it.cropRegion.rect.width() }
        val cropHeights = regions.map { it.cropRegion.rect.height() }
        val avgWidth = widths.average().toFloat()
        val avgHeight = heights.average().toFloat()
        val verticalCount = regions.count { it.anchorRect.height() > it.anchorRect.width() * 13 / 10 }
        log { "metric.ocr.bubble_detector.region_avg_width=${avgWidth.toInt()}" }
        log { "metric.ocr.bubble_detector.region_avg_height=${avgHeight.toInt()}" }
        log { "metric.ocr.bubble_detector.region_min_width=${widths.minOrNull() ?: 0}" }
        log { "metric.ocr.bubble_detector.region_max_width=${widths.maxOrNull() ?: 0}" }
        log { "metric.ocr.bubble_detector.region_min_height=${heights.minOrNull() ?: 0}" }
        log { "metric.ocr.bubble_detector.region_max_height=${heights.maxOrNull() ?: 0}" }
        log { "metric.ocr.bubble_detector.crop_avg_width=${cropWidths.average().toInt()}" }
        log { "metric.ocr.bubble_detector.crop_avg_height=${cropHeights.average().toInt()}" }
        log { "metric.ocr.bubble_detector.region_vertical_count=$verticalCount" }
        regions.take(MAX_REGION_SAMPLES).forEachIndexed { index, region ->
            log {
                "ocr bubble region[$index]=${region.anchorRect} crop=${region.cropRegion.rect} " +
                    "dir=${region.cropRegion.directionHint.name.lowercase()}"
            }
        }
    }

    private companion object {
        const val MAX_REGION_SAMPLES = 6
    }

    private fun Bitmap.toSoftwareBitmapIfNeeded(): Bitmap {
        return if (config == Bitmap.Config.HARDWARE) {
            copy(Bitmap.Config.ARGB_8888, false)
        } else {
            this
        }
    }
}
