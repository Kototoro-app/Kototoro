package org.skepsun.kototoro.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.migration.domain.MigrationDataFlag
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.migration.domain.MigrationPlanRules
import org.skepsun.kototoro.migration.domain.MigrationContentInput
import org.skepsun.kototoro.migration.domain.MigrationChapter
import org.skepsun.kototoro.migration.domain.MigrationSnapshot
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.tracker.data.TrackEntity

class MigrationPlannerTest {

    private fun content(id: Long, vararg chapters: Long) = MigrationContentInput(
        id = id,
        sourceName = "TEST",
        chapters = chapters.mapIndexed { index, chapterId ->
            MigrationChapter(chapterId, volume = 0, number = (index + 1).toFloat(), branch = null, uploadDate = 0)
        },
        preferredBranch = null,
    )

    private val old = content(1, 101, 102, 103)
    private val new = content(2, 201, 202, 203, 204)

    private val favourite = FavouriteEntity(
        mangaId = 1, categoryId = 7, sortKey = 3, isPinned = true, createdAt = 10, deletedAt = 0, updatedAt = 10,
    )
    private val history = HistoryEntity(
        mangaId = 1, createdAt = 5, updatedAt = 6, chapterId = 102, page = 4, scroll = 0f, percent = 0.5f,
        deletedAt = 0, chaptersCount = 3,
    )
    private val note = MediaNoteEntity(
        id = 55, mangaId = 1, chapterId = 103, chapterIndex = 2, mediaType = 0, createdAt = 1, updatedAt = 1,
    )
    private val session = ReadingRecordEntity(
        id = 9, mangaId = 1, startAt = 1, endAt = 2, startChapterId = 101, startPage = 0, startScroll = 0,
        endChapterId = 102, endPage = 0, endScroll = 0, startPercent = 0f, endPercent = 0f,
    )
    private val link = TrackingSiteLinkEntity(
        service = 1, remoteId = 99, mangaId = 1, sourceName = "OLD", confidence = 1f, isManual = true,
        createdAt = 1, updatedAt = 1,
    )
    private val track = TrackEntity(1, 103, 0, 0, 0, TrackEntity.RESULT_FAILED, "boom")

    private val snapshot = MigrationSnapshot(
        favourites = listOf(favourite), history = history, prefs = null, trackingLinks = listOf(link),
        track = track, notes = listOf(note), sessions = listOf(session), jumpPoints = emptyList(),
    )

    @Test
    fun `replace moves favourites and deletes old ones`() {
        val plan = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        assertEquals(listOf(favourite.copy(mangaId = 2, updatedAt = 100)), plan.favouritesToUpsert)
        assertTrue(plan.deleteOldFavourites)
    }

    @Test
    fun `copy keeps old favourites`() {
        val plan = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.COPY, MigrationDataFlag.ALL, now = 100)
        assertFalse(plan.deleteOldFavourites)
        assertFalse(plan.deleteOldHistory)
        assertEquals(2L, plan.favouritesToUpsert.single().mangaId)
    }

    @Test
    fun `history is remapped by chapter number`() {
        val plan = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        val newHistory = checkNotNull(plan.historyToUpsert)
        assertEquals(2L, newHistory.mangaId)
        assertEquals(202L, newHistory.chapterId)
        assertEquals(4, newHistory.chaptersCount)
    }

    @Test
    fun `progress flag off skips history`() {
        val flags = MigrationDataFlag.ALL - MigrationDataFlag.PROGRESS
        val plan = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.REPLACE, flags, now = 100)
        assertNull(plan.historyToUpsert)
        assertFalse(plan.deleteOldHistory)
    }

    @Test
    fun `replace updates notes in place with remapped chapter`() {
        val plan = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        assertEquals(listOf(note.copy(mangaId = 2, chapterId = 203, updatedAt = 100)), plan.notesToUpdate)
        assertTrue(plan.notesToInsert.isEmpty())
    }

    @Test
    fun `copy inserts note copies with fresh ids`() {
        val plan = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.COPY, MigrationDataFlag.ALL, now = 100)
        assertEquals(0L, plan.notesToInsert.single().id)
        assertTrue(plan.notesToUpdate.isEmpty())
    }

    @Test
    fun `stats move only in replace mode`() {
        val replace = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        val copy = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.COPY, MigrationDataFlag.ALL, now = 100)
        assertTrue(replace.moveStats)
        assertEquals(listOf(session.copy(mangaId = 2, startChapterId = 201, endChapterId = 202)), replace.sessionsToUpdate)
        assertFalse(copy.moveStats)
        assertTrue(copy.sessionsToUpdate.isEmpty())
    }

    @Test
    fun `tracking is moved in replace and copied in copy`() {
        val replace = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100)
        val copy = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.COPY, MigrationDataFlag.ALL, now = 100)
        assertEquals(listOf(link), replace.trackingLinksToDelete)
        assertEquals(2L, replace.trackingLinksToUpsert.single().mangaId)
        assertTrue(replace.deleteOldTrack)
        assertTrue(copy.trackingLinksToDelete.isEmpty())
        assertFalse(copy.deleteOldTrack)
        assertEquals(2L, copy.trackToUpsert?.mangaId)
        assertEquals(204L, copy.trackToUpsert?.lastChapterId)
    }

    @Test
    fun `tracking flag off leaves tracking untouched`() {
        val flags = MigrationDataFlag.ALL - MigrationDataFlag.TRACKING
        val plan = MigrationPlanRules.plan(old, new, snapshot, MigrationMode.REPLACE, flags, now = 100)
        assertTrue(plan.trackingLinksToUpsert.isEmpty())
        assertNull(plan.trackToUpsert)
        assertFalse(plan.deleteOldTrack)
    }

    @Test
    fun `unknown old chapters carry proportional progress on the selected target branch`() {
        val target = new.copy(
            chapters = new.chapters.map { it.copy(branch = "B") } +
                listOf(MigrationChapter(999, 0, 1f, "A", 0)),
            preferredBranch = "B",
        )
        val plan = MigrationPlanRules.plan(
            old.copy(chapters = emptyList()), target, snapshot,
            MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100,
        )
        val carried = checkNotNull(plan.historyToUpsert)
        assertEquals(202L, carried.chapterId)
        assertEquals(0.5f, carried.percent)
        assertEquals(4, carried.chaptersCount)
    }

    @Test
    fun `empty target keeps old history and note chapter identities`() {
        val plan = MigrationPlanRules.plan(
            old, new.copy(chapters = emptyList()), snapshot,
            MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100,
        )
        assertNull(plan.historyToUpsert)
        assertFalse(plan.deleteOldHistory)
        assertEquals(note.chapterId, plan.notesToUpdate.single().chapterId)
        assertEquals(0L, plan.trackToUpsert?.lastChapterId)
    }

    @Test
    fun `history branch fallback follows platform policy while notes use the largest branch`() {
        val target = new.copy(
            chapters = listOf(
                MigrationChapter(301, 0, 1f, "B", 0),
                MigrationChapter(302, 0, 2f, "B", 0),
                MigrationChapter(401, 0, 1f, "C", 0),
            ),
            preferredBranch = "C",
        )
        val source = old.copy(chapters = old.chapters.map { it.copy(branch = "A") }, preferredBranch = "A")
        val plan = MigrationPlanRules.plan(
            source, target, snapshot, MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100,
        )
        assertEquals(401L, plan.historyToUpsert?.chapterId)
        assertEquals(-1f, plan.historyToUpsert?.percent)
        assertEquals(302L, plan.notesToUpdate.single().chapterId)
    }

    @Test
    fun `missing history chapter falls back to progress index and clamps to the target end`() {
        val plan = MigrationPlanRules.plan(
            old, content(2, 201, 202), snapshot.copy(history = history.copy(chapterId = 999, percent = 1f)),
            MigrationMode.REPLACE, MigrationDataFlag.ALL, now = 100,
        )
        assertEquals(202L, plan.historyToUpsert?.chapterId)
        assertEquals(-1f, plan.historyToUpsert?.percent)
    }
}
