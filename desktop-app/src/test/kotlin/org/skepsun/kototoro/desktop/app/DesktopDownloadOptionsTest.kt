package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceRef

class DesktopDownloadOptionsTest {
    private val source = SourceRef("fixture", "zh", "MANGA")
    private fun chapter(number: Int, branch: String? = null) =
        SourceChapter(number.toLong(), "第${number}话", number.toFloat(), 0, "/$number", null, 0, branch, source)
    private val chapters = listOf(chapter(1, "A"), chapter(2), chapter(3, "A"), chapter(4), chapter(5, "A"))
    private val third = chapters[2]

    @Test
    fun `whole work skips chapters that already have a complete download`() {
        val targets = desktopDownloadTargets(chapters, third, listOf(chapters[1], chapters[3]),
            DesktopDownloadOption(DesktopDownloadScope.WHOLE))
        assertEquals(listOf(1L, 3L, 5L), targets.map { it.id })
    }

    @Test
    fun `unread starts after the current chapter instead of comparing numbers`() {
        val unread = desktopDownloadTargets(chapters, third, emptyList(),
            DesktopDownloadOption(DesktopDownloadScope.UNREAD))
        assertEquals(listOf(4L, 5L), unread.map { it.id })
        // No current chapter means the whole work is still unread, and an anchor that is gone does the same.
        assertEquals(5, desktopDownloadTargets(chapters, null, emptyList(),
            DesktopDownloadOption(DesktopDownloadScope.UNREAD)).size)
        assertEquals(5, desktopDownloadTargets(chapters, chapter(99), emptyList(),
            DesktopDownloadOption(DesktopDownloadScope.UNREAD)).size)
    }

    @Test
    fun `a branch scope keeps only that translation, default branch included`() {
        val branch = desktopDownloadTargets(chapters, third, listOf(chapters[0]),
            DesktopDownloadOption(DesktopDownloadScope.BRANCH, branch = "A"))
        assertEquals(listOf(3L, 5L), branch.map { it.id })
        val default = desktopDownloadTargets(chapters, third, emptyList(),
            DesktopDownloadOption(DesktopDownloadScope.BRANCH, branch = null))
        assertEquals(listOf(2L, 4L), default.map { it.id })
    }

    @Test
    fun `first N counts pending chapters and tolerates a non-positive limit`() {
        // Chapter 4 is saved, so the first two pending chapters are 1 and 2.
        assertEquals(listOf(1L, 2L), desktopDownloadTargets(chapters, third, listOf(chapters[3]),
            DesktopDownloadOption(DesktopDownloadScope.FIRST, count = 2)).map { it.id })
        assertTrue(desktopDownloadTargets(chapters, third, emptyList(),
            DesktopDownloadOption(DesktopDownloadScope.FIRST, count = 0)).isEmpty())
    }

    @Test
    fun `menu labels carry the count and fall back to the default branch name`() {
        val whole = desktopDownloadMenuLabel(DesktopDownloadOption(DesktopDownloadScope.WHOLE), 4)
        assertTrue(whole.contains("4"), whole)
        val branch = desktopDownloadMenuLabel(
            DesktopDownloadOption(DesktopDownloadScope.BRANCH, branch = null), 2, branch = null)
        assertTrue(branch.contains("2"), branch)
        assertTrue(branch.contains(desktopText("details.chapterBranchDefault")), branch)
        val first = desktopDownloadMenuLabel(DesktopDownloadOption(DesktopDownloadScope.FIRST, count = 25), 7)
        assertTrue(first.contains("25") && first.contains("7"), first)
        // Every option gets its own tag, otherwise one menu item would replace another in UI tests.
        val tags = listOf(
            DesktopDownloadOption(DesktopDownloadScope.WHOLE),
            DesktopDownloadOption(DesktopDownloadScope.UNREAD),
            DesktopDownloadOption(DesktopDownloadScope.BRANCH, branch = "A"),
            DesktopDownloadOption(DesktopDownloadScope.FIRST, count = 10),
            DesktopDownloadOption(DesktopDownloadScope.FIRST, count = 25),
        ).map(::desktopDownloadMenuTag)
        assertEquals(tags.size, tags.toSet().size)
    }
}
