package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import kotlin.random.Random

class PagedShadowParityTest {

    private fun createPage(id: Long, chapterId: Long, index: Int): ReaderPage {
        return ReaderPage(
            id = id,
            url = "https://example.com/chapter$chapterId/page$index.jpg",
            preview = null,
            headers = null,
            chapterId = chapterId,
            index = index,
            source = TestContentSource,
        )
    }

    private fun generateChapterPages(chapterId: Long, count: Int, startId: Long): List<ReaderPage> {
        return (0 until count).map { i ->
            createPage(id = startId + i, chapterId = chapterId, index = i)
        }
    }

    @Test
    fun `validate parity across regular even chapter pages without cover offset`() {
        val pages = generateChapterPages(chapterId = 1L, count = 8, startId = 100L)

        val discrepancies = PagedShadowValidator.validateDoublePageParity(
            pages = pages,
            coverPage = false,
        )

        assertTrue(discrepancies.isEmpty(), "Expected 0 discrepancies but got: $discrepancies")
    }

    @Test
    fun `validate parity across odd chapter pages without cover offset`() {
        val pages = generateChapterPages(chapterId = 1L, count = 7, startId = 100L)

        val discrepancies = PagedShadowValidator.validateDoublePageParity(
            pages = pages,
            coverPage = false,
        )

        assertTrue(discrepancies.isEmpty(), "Expected 0 discrepancies but got: $discrepancies")
    }

    @Test
    fun `validate parity with cover offset enabled across even and odd chapters`() {
        val evenPages = generateChapterPages(chapterId = 1L, count = 6, startId = 100L)
        val discrepanciesEven = PagedShadowValidator.validateDoublePageParity(
            pages = evenPages,
            coverPage = true,
        )
        assertTrue(discrepanciesEven.isEmpty(), "Even chapter with cover failed: $discrepanciesEven")

        val oddPages = generateChapterPages(chapterId = 1L, count = 7, startId = 200L)
        val discrepanciesOdd = PagedShadowValidator.validateDoublePageParity(
            pages = oddPages,
            coverPage = true,
        )
        assertTrue(discrepanciesOdd.isEmpty(), "Odd chapter with cover failed: $discrepanciesOdd")
    }

    @Test
    fun `validate parity across multiple chapters with boundary isolation`() {
        val pages = buildList {
            // Chapter 1: 5 pages (odd)
            addAll(generateChapterPages(chapterId = 1L, count = 5, startId = 100L))
            // Chapter 2: 4 pages (even)
            addAll(generateChapterPages(chapterId = 2L, count = 4, startId = 200L))
            // Chapter 3: 3 pages (odd)
            addAll(generateChapterPages(chapterId = 3L, count = 3, startId = 300L))
        }

        // 1. Without cover
        val noCoverIssues = PagedShadowValidator.validateDoublePageParity(
            pages = pages,
            coverPage = false,
        )
        assertTrue(noCoverIssues.isEmpty(), "Multi-chapter without cover failed: $noCoverIssues")

        // 2. With cover
        val withCoverIssues = PagedShadowValidator.validateDoublePageParity(
            pages = pages,
            coverPage = true,
        )
        assertTrue(withCoverIssues.isEmpty(), "Multi-chapter with cover failed: $withCoverIssues")
    }

    @Test
    fun `validate parity with RTL reading direction`() {
        val pages = buildList {
            addAll(generateChapterPages(chapterId = 10L, count = 6, startId = 1000L))
            addAll(generateChapterPages(chapterId = 20L, count = 5, startId = 2000L))
        }

        val discrepancies = PagedShadowValidator.validateDoublePageParity(
            pages = pages,
            coverPage = true,
            reverseLayout = true,
        )

        assertTrue(discrepancies.isEmpty(), "RTL layout failed: $discrepancies")
    }

    @Test
    fun `fuzzing parity across 100 randomized manga chapter configurations`() {
        val random = Random(42) // pinned seed for deterministic reproducibility

        for (trial in 1..100) {
            val chapterCount = random.nextInt(1, 6)
            var currentId = 1L

            val pages = buildList {
                for (c in 1..chapterCount) {
                    val pageCount = random.nextInt(1, 15)
                    val chapterId = c * 10L
                    for (p in 0 until pageCount) {
                        add(createPage(id = currentId++, chapterId = chapterId, index = p))
                    }
                }
            }

            val coverPage = random.nextBoolean()
            val reverseLayout = random.nextBoolean()

            val discrepancies = PagedShadowValidator.validateDoublePageParity(
                pages = pages,
                coverPage = coverPage,
                reverseLayout = reverseLayout,
            )

            assertEquals(
                0,
                discrepancies.size,
                "Fuzz trial $trial failed with coverPage=$coverPage reverseLayout=$reverseLayout: $discrepancies",
            )
        }
    }
}
