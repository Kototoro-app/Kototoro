package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.tracker.domain.feed.FeedUpdateRow

/**
 * The Windows projection of Android's updates page: which tracks make one row, how their counts add up and in which
 * order the rows land.
 */
class DesktopUpdatesTest {

    private fun row(
        ownerId: Long,
        newChapters: Int = 1,
        displayMangaId: Long? = null,
        title: String = "作品 $ownerId",
        sourceName: String = "MIHON_$ownerId",
        lastChapterDate: Long? = 1_000L * ownerId,
        lastCheckTime: Long = 5_000L,
        isPinned: Boolean = false,
        isNsfw: Boolean = false,
    ) = FeedUpdateRow(
        ownerId = ownerId,
        mangaId = ownerId,
        entityId = null,
        preferredLocalMangaId = null,
        newChapters = newChapters,
        lastChapterDate = lastChapterDate,
        lastCheckTime = lastCheckTime,
        lastChapterId = 0L,
        isPinned = isPinned,
        displayMangaId = displayMangaId,
        title = title,
        coverUrl = "/cover/$ownerId",
        sourceName = sourceName,
        isNsfw = isNsfw,
    )

    @Test
    fun `tracks of the same work fold into one row and add their chapters`() {
        val groups = desktopUpdateGroups(
            listOf(
                row(1, newChapters = 2, displayMangaId = 100),
                row(2, newChapters = 3, displayMangaId = 100, sourceName = "MIHON_2"),
                row(3, newChapters = 1, displayMangaId = 300),
            ),
        )
        assertEquals(listOf(300L, 100L), groups.map { it.key }, "freshest last chapter first")
        val folded = groups.single { it.key == 100L }
        assertEquals(listOf(1L, 2L), folded.ownerIds, "every track is kept for mark-read")
        assertEquals(5, folded.newChapters)
        assertEquals("MIHON_2", folded.sourceName, "the row checked last describes the work")
        assertEquals(2_000L, folded.lastChapterDate)
    }

    @Test
    fun `a work without a display id keeps its own key and the chapter sum never goes negative`() {
        val groups = desktopUpdateGroups(listOf(row(7, newChapters = -1, title = "未知来源"), row(7, newChapters = 4)))
        assertEquals(listOf(7L), groups.map { it.key })
        assertEquals(4, groups.single().newChapters, "a track without a readable counter adds nothing")
        assertEquals("未知来源", groups.single().title)
    }

    @Test
    fun `pinned works come first and the adult target drops them entirely`() {
        val rows = listOf(
            row(1, lastChapterDate = 9_000L),
            row(2, lastChapterDate = 1_000L, isPinned = true),
            row(3, lastChapterDate = 5_000L, isNsfw = true),
        )
        assertEquals(listOf(2L, 1L, 3L), desktopUpdateGroups(rows).map { it.key })
        assertEquals(listOf(2L, 1L), desktopUpdateGroups(rows, hideNsfw = true).map { it.key })
    }

    @Test
    fun `the source filter runs before the fold and the summary counts what is left`() {
        val rows = listOf(row(1, newChapters = 2), row(2, newChapters = 3), row(3, isPinned = true))
        val groups = desktopUpdateGroups(rows) { it.ownerId != 3L }
        assertEquals(listOf(2L, 1L), groups.map { it.key })
        assertEquals(DesktopUpdatesSummary(works = 2, newChapters = 5), desktopUpdatesSummary(groups))
        assertEquals(DesktopUpdatesSummary(works = 0, newChapters = 0), desktopUpdatesSummary(emptyList()))
        assertTrue(desktopUpdateGroups(rows).single { it.key == 2L }.coverUrl != null)
        assertFalse(desktopUpdateGroups(emptyList()).any())
    }
}
