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

    private fun work(id: Long, chapters: List<SourceChapter>, current: Long?, downloaded: List<SourceChapter> = emptyList()) =
        DesktopShelfDownloadWork(id, "work $id", chapters, current, downloaded)

    @Test
    fun `a shelf batch keeps each work's own progress and saved chapters`() {
        val first = chapters
        val second = listOf(chapter(1), chapter(2), chapter(3))
        val works = listOf(
            work(1, first, current = chapters[2].id, downloaded = listOf(chapters[3])),
            work(2, second, current = second[1].id),
        )
        val unread = desktopShelfDownloadTargets(works, DesktopDownloadOption(DesktopDownloadScope.UNREAD))
        assertEquals(listOf(1L, 2L), unread.map { it.work.contentId })
        // The first work continues after its third chapter and skips the saved fourth; the second after its second.
        assertEquals(listOf(5L), unread[0].chapters.map { it.id })
        assertEquals(listOf(3L), unread[1].chapters.map { it.id })
        // Every branch of a selected work is queued, not only the translation the details list was filtered to.
        assertEquals(3, desktopShelfDownloadTargets(works, DesktopDownloadOption(DesktopDownloadScope.WHOLE))
            .first { it.work.contentId == 2L }.chapters.size)
    }

    @Test
    fun `works with nothing to queue drop out and an empty batch stays empty`() {
        val works = listOf(
            work(1, chapters, current = null, downloaded = chapters),
            work(2, emptyList(), current = null),
            work(3, chapters, current = chapters[4].id),
        )
        // The first work is fully saved and the second holds no chapters at all, so only the third is left.
        assertEquals(listOf(3L), desktopShelfDownloadTargets(works,
            DesktopDownloadOption(DesktopDownloadScope.WHOLE)).map { it.work.contentId })
        // Having read the last chapter leaves nothing unread, and a work that is fully saved has nothing unread
        // either, so nothing is left for the batch to queue.
        assertTrue(desktopShelfDownloadTargets(works,
            DesktopDownloadOption(DesktopDownloadScope.UNREAD)).isEmpty())
        assertTrue(desktopShelfDownloadTargets(emptyList(),
            DesktopDownloadOption(DesktopDownloadScope.WHOLE)).isEmpty())
    }

    @Test
    fun `first N applies per work instead of to one shared list`() {
        val works = listOf(work(1, chapters, current = null), work(2, chapters, current = null))
        val first = desktopShelfDownloadTargets(works, DesktopDownloadOption(DesktopDownloadScope.FIRST, count = 1))
        assertEquals(listOf(1L, 2L), first.map { it.work.contentId })
        assertEquals(listOf(1, 1), first.map { it.chapters.size })
        assertEquals(listOf(1L), first[0].chapters.map { it.id })
    }

    @Test
    fun `shelf scopes and tags stay unique and name every scope`() {
        assertEquals(5, DesktopShelfDownloadOptions.size)
        assertTrue(DesktopShelfDownloadOptions.all { it.scope != DesktopDownloadScope.BRANCH })
        val tags = DesktopShelfDownloadOptions.map(::desktopLibraryDownloadTag)
        assertEquals(tags.size, tags.toSet().size)
        DesktopShelfDownloadOptions.forEach { option ->
            assertTrue(desktopLibraryDownloadLabel(option).isNotBlank(), option.toString())
        }
        val first = DesktopShelfDownloadOptions.first { it.scope == DesktopDownloadScope.FIRST }
        assertTrue(desktopLibraryDownloadLabel(first).contains("10"), desktopLibraryDownloadLabel(first))
    }
}
