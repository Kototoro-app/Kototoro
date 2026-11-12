package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceRef

class DesktopChapterReadStateTest {
    private val source = SourceRef("fixture", "zh", "MANGA")

    private fun chapter(number: Int, branch: String? = null) = SourceChapter(
        id = 100L + number, title = "Chapter $number", number = number.toFloat(), volume = 0, url = "c$number",
        scanlator = null, uploadDate = 0L, branch = branch, source = source,
    )

    private val chapters = (1..20).map(::chapter)

    private fun unread(current: SourceChapter?, furthest: SourceChapter?, branch: String? = null) =
        chapters.map { chapter ->
            desktopChapterCardData(chapter.copy(branch = branch), current?.copy(branch = branch), emptySet(), emptyList(),
                furthest?.copy(branch = branch)).isUnread
        }.mapIndexedNotNull { index, isUnread -> (index + 1).takeIf { isUnread } }

    @Test
    fun `chapters after the current one are unread without a furthest chapter`() {
        assertEquals((11..20).toList(), unread(chapters[9], null))
    }

    @Test
    fun `reopening an earlier chapter keeps the furthest ones read`() {
        assertEquals(emptyList<Int>(), unread(chapters[9], chapters[19]))
    }

    @Test
    fun `reading past the furthest chapter moves the boundary`() {
        assertEquals((16..20).toList(), unread(chapters[14], chapters[9]))
    }

    @Test
    fun `a furthest chapter of another branch is ignored`() {
        val other = chapter(20, branch = "other")
        val cards = chapters.map {
            desktopChapterCardData(it, chapters[9], emptySet(), emptyList(), other).isUnread
        }
        assertEquals(10, cards.count { it })
    }

    @Test
    fun `nothing is read without a history chapter`() {
        assertEquals((1..20).toList(), unread(null, null))
    }
}
