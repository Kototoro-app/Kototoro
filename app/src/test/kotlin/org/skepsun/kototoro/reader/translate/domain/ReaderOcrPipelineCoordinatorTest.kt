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
}
