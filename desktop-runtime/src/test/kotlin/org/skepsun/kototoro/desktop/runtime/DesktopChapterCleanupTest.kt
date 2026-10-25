package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceRef

/**
 * "Read" is history-relative on Android, not a per-chapter flag, so these tests pin the walk the Windows cleanup uses:
 * number order, one branch, everything before the chapter the history stopped at.
 */
class DesktopChapterCleanupTest {
    private val source = SourceRef("fixture", "zh", "MANGA")

    private fun chapter(id: Long, number: Float, branch: String? = null) =
        SourceChapter(id, "第 ${number.toInt()} 话", number, 0, "/$id", null, 0L, branch, source)

    @Test
    fun `the chapters before the current one on its branch count as read`() {
        val chapters = listOf(chapter(3, 3f), chapter(1, 1f), chapter(2, 2f), chapter(4, 4f))

        assertEquals(setOf(1L, 2L, 3L), desktopReadChapterIds(chapters, currentChapterId = 4))
    }

    @Test
    fun `the current chapter itself and everything after it stay downloadable`() {
        val chapters = listOf(chapter(1, 1f), chapter(2, 2f), chapter(3, 3f))

        assertEquals(emptySet<Long>(), desktopReadChapterIds(chapters, currentChapterId = 1))
        assertEquals(setOf(1L), desktopReadChapterIds(chapters, currentChapterId = 2))
    }

    @Test
    fun `another branch is a different reading order and is left alone`() {
        // Android's "translated" branch restarts at chapter one; reading the official branch must not mark it read.
        val chapters = listOf(
            chapter(10, 1f, branch = "official"),
            chapter(11, 2f, branch = "official"),
            chapter(20, 1f, branch = "team"),
            chapter(21, 2f, branch = "team"),
        )

        assertEquals(setOf(20L), desktopReadChapterIds(chapters, currentChapterId = 21))
        assertEquals(emptySet<Long>(), desktopReadChapterIds(chapters, currentChapterId = 10))
    }

    @Test
    fun `a history chapter outside the list reads as nothing read`() {
        val chapters = listOf(chapter(1, 1f), chapter(2, 2f))

        assertTrue(desktopReadChapterIds(chapters, currentChapterId = 99).isEmpty())
        assertTrue(desktopReadChapterIds(emptyList(), currentChapterId = 1).isEmpty())
    }

    @Test
    fun `downloaded chapters extend the saved list and never duplicate a row`() {
        val saved = listOf(chapter(1, 1f), chapter(2, 2f))
        val downloaded = listOf(chapter(2, 2f), chapter(3, 3f))

        val merged = desktopChapterCandidates(saved, downloaded)

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        // Without the download rows a work whose source list never reached the database would look finished at ch. 1.
        assertEquals(setOf(1L, 2L), desktopReadChapterIds(merged, currentChapterId = 3))
    }
}
