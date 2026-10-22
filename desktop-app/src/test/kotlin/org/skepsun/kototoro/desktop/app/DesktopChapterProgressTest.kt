package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceRef

class DesktopChapterProgressTest {
    private val source = SourceRef("MIHON_9007199254740993", "zh", "MANGA")

    private fun chapter(number: Float, branch: String? = "scan") =
        SourceChapter(number.toLong(), "第${number.toInt()}话", number, 0, "/$number", null, 0, branch, source)

    /** Stored and displayed order is the reading order; the directory only reverses on request. */
    private val chapters = listOf(chapter(1f), chapter(2f), chapter(3f))
    private val content = SourceContent(
        id = 42, title = "作品", altTitles = emptySet(), url = "/work", publicUrl = "/work", rating = 0f,
        contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
        source = source, chapters = chapters,
    )

    @Test
    fun `mark as read targets that chapter and tolerates a stale list`() {
        assertEquals(chapter(2f), desktopMarkReadTarget(content, chapter(2f)))
        assertNull(desktopMarkReadTarget(content, chapter(9f)))
        // A different url for the same number is a refreshed chapter, not this one.
        assertNull(desktopMarkReadTarget(content, chapter(2f).copy(url = "/elsewhere")))
        assertNull(desktopMarkReadTarget(content.copy(chapters = null), chapter(2f)))
    }

    @Test
    fun `mark as unread moves history to the previous chapter in reading order`() {
        assertEquals(DesktopUnreadMark.Move(chapter(2f)), desktopUnreadMark(content, chapter(3f)))
        assertEquals(DesktopUnreadMark.Move(chapter(1f)), desktopUnreadMark(content, chapter(2f)))
        // The first chapter has nothing before it, so the work must look unread rather than point at nothing.
        assertEquals(DesktopUnreadMark.Clear, desktopUnreadMark(content, chapter(1f)))
        assertEquals(DesktopUnreadMark.Missing, desktopUnreadMark(content, chapter(9f)))
    }

    @Test
    fun `branches are independent when marking unread`() {
        val mixed = listOf(chapter(1f, "a"), chapter(2f, "a"), chapter(1f, "b"), chapter(2f, "b"))
        val work = content.copy(chapters = mixed)
        assertEquals(DesktopUnreadMark.Move(chapter(1f, "b")), desktopUnreadMark(work, chapter(2f, "b")))
        assertEquals(DesktopUnreadMark.Clear, desktopUnreadMark(work, chapter(1f, "b")))
        assertEquals(DesktopUnreadMark.Move(chapter(1f, "a")), desktopUnreadMark(work, chapter(2f, "a")))
    }
}
