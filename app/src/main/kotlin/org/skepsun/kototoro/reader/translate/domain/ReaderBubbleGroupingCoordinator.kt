package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug

internal class ReaderBubbleGroupingCoordinator(
    private val settings: AppSettings,
    private val onnxBubbleDetectorEngine: OnnxBubbleDetectorEngine,
    private val mergeRects: (List<Rect>) -> Rect?,
    private val rectArea: (Rect) -> Float,
    private val dp: (Float) -> Int,
    private val log: (() -> String) -> Unit,
    private val formatError: (String, Int) -> String,
    private val maxDetectedGroupFragments: Int,
) {

    suspend fun groupFragmentsForTranslation(
        fragments: List<TextFragment>,
        bitmap: Bitmap,
    ): BubbleGroupingResult {
        if (fragments.isEmpty()) {
            return BubbleGroupingResult(
                groups = emptyList(),
                detectorCandidateCount = 0,
                detectorMatchedFragmentCount = 0,
                detectorUsedGroupCount = 0,
                detectorSubdividedGroupCount = 0,
                detectorSubdividedFragmentCount = 0,
                detectorCoverageRate = 0f,
                detectorEngine = "none",
                detectorModelId = "",
                detectorRawBoxCount = 0,
                detectorTotalMs = 0L,
                detectorFallbackReason = "",
                fallbackFragmentCount = 0,
                fallbackGroupCount = 0,
                fallbackMode = "merge_primary",
            )
        }
        if (!settings.isReaderTranslationBubbleGroupingEnabled) {
            val groups = fragments.map { fragment ->
                GroupedBubbleSource(
                    fragments = listOf(fragment),
                    bubbleRect = null,
                    detectorAnchored = false,
                )
            }
            return BubbleGroupingResult(
                groups = groups,
                detectorCandidateCount = 0,
                detectorMatchedFragmentCount = 0,
                detectorUsedGroupCount = 0,
                detectorSubdividedGroupCount = 0,
                detectorSubdividedFragmentCount = 0,
                detectorCoverageRate = 0f,
                detectorEngine = "disabled",
                detectorModelId = "",
                detectorRawBoxCount = 0,
                detectorTotalMs = 0L,
                detectorFallbackReason = "grouping_disabled",
                fallbackFragmentCount = fragments.size,
                fallbackGroupCount = groups.size,
                fallbackMode = "merge_primary",
            )
        }
        val detectorOutcome = detectBubbleGroups(bitmap, fragments)
        val fallbackFragments = fragments.filterIndexed { index, _ -> index !in detectorOutcome.matchedFragmentIndices }
        val fallbackGroups = groupFallbackFragments(fallbackFragments)
        return BubbleGroupingResult(
            groups = detectorOutcome.groups + fallbackGroups,
            detectorCandidateCount = detectorOutcome.candidateCount,
            detectorMatchedFragmentCount = detectorOutcome.matchedFragmentCount,
            detectorUsedGroupCount = detectorOutcome.groups.size,
            detectorSubdividedGroupCount = detectorOutcome.subdividedGroupCount,
            detectorSubdividedFragmentCount = detectorOutcome.subdividedFragmentCount,
            detectorCoverageRate = detectorOutcome.matchedFragmentCount.toFloat() / fragments.size.toFloat(),
            detectorEngine = detectorOutcome.engine,
            detectorModelId = detectorOutcome.modelId,
            detectorRawBoxCount = detectorOutcome.rawBoxCount,
            detectorTotalMs = detectorOutcome.totalMs,
            detectorFallbackReason = detectorOutcome.fallbackReason,
            fallbackFragmentCount = fallbackFragments.size,
            fallbackGroupCount = fallbackGroups.size,
            fallbackMode = "merge_primary",
        )
    }

    private fun groupFallbackFragments(fragments: List<TextFragment>): List<GroupedBubbleSource> {
        if (fragments.isEmpty()) return emptyList()
        val parent = IntArray(fragments.size) { it }

        fun find(x: Int): Int {
            var current = x
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(a: Int, b: Int) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) {
                parent[rootB] = rootA
            }
        }

        for (i in fragments.indices) {
            for (j in i + 1 until fragments.size) {
                if (shouldFallbackGroupFragments(fragments[i], fragments[j])) {
                    union(i, j)
                }
            }
        }

        val groupsMap = linkedMapOf<Int, MutableList<TextFragment>>()
        for (i in fragments.indices) {
            groupsMap.getOrPut(find(i)) { mutableListOf() }.add(fragments[i])
        }

        return groupsMap.values.map { group ->
            GroupedBubbleSource(
                fragments = group.sortedBy { it.rect.top },
                bubbleRect = null,
                detectorAnchored = false,
            )
        }
    }

    private fun shouldFallbackGroupFragments(a: TextFragment, b: TextFragment): Boolean {
        if (
            a.directionHint != TextDirectionHint.UNKNOWN &&
            b.directionHint != TextDirectionHint.UNKNOWN &&
            a.directionHint != b.directionHint
        ) {
            return false
        }
        val minW = min(a.rect.width(), b.rect.width()).coerceAtLeast(1).toFloat()
        val minH = min(a.rect.height(), b.rect.height()).coerceAtLeast(1).toFloat()
        val gapX = axisGap(a.rect.left, a.rect.right, b.rect.left, b.rect.right).toFloat()
        val gapY = axisGap(a.rect.top, a.rect.bottom, b.rect.top, b.rect.bottom).toFloat()
        val xOverlap = overlapLen(a.rect.left, a.rect.right, b.rect.left, b.rect.right).toFloat()
        val yOverlap = overlapLen(a.rect.top, a.rect.bottom, b.rect.top, b.rect.bottom).toFloat()
        val xOverlapRatio = xOverlap / minW
        val yOverlapRatio = yOverlap / minH
        val dx = kotlin.math.abs(a.rect.centerX() - b.rect.centerX()).toFloat()
        val dy = kotlin.math.abs(a.rect.centerY() - b.rect.centerY()).toFloat()
        val preferColumnMerge = shouldPreferColumnMerge(a, b)
        val sameColumnCandidate =
            yOverlapRatio >= 0.28f &&
                gapX <= minW * 0.60f + dp(2f) &&
                dx <= minW * 1.75f
        val sameRowCandidate =
            xOverlapRatio >= 0.28f &&
                gapY <= minH * 0.70f + dp(2f) &&
                dy <= minH * 1.75f
        if (!(if (preferColumnMerge) sameColumnCandidate else sameRowCandidate)) {
            return false
        }
        val merged = Rect(
            min(a.rect.left, b.rect.left),
            min(a.rect.top, b.rect.top),
            max(a.rect.right, b.rect.right),
            max(a.rect.bottom, b.rect.bottom),
        )
        val sumArea = rectArea(a.rect) + rectArea(b.rect)
        val mergedArea = rectArea(merged).coerceAtLeast(1f)
        val inflation = if (sumArea > 0f) mergedArea / sumArea else Float.MAX_VALUE
        if (inflation > 1.85f) {
            return false
        }
        return true
    }

    private fun shouldPreferColumnMerge(a: TextFragment, b: TextFragment): Boolean {
        return when {
            a.directionHint == TextDirectionHint.VERTICAL || b.directionHint == TextDirectionHint.VERTICAL -> true
            a.directionHint == TextDirectionHint.HORIZONTAL || b.directionHint == TextDirectionHint.HORIZONTAL -> false
            isLikelyVerticalFragment(a) && isLikelyVerticalFragment(b) -> true
            isLikelyHorizontalFragment(a) && isLikelyHorizontalFragment(b) -> false
            else -> {
                val avgWidth = (a.rect.width() + b.rect.width()) / 2f
                val avgHeight = (a.rect.height() + b.rect.height()) / 2f
                avgHeight > avgWidth * 1.2f
            }
        }
    }

    private fun isLikelyVerticalFragment(fragment: TextFragment): Boolean {
        return when (fragment.directionHint) {
            TextDirectionHint.VERTICAL -> true
            TextDirectionHint.HORIZONTAL -> false
            else -> fragment.rect.height() > fragment.rect.width() * 1.2f
        }
    }

    private fun isLikelyHorizontalFragment(fragment: TextFragment): Boolean {
        return when (fragment.directionHint) {
            TextDirectionHint.HORIZONTAL -> true
            TextDirectionHint.VERTICAL -> false
            else -> fragment.rect.width() >= fragment.rect.height() * 0.85f
        }
    }

    private fun overlapLen(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int {
        return (min(aEnd, bEnd) - max(aStart, bStart)).coerceAtLeast(0)
    }

    private fun axisGap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int {
        return when {
            aEnd < bStart -> bStart - aEnd
            bEnd < aStart -> aStart - bEnd
            else -> 0
        }
    }

    private suspend fun detectBubbleGroups(
        bitmap: Bitmap,
        fragments: List<TextFragment>,
    ): BubbleDetectorOutcome {
        if (!settings.isReaderTranslationBubbleDetectorEnabled) {
            return BubbleDetectorOutcome(
                groups = emptyList(),
                matchedFragmentIndices = emptySet(),
                candidateCount = 0,
                matchedFragmentCount = 0,
                subdividedGroupCount = 0,
                subdividedFragmentCount = 0,
                engine = "disabled",
                modelId = "",
                rawBoxCount = 0,
                totalMs = 0L,
                fallbackReason = "detector_disabled",
            )
        }
        val onnxAttempt = runCatching {
            onnxBubbleDetectorEngine.detectAttempt(bitmap)
        }.onFailure {
            it.printStackTraceDebug()
        }.getOrNull()
        if (onnxAttempt != null) {
            log { "metric.bubble.detector.onnx.status=${onnxAttempt.status.name.lowercase()}" }
            log { "metric.bubble.detector.onnx.stage=${onnxAttempt.stage.ifBlank { "none" }}" }
            log { "metric.bubble.detector.onnx.backend=${onnxAttempt.backend.ifBlank { "none" }}" }
            log { "metric.bubble.detector.onnx.parser=${onnxAttempt.parser.ifBlank { "none" }}" }
            log { "metric.bubble.detector.onnx.input_name=${onnxAttempt.inputName.ifBlank { "none" }}" }
            log { "metric.bubble.detector.onnx.input_shape=${onnxAttempt.inputShape.ifBlank { "none" }}" }
            log { "metric.bubble.detector.onnx.output_names=${onnxAttempt.outputNames.ifBlank { "none" }}" }
            onnxAttempt.result?.let { result ->
                log { "metric.bubble.detector.onnx.decoded_boxes=${result.decodedBoxCount}" }
                log { "metric.bubble.detector.onnx.final_boxes=${result.finalBoxCount}" }
            }
            if (onnxAttempt.error.isNotBlank()) {
                log { "bubble detector onnx error=${formatError(onnxAttempt.error, 400)}" }
            }
        }
        val onnxResult = onnxAttempt?.result
        if (onnxResult != null) {
            val grouped = groupFragmentsByDetectedRects(
                fragments = fragments,
                detectedRects = onnxResult.boxes,
                bitmap = bitmap,
            )
            if (grouped.groups.isNotEmpty()) {
                return BubbleDetectorOutcome(
                    groups = grouped.groups,
                    matchedFragmentIndices = grouped.matchedFragmentIndices,
                    candidateCount = grouped.candidateCount,
                    matchedFragmentCount = grouped.matchedFragmentCount,
                    subdividedGroupCount = grouped.subdividedGroupCount,
                    subdividedFragmentCount = grouped.subdividedFragmentCount,
                    engine = "onnx_${onnxResult.backend.lowercase()}",
                    modelId = onnxResult.modelId,
                    rawBoxCount = onnxResult.rawBoxCount,
                    totalMs = onnxResult.totalMs,
                    fallbackReason = "",
                )
            }
            log {
                "bubble detector onnx no usable groups model=${onnxResult.modelId} rawBoxes=${onnxResult.rawBoxCount}, fallback=cv"
            }
        }
        val fallbackReason = when (onnxAttempt?.status) {
            OnnxBubbleDetectorEngine.AttemptStatus.NO_MODEL_DOWNLOADED -> "onnx_no_model_downloaded"
            OnnxBubbleDetectorEngine.AttemptStatus.RUNTIME_UNAVAILABLE -> "onnx_runtime_unavailable"
            OnnxBubbleDetectorEngine.AttemptStatus.NO_BOXES -> "onnx_no_boxes"
            OnnxBubbleDetectorEngine.AttemptStatus.SUCCESS -> "onnx_no_usable_groups"
            null -> "onnx_attempt_failed"
        }
        val attemptedModelId = onnxAttempt?.modelId.orEmpty()

        return BubbleDetectorOutcome(
            groups = emptyList(),
            matchedFragmentIndices = emptySet(),
            candidateCount = 0,
            matchedFragmentCount = 0,
            subdividedGroupCount = 0,
            subdividedFragmentCount = 0,
            engine = "onnx",
            modelId = attemptedModelId,
            rawBoxCount = 0,
            totalMs = 0L,
            fallbackReason = fallbackReason,
        )
    }

    private fun groupFragmentsByDetectedRects(
        fragments: List<TextFragment>,
        detectedRects: List<OnnxBubbleDetectorEngine.DetectedBox>,
        bitmap: Bitmap,
    ): BubbleDetectorOutcome {
        if (detectedRects.isEmpty()) {
            return BubbleDetectorOutcome(
                groups = emptyList(),
                matchedFragmentIndices = emptySet(),
                candidateCount = 0,
                matchedFragmentCount = 0,
                subdividedGroupCount = 0,
                subdividedFragmentCount = 0,
                engine = "onnx",
                modelId = "",
                rawBoxCount = 0,
                totalMs = 0L,
                fallbackReason = "",
            )
        }
        val bitmapArea = (bitmap.width * bitmap.height).toFloat().coerceAtLeast(1f)
        val uniqueCandidates = linkedMapOf<String, DetectedBubbleCandidate>()
        for (detectedBox in detectedRects) {
            val matched = fragments.indices.filter { index ->
                matchesDetectedBubbleRect(detectedBox.rect, fragments[index].rect)
            }
            if (matched.isEmpty()) continue
            val unionRect = mergeRects(matched.map { fragments[it].rect }) ?: continue
            val candidate = buildDetectedBubbleCandidate(
                detectedBox = detectedBox,
                unionRect = unionRect,
                fragmentRects = fragments.map { it.rect },
                matchedIndices = matched,
                bitmapArea = bitmapArea,
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
            ) ?: continue
            val key = candidate.fragmentIndices.joinToString(",")
            val existing = uniqueCandidates[key]
            if (existing == null || candidate.isBetterThan(existing)) {
                uniqueCandidates[key] = candidate
            }
        }
        if (uniqueCandidates.isEmpty()) {
            return BubbleDetectorOutcome(
                groups = emptyList(),
                matchedFragmentIndices = emptySet(),
                candidateCount = 0,
                matchedFragmentCount = 0,
                subdividedGroupCount = 0,
                subdividedFragmentCount = 0,
                engine = "onnx",
                modelId = "",
                rawBoxCount = detectedRects.size,
                totalMs = 0L,
                fallbackReason = "",
            )
        }
        val claimed = linkedSetOf<Int>()
        val groups = buildList {
            for (candidate in uniqueCandidates.values.sortedWith(
                compareByDescending<DetectedBubbleCandidate> { it.fragmentIndices.size }
                    .thenByDescending { it.score }
                    .thenBy { rectArea(it.rect) }
            )) {
                val available = candidate.fragmentIndices.filterNot { it in claimed }
                if (available.isEmpty()) continue
                val groupFragments = available.map { fragments[it] }
                val groupRect = mergeRects(groupFragments.map { it.rect }) ?: continue
                val tightened = tightenDetectedBubbleRect(candidate.rect, groupRect)
                if (tightened.width() <= dp(8f) || tightened.height() <= dp(8f)) continue
                claimed += available
                add(
                    GroupedBubbleSource(
                        fragments = groupFragments,
                        bubbleRect = tightened,
                        classId = candidate.classId,
                        detectorAnchored = true,
                    )
                )
            }
        }
        return BubbleDetectorOutcome(
            groups = groups,
            matchedFragmentIndices = claimed,
            candidateCount = uniqueCandidates.size,
            matchedFragmentCount = claimed.size,
            subdividedGroupCount = 0,
            subdividedFragmentCount = 0,
            engine = "onnx",
            modelId = "",
            rawBoxCount = detectedRects.size,
            totalMs = 0L,
            fallbackReason = "",
        )
    }

    private fun matchesDetectedBubbleRect(candidateRect: Rect, fragmentRect: Rect): Boolean {
        if (candidateRect.contains(fragmentRect.centerX(), fragmentRect.centerY())) {
            return true
        }
        val fragmentArea = rectArea(fragmentRect).coerceAtLeast(1f)
        val directOverlap = overlapArea(candidateRect, fragmentRect) / fragmentArea
        if (directOverlap >= 0.28f) {
            return true
        }
        val padX = max(dp(6f), candidateRect.width() / 10)
        val padY = max(dp(6f), candidateRect.height() / 10)
        val expanded = Rect(
            (candidateRect.left - padX).coerceAtLeast(0),
            (candidateRect.top - padY).coerceAtLeast(0),
            candidateRect.right + padX,
            candidateRect.bottom + padY,
        )
        if (!expanded.contains(fragmentRect.centerX(), fragmentRect.centerY())) {
            return false
        }
        val expandedOverlap = overlapArea(expanded, fragmentRect) / fragmentArea
        return expandedOverlap >= 0.60f
    }

    private fun buildDetectedBubbleCandidate(
        detectedBox: OnnxBubbleDetectorEngine.DetectedBox,
        unionRect: Rect,
        fragmentRects: List<Rect>,
        matchedIndices: List<Int>,
        bitmapArea: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): DetectedBubbleCandidate? {
        val candidateArea = rectArea(detectedBox.rect).coerceAtLeast(1f)
        if (candidateArea > bitmapArea * 0.45f) return null
        val touchesEdge = detectedBox.rect.left <= 0 || detectedBox.rect.top <= 0 ||
            detectedBox.rect.right >= bitmapWidth || detectedBox.rect.bottom >= bitmapHeight
        if (touchesEdge && candidateArea > bitmapArea * 0.24f) return null
        val fragmentsArea = matchedIndices.sumOf { rectArea(fragmentRects[it]).toDouble() }.toFloat()
        val unionArea = rectArea(unionRect).coerceAtLeast(1f)
        val inflation = candidateArea / unionArea
        val textCoverage = fragmentsArea / candidateArea
        val matchedCount = matchedIndices.size
        if (matchedCount > maxDetectedGroupFragments) {
            return null
        }
        val maxInflation = when {
            matchedCount >= 3 -> 16f
            matchedCount == 2 -> 20f
            else -> 26f
        }
        val minCoverage = when {
            matchedCount >= 3 -> 0.006f
            matchedCount == 2 -> 0.010f
            else -> 0.015f
        }
        if (inflation > maxInflation || textCoverage < minCoverage) {
            return null
        }
        // Store the original detector rect (not tightened) so the single tighten
        // call at group assembly time has full bubble dimensions for padding.
        if (detectedBox.rect.width() <= dp(8f) || detectedBox.rect.height() <= dp(8f)) {
            return null
        }
        val score = matchedCount * 4f + textCoverage * 120f - inflation - if (touchesEdge) 2f else 0f
        return DetectedBubbleCandidate(
            rect = detectedBox.rect,
            fragmentIndices = matchedIndices.sorted(),
            score = score,
            classId = detectedBox.classId,
        )
    }

    private fun tightenDetectedBubbleRect(candidateRect: Rect, unionRect: Rect): Rect {
        val candidateArea = rectArea(candidateRect).coerceAtLeast(1f)
        val unionCoverage = rectArea(unionRect) / candidateArea
        if (unionCoverage <= 0.38f) {
            return Rect(candidateRect)
        }
        // Use the larger of candidate-based and union-based padding so that
        // the rendering rect covers most of the detected bubble area even when
        // MLKit only found a small portion of the text inside.
        val padX = max(dp(8f), max(candidateRect.width() / 3, unionRect.width() / 5))
        val padY = max(dp(8f), max(candidateRect.height() / 3, unionRect.height() / 5))
        val left = max(candidateRect.left, unionRect.left - padX)
        val top = max(candidateRect.top, unionRect.top - padY)
        val right = min(candidateRect.right, unionRect.right + padX)
        val bottom = min(candidateRect.bottom, unionRect.bottom + padY)
        return Rect(
            left,
            top,
            max(left + dp(8f), right),
            max(top + dp(8f), bottom),
        )
    }

    private fun overlapArea(a: Rect, b: Rect): Float {
        val width = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0)
        val height = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0)
        return (width * height).toFloat()
    }
}
