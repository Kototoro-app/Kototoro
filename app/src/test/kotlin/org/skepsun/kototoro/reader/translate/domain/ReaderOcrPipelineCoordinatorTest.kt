package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ReaderOcrPipelineCoordinatorTest {

    private val testUri = mockk<Uri>(relaxed = true)

    @Test
    fun `execute returns empty grouping when page OCR has no text`() = runTest {
        val coordinator = ReaderOcrPipelineCoordinator(
            loadPageText = { _, _, _ ->
                PageOcrLoadResult(
                    textBlocks = emptyList(),
                    cacheHit = false,
                    durationMs = 1L,
                )
            },
            mergePageTextBlocks = { _, _ -> emptyList() },
        )

        val result = coordinator.execute(
            sourceUri = testUri,
            sourceLang = "ja",
            pageId = 1L,
            bitmap = mockk<Bitmap>(relaxed = true),
        )

        assertEquals(emptyList<OcrTextBlock>(), result.pageTextBlocks)
        assertEquals(emptyList<TextFragment>(), result.textFragments)
        assertNotNull(result.pageOcr)
    }

    @Test
    fun `execute merges page OCR blocks before grouping`() = runTest {
        val block = OcrTextBlock("hello", Rect(0, 0, 10, 10))
        val fragment = TextFragment(Rect(0, 0, 10, 10), "hello")
        val coordinator = ReaderOcrPipelineCoordinator(
            loadPageText = { _, _, _ ->
                PageOcrLoadResult(
                    textBlocks = listOf(block),
                    cacheHit = true,
                    durationMs = 5L,
                )
            },
            mergePageTextBlocks = { blocks, _ ->
                assertEquals(listOf(block), blocks)
                listOf(fragment)
            },
        )

        val result = coordinator.execute(
            sourceUri = testUri,
            sourceLang = "ja",
            pageId = 2L,
            bitmap = mockk<Bitmap>(relaxed = true),
        )

        assertEquals(listOf(block), result.pageTextBlocks)
        assertEquals(listOf(fragment), result.textFragments)
        assertEquals(true, result.pageOcr?.cacheHit)
    }

    @Test
    fun `empty page without engine error is classified as OCR empty`() = runTest {
        val coordinator = ReaderOcrPipelineCoordinator(
            loadPageText = { _, _, _ ->
                PageOcrLoadResult(
                    textBlocks = emptyList(),
                    cacheHit = false,
                    durationMs = 1L,
                    hadOcrEngineError = false,
                )
            },
            mergePageTextBlocks = { _, _ -> emptyList() },
        )

        val result = coordinator.execute(
            sourceUri = testUri,
            sourceLang = "ja",
            pageId = 3L,
            bitmap = mockk<Bitmap>(relaxed = true),
        )

        assertEquals(ReaderTranslationFailCode.OCR_EMPTY, result.failCode)
    }

    @Test
    fun `empty page from engine failure is classified as OCR engine failed`() = runTest {
        val coordinator = ReaderOcrPipelineCoordinator(
            loadPageText = { _, _, _ ->
                PageOcrLoadResult(
                    textBlocks = emptyList(),
                    cacheHit = false,
                    durationMs = 1L,
                    hadOcrEngineError = true,
                )
            },
            mergePageTextBlocks = { _, _ -> emptyList() },
        )

        val result = coordinator.execute(
            sourceUri = testUri,
            sourceLang = "ja",
            pageId = 4L,
            bitmap = mockk<Bitmap>(relaxed = true),
        )

        assertEquals(ReaderTranslationFailCode.OCR_ENGINE_FAILED, result.failCode)
        assertEquals(true, result.pageOcr?.hadOcrEngineError)
    }

    @Test
    fun `non-empty page carries no fail code even when engine had an earlier error`() = runTest {
        val block = OcrTextBlock("文字", Rect(0, 0, 10, 10))
        val coordinator = ReaderOcrPipelineCoordinator(
            loadPageText = { _, _, _ ->
                PageOcrLoadResult(
                    textBlocks = listOf(block),
                    cacheHit = false,
                    durationMs = 1L,
                    hadOcrEngineError = true,
                )
            },
            mergePageTextBlocks = { blocks, _ ->
                listOf(TextFragment(Rect(0, 0, 10, 10), blocks.first().text))
            },
        )

        val result = coordinator.execute(
            sourceUri = testUri,
            sourceLang = "ja",
            pageId = 5L,
            bitmap = mockk<Bitmap>(relaxed = true),
        )

        assertNotNull(result.pageOcr)
        assertEquals(null, result.failCode)
        assertEquals(1, result.textFragments.size)
    }
}
