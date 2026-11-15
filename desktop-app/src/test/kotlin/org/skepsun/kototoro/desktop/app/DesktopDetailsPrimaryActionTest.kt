package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.details.DetailsPrimaryActionKind
import org.skepsun.kototoro.core.details.detailsPrimaryAction

/**
 * The details page's one button names the chapter it will open, and says whether it starts or continues. The decision
 * itself lives in core-domain; these tests pin the wording the desktop turns it into.
 */
class DesktopDetailsPrimaryActionTest {

    private val chapters = listOf("第 1 话", "第 7 话").mapIndexed { index, title ->
        org.skepsun.kototoro.core.details.DetailsChapterRef(index + 1L, title)
    }

    @Test
    fun `a first open says start and names the first chapter`() {
        val action = detailsPrimaryAction(chapters, currentChapterId = null)
        val label = desktopDetailsPrimaryLabel(action, isVideo = false)
        assertTrue(label.contains("第 1 话"), "label should name the chapter: $label")
        assertNotEquals(label, desktopDetailsPrimaryLabel(action.copy(kind = DetailsPrimaryActionKind.CONTINUE), isVideo = false))
    }

    @Test
    fun `a work with history says continue and names where it stops`() {
        val action = detailsPrimaryAction(chapters, currentChapterId = 2L)
        val label = desktopDetailsPrimaryLabel(action, isVideo = false)
        assertTrue(label.contains("第 7 话"), "label should name the chapter: $label")
        assertNotEquals(label, desktopText("details.startReadingAt", "第 7 话"))
    }

    @Test
    fun `video wording is not the reading wording`() {
        val action = detailsPrimaryAction(chapters, currentChapterId = 2L)
        assertNotEquals(
            desktopDetailsPrimaryLabel(action, isVideo = false),
            desktopDetailsPrimaryLabel(action, isVideo = true),
        )
        assertFalse(action.chapterTitle.isNullOrBlank())
    }
}
