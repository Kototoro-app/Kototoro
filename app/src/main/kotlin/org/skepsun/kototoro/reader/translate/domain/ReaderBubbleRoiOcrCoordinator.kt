package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderOcrEngine
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug

internal class ReaderBubbleRoiOcrCoordinator(
    private val settings: AppSettings,
    private val recognizeTextByEngine: suspend (ReaderOcrEngine, OcrRequest) -> List<OcrTextBlock>,
    private val composeGroupedText: (List<TextFragment>, String) -> String,
    private val mergeRects: (List<Rect>) -> Rect?,
    private val isLikelySpeechBubbleRegion: (Bitmap, Rect) -> Boolean,
    private val dp: (Float) -> Int,
    private val log: (() -> String) -> Unit,
) {

    suspend fun recognize(
        groups: List<GroupedBubbleSource>,
        sourceUri: Uri,
        sourceLang: String,
        pageId: Long,
        bitmap: Bitmap,
        maxRequestsPerPage: Int,
    ): BubbleRoiOcrResult {
        if (groups.isEmpty()) {
            return BubbleRoiOcrResult(emptyMap(), 0, 0, 0, 0L, 0f)
        }
        val engine = preferredRoiOcrEngine()
        val textsByIndex = linkedMapOf<Int, String>()
        var requestCount = 0
        var successCount = 0
        var attemptedArea = 0f
        var successArea = 0f
        var totalMs = 0L
        for ((index, group) in groups.withIndex()) {
            if (requestCount >= maxRequestsPerPage) break
            val roiRect = group.bubbleRect ?: mergeRects(group.fragments.map { it.rect }) ?: continue
            if (!shouldTryRoiOcr(roiRect, bitmap)) continue
            requestCount++
            attemptedArea += rectArea(roiRect)
            val request = OcrRequest(
                sourceUri = sourceUri,
                sourceLang = sourceLang,
                roi = roiRect,
                pageId = pageId,
                requestType = OcrRequestType.ROI,
                debugTag = "page:$pageId:bubble:$index",
            )
            val startMs = SystemClock.elapsedRealtime()
            val roiBlocks = runCatching {
                recognizeTextByEngine(engine, request)
            }.onFailure {
                it.printStackTraceDebug()
            }.getOrDefault(emptyList())
            totalMs += SystemClock.elapsedRealtime() - startMs
            val roiText = composeOcrBlocksText(roiBlocks, sourceLang, roiRect).trim()
            if (roiText.isBlank()) continue
            successCount++
            successArea += rectArea(roiRect)
            textsByIndex[index] = roiText
            log { "roi ocr hit engine=${engine.name} idx=$index box=$roiRect text=$roiText" }
        }
        return BubbleRoiOcrResult(
            textsByGroupIndex = textsByIndex,
            requestCount = requestCount,
            successCount = successCount,
            fallbackCount = (requestCount - successCount).coerceAtLeast(0),
            totalMs = totalMs,
            coverageArea = if (attemptedArea > 0f) successArea / attemptedArea else 0f,
        )
    }

    private fun preferredRoiOcrEngine(): ReaderOcrEngine {
        return settings.readerTranslationOcrEngine
    }

    private fun shouldTryRoiOcr(rect: Rect, bitmap: Bitmap): Boolean {
        if (rect.width() < dp(24f) || rect.height() < dp(24f)) return false
        val pageArea = (bitmap.width * bitmap.height).toFloat().coerceAtLeast(1f)
        if (rectArea(rect) / pageArea > 0.22f) return false
        return isLikelySpeechBubbleRegion(bitmap, rect)
    }

    private fun composeOcrBlocksText(
        blocks: List<OcrTextBlock>,
        sourceLang: String,
        fallbackRect: Rect,
    ): String {
        if (blocks.isEmpty()) return ""
        val fragments = blocks.mapNotNull { block ->
            val text = block.text.trim()
            if (text.isBlank()) return@mapNotNull null
            TextFragment(
                rect = block.boundingBox ?: fallbackRect,
                text = text,
                directionHint = block.directionHint,
                angleHintDegrees = block.angleHintDegrees,
                isAxisAligned = block.isAxisAligned,
                quadPoints = block.quadPoints ?: rectToTextQuad(block.boundingBox ?: fallbackRect),
            )
        }
        if (fragments.isEmpty()) return ""
        return composeGroupedText(fragments, sourceLang).trim()
    }

    private fun rectArea(rect: Rect): Float {
        return (rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)).toFloat()
    }
}
