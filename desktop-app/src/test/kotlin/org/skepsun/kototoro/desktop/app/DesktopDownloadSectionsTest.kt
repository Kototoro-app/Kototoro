package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterDownload
import org.skepsun.kototoro.core.source.SourceRef

/** The downloads page's split: what still has work is a queue, what is saved is a library, and never both at once. */
class DesktopDownloadSectionsTest {

    private val source = SourceRef("fixture", "zh", "MANGA")

    private fun state(chapter: String, status: DesktopDownloadStatus): DesktopDownloadState =
        DesktopDownloadState(
            record = SourceChapterDownload(
                contentId = 1L,
                chapter = SourceChapter(1L + chapter.hashCode(), chapter, 1f, 0, "/$chapter", null, 0, null, source),
                extensionRevision = "a".repeat(64),
            ),
            status = status,
        )

    @Test
    fun `every status but complete belongs to the queue`() {
        val states = DesktopDownloadStatus.entries.map { state(it.name, it) }
        val sections = desktopDownloadSections(states)

        assertEquals(
            listOf(
                DesktopDownloadStatus.QUEUED,
                DesktopDownloadStatus.DOWNLOADING,
                DesktopDownloadStatus.PAUSED,
                DesktopDownloadStatus.FAILED,
            ),
            sections.queue.map { it.status },
        )
        assertEquals(listOf(DesktopDownloadStatus.COMPLETE), sections.completed.map { it.status })
    }

    @Test
    fun `both lists keep the queue's order`() {
        val states = listOf(
            state("b", DesktopDownloadStatus.COMPLETE),
            state("a", DesktopDownloadStatus.PAUSED),
            state("d", DesktopDownloadStatus.COMPLETE),
            state("c", DesktopDownloadStatus.FAILED),
        )
        val sections = desktopDownloadSections(states)

        assertEquals(listOf("a", "c"), sections.queue.map { it.record.chapter.title })
        assertEquals(listOf("b", "d"), sections.completed.map { it.record.chapter.title })
    }

    @Test
    fun `every state lands in exactly one list`() {
        val states = DesktopDownloadStatus.entries.map { state(it.name, it) }
        val sections = desktopDownloadSections(states)

        assertEquals(states.size, sections.queue.size + sections.completed.size)
        assertEquals(states.map { it.key }.toSet(), (sections.queue + sections.completed).map { it.key }.toSet())
    }

    @Test
    fun `an empty page is empty, one waiting chapter is not`() {
        assertTrue(desktopDownloadSections(emptyList()).isEmpty)
        assertFalse(desktopDownloadSections(listOf(state("a", DesktopDownloadStatus.QUEUED))).isEmpty)
        assertFalse(desktopDownloadSections(listOf(state("a", DesktopDownloadStatus.COMPLETE))).isEmpty)
    }
}
