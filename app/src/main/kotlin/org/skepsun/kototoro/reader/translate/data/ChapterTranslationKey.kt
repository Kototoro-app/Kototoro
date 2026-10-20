package org.skepsun.kototoro.reader.translate.data

import org.skepsun.kototoro.core.prefs.AppSettings
import java.security.MessageDigest

/**
 * The recipe a finished chapter translation was produced with.
 *
 * The rendered pages are pixels: any setting that changes OCR, the translation backend, the language
 * pair or the baked-in overlay layout changes the result. Comparing this fingerprint with the one stored
 * per chapter is what lets the chapter list say "this translation came from other settings" instead of
 * pretending the existing pixels still match what the user configured.
 *
 * View-only switches (show translated, debug logs, API key) deliberately stay out: they never change
 * output, so they must not invalidate a finished translation.
 */
data class ChapterTranslationKey(
    val fingerprint: String,
    val engineLabel: String,
    val sourceLang: String,
    val targetLang: String,
)

/**
 * What the chapter list needs to know about a chapter's stored translation.
 *
 * [isStale] means pixels exist but were produced by a different recipe (engine, models, language pair or
 * layout settings), so the row should offer a re-translate instead of silently pretending it is current.
 */
data class ChapterTranslationState(
    val isPrepared: Boolean,
    val isStale: Boolean,
    val version: Int = 0,
    val engineLabel: String = "",
    /** Pages already rendered with the stored recipe, and how many the chapter has in total. */
    val pagesDone: Int = 0,
    val pagesTotal: Int = 0,
) {
    /** The chapter is not finished: a job started it, or the interactive reader translated only some pages. */
    val isPartial: Boolean get() = !isPrepared && pagesTotal > 0

    val progress: Float?
        get() = when {
            pagesTotal <= 0 -> null
            else -> (pagesDone.toFloat() / pagesTotal).coerceIn(0f, 1f)
        }
}

/** Chapter ids split by whether their stored translation still matches the current settings. */
data class TranslatedChapters(
    val ready: Set<Long>,
    val stale: Set<Long>,
    /** Chapters with a partially rendered translation, mapped to their fraction (0..1). */
    val partial: Map<Long, Float> = emptyMap(),
) {
    val isEmpty: Boolean get() = ready.isEmpty() && stale.isEmpty() && partial.isEmpty()

    companion object {
        val EMPTY = TranslatedChapters(ready = emptySet(), stale = emptySet(), partial = emptyMap())
    }
}

/**
 * The pure inputs of a translation recipe.
 *
 * Kept as plain values so the fingerprint rules are testable without Android preferences: settings are
 * read once in [toChapterTranslationRecipe], everything after that is arithmetic on this class.
 */
data class ChapterTranslationRecipe(
    val ocrEngine: String,
    val ocrMode: String,
    val ocrDetectionModel: String,
    val ocrRecognitionModel: String,
    val ocrDetectionMaxSide: Int,
    val ocrDetectionThreshold: Float,
    val ocrMinBoxSize: Int,
    val ocrRecognitionThreshold: Float,
    val ocrRecognitionMaxWidth: Int,
    val ocrRecognitionBatchSize: Int,
    val backendPreset: String,
    val backendEndpoint: String,
    val backendModel: String,
    val backendOnnxModel: String,
    val isBubbleDetectorEnabled: Boolean,
    val isBubbleGroupingEnabled: Boolean,
    val bubbleGroupingTuning: String,
    val isQualityFilterEnabled: Boolean,
    val renderStyle: String,
    val overlayCompactness: String,
    val sourceLang: String,
    val targetLang: String,
) {

    fun fingerprint(): String {
        val parts = listOf(
            "ocr_engine=$ocrEngine",
            "ocr_mode=$ocrMode",
            "ocr_det_model=$ocrDetectionModel",
            "ocr_rec_model=$ocrRecognitionModel",
            "ocr_det_max_side=$ocrDetectionMaxSide",
            "ocr_det_threshold=$ocrDetectionThreshold",
            "ocr_min_box=$ocrMinBoxSize",
            "ocr_rec_threshold=$ocrRecognitionThreshold",
            "ocr_rec_max_width=$ocrRecognitionMaxWidth",
            "ocr_rec_batch=$ocrRecognitionBatchSize",
            "backend_preset=$backendPreset",
            "backend_endpoint=$backendEndpoint",
            "backend_model=$backendModel",
            "backend_onnx=$backendOnnxModel",
            "bubble_detector=$isBubbleDetectorEnabled",
            "bubble_grouping=$isBubbleGroupingEnabled",
            "bubble_grouping_tuning=$bubbleGroupingTuning",
            "quality_filter=$isQualityFilterEnabled",
            "render_style=$renderStyle",
            "overlay_compactness=$overlayCompactness",
            "source=$sourceLang",
            "target=$targetLang",
        )
        return parts.joinToString(separator = "\u001f").sha256Prefix()
    }

    /** Short human readable description for the translation history: "Paddle ja... -> Gemini x". */
    fun engineLabel(): String {
        val ocr = if (ocrEngine.equals("PADDLE", ignoreCase = true)) {
            "Paddle $ocrRecognitionModel"
        } else {
            "MLKit"
        }
        val backend = when {
            backendEndpoint.isNotBlank() -> "$backendPreset $backendModel"
            backendOnnxModel.isNotBlank() -> "ONNX $backendOnnxModel"
            else -> "MLKit"
        }
        return "$ocr \u2192 $backend"
    }
}

fun AppSettings.toChapterTranslationRecipe(
    sourceLang: String,
    targetLang: String,
): ChapterTranslationRecipe = ChapterTranslationRecipe(
    ocrEngine = readerTranslationOcrEngine.name,
    ocrMode = readerTranslationOcrMode.name,
    ocrDetectionModel = readerTranslationPaddleDetModelId,
    ocrRecognitionModel = readerTranslationPaddleOfficialModelId,
    ocrDetectionMaxSide = readerTranslationOcrDetectionMaxSide,
    ocrDetectionThreshold = readerTranslationOcrDetectionThreshold,
    ocrMinBoxSize = readerTranslationOcrMinBoxSize,
    ocrRecognitionThreshold = readerTranslationOcrRecognitionThreshold,
    ocrRecognitionMaxWidth = readerTranslationOcrRecognitionMaxWidth,
    ocrRecognitionBatchSize = readerTranslationOcrRecognitionBatchSize,
    backendPreset = readerTranslationApiProviderPreset,
    backendEndpoint = readerTranslationApiEndpoint,
    backendModel = readerTranslationApiModel,
    backendOnnxModel = readerTranslationOnnxModelId,
    isBubbleDetectorEnabled = isReaderTranslationBubbleDetectorEnabled,
    isBubbleGroupingEnabled = isReaderTranslationBubbleGroupingEnabled,
    bubbleGroupingTuning = readerTranslationBubbleGroupingTuning,
    isQualityFilterEnabled = isReaderTranslationQualityFilterEnabled,
    renderStyle = readerTranslationRenderStyle,
    overlayCompactness = readerTranslationOverlayCompactness,
    sourceLang = sourceLang,
    targetLang = targetLang,
)

fun AppSettings.resolveChapterTranslationKey(
    sourceLang: String,
    targetLang: String,
): ChapterTranslationKey {
    val recipe = toChapterTranslationRecipe(sourceLang, targetLang)
    return ChapterTranslationKey(
        fingerprint = recipe.fingerprint(),
        engineLabel = recipe.engineLabel(),
        sourceLang = sourceLang,
        targetLang = targetLang,
    )
}

private fun String.sha256Prefix(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString(separator = "") { "%02x".format(it) }
}
