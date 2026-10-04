package org.skepsun.kototoro.tracker.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackFeedRulesTest {

    @Test
    fun `chapter date takes priority over check time and chapter id`() {
        val older = track(date = 10, checked = 500, chapter = 99)
        val newer = track(date = 20, checked = 100, chapter = 1)
        assertTrue(newer.isNewerThan(older))
        assertFalse(older.isNewerThan(newer))
    }

    @Test
    fun `check time then chapter id break equal chapter date ties`() {
        assertTrue(track(checked = 20).isNewerThan(track(checked = 10, chapter = 99)))
        assertTrue(track(chapter = 2).isNewerThan(track(chapter = 1)))
        assertFalse(track().isNewerThan(track()))
    }

    @Test
    fun `a newer cleared counter wins regardless of merge direction`() {
        val pending = track(date = 10, newChapters = 8)
        val cleared = track(date = 20, newChapters = 0)
        assertEquals(0, mergeRestoredTrackNewChapters(pending, cleared))
        assertEquals(0, mergeRestoredTrackNewChapters(cleared, pending))
    }

    @Test
    fun `an older clear cannot erase pending updates and pending counts keep the maximum`() {
        assertEquals(3, mergeRestoredTrackNewChapters(track(date = 20, newChapters = 3), track(newChapters = 0)))
        assertEquals(8, mergeRestoredTrackNewChapters(track(date = 20, newChapters = 3), track(newChapters = 8)))
    }

    @Test
    fun `logs only clear a track at or after both its chapter date and check time`() {
        listOf(track(date = 100, checked = 200), track(date = 200, checked = 100)).forEach { track ->
            assertFalse(track.canBeClearedBy(log(199)))
            assertTrue(track.canBeClearedBy(log(200)))
            assertTrue(track.canBeClearedBy(log(201)))
        }
        assertTrue(track().canBeClearedBy(log(0)))
    }

    private fun track(date: Long = 0, checked: Long = 0, chapter: Long = 0, newChapters: Int = 0) = TrackEntity(
        mangaId = 1,
        lastChapterId = chapter,
        newChapters = newChapters,
        lastCheckTime = checked,
        lastChapterDate = date,
        lastResult = TrackEntity.RESULT_NONE,
        lastError = null,
    )

    private fun log(createdAt: Long) = TrackLogEntity(
        mangaId = 1,
        chapters = "Chapter 1",
        createdAt = createdAt,
        isUnread = false,
    )
}
