package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.net.Uri

/** Shared fail codes surfaced in the translation diagnostic output and the task panel filter. */
internal object ReaderTranslationFailCode {
    const val OCR_EMPTY = "OCR_EMPTY"
    const val OCR_ENGINE_FAILED = "OCR_ENGINE_FAILED"
    const val TRANSLATE_EMPTY = "TRANSLATE_EMPTY"
    const val RENDER_FILTERED = "RENDER_FILTERED"
    const val PROCESS_EXCEPTION = "PROCESS_EXCEPTION"
}

internal class ReaderOcrPipelineCoordinator(
    private val loadPageText: suspend (Uri, String, Long) -> PageOcrLoadResult,
    private val mergePageTextBlocks: (List<OcrTextBlock>, String) -> List<TextFragment>,
) {

    suspend fun execute(
        sourceUri: Uri,
        sourceLang: String,
        pageId: Long,
        bitmap: Bitmap,
    ): OcrPipelineResult {
        return executePageFirst(
            sourceUri = sourceUri,
            sourceLang = sourceLang,
            pageId = pageId,
            bitmap = bitmap,
        )
    }

    private suspend fun executePageFirst(
        sourceUri: Uri,
        sourceLang: String,
        pageId: Long,
        bitmap: Bitmap,
    ): OcrPipelineResult {
        val pageOcr = loadPageText(sourceUri, sourceLang, pageId)
        if (pageOcr.textBlocks.isEmpty()) {
            // Distinguish "no text found" from "the OCR engine itself failed to
            // initialize (e.g. MLKit native/GMS init NPE on some devices)" so the
            // diagnostic tells the truth instead of a misleading OCR_EMPTY.
            val failCode = if (pageOcr.hadOcrEngineError) {
                ReaderTranslationFailCode.OCR_ENGINE_FAILED
            } else {
                ReaderTranslationFailCode.OCR_EMPTY
            }
            return OcrPipelineResult(
                pageTextBlocks = emptyList(),
                textFragments = emptyList(),
                pageOcr = pageOcr,
                failCode = failCode,
            )
        }
        val textFragments = mergePageTextBlocks(pageOcr.textBlocks, sourceLang)
        return OcrPipelineResult(
            pageTextBlocks = pageOcr.textBlocks,
            textFragments = textFragments,
            pageOcr = pageOcr,
        )
    }
}

internal data class OcrPipelineResult(
    val pageTextBlocks: List<OcrTextBlock>,
    val textFragments: List<TextFragment>,
    val pageOcr: PageOcrLoadResult?,
    val failCode: String? = null,
)

internal data class PageOcrLoadResult(
    val textBlocks: List<OcrTextBlock>,
    val cacheHit: Boolean,
    val durationMs: Long,
    /** True when at least one OCR engine attempt threw (init failure), not merely found no text. */
    val hadOcrEngineError: Boolean = false,
)
