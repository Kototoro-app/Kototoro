package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.net.Uri

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
			return OcrPipelineResult(
				pageTextBlocks = emptyList(),
				textFragments = emptyList(),
				pageOcr = pageOcr,
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
)

internal data class PageOcrLoadResult(
	val textBlocks: List<OcrTextBlock>,
	val cacheHit: Boolean,
	val durationMs: Long,
)
