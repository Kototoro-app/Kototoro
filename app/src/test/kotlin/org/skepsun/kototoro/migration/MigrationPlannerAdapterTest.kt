package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationPlanner
import org.skepsun.kototoro.migration.domain.MigrationSnapshot
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter

class MigrationPlannerAdapterTest {

    private fun content(id: Long, chapters: List<ContentChapter>) = Content(
        id = id, title = "T", altTitles = emptySet(), url = "/$id", publicUrl = "", rating = -1f,
        contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(),
        chapters = chapters, source = TestContentSource,
    )

    private val history = HistoryEntity(
        mangaId = 1, createdAt = 5, updatedAt = 6, chapterId = 101, page = 4, scroll = 0f, percent = 0.5f,
        deletedAt = 0, chaptersCount = 3,
    )

    private val snapshot = MigrationSnapshot(
        favourites = emptyList(), history = history, prefs = null, trackingLinks = emptyList(), track = null,
        notes = emptyList(), sessions = emptyList(), jumpPoints = emptyList(),
    )

    @Test
    fun `history chapter branch reaches the shared planner even when another branch is larger`() {
        val old = content(1, listOf(chapter(101, 1f, branch = "B"), chapter(102, 1f, branch = "A")))
        val target = content(2, listOf(
            chapter(201, 1f, branch = "A"), chapter(202, 2f, branch = "A"), chapter(301, 1f, branch = "B"),
        ))
        val plan = MigrationPlanner.plan(old, target, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, 100)
        assertEquals(301L, plan.historyToUpsert?.chapterId)
        assertEquals(-1f, plan.historyToUpsert?.percent)
    }

    @Test
    fun `unknown old chapters keep proportional progress after projection`() {
        val target = content(2, listOf(chapter(201, 1f), chapter(202, 2f), chapter(203, 3f)))
        val plan = MigrationPlanner.plan(
            content(1, emptyList()), target, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, 100,
        )
        assertEquals(202L, plan.historyToUpsert?.chapterId)
        assertEquals(0.5f, plan.historyToUpsert?.percent)
    }

    @Test
    fun `disabled progress does not require platform branch selection`() {
        val old = content(1, listOf(chapter(101, 1f, branch = "A"), chapter(102, 1f, branch = "B")))
        val target = content(2, listOf(chapter(201, 1f, branch = "C"), chapter(202, 1f, branch = "D")))
        val plan = MigrationPlanner.plan(
            old, target, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL - MigrationDataFlag.PROGRESS, 100,
        )
        assertNull(plan.historyToUpsert)
        assertFalse(plan.deleteOldHistory)
    }
}
